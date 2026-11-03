package app.mg4dash.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import app.mg4dash.BuildConfig
import java.io.File

/**
 * Link with MG4Dash Rescue (rescue/ module), the separate safety net: if it does not see MG4Dash start,
 * it recompiles and relaunches it, then restores SAIC home if that is not enough.
 *  - sign of life at each launch (global setting mg4dash_alive);
 *  - installation of the embedded rescue app (assets of the car version) if missing or older;
 *  - message at the next launch when it had to step in.
 */
object Rescue {
    private const val TAG = "Rescue"
    const val PKG = "app.mg4dash.rescue"
    /** Version of the embedded rescue app (versionCode of rescue/build.gradle.kts). */
    private const val BUNDLED_VERSION = 3L
    private const val ASSET = "rescue.apk"

    /** MG4Dash started during this head unit boot. */
    fun alive(context: Context) {
        if (BuildConfig.MOCK) return
        try {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            Settings.Global.putString(context.contentResolver, "mg4dash_alive", "$boot:${android.os.SystemClock.elapsedRealtime()}")
        } catch (e: Exception) { Log.w(TAG, "alive: ${e.message}") }
    }

    /** Message to show once on home (text already translated), or null. */
    @Volatile var notice: Int? = null

    /** At launch: sign of life, possible rescue app intervention, its installation. */
    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        alive(context)
        when (takeRescued(context)) {
            "stock" -> { Prefs(context).valetMode = true; notice = app.mg4dash.R.string.rescue_stock }
            "recompiled" -> notice = app.mg4dash.R.string.rescue_recompiled
        }
        Thread { Thread.sleep(30_000); ensureInstalled(context) }.start()
    }

    /** Rescue app intervention since the last launch: "recompiled" or "stock", then erased. */
    fun takeRescued(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "mg4dash_rescued")?.substringBefore(':')?.also {
            Settings.Global.putString(context.contentResolver, "mg4dash_rescued", null)
            Log.i(TAG, "rescue happened: $it")
        }
    } catch (e: Exception) { null }

    /** Installs the rescue app if needed (off the main thread), then wakes it. */
    fun ensureInstalled(context: Context) {
        if (BuildConfig.MOCK) return
        val installed = try {
            context.packageManager.getPackageInfo(PKG, 0).longVersionCode
        } catch (_: Exception) { -1L }
        if (installed >= BUNDLED_VERSION) { wake(context); return }
        try {
            val apk = File(context.cacheDir, ASSET)
            context.assets.open(ASSET).use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(PKG)
                setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
                try {
                    val f = javaClass.getField("installFlags")
                    f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL, like "pm install -f"
                } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) } }
                val sender = android.app.PendingIntent.getBroadcast(context, id, Intent(context, Result::class.java),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                s.commit(sender.intentSender)
            }
            Log.i(TAG, "installing rescue app $BUNDLED_VERSION (installed: $installed)")
        } catch (e: Exception) {
            Log.w(TAG, "install failed: ${e.message}")
        }
    }

    /**
     * An installed app stays "stopped" and does not receive the head unit's boot until one of its
     * components has been used: an explicit message gets it out of that state.
     */
    private fun wake(context: Context) {
        try {
            context.sendBroadcast(Intent("$PKG.INIT").setPackage(PKG).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        } catch (e: Exception) { Log.w(TAG, "wake: ${e.message}") }
    }

    class Result : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            Log.i(TAG, "rescue install result: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            if (status == PackageInstaller.STATUS_SUCCESS) wake(context)
        }
    }
}
