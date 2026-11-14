package app.mg4dash.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.IBinder
import app.mg4dash.BuildConfig
import java.io.File

/**
 * Hides MG's rear fog light button for good while the shortcuts menu is on, instead of covering it: a resource
 * overlay of SystemUI (app.mg4dash.overlay.nofog, embedded in the car version's assets, platform signed) gives the
 * two icons of its window (layout_ball, same ids) a size of 0, so the window shows nothing and takes no touch.
 *
 * The setting switches the overlay on or off (IOverlayManager, allowed for the system uid). SystemUI builds the
 * button once, at its start: the change shows at the head unit's next start.
 *
 * The overlay's two ImageViews carry the numeric ids of the SystemUI it was built against (SWI69, A9_Systemui
 * V1.0.0.222). Another SystemUI numbers them differently (SWI173 .153: img_front_lamp 0x7f0800b6): its FloatBallView
 * then finds no front lamp, a NullPointerException at SystemUI's start, again and again, and the head unit restarts
 * (#113). So the overlay is only installed and enabled when this SystemUI's ids are the overlay's, and is disabled
 * everywhere else; the fog light button then stays, its corner kept free as before.
 */
object FogOverlay {
    private const val TAG = "FogOverlay"
    const val PKG = "app.mg4dash.overlay.nofog"
    private const val BUNDLED_VERSION = 1L
    private const val ASSET = "overlay_nofog.apk"
    private const val USER = 0
    private const val SYSTEMUI = "com.android.systemui"
    /** The ids compiled into the overlay's layout_ball (SWI69's SystemUI). */
    internal const val FRONT_ID = 0x7f0800b8
    internal const val REAR_ID = 0x7f0800ba

    /** The overlay fits a SystemUI whose img_front_lamp and img_rear_lamp are [front] and [rear]; 0: not found. */
    internal fun idsMatch(front: Int, rear: Int) = front == FRONT_ID && rear == REAR_ID

    @Volatile private var fits: Boolean? = null

    /** This head unit's SystemUI has the overlay's ids (read once); false when they cannot be read. */
    fun fits(context: Context): Boolean {
        if (BuildConfig.MOCK) return true
        fits?.let { return it }
        val r = try {
            val res = context.packageManager.getResourcesForApplication(SYSTEMUI)
            val front = res.getIdentifier("img_front_lamp", "id", SYSTEMUI)
            val rear = res.getIdentifier("img_rear_lamp", "id", SYSTEMUI)
            idsMatch(front, rear).also {
                if (!it) Log.i(TAG, "SystemUI ids front 0x${Integer.toHexString(front)} rear 0x${Integer.toHexString(rear)}: the overlay does not fit this SystemUI, fog light button left (#113)")
            }
        } catch (e: Exception) { Log.w(TAG, "SystemUI ids unreadable: ${e.message}"); false }
        fits = r
        return r
    }

    /** MG's fog light button hidden: the shortcuts menu on and an overlay that fits this SystemUI. */
    fun hides(context: Context): Boolean = Prefs(context).shortcutsMenu && fits(context)

    /** At launch (off the main thread): installs the overlay if needed, then follows the setting. */
    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        // Not fitting this SystemUI: never installed, and disabled if an earlier version enabled it (#113)
        Thread { try { if (!fits(context) || ensureInstalled(context)) apply(context) } catch (e: Exception) { Log.w(TAG, "start: ${e.message}") } }.start()
    }

    /** Setting changed: the overlay follows it (effective at the next start of the head unit). */
    fun settingChanged(context: Context) {
        if (BuildConfig.MOCK) return
        Thread { apply(context) }.start()
    }

    /** Overlay on with the shortcuts menu where it fits this SystemUI, off otherwise. */
    fun apply(context: Context) {
        val on = hides(context)
        val om = manager() ?: return
        try {
            val cur = enabled(om)
            // Not installed (null) and not wanted: nothing to do
            if (cur == on || (cur == null && !on)) return
            om.javaClass.getMethod("setEnabled", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(om, PKG, on, USER)
            Log.i(TAG, "overlay ${if (on) "enabled" else "disabled"} (was $cur): effective at the next start of SystemUI")
        } catch (e: Exception) { Log.w(TAG, "setEnabled: ${(e.cause ?: e).message}") }
    }

    /** True / false if the overlay is known to the overlay manager, null otherwise. */
    private fun enabled(om: Any): Boolean? = try {
        val info = om.javaClass.getMethod("getOverlayInfo", String::class.java, Int::class.javaPrimitiveType).invoke(om, PKG, USER) ?: return null
        info.javaClass.getMethod("isEnabled").invoke(info) as? Boolean
    } catch (_: Exception) { null }

    private fun manager(): Any? = try {
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "overlay") as? IBinder
        b?.let { Class.forName("android.content.om.IOverlayManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, it) }
    } catch (e: Exception) { Log.w(TAG, "overlay manager: ${e.message}"); null }

    /** True if the overlay is already installed at the embedded version; otherwise starts its installation. */
    private fun ensureInstalled(context: Context): Boolean {
        val installed = try { context.packageManager.getPackageInfo(PKG, 0).longVersionCode } catch (_: Exception) { -1L }
        if (installed >= BUNDLED_VERSION) return true
        val apk = File(context.cacheDir, ASSET)
        context.assets.open(ASSET).use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(PKG)
            setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
            // The car refuses apps on internal storage without it ("pm install -f"), as for Rescue
            try {
                val f = javaClass.getField("installFlags")
                f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL
            } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) } }
            val sender = android.app.PendingIntent.getBroadcast(context, id, Intent(context, Result::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(sender.intentSender)
        }
        Log.i(TAG, "installing overlay $BUNDLED_VERSION (installed: $installed)")
        return false
    }

    class Result : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            Log.i(TAG, "overlay install result: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            if (status == PackageInstaller.STATUS_SUCCESS) Thread { apply(context) }.start()
        }
    }
}
