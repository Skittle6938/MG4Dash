package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Son des firmwares « ancien SDK » (SWI68, #84) : pas de caradapter, mais le service exporté SettingsService
 * (ISmartSoundService de SystemSettingsService), mêmes échelles que sur A9 (0 à 14, 7 = neutre ou centre).
 * Numéros relevés par analyse de SWI68 R46.
 */
object LegacySound {
    private const val TAG = "LegacySound"
    private const val PKG = "com.saicmotor.service.systemsettings"
    private const val ACTION = "$PKG.ISmartSoundService"
    private const val BIND_WAIT_S = 3L

    @Volatile private var binder: IBinder? = null

    /** Service son ; attend la liaison, donc à appeler hors du fil principal. */
    fun service(context: Context): AudioService? {
        binder?.takeIf { it.isBinderAlive }?.let { return Service(it) }
        val bound = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) { binder = b; bound.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        }
        val intent = Intent(ACTION).setComponent(ComponentName(PKG, "$PKG.SettingsService"))
        val ok = try { context.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            catch (e: Exception) { Log.w(TAG, "bind: ${e.message}"); false }
        if (!ok) { Log.w(TAG, "sound service not found"); return null }
        bound.await(BIND_WAIT_S, TimeUnit.SECONDS)
        return binder?.let { Service(it) }
    }

    private class Service(b: IBinder) : AudioService(b, "com.saicmotor.sdk.systemsettings.ISmartSoundService") {
        override val centredOnSeven get() = true
        override val loudness get() = bool(7)
        override val equalizerPreset get() = int(26)
        /** -1 si le service ne sait pas : on affiche le centre. */
        override val balance get() = int(17).takeIf { it >= 0 } ?: CENTRE
        override val fader get() = int(18).takeIf { it >= 0 } ?: CENTRE

        override fun setLoudness(on: Boolean) = raw(8, { it.writeInt(if (on) 1 else 0) }) { it.readInt() != 0 }
        override fun setEqualizerPreset(v: Int) = raw(27, { it.writeInt(v) }) { it.readInt() != 0 }
        override fun setSoundField(balance: Int, fader: Int) = raw(19, { it.writeInt(balance); it.writeInt(fader) }) { it.readInt() != 0 }
        /** Le service ne donne pas les courbes des préréglages : celles qu'affichent ses réglages d'origine. */
        override fun eqBand(preset: Int): IntArray? = PRESETS[preset]
        override val userEq: IntArray? get() = raw(28, {}) { p: Parcel -> if (p.readInt() == 0) null else IntArray(5) { p.readInt() } }
        override fun setUserEq(v: IntArray) = raw(29, { p -> p.writeInt(1); v.forEach { p.writeInt(it) } }) { it.readInt() != 0 }

        private companion object {
            const val CENTRE = 7
            val PRESETS = mapOf(
                2 to intArrayOf(9, 9, 7, 10, 11), 3 to intArrayOf(11, 5, 11, 9, 7), 4 to intArrayOf(3, 9, 9, 9, 3),
                5 to intArrayOf(7, 7, 11, 11, 7), 6 to intArrayOf(13, 7, 9, 7, 13),
            )
        }
    }
}
