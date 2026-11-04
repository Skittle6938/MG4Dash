package app.mg4dash.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context

/**
 * Phone's Bluetooth audio. During Android Auto the phone only keeps calls (HFP); when
 * projection stops (connection cut by the button), it does not reconnect music by itself and the
 * Bluetooth source disappeared. The car (A2DP "sink" role) can ask it again.
 */
object BtAudio {
    private const val TAG = "BtAudio"
    private const val A2DP_SINK = 11
    private const val HEADSET_CLIENT = 16

    private fun state(profile: Int) = try {
        BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { false }

    fun musicConnected() = state(A2DP_SINK)
    /** Phone connected over Bluetooth (calls), with or without music. */
    fun phoneConnected() = state(HEADSET_CLIENT) || musicConnected()

    /** Address of the phone connected for calls (HFP), or null. */
    fun phoneAddress(): String? = try {
        val a = BluetoothAdapter.getDefaultAdapter()
        a?.bondedDevices?.firstOrNull { d ->
            try { d.javaClass.getMethod("isConnected").invoke(d) == true } catch (_: Throwable) { false }
        }?.address
    } catch (_: Throwable) { null }

    /** Phone indicators over hands-free (#86): battery and signal from 0 to 5, null if the phone does not send them. */
    data class PhoneIndicators(val battery: Int?, val signal: Int?, val operator: String?)

    @Volatile private var hfpProxy: BluetoothProfile? = null
    private var hfpRequested = false

    /** Call off the main thread. The first call only opens the profile: the values come at the next one. */
    fun phoneIndicators(context: Context): PhoneIndicators? {
        val hfp = hfpProxy
        if (hfp == null) {
            if (hfpRequested) return null
            hfpRequested = true
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
            try {
                adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) { hfpProxy = proxy }
                    override fun onServiceDisconnected(p: Int) { hfpProxy = null; hfpRequested = false }
                }, HEADSET_CLIENT)
            } catch (e: Throwable) { Log.w(TAG, "phone indicators: ${e.message}") }
            return null
        }
        return try {
            val phone = hfp.connectedDevices.firstOrNull() ?: return null
            val events = hfp.javaClass.getMethod("getCurrentAgEvents", BluetoothDevice::class.java).invoke(hfp, phone) as? android.os.Bundle ?: return null
            fun level(key: String) = events.getInt("android.bluetooth.headsetclient.extra.$key", -1).takeIf { it in 0..5 }
            PhoneIndicators(level("BATTERY_LEVEL"), level("NETWORK_SIGNAL_STRENGTH"),
                events.getString("android.bluetooth.headsetclient.extra.OPERATOR_NAME")?.takeIf { it.isNotBlank() })
        } catch (e: Throwable) { Log.w(TAG, "phone indicators: ${e.message}"); null }
    }

    /** Asks the phone connected for calls for music again. */
    fun connectMusic(context: Context) {
        if (musicConnected()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        withProxy(context, adapter, HEADSET_CLIENT) { hfp ->
            val phone = hfp.connectedDevices.firstOrNull() ?: return@withProxy
            withProxy(context, adapter, A2DP_SINK) { sink ->
                try {
                    runCatching { sink.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType).invoke(sink, phone, 100) }
                    val ok = sink.javaClass.getMethod("connect", BluetoothDevice::class.java).invoke(sink, phone)
                    Log.i(TAG, "music connect requested: $ok")
                } catch (e: Throwable) { Log.w(TAG, "music connect: ${e.message}") }
            }
        }
    }

    private fun withProxy(context: Context, adapter: BluetoothAdapter, profile: Int, use: (BluetoothProfile) -> Unit) {
        try {
            adapter.getProfileProxy(context.applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try { use(proxy) } finally { adapter.closeProfileProxy(p, proxy) }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        } catch (e: Throwable) { Log.w(TAG, "profile $profile: ${e.message}") }
    }
}
