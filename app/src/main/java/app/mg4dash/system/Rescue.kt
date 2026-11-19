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
 *  - sign of life once home's screen is drawn (global setting mg4dash_alive): the code really loads (19/11: written at
 *    the very start, it came before the crash and the rescue thought all was well);
 *  - installation of the embedded rescue app (assets of the car version) if missing or older, then checked;
 *  - after MG4Dash's own update, the hand-over to it (a clean recompilation and a watched relaunch);
 *  - message at the next launch when it had to step in.
 */
object Rescue {
    private const val TAG = "Rescue"
    const val PKG = "app.mg4dash.rescue"
    /** Version of the embedded rescue app (versionCode of rescue/build.gradle.kts). */
    const val BUNDLED_VERSION = 5L
    /** The first rescue that takes over after MG4Dash's update (UPDATED). */
    const val HANDS_OVER_FROM = 4L
    private const val ASSET = "rescue.apk"
    const val ACTION_UPDATED = "$PKG.UPDATED"
    const val ACTION_INIT = "$PKG.INIT"
    const val KEY_OK = "mg4dash_rescue_ok"
    /** Waited for the rescue's answer to INIT. */
    private const val ACK_WAIT_MS = 30_000L

    /** MG4Dash's sign of life during this head unit boot: home's first frame drawn. */
    fun alive(context: Context) {
        if (BuildConfig.MOCK) return
        try {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            Settings.Global.putString(context.contentResolver, "mg4dash_alive", "$boot:${android.os.SystemClock.elapsedRealtime()}")
        } catch (e: Exception) { Log.w(TAG, "alive: ${e.message}") }
    }

    /** The sign of life given once [activity]'s first frame is drawn (not before: the code has to load first). */
    fun aliveAtFirstFrame(activity: android.app.Activity) {
        val decor = activity.window.decorView
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            var done = false
            override fun onDraw() {
                if (done) return
                done = true
                alive(activity.applicationContext)
                // (removing a draw listener while drawing is refused: done after)
                decor.post { try { decor.viewTreeObserver.removeOnDrawListener(this) } catch (_: Exception) {} }
            }
        }
        decor.viewTreeObserver.addOnDrawListener(listener)
    }

    /** Message to show once on home (text already translated), or null. */
    @Volatile var notice: Int? = null

    /** At launch: possible rescue app intervention, its installation. The sign of life comes with the first frame. */
    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        when (takeRescued(context)) {
            // SAIC home put back by the rescue: a service mode of its own (not to be retried at each start)
            "stock" -> { Prefs(context).valetMode = true; Prefs(context).valetAuto = false; notice = app.mg4dash.R.string.rescue_stock }
            "recompiled" -> { notice = app.mg4dash.R.string.rescue_recompiled; HomeSwitcher.backAfterRescue(context) }
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

    /** Version of the rescue app installed, -1 without one. */
    fun installedVersion(context: Context): Long = try {
        context.packageManager.getPackageInfo(PKG, 0).longVersionCode
    } catch (_: Exception) { -1L }

    /** Whether the rescue installed takes over after MG4Dash's update. */
    fun handsOver(installed: Long) = installed >= HANDS_OVER_FROM

    /**
     * MG4Dash just updated: the rescue (v4 and later) recompiles it cleanly while it is stopped, relaunches and watches
     * it. True when handed over (MG4Dash then does not restart itself), false with an older or no rescue.
     */
    fun handOverUpdate(context: Context): Boolean {
        if (BuildConfig.MOCK || !handsOver(installedVersion(context))) return false
        return try {
            context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(PKG)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND))
            Log.i(TAG, "update handed over to the rescue")
            true
        } catch (e: Exception) { Log.w(TAG, "hand-over: ${e.message}"); false }
    }

    /** Installs the rescue app if needed (off the main thread), then wakes it. */
    fun ensureInstalled(context: Context) {
        if (BuildConfig.MOCK) return
        val installed = installedVersion(context)
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
     * components has been used: an explicit message gets it out of that state (and it answers in mg4dash_rescue_ok).
     */
    private fun wake(context: Context) {
        try {
            context.sendBroadcast(Intent(ACTION_INIT).setPackage(PKG).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
        } catch (e: Exception) { Log.w(TAG, "wake: ${e.message}") }
    }

    /** Recompiles [pkg] (forced, as "cmd package compile -f"): the rescue is never home, so never running meanwhile. */
    internal var recompile: (Context, String) -> Boolean = { _, pkg ->
        try {
            val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)
            val m = pm.javaClass.methods.first { it.name == "performDexOptMode" && it.parameterTypes.size == 6 }
            (m.invoke(pm, pkg, false, "speed", true, true, null) as? Boolean) ?: false
        } catch (e: Throwable) { Log.w(TAG, "recompile $pkg: ${(e.cause ?: e).message}"); false }
    }

    /** The rescue's answer to INIT after [since] (wall clock), as "versionCode:time". */
    internal fun answered(context: Context, since: Long): Boolean = try {
        Settings.Global.getString(context.contentResolver, KEY_OK)?.substringAfter(':')?.toLongOrNull()?.let { it >= since } == true
    } catch (_: Exception) { false }

    /**
     * After the rescue's install: its code recompiled cleanly (the install's compilation may not load, as MG4Dash's did),
     * then woken and its answer waited for; no answer, recompiled again and asked again. True when it answered.
     */
    internal fun checkInstalled(context: Context, sleep: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        for (attempt in 1..2) {
            Log.i(TAG, "rescue recompiled (attempt $attempt): ${recompile(context, PKG)}")
            val since = System.currentTimeMillis()
            wake(context)
            var waited = 0L
            while (waited < ACK_WAIT_MS) {
                if (answered(context, since)) { Log.i(TAG, "rescue answered (attempt $attempt)"); return true }
                sleep(1_000L); waited += 1_000L
            }
            Log.w(TAG, "rescue gave no answer (attempt $attempt)")
        }
        return false
    }

    class Result : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            Log.i(TAG, "rescue install result: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            if (status == PackageInstaller.STATUS_SUCCESS) {
                val app = context.applicationContext
                Thread { checkInstalled(app) }.start()
            }
        }
    }
}
