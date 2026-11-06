package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * MG4Dash Web (web/ module), the page of the "Web app" card (#97). Android refuses WebView to an app
 * with the system uid ("WebView is not allowed in privileged processes"), which the car version is:
 * the page is shown by this small separate app, with its own uid, embedded in the card like an app.
 * Embedded in the assets of the car version and installed by MG4Dash the first time the card is
 * shown (or when the embedded one is newer), as MG4Dash Rescue.
 */
object WebViewer {
    private const val TAG = "WebViewer"
    const val PKG = "app.mg4dash.web"
    val component = ComponentName(PKG, "$PKG.WebActivity")
    /** Version of the embedded app (versionCode of web/build.gradle.kts). */
    private const val BUNDLED_VERSION = 1L
    private const val ASSET = "web.apk"
    private const val ACTION_RESULT = "app.mg4dash.WEB_VIEWER_INSTALLED"

    /** This process cannot show a WebView itself: system (car version) or root uid. */
    fun needed(): Boolean = android.os.Process.myUid().let { it == android.os.Process.SYSTEM_UID || it == 0 }

    private fun installedVersion(context: Context): Long = try {
        context.packageManager.getPackageInfo(PKG, 0).longVersionCode
    } catch (_: Exception) { -1L }

    /** Installed and at least as recent as the embedded one. */
    fun ready(context: Context): Boolean = installedVersion(context) >= BUNDLED_VERSION

    /** The embedded app exists (car version). */
    fun bundled(context: Context): Boolean = try { context.assets.open(ASSET).close(); true } catch (_: Exception) { false }

    /** Intent of the page: address, card colours, translated messages, full screen choice. */
    fun intent(context: Context, url: String, bg: Int, fg: Int): Intent = Intent(Intent.ACTION_VIEW)
        .putExtra("url", url)
        .putExtra("bg", bg).putExtra("fg", fg)
        .putExtra("loading", context.getString(app.mg4dash.R.string.wc_web_loading))
        .putExtra("error", context.getString(app.mg4dash.R.string.wc_web_error))
        .putExtra("immersive", Prefs(context).fullscreen)
        // A new page each time: the card's virtual display is new, the old one is gone
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /** Installs the embedded app (off the main thread); [done] on the main thread, true if installed. */
    fun install(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val apk = File(app.cacheDir, ASSET)
                app.assets.open(ASSET).use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(PKG)
                    setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
                    try {
                        val f = javaClass.getField("installFlags")
                        f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL, like "pm install -f"
                    } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
                }
                val id = pi.createSession(params)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
                        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
                        try { app.unregisterReceiver(this) } catch (_: Exception) {}
                        Log.i(TAG, "install result: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                        apk.delete()
                        done(status == PackageInstaller.STATUS_SUCCESS)
                    }
                }
                app.registerReceiver(receiver, IntentFilter(ACTION_RESULT), null, main)
                pi.openSession(id).use { s ->
                    apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) } }
                    val sender = android.app.PendingIntent.getBroadcast(app, id, Intent(ACTION_RESULT).setPackage(app.packageName),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    s.commit(sender.intentSender)
                }
                Log.i(TAG, "installing MG4Dash Web $BUNDLED_VERSION (installed: ${installedVersion(app)})")
            } catch (e: Exception) {
                Log.w(TAG, "install failed: ${e.message}")
                main.post { done(false) }
            }
        }.start()
    }
}
