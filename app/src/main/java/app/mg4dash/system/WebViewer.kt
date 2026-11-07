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
 * Embedded in the assets of the car version and installed by MG4Dash the first time it is needed
 * (or when the embedded one is newer), as MG4Dash Rescue. It also opens the compatibility report in
 * full screen (#95): the car's browser refuses pages on some head units.
 */
object WebViewer {
    private const val TAG = "WebViewer"
    const val PKG = "app.mg4dash.web"
    val component = ComponentName(PKG, "$PKG.WebActivity")
    private val page = ComponentName(PKG, "$PKG.PageActivity")
    /** Version of the embedded app (versionCode of web/build.gradle.kts). */
    private const val BUNDLED_VERSION = 15L
    private const val ASSET = "web.apk"

    /**
     * Between the card and its page (#98), through broadcasts sent and received only by holders of [TALK], a
     * signature permission of MG4Dash Web (and by the system, as MG4Dash on the car): MG4Dash Web says when it
     * starts and when one of the page's fields is touched or left ([FIELD]); MG4Dash sends the keys of its
     * keyboard ([KEY]) and whether the card is shown ([SHOWN]).
     */
    const val TALK = "$PKG.permission.TALK"
    const val FIELD = "$PKG.FIELD"
    const val KEY = "$PKG.KEY"
    const val SHOWN = "$PKG.SHOWN"
    private const val ACTION_RESULT = "app.mg4dash.WEB_VIEWER_INSTALLED"

    /** This process cannot show a WebView itself: system (car version) or root uid. */
    fun needed(): Boolean = android.os.Process.myUid().let { it == android.os.Process.SYSTEM_UID || it == 0 }

    private fun installedVersion(context: Context): Long = try {
        context.packageManager.getPackageInfo(PKG, 0).longVersionCode
    } catch (_: Exception) { -1L }

    /** Installed and at least as recent as the embedded one. */
    fun ready(context: Context): Boolean = installedVersion(context) >= BUNDLED_VERSION

    private val LOCATION = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * The car's position for the page (#98): MG4Dash, a system app, grants it itself, as the page
     * cannot ask for it in the card. Hidden API, through reflection; nothing if refused.
     */
    fun grantLocation(context: Context) {
        val pm = context.packageManager
        LOCATION.filter { pm.checkPermission(it, PKG) != android.content.pm.PackageManager.PERMISSION_GRANTED }.forEach { p ->
            try {
                pm.javaClass.getMethod("grantRuntimePermission", String::class.java, String::class.java, android.os.UserHandle::class.java)
                    .invoke(pm, PKG, p, android.os.Process.myUserHandle())
                Log.i(TAG, "granted $p")
            } catch (e: Exception) { Log.w(TAG, "grant $p: ${e.cause?.message ?: e.message}") }
        }
    }

    /** The embedded app exists (car version). */
    fun bundled(context: Context): Boolean = try { context.assets.open(ASSET).close(); true } catch (_: Exception) { false }

    /** Intent of the page: address, card colours, translated messages, full screen choice. */
    fun intent(context: Context, url: String, bg: Int, fg: Int): Intent = Intent(Intent.ACTION_VIEW)
        .putExtra("url", url)
        .putExtra("bg", bg).putExtra("fg", fg)
        .putExtra("loading", context.getString(app.mg4dash.R.string.wc_web_loading))
        .putExtra("error", context.getString(app.mg4dash.R.string.wc_web_error))
        .putExtra("close", context.getString(app.mg4dash.R.string.st_close))
        .putExtra("immersive", Prefs(context).fullscreen)
        // MG4Dash's keyboard in the page's fields: where MG4Dash Web says that one is touched (#98)
        .putExtra("keyboard", if (Prefs(context).ownKeyboard) context.packageName else null)
        // A new page each time: the card's virtual display is new, the old one is gone
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    /**
     * [url] in full screen (compatibility report, #95): in MG4Dash Web on the car, installed first if
     * needed, as the car's browser refuses pages on some head units; otherwise in the browser.
     */
    fun open(activity: android.app.Activity, url: String) {
        fun browser() = try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: Exception) {
            app.mg4dash.ui.Toasts.show(activity, app.mg4dash.R.string.st_compat_no_browser, long = true)
        }
        if (!needed() || !bundled(activity)) { browser(); return }
        fun start() = try {
            val ui = app.mg4dash.ui.Themes
            activity.startActivity(intent(activity, url, ui.color(activity, app.mg4dash.R.color.bg_top), ui.color(activity, app.mg4dash.R.color.text_sub))
                .setComponent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "page: ${e.message}"); browser() }
        if (ready(activity)) { start(); return }
        app.mg4dash.ui.Toasts.show(activity, app.mg4dash.R.string.wc_web_installing)
        install(activity) { ok -> if (ok) start() else browser() }
    }

    /** To the page: [text] typed, or a [key] ("backspace", "clear", "done"). */
    fun key(context: Context, text: String? = null, key: String? = null) = tell(context, Intent(KEY).putExtra("text", text).putExtra("key", key))

    /** To the page: the card is shown, or hidden (the page goes on without drawing). */
    fun shown(context: Context, on: Boolean) = tell(context, Intent(SHOWN).putExtra("shown", on))

    // To this user: from the system process, a plain sendBroadcast warns in the log at each call
    private fun tell(context: Context, i: Intent) = try {
        context.sendBroadcastAsUser(i.setPackage(PKG).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), android.os.Process.myUserHandle(), TALK)
    } catch (e: Exception) { Log.w(TAG, "${i.action}: ${e.message}") }

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
