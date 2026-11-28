package app.mg4dash.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Update of Radio Dash (modified SAIC radio app, published on GitHub) from MG4Dash.
 * Radio Dash replaces com.saicmotor.radio. It is built from the SWI69 radio and offered on every firmware
 * whose radio Android lets it replace ([blocker]); the firmwares already tested are named in Settings.
 * As with "adb shell pm install -r -f", installation goes through PackageInstaller (system uid);
 * Android checks the signature. The new radio only applies after a restart of the central screen.
 */
object RadioDashUpdater {
    private const val TAG = "RadioDashUpdate"
    const val PKG = "com.saicmotor.radio"
    private const val API = "https://api.github.com/repos/Skittle6938/MG4Radio/releases?per_page=10"
    /**
     * Firmwares on which Radio Dash has been tested, named in Settings: a whole generation ("SWI69", any revision,
     * the firmware it is built from), or one revision confirmed by a driver's compatibility report on GitHub after a
     * few days of use ("SWI133 R30"). One line per firmware.
     */
    val TESTED = listOf("SWI69", "SWI165 R11")
    val TESTED_ON: String get() = TESTED.joinToString(", ")

    /**
     * Whether Radio Dash is validated on the firmware [raw] (19/11, discussion #111: on SWI68 R46 it restarted the
     * head unit). Elsewhere it is offered only after a clear warning (25/11, #110), and one already installed still
     * gets its updates.
     */
    fun validatedOn(raw: String?, tested: List<String> = TESTED): Boolean = tested.any { matches(it, raw) }

    /** [entry] ("SWI69", "SWI133 R30") covers the firmware [raw] ("SWI133-29958-1300R30"). */
    internal fun matches(entry: String, raw: String?): Boolean {
        val fw = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val parts = entry.trim().split(Regex("\\s+"))
        if (!fw.substringBefore('-').equals(parts[0], ignoreCase = true)) return false
        if (parts.size == 1) return true
        val rev = Regex("R(\\d+)$", RegexOption.IGNORE_CASE).find(fw)?.groupValues?.get(1) ?: return false
        return parts[1].trimStart('R', 'r').toIntOrNull() == rev.toInt()
    }

    /** Radio Dash validated on this car's firmware. */
    val validated: Boolean get() = validatedOn(Firmware.version)

    /** versionCode of Radio Dash, the one of the stock radio it is built from. */
    private const val RADIO_DASH_CODE = 2L

    /**
     * Why Android would refuse Radio Dash on this car (string resource), null if nothing is known against it:
     * a newer stock radio (no downgrade), or a radio signed with another key than MG4Dash's platform key.
     */
    @Suppress("DEPRECATION")
    fun blocker(context: Context): Int? {
        if (installed(context)) return null
        val pm = context.packageManager
        val radio = try { pm.getPackageInfo(PKG, android.content.pm.PackageManager.GET_SIGNATURES) } catch (_: Exception) { return null }
        if (radio.longVersionCode > RADIO_DASH_CODE) return app.mg4dash.R.string.st_rd_newer_radio
        val own = try { pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES).signatures } catch (_: Exception) { null }
        val theirs = radio.signatures
        // Unknown on either side: let Android decide
        if (own.isNullOrEmpty() || theirs.isNullOrEmpty()) return null
        return if (own.map { it.toCharsString() }.toSet() == theirs.map { it.toCharsString() }.toSet()) null else app.mg4dash.R.string.st_rd_other_key
    }

    /** Radio Dash is installed (its dock icons are in the radio app). */
    fun installed(context: Context): Boolean = declaredVersion(context) != null || try {
        val res = context.packageManager.getResourcesForApplication(PKG)
        res.getIdentifier("rd_ic_home", "drawable", PKG) != 0
    } catch (_: Exception) { false }

    /**
     * Version declared by Radio Dash in its manifest, however it was installed (it keeps the stock radio's
     * versionName). Older versions do not declare it: the one installed from MG4Dash, null if unknown.
     */
    fun installedVersion(context: Context): String? = declaredVersion(context) ?: Prefs(context).raw.getString("radio_dash_version", null)

    private fun declaredVersion(context: Context): String? = try {
        context.packageManager.getApplicationInfo(PKG, android.content.pm.PackageManager.GET_META_DATA)
            .metaData?.get("radiodash.version")?.toString()
    } catch (_: Exception) { null }

    /** Latest Radio Dash release; pre-releases included if MG4Dash's "beta versions" option is ticked. */
    fun latest(beta: Boolean): Updater.Release? = Updater.latest(beta, api = API)

    fun download(context: Context, r: Updater.Release, progress: (Int) -> Unit): File =
        Updater.download(context, r, "radio_dash.apk", progress = progress)

    fun install(context: Context, apk: File, version: String) {
        Prefs(context).raw.edit().putString("radio_dash_pending", version).apply()
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
            val sender = PendingIntent.getBroadcast(context, id, Intent(context, Result::class.java), PendingIntent.FLAG_UPDATE_CURRENT)  // mobsf-ignore: android_kotlin_pending_intent_not_immutable (the installer fills the status extras in; explicit intent)
            Log.i(TAG, "Radio Dash $version install started (session $id)")
            s.commit(sender.intentSender)
        }
    }

    /** Installation result: version kept and reminder to restart the central screen. */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install result: $status $msg")
            val sp = Prefs(context).raw
            val ok = status == PackageInstaller.STATUS_SUCCESS
            if (ok) sp.edit().putString("radio_dash_version", sp.getString("radio_dash_pending", null)).apply()
            listener?.invoke(ok)
        }
    }

    /** Settings screen open: notified of the result. */
    @Volatile var listener: ((Boolean) -> Unit)? = null
}
