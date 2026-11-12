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
 * rings the large screen stays (to answer; the phone does not shrink then anyway). Any car with that phone app
 * (SWI69, SWI131, SWI132, SWI86…), whatever its firmware name. SWI68 / SWI133 / SWI165 (hmi.btcall) have no floating
 * card: their call screen shows the SystemUI call bar by itself when MG4Dash comes in front, nothing is sent there.
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

    /**
     * Follows the calls: once one is placed or answered, it shrinks to the floating card. Through the system's
     * PHONE_STATE broadcast, which Telecom sends for the car's Bluetooth calls too (a PhoneStateListener alone heard
     * nothing of them on SWI69), and the listener as well; the same state from both counts once.
     */
    fun watchCalls(context: Context) {
        val ctx = context.applicationContext
        if (listening || !applies(ctx)) return
        listening = true
        try {
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    callState(ctx, stateOf(i?.getStringExtra(TelephonyManager.EXTRA_STATE)) ?: return, "broadcast")
                }
            }, android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED))
        } catch (e: Exception) { Log.w(TAG, "call broadcast: ${e.message}") }
        try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
            @Suppress("DEPRECATION")
            tm.listen(object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, number: String?) = callState(ctx, state, "listener")
            }, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) { Log.w(TAG, "call state: ${e.message}") }
        // Backup: Telecom asked every 2 s (in a call, ringing), should neither of them be heard
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager ?: return
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        main.post(object : Runnable {
            override fun run() {
                try {
                    @Suppress("MissingPermission")
                    val state = when {
                        !telecom.isInCall -> TelephonyManager.CALL_STATE_IDLE
                        audio?.mode == android.media.AudioManager.MODE_RINGTONE -> TelephonyManager.CALL_STATE_RINGING
                        else -> TelephonyManager.CALL_STATE_OFFHOOK
                    }
                    callState(ctx, state, "Telecom")
                } catch (_: Exception) {}
                main.postDelayed(this, 2_000)
            }
        })
    }

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /** A call state from the broadcast or the listener (main thread). */
    private fun callState(ctx: Context, state: Int, from: String) {
        if (state == lastState) return
        val shrink = shrinkWhen(lastState, state)
        Log.i(TAG, "Appel : état ${name(lastState)} → ${name(state)} ($from)")
        lastState = state
        // A moment later: the phone shows its call window first
        if (shrink) main.postDelayed({ shrinkCall(ctx) }, SHRINK_DELAY_MS)
    }

    /** EXTRA_STATE of the PHONE_STATE broadcast as a CALL_STATE_ value. */
    internal fun stateOf(extra: String?): Int? = when (extra) {
        TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
        TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
        TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
        else -> null
    }

    private fun name(state: Int) = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "off hook"; else -> "idle"
    }

    /**
     * The call shrunk to the floating card: the launcher's command, handled by every A9 phone version, and the phone's
     * own one, which SWI131 R27 and SWI86 R14 do not have (ignored there).
     */
    internal fun shrinkCall(context: Context) {
        send(context, LAUNCHER_RESUME, type = 1, state = 0, log = null)
        send(context, SHRINK_CALL_WINDOW, log = "Appel : réduit en carte flottante")
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
