package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.saicmotor.common.CommandParcelable

/**
 * The SAIC phone's call window (#93), on the A9 phone app (com.saicmotor.btphone: SWI69, SWI131, SWI132, SWI86…).
 * As the stock launcher does each time it comes back in front (Home), MG4Dash sends its PhoneService the command
 * MODE_COMMAND_LAUNCHER_RESUME (16416, type 1): a call in progress then shrinks to its floating card. Only on Home:
 * shrinking it by itself at each call start made the bars flash (tested on SWI69, 12 Nov 2026), so it is not done.
 * SWI68 / SWI133 / SWI165 (hmi.btcall) have no floating card, nothing is sent there.
 */
object SaicPhone {
    private const val TAG = "Phone"
    private const val PKG = "com.saicmotor.btphone"
    private val SERVICE = ComponentName(PKG, "$PKG.service.PhoneService")
    /** ModeConstants.MODE_COMMAND_LAUNCHER_RESUME of the stock launcher (its onResume), type 1. */
    const val LAUNCHER_RESUME = 16416

    @Volatile private var present: Boolean? = null

    /** The A9 phone app and its PhoneService are on this car. */
    fun applies(context: Context): Boolean = present ?: try {
        context.packageManager.getServiceInfo(SERVICE, 0); true
    } catch (_: Exception) { false }.also { present = it }

    /** MG4Dash back in front as the home screen (Home pressed): as the stock launcher, a call shrinks to its card. */
    fun onHome(context: Context) {
        if (!applies(context)) return
        val cmd = CommandParcelable().setEvent(LAUNCHER_RESUME).setTargetPackage(PKG).setTargetType(1).setTargetState(0)
        try { context.startService(Intent().setComponent(SERVICE).setPackage(PKG).putExtra("command", cmd)) }
        catch (e: Exception) { Log.w(TAG, "$cmd: ${e.message}") }
    }
}
