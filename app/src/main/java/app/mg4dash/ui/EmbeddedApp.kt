package app.mg4dash.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import app.mg4dash.system.ImmersiveMode
import app.mg4dash.system.ImmersivePolicy
import app.mg4dash.system.Log

/**
 * Another app (Waze, ABRP…) shown in the map widget (#41, trial). Android 9 has a
 * hidden component, android.app.ActivityView (the one of AOSP's car launcher): it launches the app
 * in a virtual display built into the view and passes touches to it. Reserved to system apps,
 * which MG4Dash is; called by reflection, null if missing on this firmware.
 */
object EmbeddedApp {
    private const val TAG = "MapApp"

    /** Attached views of apps stopped on release, by package: restarted after another view's stop. */
    private val live = mutableMapOf<String, MutableSet<() -> Unit>>()

    /**
     * [launch]: intent to start instead of the app's launcher one (the web app's page, #97).
     * [stopOnRelease]: the app is stopped before its virtual display goes, instead of being moved by
     * Android to the main screen, over home (MG4Dash Web: a black screen for a second at each home rebuild).
     * [newSurfaceOnResize]: the view gets a new surface when its size changes ([newSurface]).
     */
    fun create(activity: Activity, cn: ComponentName, launch: Intent? = null, stopOnRelease: Boolean = false,
               newSurfaceOnResize: Boolean = false, onStarted: (() -> Unit)? = null): ViewGroup? = try {
        val cls = Class.forName("android.app.ActivityView")
        val view = cls.getConstructor(Context::class.java).newInstance(activity) as ViewGroup
        val start = cls.getMethod("startActivity", Intent::class.java)
        val intent = (launch ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The virtual display only exists once the surface is created: retry until it is ready
        fun tryStart(n: Int) {
            view.postDelayed({
                if (!view.isAttachedToWindow) return@postDelayed
                try {
                    start.invoke(view, intent)
                    Log.i(TAG, "${cn.flattenToShortString()} started in the map widget (${view.width}×${view.height})")
                    onStarted?.invoke()
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    // No limit: the virtual display only exists when home is shown (otherwise the widget stayed black)
                    if (n == 20) Log.w(TAG, "waiting for the virtual display: ${e.cause?.message}")
                    tryStart(n + 1)
                }
            }, if (n < 20) 300L else 1000L)
        }
        // Size changed (full screen): the app's virtual display follows the new size, otherwise
        // the app keeps the old width and the rest of the widget stays black
        view.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) {
                // Not at the first layout: the surface is being created
                val resized = or > ol && ob > ot
                v.post { resize(v); if (resized && newSurfaceOnResize) newSurface(v) }
            }
        }
        val restart: () -> Unit = { tryStart(0) }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (stopOnRelease) live.getOrPut(cn.packageName) { mutableSetOf() } += restart
                tryStart(0)
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (stopOnRelease) {
                    // At a home rebuild the new view has already started the app: the stop kills it too, start it again there
                    live[cn.packageName]?.remove(restart)
                    forceStop(activity, cn.packageName)
                    live[cn.packageName]?.forEach { it() }
                }
                release(v)
            }
        })
        Log.i(TAG, "ActivityView available")
        view
    } catch (e: Throwable) {
        Log.w(TAG, "ActivityView unavailable: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /**
     * Back key to the app of [v] (#98, the card's menu): through the input forwarder of its virtual display, as
     * ActivityView sends it the touches (Android 9). False when it could not be sent.
     */
    fun back(v: View): Boolean = try {
        val f = Class.forName("android.app.ActivityView").getDeclaredField("mInputForwarder").apply { isAccessible = true }
        val fwd = f.get(v) ?: throw IllegalStateException("no input forwarder yet")
        val send = fwd.javaClass.getMethod("forward", android.view.InputEvent::class.java)
        val t = android.os.SystemClock.uptimeMillis()
        for (action in intArrayOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            send.invoke(fwd, android.view.KeyEvent(t, t, action, android.view.KeyEvent.KEYCODE_BACK, 0))
        Log.i(TAG, "back key sent to the embedded app")
        true
    } catch (e: Throwable) {
        Log.w(TAG, "back key to the embedded app: ${e.javaClass.simpleName} ${e.message}")
        false
    }

    /**
     * Full screen: when touching the embedded app, its window takes control, and as it
     * does not ask for immersive mode, the SAIC bars reappeared. The system setting
     * "policy_control" forces immersive mode for this app (and MG4Dash); removed with no app.
     * The setting is shared with MG4Control (its "full screen apps", #248): both apps merge through
     * [ImmersivePolicy], the rule agreed with its developer, each one only removing its own packages
     * (its claims in the Global key "mg4dash_immersive", what either app wrote in "immersive_managed").
     * Android Auto and CarPlay are never forced, nor reached by a "*" or "apps" (excluded next to them),
     * and always taken out (CarPlay left by 1.7.3 beta 2 hid the SAIC status bar over it, #104).
     * [pkg]: the map's app, null with none. A pass writes in the agreed order, and once more 5 s later
     * if the value still has to change (no atomic read-write in Settings, MG4Control may write at the same time).
     */
    fun immersive(context: Context, pkg: String?) {
        val app = context.applicationContext
        main.removeCallbacks(reread)
        if (pass(app, pkg, "map app ${pkg ?: "none"}")) {
            reread = Runnable { pass(app, mapPackage(app), "re-read") }
            main.postDelayed(reread, REREAD_MS)
        }
    }

    /** The same pass with the map's app from the settings: return to home, full screen setting changed. */
    fun refreshImmersive(context: Context) = immersive(context, mapPackage(context))

    private const val REREAD_MS = 5_000L

    /** The SAIC phone (A9 firmwares), whose call window brought the top bar back during calls. */
    const val SAIC_PHONE = "com.saicmotor.btphone"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var reread = Runnable {}

    private fun mapPackage(context: Context): String? =
        app.mg4dash.system.Prefs(context).mapApp?.let { ComponentName.unflattenFromString(it)?.packageName }

    /**
     * One pass of the common rule; true when something was written, or a write was refused (the
     * re-read 5 s later tries again). Nothing done when MG4Dash has no stake in the value (nothing to
     * add or remove): it may belong to another app, not even "tidied".
     */
    private fun pass(context: Context, pkg: String?, why: String): Boolean = try {
        // The car's package even on the emulator build (app.mg4dash.emu): the key of the common rule
        val self = ImmersivePolicy.MG4DASH
        val prefs = app.mg4dash.system.Prefs(context)
        val wanted = buildMap {
            if (prefs.fullscreen && pkg != null) { put(self, setOf(ImmersiveMode.FULL)); put(pkg, setOf(ImmersiveMode.FULL)) }
            // The SAIC phone's call window (Marc, 17/11): it does not ask for full screen, so the top bar came back over
            // home for the whole call; forced like the map's app, the bar stays hidden (a swipe from the top still shows it)
            if (prefs.fullscreen && prefs.callBarHidden) put(SAIC_PHONE, setOf(ImmersiveMode.FULL))
        }
        val cr = context.contentResolver
        fun get(name: String) = android.provider.Settings.Global.getString(cr, name)?.ifEmpty { null }
        // A refused write stops the pass, the tracking keys as they are: the re-read takes over
        var written = false
        fun put(name: String, value: String?): Boolean {
            written = true
            return android.provider.Settings.Global.putString(cr, name, value).also {
                if (!it) Log.w(TAG, "policy_control ($why): writing $name refused")
            }
        }
        val myKey = ImmersivePolicy.OWNER_KEYS.getValue(self)
        val keys = ImmersivePolicy.OWNER_KEYS.mapValues { (_, k) -> get(k) }
        val installed = ImmersivePolicy.OWNER_KEYS.keys.filter { it == self || installed(context, it) }.toSet()
        val before = get(ImmersivePolicy.SETTING)
        var managed = get(ImmersivePolicy.MANAGED)
        if (ImmersivePolicy.hasStake(self, wanted, before, keys, installed, managed)) run {
            val p = ImmersivePolicy.plan(self, wanted, before, keys, installed, managed)
            var key = keys[self]
            // The agreed order: own key, common key, policy_control, own key, common key, uninstalled owners' keys
            if (p.keyBefore != key) { if (!put(myKey, p.keyBefore)) return@run; key = p.keyBefore }
            if (p.managedBefore != managed) { if (!put(ImmersivePolicy.MANAGED, p.managedBefore)) return@run; managed = p.managedBefore }
            if (p.policyChanged) {
                if (!put(ImmersivePolicy.SETTING, p.policy)) return@run
                Log.i(TAG, "policy_control ($why): ${before ?: "(empty)"} → ${p.policy ?: "(empty)"}")
            }
            if (p.keyAfter != key && !put(myKey, p.keyAfter)) return@run
            if (p.managedAfter != managed && !put(ImmersivePolicy.MANAGED, p.managedAfter)) return@run
            p.clearKeys.forEach { if (!put(it, null)) return@run; Log.i(TAG, "policy_control: $it cleared (its app is uninstalled)") }
        }
        written
    } catch (e: Throwable) { Log.w(TAG, "policy_control ($why): ${e.message}"); false }

    private fun installed(context: Context, pkg: String) =
        try { context.packageManager.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    private fun resize(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        try {
            val f = v.javaClass.getDeclaredField("mVirtualDisplay").apply { isAccessible = true }
            val vd = f.get(v) as? android.hardware.display.VirtualDisplay ?: return
            val dpi = v.resources.displayMetrics.densityDpi
            vd.resize(v.width, v.height, dpi)
            Log.i(TAG, "virtual display resized: ${v.width}×${v.height}")
        } catch (e: Throwable) { Log.w(TAG, "resize: ${e.message}") }
    }

    /**
     * Full screen and back (#98): on the car, the view's SurfaceView kept its layer at the old size. Larger, the
     * rest stayed black; smaller (brought back to the card after its creation at the screen's width), nothing
     * showed after a change of address. Hidden for a moment, it gets a new one at the new size.
     * ActivityView would turn its virtual display off meanwhile (no surface): the app was then stopped, and the
     * image of Chromium's videos stayed black. Its callback is set aside for that moment, and the virtual
     * display goes straight from the old surface to the new one, never off.
     */
    /**
     * The card moved without changing size, or just after (full screen beside the vertical bar, #98: its top goes up to
     * the bar's): on the car, the layer stayed at the old place, the page's top lower than the bar's. A new surface
     * puts it where the view is now.
     */
    fun moved(v: View) {
        if (!v.isAttachedToWindow || v.width <= 0 || v.height <= 0) return
        val at = IntArray(2).also { v.getLocationOnScreen(it) }
        Log.i(TAG, "moved to ${at[0]},${at[1]} (${v.width}×${v.height})")
        newSurface(v)
    }

    private fun newSurface(v: View) {
        val sv = (v as? ViewGroup)?.getChildAt(0) as? android.view.SurfaceView ?: return
        val holder = sv.holder
        val own = try {
            v.javaClass.getDeclaredField("mSurfaceCallback").apply { isAccessible = true }.get(v) as? android.view.SurfaceHolder.Callback
        } catch (e: Throwable) { null }
        val vd = try {
            v.javaClass.getDeclaredField("mVirtualDisplay").apply { isAccessible = true }.get(v) as? android.hardware.display.VirtualDisplay
        } catch (e: Throwable) { null }
        if (own != null && vd != null) {
            holder.removeCallback(own)
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) {
                    h.removeCallback(this)
                    h.addCallback(own)
                    // The same Surface object, refilled: set again only after null, back to back, before anyone sees it off
                    vd.surface = null
                    vd.surface = h.surface
                    // The place the main screen leaves to the app's touches, as ActivityView does with a new surface
                    try { v.javaClass.getDeclaredMethod("updateLocation").apply { isAccessible = true }.invoke(v) } catch (_: Throwable) {}
                }
                override fun surfaceChanged(h: android.view.SurfaceHolder, format: Int, w: Int, height: Int) {}
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
            })
        } else Log.w(TAG, "new surface: ActivityView's callback not found, its virtual display goes off for a moment")
        sv.visibility = View.INVISIBLE
        sv.post { sv.visibility = View.VISIBLE }
        Log.i(TAG, "new surface at ${v.width}×${v.height}")
    }

    private fun forceStop(context: Context, pkg: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
            Log.i(TAG, "$pkg stopped before its virtual display is released")
        } catch (e: Throwable) { Log.w(TAG, "force stop $pkg: ${e.cause?.message ?: e.message}") }
    }

    fun release(v: View) {
        try { v.javaClass.getMethod("release").invoke(v) } catch (_: Throwable) {}
    }
}
