package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.saicmotor.common.CommandParcelable

/**
 * The SAIC phone's call window (#93), on the A9 phone app (com.saicmotor.btphone: SWI69, SWI131, SWI132). Its
 * PhoneService always opens a call full screen ("showBTCallingWindow, CardDisplay = false"); the stock launcher has it
 * shrink to its floating card: each time the launcher comes back in front (Home), it sends the command
 * MODE_COMMAND_LAUNCHER_RESUME (16416, type 1). MG4Dash, as the home screen, sends it too, and also the phone's own
 * SHRINK_BT_CALLWINDOW (16423) once a call is placed or answered, so it is a floating card by default. While it
 * rings the large screen stays (to answer; the phone does not shrink then anyway). SWI68 / SWI165 (hmi.btcall): nothing.
 */
object SaicPhone {
    private const val TAG = "Phone"
    private const val PKG = "com.saicmotor.btphone"
    private val SERVICE = ComponentName(PKG, "$PKG.service.PhoneService")
    /** ModeConstants.MODE_COMMAND_LAUNCHER_RESUME of the stock launcher (its onResume), type 1. */
    const val LAUNCHER_RESUME = 16416
    /** ModeConstants.MODE_COMMAND_SHRINK_BT_CALLWINDOW of the phone's PhoneService. */
    const val SHRINK_CALL_WINDOW = 16423
    private const val SHRINK_DELAY_MS = 600L

    @Volatile private var present: Boolean? = null
    private var listening = false
    private var lastState = TelephonyManager.CALL_STATE_IDLE

    /** The A9 phone app and its PhoneService are on this car. */
    fun applies(context: Context): Boolean = present ?: try {
        context.packageManager.getServiceInfo(SERVICE, 0); true
    } catch (_: Exception) { false }.also { present = it }

    /** MG4Dash back in front as the home screen (Home pressed): as the stock launcher, a call shrinks to its card. */
    fun onHome(context: Context) {
        if (applies(context)) send(context, LAUNCHER_RESUME, type = 1, state = 0, log = null)
    }

    /** Follows the calls: once one is placed or answered, it shrinks to the floating card. */
    fun watchCalls(context: Context) {
        val ctx = context.applicationContext
        if (listening || !applies(ctx)) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, number: String?) {
                    val shrink = shrinkWhen(lastState, state)
                    lastState = state
                    // A moment later: the phone shows its call window first
                    if (shrink) main.postDelayed({ send(ctx, SHRINK_CALL_WINDOW, log = "Appel : réduit en carte flottante") }, SHRINK_DELAY_MS)
                }
            }, PhoneStateListener.LISTEN_CALL_STATE)
            listening = true
        } catch (e: Exception) { Log.w(TAG, "call state: ${e.message}") }
    }

    /** A call placed or answered (off hook, not while it rings): shrunk once, when it gets there. */
    internal fun shrinkWhen(previous: Int, state: Int) =
        state == TelephonyManager.CALL_STATE_OFFHOOK && previous != TelephonyManager.CALL_STATE_OFFHOOK

    private fun send(context: Context, event: Int, type: Int = -1, state: Int = -1, log: String?) {
        val cmd = CommandParcelable().setEvent(event).setTargetPackage(PKG).setTargetType(type).setTargetState(state)
        try {
            context.startService(Intent().setComponent(SERVICE).setPackage(PKG).putExtra("command", cmd))
            log?.let { Log.i(TAG, it) }
        } catch (e: Exception) { Log.w(TAG, "$cmd: ${e.message}") }
    }
}
