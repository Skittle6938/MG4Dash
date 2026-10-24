package app.mg4dash.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Log

/**
 * Radio des firmwares ancien SDK (SWI68…) : pas de MediaBrowserService ni de session média,
 * seulement le service `com.saicmotor.service.radio` et son interface AIDL `IRadioAppService`.
 * Codes de transaction relevés dans IRadioAppService$Stub du RadioService d'origine (SWI68 R46).
 */
object SaicRadio {
    private const val TAG = "Radio"
    private const val PKG = "com.saicmotor.service.radio"
    private const val ACTION = "com.saicmotor.service.radio.radioservice"
    private const val DESCRIPTOR = "com.saicmotor.sdk.radio.IRadioAppService"
    /** Appli radio d'origine (écran ouvert en touchant la carte). */
    const val HMI_PKG = "com.saicmotor.hmi.radio"

    private const val TX_NEXT = 13
    private const val TX_PREVIOUS = 14
    private const val TX_CURRENT_INFO = 19
    private const val TX_START_ACTIVITY = 26
    private const val TX_SRC_PLAY = 27
    private const val TX_SRC_PAUSE = 28

    /** RadioType d'origine. */
    const val BAND_AM = 1
    const val BAND_FM = 2
    const val BAND_DAB = 4

    data class Station(val name: String, val frequencyKhz: Int, val band: Int, val playing: Boolean)

    @Volatile private var binder: IBinder? = null
    @Volatile private var bound = false
    @Volatile var last: Station? = null
        private set

    val ready: Boolean get() = binder?.isBinderAlive == true

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service; Log.i(TAG, "radio service connected") }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null; Log.w(TAG, "radio service disconnected") }
    }

    fun connect(context: Context) {
        if (bound && binder != null) return
        bound = try {
            context.applicationContext.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) { Log.w(TAG, "binding radio service: ${e.message}"); false }
        Log.i(TAG, "binding radio service: $bound")
    }

    private fun <T> call(code: Int, read: (Parcel) -> T): T? {
        val b = binder ?: return null
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: Exception) {
            Log.w(TAG, "transaction $code: ${e.message}"); null
        } finally { data.recycle(); reply.recycle() }
    }

    /**
     * Station en cours (getCurrentRadioInfo). RadioBean.writeToParcel commence par : enable (byte),
     * nom, rds (byte), pochette, fréquence (kHz), bande, état (1 = lecture) ; un byte occupe un int.
     */
    fun refresh(): Station? {
        val st = call(TX_CURRENT_INFO) { r ->
            if (r.readInt() == 0) return@call null
            r.readInt()
            val name = r.readString().orEmpty().trim()
            r.readInt(); r.readString()
            val freq = r.readInt()
            val band = r.readInt()
            Station(name, freq, band, r.readInt() == 1)
        }
        if (st != null) last = st
        return st
    }

    fun play(): Boolean = call(TX_SRC_PLAY) { it.readInt() != 0 } ?: false
    fun pause(): Boolean = call(TX_SRC_PAUSE) { true } ?: false
    fun next(): Boolean = call(TX_NEXT) { true } ?: false
    fun previous(): Boolean = call(TX_PREVIOUS) { true } ?: false
    fun openScreen(): Boolean = call(TX_START_ACTIVITY) { it.readInt() != 0 } ?: false

    fun bandLabel(band: Int): String = when (band) { BAND_AM -> "RADIO AM"; BAND_DAB -> "RADIO DAB"; else -> "RADIO FM" }

    fun frequencyLabel(st: Station): String = when (st.band) {
        BAND_FM -> String.format(java.util.Locale.getDefault(), "%.1f MHz", st.frequencyKhz / 1000f)
        BAND_AM -> "${st.frequencyKhz} kHz"
        else -> ""
    }
}
