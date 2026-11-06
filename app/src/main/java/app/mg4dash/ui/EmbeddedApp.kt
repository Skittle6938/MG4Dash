package app.mg4dash.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import app.mg4dash.system.Log

/**
 * Another app (Waze, ABRP…) shown in the map widget (#41, trial). Android 9 has a
 * hidden component, android.app.ActivityView (the one of AOSP's car launcher): it launches the app
 * in a virtual display built into the view and passes touches to it. Reserved to system apps,
 * which MG4Dash is; called by reflection, null if missing on this firmware.
 */
object EmbeddedApp {
    private const val TAG = "MapApp"

    /**
     * [launch]: intent to start instead of the app's launcher one (the web app's page, #97).
     * [stopOnRelease]: the app is stopped before its virtual display goes, instead of being moved by
     * Android to the main screen, over home (MG4Dash Web: a black screen for a second at each home rebuild).
     */
    fun create(activity: Activity, cn: ComponentName, launch: Intent? = null, stopOnRelease: Boolean = false,
               onStarted: (() -> Unit)? = null): ViewGroup? = try {
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
            if (r - l != or - ol || b - t != ob - ot) v.post { resize(v) }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = tryStart(0)
            override fun onViewDetachedFromWindow(v: View) {
                if (stopOnRelease) forceStop(activity, cn.packageName)
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
     * Full screen: when touching the embedded app, its window takes control, and as it
     * does not ask for immersive mode, the SAIC bars reappeared. The system setting
     * "policy_control" forces immersive mode for this app (and MG4Dash); removed with no app.
     */
    fun immersive(context: Context, pkg: String?) {
        val want = if (pkg != null && app.mg4dash.system.Prefs(context).fullscreen) "immersive.full=${context.packageName},$pkg" else null
        try {
            val cr = context.contentResolver
            val cur = android.provider.Settings.Global.getString(cr, "policy_control")
            // Only touch a value set by us (or empty)
            if (cur != null && cur.isNotEmpty() && !cur.startsWith("immersive.full=${context.packageName},")) return
            if (cur != want) {
                android.provider.Settings.Global.putString(cr, "policy_control", want)
                Log.i(TAG, "policy_control → ${want ?: "(empty)"}")
            }
        } catch (e: Throwable) { Log.w(TAG, "policy_control: ${e.message}") }
    }

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
