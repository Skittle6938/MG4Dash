package app.mg4dash.system

import android.content.Context
import android.provider.Settings
import app.mg4dash.BuildConfig

/**
 * ADB over the car's USB port, as the "adbutil" app does it (same steps and pauses, read from its code): the debugging
 * setting, the port put in gadget mode (persist.sys.service.otg_gadget, allgo.usb.interface), the USB functions set to
 * "mass_storage,adb", then adbd restarted. Allowed for the system uid, as MG4Dash. adbutil has no way to switch it
 * off, so neither does MG4Dash: the car's next start does it.
 */
object AdbSwitch {
    private const val TAG = "Adb"
    @Volatile private var busy = false

    /** ADB on: debugging allowed and adbd among the USB functions in use. */
    fun on(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 && prop("sys.usb.state").contains("adb")
    } catch (_: Exception) { false }

    /** Turns ADB on (off the main thread, about 3 s); nothing if it already is or is being turned on. */
    fun enable(context: Context, why: String) {
        if (BuildConfig.MOCK || busy) return
        busy = true
        Thread {
            try {
                if (on(context)) { Log.i(TAG, "already on ($why)"); return@Thread }
                Log.i(TAG, "turning on ($why)")
                Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                for ((k, v) in listOf("persist.sys.service.otg_gadget" to "1", "allgo.usb.interface" to "up",
                        "sys.usb.config" to "none", "sys.usb.config" to "mass_storage,adb", "ctl.restart" to "adbd")) {
                    Thread.sleep(500)
                    setprop(k, v)
                }
                Log.i(TAG, "on: ${on(context)} (usb ${prop("sys.usb.state")})")
            } catch (e: Exception) { Log.w(TAG, "turn on: ${e.message}") } finally { busy = false }
        }.start()
    }

    private fun setprop(key: String, value: String) {
        val p = Runtime.getRuntime().exec(arrayOf("setprop", key, value))
        val exit = p.waitFor()
        if (exit != 0) Log.w(TAG, "setprop $key $value: exit $exit ${p.errorStream.bufferedReader().readText().trim()}")
    }

    private fun prop(key: String): String = try {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Exception) { "" }
}
