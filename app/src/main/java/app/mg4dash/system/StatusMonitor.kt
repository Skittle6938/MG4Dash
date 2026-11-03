package app.mg4dash.system

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

/** Bluetooth / Wi-Fi state for the top bar. Read only. */
object StatusMonitor {
    data class Status(val btOn: Boolean, val btConnected: Boolean, val wifiOn: Boolean, val wifiConnected: Boolean)

    // Car side profiles ("sink" role): A2DP_SINK = 11, HEADSET_CLIENT = 16 (hidden constants).
    private val PROFILES = intArrayOf(11, 16, BluetoothProfile.A2DP, BluetoothProfile.HEADSET)

    fun read(context: Context): Status {
        val bt = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Exception) { null }
        val btOn = bt?.isEnabled == true
        val btConnected = btOn && PROFILES.any { p ->
            try { bt?.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED } catch (_: Exception) { false }
        }
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = try { cm?.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
        return Status(
            btOn = btOn,
            btConnected = btConnected,
            wifiOn = wifi?.isWifiEnabled == true,
            wifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
        )
    }

    /** Home connection icons. [signalLevel]: null if the car has no readable T-box. */
    data class Connections(
        val btOn: Boolean, val btConnected: Boolean, val btName: String?,
        val wifiOn: Boolean, val wifiConnected: Boolean, val wifiName: String?,
        val hotspotOn: Boolean, val signalLevel: Int?, val signalMode: Int, val usbCount: Int,
    )

    const val SIGNAL_MAX = 4
    private const val CSQ_MAX = 31

    /** Call off the main thread: T-box service and storage. */
    fun connections(context: Context): Connections {
        val s = read(context)
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val btName = if (!s.btConnected) null else try {
            BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { d ->
                d.javaClass.getMethod("isConnected").invoke(d) == true
            }?.name
        } catch (_: Throwable) { null }
        val ssid = if (!s.wifiConnected) null else try {
            wm?.connectionInfo?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        } catch (_: Exception) { null }
        // Hidden method, like the stock banner (SystemUI's ApController)
        val ap = try { wm?.javaClass?.getMethod("isWifiApEnabled")?.invoke(wm) == true } catch (_: Throwable) { false }
        val tbox = try { app.mg4dash.car.CarBridge.tbox() } catch (_: Exception) { null }
        // Same calculation as the stock banner (SignalController): strength 0 to 31, in steps
        val level = try {
            tbox?.let { t -> if (!t.connected) 0 else (t.csq.coerceIn(0, CSQ_MAX) * SIGNAL_MAX / CSQ_MAX) }
        } catch (_: Exception) { null }
        val mode = try { tbox?.wanStatus ?: 0 } catch (_: Exception) { 0 }
        val storage = context.getSystemService(Context.STORAGE_SERVICE) as? android.os.storage.StorageManager
        val usb = try {
            storage?.storageVolumes?.count { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED } ?: 0
        } catch (_: Exception) { 0 }
        return Connections(s.btOn, s.btConnected, btName, s.wifiOn, s.wifiConnected, ssid, ap, level, mode.coerceIn(0, 4), usb)
    }

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
