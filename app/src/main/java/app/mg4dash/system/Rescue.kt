package app.mg4dash.system

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.provider.Settings
import app.mg4dash.BuildConfig
import java.io.File

/**
 * Lien avec MG4Dash Rescue (module rescue/), le filet de sécurité séparé : s'il ne voit pas MG4Dash démarrer,
 * il le recompile et le relance, puis remet l'accueil SAIC si ça ne suffit pas.
 *  - signe de vie à chaque lancement (réglage global mg4dash_alive) ;
 *  - installation de l'appli de secours embarquée (assets de la version voiture) si elle manque ou est plus ancienne ;
 *  - message au lancement suivant quand elle a dû intervenir.
 */
object Rescue {
    private const val TAG = "Rescue"
    const val PKG = "app.mg4dash.rescue"
    /** Version de l'appli de secours embarquée (versionCode de rescue/build.gradle.kts). */
    private const val BUNDLED_VERSION = 3L
    private const val ASSET = "rescue.apk"

    /** MG4Dash a démarré pendant ce démarrage de l'autoradio. */
    fun alive(context: Context) {
        if (BuildConfig.MOCK) return
        try {
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            Settings.Global.putString(context.contentResolver, "mg4dash_alive", "$boot:${android.os.SystemClock.elapsedRealtime()}")
        } catch (e: Exception) { Log.w(TAG, "alive: ${e.message}") }
    }

    /** Message à montrer une fois sur l'accueil (texte déjà traduit), ou null. */
    @Volatile var notice: Int? = null

    /** Au lancement : signe de vie, intervention éventuelle de l'appli de secours, installation de celle-ci. */
    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        alive(context)
        when (takeRescued(context)) {
            "stock" -> { Prefs(context).valetMode = true; notice = app.mg4dash.R.string.rescue_stock }
            "recompiled" -> notice = app.mg4dash.R.string.rescue_recompiled
        }
        Thread { Thread.sleep(30_000); ensureInstalled(context) }.start()
    }

    /** Intervention de l'appli de secours depuis le dernier lancement : « recompiled » ou « stock », puis effacée. */
    fun takeRescued(context: Context): String? = try {
        Settings.Global.getString(context.contentResolver, "mg4dash_rescued")?.substringBefore(':')?.also {
            Settings.Global.putString(context.contentResolver, "mg4dash_rescued", null)
            Log.i(TAG, "rescue happened: $it")
        }
    } catch (e: Exception) { null }

    /** Installe l'appli de secours si besoin (hors du fil principal), puis la réveille. */
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
                    f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL, comme « pm install -f »
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
     * Une appli installée reste « arrêtée » et ne reçoit pas le démarrage de l'autoradio tant qu'aucun de ses
     * composants n'a servi : un message explicite l'en sort.
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
