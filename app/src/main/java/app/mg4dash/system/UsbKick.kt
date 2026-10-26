package app.mg4dash.system

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import app.mg4dash.BuildConfig

/**
 * Téléphone branché en USB avant le démarrage de la voiture : Android Auto ne se lance pas tant qu'on ne
 * le débranche et rebranche pas. AllGo / caradapter ne sont pas prêts quand le téléphone est détecté, et
 * le système ne renvoie pas l'annonce « appareil branché » pour un appareil déjà présent.
 *
 * Repris de la partie USB d'AA Kick : au démarrage, si aucun téléphone n'est passé en mode Android Auto
 * (accessoire), on remet à zéro le téléphone branché (comme un rebranchement) puis on renvoie l'annonce.
 * Rien n'est fait dès qu'un appareil est en mode accessoire, ni pour un iPhone (CarPlay).
 */
object UsbKick {
    private const val TAG = "UsbKick"
    private const val VID_APPLE = 0x05ac
    private const val VID_AOAP = 0x18d1
    private const val VID_ROOTHUB = 0x1d6b
    private const val CLASS_MASS_STORAGE = 8
    private const val CLASS_HUB = 9
    /** Seulement juste après le démarrage de l'écran, pas à un redémarrage de MG4Dash. */
    private const val BOOT_WINDOW_MS = 5 * 60_000L
    private const val ATTEMPTS = 15

    fun start(context: Context) {
        if (BuildConfig.MOCK || !Prefs(context).usbKick) return
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return
        // Connexion du téléphone coupée par le bouton Android Auto : rien à relancer
        if (ConnectionToggle.isOff(context, Apps.ANDROID_AUTO.packageName)) return
        val app = context.applicationContext
        Thread({ run(app) }, "usb-kick").start()
    }

    private fun run(context: Context) {
        val um = context.getSystemService(Context.USB_SERVICE) as UsbManager
        sleep(4_000)
        repeat(ATTEMPTS) { attempt ->
            val devices = um.deviceList.values
            if (devices.any { it.vendorId == VID_AOAP }) {
                Log.i(TAG, "phone in Android Auto mode, nothing to do (attempt ${attempt + 1})")
                return
            }
            val phone = devices.firstOrNull { candidate(it) }
            if (phone == null) { sleep(4_000); return@repeat }
            Log.i(TAG, "phone ${phone.deviceName} vid=0x${Integer.toHexString(phone.vendorId)} not taken up: kick (attempt ${attempt + 1})")
            reset(um, phone)
            sleep(1_000)
            try {
                context.sendBroadcast(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, phone))
            } catch (e: Throwable) { Log.w(TAG, "attach: ${e.message}") }
            // Attente du mode accessoire (14 s au plus)
            repeat(14) {
                sleep(1_000)
                if (um.deviceList.values.any { it.vendorId == VID_AOAP }) { Log.i(TAG, "Android Auto mode reached"); return }
            }
        }
        Log.i(TAG, "no phone to kick")
    }

    /** Téléphone Android en mode normal sur le port USB du client (bus 001 = modem, MCU et hub internes). */
    private fun candidate(d: UsbDevice): Boolean {
        if (d.vendorId == VID_ROOTHUB || d.vendorId == VID_APPLE || d.vendorId == VID_AOAP) return false
        if (d.deviceName.contains("/001/")) return false
        for (i in 0 until d.interfaceCount) {
            val c = d.getInterface(i).interfaceClass
            if (c == CLASS_HUB || c == CLASS_MASS_STORAGE) return false
        }
        return true
    }

    private fun reset(um: UsbManager, d: UsbDevice) {
        val c = um.openDevice(d) ?: run { Log.w(TAG, "USB device not opened"); return }
        try {
            Log.i(TAG, "reset: ${c.javaClass.getMethod("resetDevice").invoke(c)}")
        } catch (e: Throwable) { Log.w(TAG, "reset: ${e.cause?.message ?: e.message}") }
        finally { c.close() }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}
}
