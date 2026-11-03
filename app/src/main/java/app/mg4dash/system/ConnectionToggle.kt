package app.mg4dash.system

import android.content.Context
import android.content.pm.PackageManager
import app.mg4dash.media.MediaHub

/**
 * Phone connection cut or restored by a double touch on the bar's Android Auto button:
 * the connection app (Android Auto, CarPlay, DiAuto…) is disabled, so nothing connects any more.
 * Android keeps the state from one boot to the next; Service mode and changing the button's app
 * first re-enable what was cut.
 */
object ConnectionToggle {
    private const val TAG = "Connection"

    /** Package targeted by the button: the chosen app, otherwise stock Android Auto. */
    fun target(context: Context): String =
        Prefs(context).dockAaTarget?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
            ?: Apps.ANDROID_AUTO.packageName

    fun isOff(context: Context, pkg: String = target(context)): Boolean = try {
        context.packageManager.getApplicationEnabledSetting(pkg).let {
            it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED || it == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        }
    } catch (_: Exception) { false }

    /** Cuts or restores, off the main thread; [done] receives the new state (true = cut), or null if Android refuses. */
    fun toggle(context: Context, done: (Boolean?) -> Unit) {
        val app = context.applicationContext
        val pkg = target(app)
        val off = !isOff(app, pkg)
        if (off && MediaHub.playingNow()) MediaHub.resumeOnBluetooth()
        Thread {
            if (off && projection(pkg)) endSession(app)
            if (off && pkg == DIAUTO) endDiAuto(app)
            val ok = set(app, pkg, off)
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(if (ok) off else null) }
        }.start()
    }

    private fun projection(pkg: String) = pkg == Apps.ANDROID_AUTO.packageName || pkg == Apps.CARPLAY.packageName

    /**
     * Android Auto / CarPlay session closed cleanly before cutting the app: otherwise AllGo keeps it
     * open, and the stock settings, believing they are still in Android Auto, refuse the phone's
     * Bluetooth music. Wait until AllGo no longer announces a device (5 s at most).
     */
    private fun endSession(context: Context) {
        if (!Projection.stopSession(context)) return
        repeat(10) {
            Thread.sleep(500)
            if ((Projection.session(context) as? Projection.Session.Read)?.target == null) return
        }
    }

    /**
     * App re-enabled, phone still plugged in: AllGo lost its link with it and restarts
     * nothing (the screen had to be restarted). Its service is restarted: it links to the app, finds
     * the plugged phone and reopens the session. Other apps reconnect by themselves.
     */
    private fun restartAllGo(context: Context) {
        Thread.sleep(1_000)
        // Process stop only (same system user): Android restarts it and reconnects the
        // linked apps (settings, launcher…). A force stop unbound them for good until reboot.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pid = am.runningAppProcesses?.firstOrNull { it.processName == "com.allgo.rui" }?.pid ?: return
        android.os.Process.killProcess(pid)
        Log.i(TAG, "AllGo restarted")
    }

    /**
     * Phone left plugged in accessory mode from the previous session: nobody tells the
     * re-enabled Android Auto app, which only discovers it on plugging. It is sent again the
     * "device attached" announcement the system made at the time (same principle as AA Kick at startup).
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

    private const val DIAUTO = "com.drivehub.diauto.mg4"
    private val DIAUTO_SERVICE = android.content.ComponentName(DIAUTO, "com.andrerinas.openheadunit.aap.AapService")

    /**
     * DiAuto (wireless Android Auto): its service closes the session cleanly ("byebye" to the phone)
     * before the app is cut. The phone stays linked over Bluetooth; it is asked for music again.
     */
    private fun endDiAuto(context: Context) {
        try {
            context.startService(android.content.Intent("com.andrerinas.openheadunit.aap.action.STOP_SERVICE").setComponent(DIAUTO_SERVICE))
            Log.i(TAG, "DiAuto session stopped")
            Thread.sleep(2_000)
        } catch (e: Throwable) { Log.w(TAG, "DiAuto stop: ${e.message}") }
        Thread { Thread.sleep(3_000); BtAudio.connectMusic(context) }.start()
        MediaHub.connectionCut(DIAUTO)
    }

    /**
     * DiAuto re-enabled: nothing restarts it until the phone reconnects over Bluetooth (it already
     * is). Its service is started as on the phone's Bluetooth connection, which starts wireless Android Auto.
     */
    private fun startDiAuto(context: Context) {
        Thread.sleep(1_000)
        try {
            val i = android.content.Intent("com.andrerinas.openheadunit.ACTION_BT_AUTO_START").setComponent(DIAUTO_SERVICE)
            BtAudio.phoneAddress()?.let { i.putExtra("extra_mac", it) }
            context.startForegroundService(i)
            Log.i(TAG, "DiAuto started")
        } catch (e: Throwable) { Log.w(TAG, "DiAuto start: ${e.message}") }
    }

    /** Re-enables the app cut by MG4Dash, if any (app change, Service mode). */
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
        if (!off && pkg == DIAUTO) Thread { startDiAuto(context.applicationContext) }.start()
        if (!off && projection(pkg)) Thread { restartAllGo(context.applicationContext); Thread.sleep(3_000); announcePhone(context.applicationContext) }.start()
        true
    } catch (e: Exception) { Log.w(TAG, "$pkg: ${e.message}"); false }
}
