package app.mg4dash.system

import android.content.Context
import android.content.pm.PackageManager

/**
 * Connexion du téléphone coupée ou rétablie par un appui long sur le bouton Android Auto de la barre :
 * l'appli de connexion (Android Auto, CarPlay, DiAuto…) est désactivée, donc plus rien ne se connecte.
 * Android garde l'état d'un démarrage à l'autre ; le mode Entretien et le changement d'appli du bouton
 * réactivent d'abord ce qui avait été coupé.
 */
object ConnectionToggle {
    private const val TAG = "Connection"

    /** Paquet visé par le bouton : l'appli choisie, sinon Android Auto d'origine. */
    fun target(context: Context): String =
        Prefs(context).dockAaTarget?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
            ?: Apps.ANDROID_AUTO.packageName

    fun isOff(context: Context, pkg: String = target(context)): Boolean = try {
        context.packageManager.getApplicationEnabledSetting(pkg).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
    } catch (_: Exception) { false }

    /** Coupe ou rétablit, hors du fil principal ; [done] reçoit le nouvel état (true = coupée), ou null si Android refuse. */
    fun toggle(context: Context, done: (Boolean?) -> Unit) {
        val app = context.applicationContext
        val pkg = target(app)
        val off = !isOff(app, pkg)
        Thread {
            if (off && projection(pkg)) endSession(app)
            val ok = set(app, pkg, off)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(if (ok) off else null) }
        }.start()
    }

    private fun projection(pkg: String) = pkg == Apps.ANDROID_AUTO.packageName || pkg == Apps.CARPLAY.packageName

    /**
     * Session Android Auto / CarPlay fermée proprement avant de couper l'appli : sinon AllGo la garde
     * ouverte, et les réglages d'origine, qui se croient toujours en Android Auto, refusent la musique
     * Bluetooth du téléphone. On attend qu'AllGo n'annonce plus d'appareil (5 s au plus).
     */
    private fun endSession(context: Context) {
        if (!Projection.stopSession(context)) return
        repeat(10) {
            Thread.sleep(500)
            if ((Projection.session(context) as? Projection.Session.Read)?.target == null) return
        }
    }

    /**
     * Appli réactivée, téléphone toujours branché : AllGo a perdu sa liaison avec elle et ne relance
     * rien (il fallait redémarrer l'écran). Son service est redémarré : il se relie à l'appli, retrouve
     * le téléphone branché et rouvre la session. Les autres applis s'y reconnectent d'elles-mêmes.
     */
    private fun restartAllGo(context: Context) {
        Thread.sleep(1_000)
        // Arrêt du processus seulement (même utilisateur système) : Android le relance et y reconnecte les
        // applis liées (réglages, launcher…). Un arrêt forcé les déliait pour de bon jusqu'au redémarrage.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pid = am.runningAppProcesses?.firstOrNull { it.processName == "com.allgo.rui" }?.pid ?: return
        android.os.Process.killProcess(pid)
        Log.i(TAG, "AllGo restarted")
    }

    /**
     * Téléphone resté branché en mode accessoire depuis la session précédente : personne ne prévient
     * l'appli Android Auto réactivée, qui ne le découvre qu'à un branchement. On lui renvoie l'annonce
     * « appareil branché » que le système avait faite à l'époque (même principe que AA Kick au démarrage).
     */
    private fun announcePhone(context: Context) {
        val um = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val phone = um.deviceList.values.firstOrNull { it.vendorId == 0x18d1 && it.productId in 0x2d00..0x2d05 } ?: return
        try {
            context.sendBroadcast(android.content.Intent(android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED)
                .putExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE, phone))
            Log.i(TAG, "phone USB announced again: ${phone.deviceName}")
        } catch (e: Throwable) { Log.w(TAG, "phone announce: ${e.message}") }
    }

    /** Réactive l'appli coupée par MG4Dash, s'il y en a une (changement d'appli, mode Entretien). */
    fun restore(context: Context) {
        val p = Prefs(context)
        val pkg = p.connectionOff ?: return
        if (isOff(context, pkg)) set(context, pkg, false)
        p.connectionOff = null
    }

    private fun set(context: Context, pkg: String, off: Boolean): Boolean = try {
        context.packageManager.setApplicationEnabledSetting(pkg,
            if (off) PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
        Prefs(context).connectionOff = if (off) pkg else null
        Log.i(TAG, "$pkg ${if (off) "disabled" else "enabled"}")
        if (!off && projection(pkg)) Thread { restartAllGo(context.applicationContext); Thread.sleep(3_000); announcePhone(context.applicationContext) }.start()
        true
    } catch (e: Exception) { Log.w(TAG, "$pkg: ${e.message}"); false }
}
