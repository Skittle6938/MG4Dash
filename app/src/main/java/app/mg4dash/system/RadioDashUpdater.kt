package app.mg4dash.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File

/**
 * Mise à jour de Radio Dash (appli radio SAIC modifiée, publiée sur GitHub) depuis MG4Dash.
 * Radio Dash remplace com.saicmotor.radio et n'est construite que pour la radio du firmware SWI69 :
 * le bouton n'apparaît que sur ce firmware, avec cette version de l'appli radio d'origine.
 * Comme pour « adb shell pm install -r -f », l'installation passe par PackageInstaller (uid système) ;
 * Android vérifie la signature. La nouvelle radio ne s'applique qu'après un redémarrage de l'écran central.
 */
object RadioDashUpdater {
    private const val TAG = "RadioDashUpdate"
    const val PKG = "com.saicmotor.radio"
    private const val API = "https://api.github.com/repos/Skittle6938/MG4-Radio-Dash/releases?per_page=10"
    /** Version de l'appli radio d'origine sur laquelle Radio Dash est construite (et qu'elle garde). */
    private const val RADIO_VERSION = "A9_Radio_V1.0.0.115"

    /** Firmware et appli radio compatibles avec Radio Dash. */
    fun compatible(context: Context): Boolean = Firmware.gen == Firmware.Gen.SWI69 && radioVersion(context) == RADIO_VERSION

    private fun radioVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(PKG, 0).versionName
    } catch (_: Exception) { null }

    /** Radio Dash est installée (ses icônes de dock sont présentes dans l'appli radio). */
    fun installed(context: Context): Boolean = try {
        val res = context.packageManager.getResourcesForApplication(PKG)
        res.getIdentifier("rd_ic_home", "drawable", PKG) != 0
    } catch (_: Exception) { false }

    /** Version installée depuis MG4Dash (null si inconnue : installée par ADB ou clé USB). */
    fun installedVersion(context: Context): String? = Prefs(context).raw.getString("radio_dash_version", null)

    /** Dernière release de Radio Dash ; pré-versions comprises si l'option « versions bêta » de MG4Dash est cochée. */
    fun latest(beta: Boolean): Updater.Release? = Updater.latest(beta, api = API)

    fun download(context: Context, r: Updater.Release, progress: (Int) -> Unit): File =
        Updater.download(context, r, "radio_dash.apk", progress)

    fun install(context: Context, apk: File, version: String) {
        Prefs(context).raw.edit().putString("radio_dash_pending", version).apply()
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(PKG)
            setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
            try {
                val f = javaClass.getField("installFlags")
                f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL, comme « pm install -f »
            } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) }
            }
            val sender = PendingIntent.getBroadcast(context, id, Intent(context, Result::class.java), PendingIntent.FLAG_UPDATE_CURRENT)
            Log.i(TAG, "Radio Dash $version install started (session $id)")
            s.commit(sender.intentSender)
        }
    }

    /** Résultat de l'installation : version retenue et rappel de redémarrer l'écran central. */
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

    /** Écran de réglages ouvert : prévenu du résultat. */
    @Volatile var listener: ((Boolean) -> Unit)? = null
}
