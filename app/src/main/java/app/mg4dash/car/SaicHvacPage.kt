package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log

/**
 * Page clim SAIC des anciens firmwares (#62) : fenêtre « hvac page » de l'interface système, ouverte par
 * la touche clim sous l'écran (288) ou à l'allumage de la clim. Aucun réglage ne la désactive ; on la
 * referme comme SystemUI lui-même, par son service exporté StartActivityService (transaction 2 =
 * stopHVAC). Relevé dans les SystemUI SWI68, SWI133 et XPower ; absent sur SWI69.
 */
object SaicHvacPage {
    private const val TAG = "HvacPage"
    private const val ACTION = "com.android.systemui.saicmotor.action.StartActivity"
    private const val DESCRIPTOR = "com.android.systemui.IStartActivityService"
    private const val TX_STOP_HVAC = 2

    @Volatile private var binder: IBinder? = null
    @Volatile private var binding = false

    private fun bind(ctx: Context) {
        if (binder?.isBinderAlive == true || binding) return
        binding = true
        val i = Intent(ACTION).setPackage("com.android.systemui")
            .setComponent(ComponentName("com.android.systemui", "com.android.systemui.StartActivityService"))
        val ok = try {
            ctx.applicationContext.bindService(i, object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; binding = false; Log.i(TAG, "SystemUI StartActivityService connected") }
                override fun onServiceDisconnected(name: ComponentName?) { binder = null; binding = false }
            }, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "bind: ${e.message}"); false }
        if (!ok) { binding = false; Log.i(TAG, "SystemUI StartActivityService not available on this firmware") }
    }

    /** Prépare la connexion (anciens firmwares seulement), pour pouvoir fermer la page sans attendre. */
    fun prepare(ctx: Context) { if (app.mg4dash.system.Firmware.family != app.mg4dash.system.Firmware.Family.A9) bind(ctx) }

    /** Ferme la page clim SAIC ; false si le service n'est pas joignable. */
    fun close(ctx: Context): Boolean {
        val b = binder?.takeIf { it.isBinderAlive } ?: run { bind(ctx); return false }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(TX_STOP_HVAC, data, reply, 0)
            reply.readException()
            Log.i(TAG, "SAIC climate page closed (stopHVAC)")
            true
        } catch (e: Exception) { Log.w(TAG, "stopHVAC: ${e.message}"); false }
        finally { data.recycle(); reply.recycle() }
    }
}
