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
 */
object FogOverlay {
    private const val TAG = "FogOverlay"
    const val PKG = "app.mg4dash.overlay.nofog"
    private const val BUNDLED_VERSION = 1L
    private const val ASSET = "overlay_nofog.apk"
    private const val USER = 0

    /** At launch (off the main thread): installs the overlay if needed, then follows the setting. */
    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        Thread { try { if (ensureInstalled(context)) apply(context) } catch (e: Exception) { Log.w(TAG, "start: ${e.message}") } }.start()
    }

    /** Setting changed: the overlay follows it (effective at the next start of the head unit). */
    fun settingChanged(context: Context) {
        if (BuildConfig.MOCK) return
        Thread { apply(context) }.start()
    }

    /** Overlay on with the shortcuts menu, off without it. */
    fun apply(context: Context) {
        val on = Prefs(context).shortcutsMenu
        val om = manager() ?: return
        try {
            val cur = enabled(om)
            if (cur == on) return
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
