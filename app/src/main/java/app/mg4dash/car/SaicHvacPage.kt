package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log

/**
 * SAIC climate page of old firmwares (#62): "hvac page" window of the system UI, opened by
 * the climate key under the screen (288) or when the climate is turned on. No setting disables it; it is
 * closed as SystemUI itself does, through its exported StartActivityService (transaction 2 =
 * stopHVAC). Found in the SWI68, SWI133 and XPower SystemUI; absent on SWI69.
 */
object SaicHvacPage {
    private const val TAG = "HvacPage"
    private const val ACTION = "com.android.systemui.saicmotor.action.StartActivity"
    private const val DESCRIPTOR = "com.android.systemui.IStartActivityService"
    private const val TX_STOP_HVAC = 2

    @Volatile private var binder: IBinder? = null
    /** The service's own name, asked once connected: not the same on every SWI68 (#112, R69 refused ours). */
    @Volatile private var descriptor: String? = DESCRIPTOR

    /**
     * Name to send for [actual], the service's own: it, when it is a StartActivityService under another package;
     * null when it is another interface, so that transaction 2 calls nothing else. Unknown: the usual name.
     */
    internal fun descriptorFor(actual: String?): String? = when {
        actual == null || actual.isEmpty() -> DESCRIPTOR
        actual == DESCRIPTOR || actual.substringAfterLast('.') == "IStartActivityService" -> actual
        else -> null
    }
    @Volatile private var binding = false

    private fun bind(ctx: Context) {
        if (binder?.isBinderAlive == true || binding) return
        binding = true
        val i = Intent(ACTION).setPackage("com.android.systemui")
            .setComponent(ComponentName("com.android.systemui", "com.android.systemui.StartActivityService"))
        val ok = try {
            ctx.applicationContext.bindService(i, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val actual = try { service?.interfaceDescriptor } catch (e: Exception) { null }
                    descriptor = descriptorFor(actual)
                    binder = service; binding = false
                    Log.i(TAG, "SystemUI StartActivityService connected (interface ${actual ?: "?"}${if (descriptor == null) ", not closable" else ""})")
                }
                override fun onServiceDisconnected(name: ComponentName?) { binder = null; binding = false }
            }, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "bind: ${e.message}"); false }
        if (!ok) { binding = false; Log.i(TAG, "SystemUI StartActivityService not available on this firmware") }
    }

    /** Prepares the connection (old firmwares only), to be able to close the page without waiting. */
    fun prepare(ctx: Context) { if (app.mg4dash.system.Firmware.family != app.mg4dash.system.Firmware.Family.A9) bind(ctx) }

    /** Closes the SAIC climate page; false if the service cannot be reached. */
    fun close(ctx: Context): Boolean {
        val b = binder?.takeIf { it.isBinderAlive } ?: run { bind(ctx); return false }
        val token = descriptor ?: run { Log.w(TAG, "stopHVAC not sent: another interface on this firmware"); return false }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(token)
            b.transact(TX_STOP_HVAC, data, reply, 0)
            reply.readException()
            Log.i(TAG, "SAIC climate page closed (stopHVAC)")
            true
        } catch (e: Exception) { Log.w(TAG, "stopHVAC: ${e.message}"); false }
        finally { data.recycle(); reply.recycle() }
    }
}
