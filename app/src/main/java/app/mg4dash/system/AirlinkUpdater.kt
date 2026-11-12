package app.mg4dash.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Update of MG4-AA-Airlink (wireless Android Auto for the MG4, formerly DiAuto MG4+) from MG4Dash.
 * It carries AllGo's package com.allgo.app.androidauto, signed with the car's platform key: Android
 * checks the signature against the installed app. Only an Airlink already installed is updated, never
 * the car's stock AllGo (versionCode 1). Its own releases are on GitHub, like MG4Dash's.
 * The download goes through MG4Dash's network, the Wi-Fi first ([WifiFirst]).
 */
object AirlinkUpdater {
    private const val TAG = "AirlinkUpdate"
    const val PKG = "com.allgo.app.androidauto"
    const val NAME = "MG4Link"
    // GitHub redirects this address if the repository is renamed
    private const val API = "https://api.github.com/repos/Skittle6938/DiAuto-MG4/releases?per_page=20"
    /** Above AllGo's (1) and the former relay's (1008): the app is Airlink, not the stock AllGo. */
    private const val MIN_AIRLINK_CODE = 2000L

    /**
     * One check or update at a time: a double press, or a check while the download runs, would
     * download into the same file and start two installations.
     */
    private val busy = AtomicBoolean(false)
    fun begin(): Boolean = busy.compareAndSet(false, true).also { if (!it) Log.i(TAG, "already running, ignored") }
    fun end() = busy.set(false)
    val running: Boolean get() = busy.get()

    /** Installed version ("2.2.5-beta9"), null when Airlink is not installed (stock AllGo or nothing). */
    fun installedVersion(context: Context): String? = try {
        val p = context.packageManager.getPackageInfo(PKG, 0)
        if (p.longVersionCode < MIN_AIRLINK_CODE) null
        else p.versionName?.removeSuffix("-debug")?.removeSuffix("-phone")
    } catch (_: Exception) { null }

    /** Latest Airlink release; pre-releases included if MG4Dash's "beta versions" option is ticked. Network: off the main thread. */
    fun latest(beta: Boolean): Updater.Release? = Updater.latest(beta, api = API)

    fun download(context: Context, r: Updater.Release, progress: (Int) -> Unit): File =
        Updater.download(context, r, "airlink.apk", progress = progress)

    /** As "adb shell pm install -r -f": Android Auto stops while Android replaces the app, then Airlink starts again by itself. */
    fun install(context: Context, apk: File, version: String) {
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
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) }
            }
            val sender = PendingIntent.getBroadcast(context, id, Intent(context, Result::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            Log.i(TAG, "$NAME $version install started (session $id)")
            s.commit(sender.intentSender)
        }
    }

    /** Installation result: the lock is released and the Settings screen told. */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install result: $status $msg")
            end()
            listener?.invoke(status == PackageInstaller.STATUS_SUCCESS)
        }
    }

    /** Settings screen open: notified of the result. */
    @Volatile var listener: ((Boolean) -> Unit)? = null
}
