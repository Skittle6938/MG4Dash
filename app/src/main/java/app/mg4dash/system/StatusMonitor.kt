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
        /** Wi-Fi strength, 0 to 3 (#86). */
        val wifiLevel: Int,
        /** Phone connected over Bluetooth, null if there is none (#86). */
        val phone: BtAudio.PhoneIndicators? = null,
        /** Devices connected to the hotspot, as the stock status bar counts them (#86). */
        val hotspotClients: Int = 0,
        /** Stick mounted on each port (#86): USB-A is the stock "USB2", USB-C its "USB1". */
        val usbA: Boolean = false, val usbC: Boolean = false,
    )

    /**
     * Stick on USB-A, on USB-C: read like the stock status bar (UsbDeviceController), from the USB controller
     * in the disk's system path. If a mounted stick matches neither, it counts on USB-C.
     */
    private fun usbPorts(storage: android.os.storage.StorageManager?, mounted: Int): Pair<Boolean, Boolean> {
        var a = false; var c = false
        try {
            (storage?.javaClass?.getMethod("getVolumes")?.invoke(storage) as? List<*>)?.forEach { v ->
                if (v == null || v.javaClass.getMethod("getState").invoke(v) != VOLUME_MOUNTED) return@forEach
                val disk = v.javaClass.getMethod("getDisk").invoke(v) ?: return@forEach
                val path = disk.javaClass.getField("sysPath").get(disk) as? String ?: return@forEach
                if (USB_C_CONTROLLER in path) c = true else if (USB_A_CONTROLLER in path) a = true
            }
        } catch (e: Exception) { Log.w("Status", "USB ports: ${e.message}") }
        return a to (c || (!a && mounted > 0))
    }
    private const val VOLUME_MOUNTED = 2
    private const val USB_C_CONTROLLER = "11271000.usb"
    private const val USB_A_CONTROLLER = "112c1000.usb"

    const val SIGNAL_MAX = 4
    private const val WIFI_LEVELS = 4
    private const val CSQ_MAX = 31

    @Volatile private var hotspotClients = 0
    private var hotspotCallback: Any? = null

    /** Number of hotspot clients: only given by a callback, the one the stock status bar uses (hidden API). */
    private fun watchHotspotClients(wm: WifiManager?): Int {
        if (hotspotCallback == null && wm != null) try {
            val type = Class.forName("android.net.wifi.WifiManager\$SoftApCallback")
            val callback = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
                when (method.name) {
                    "onNumClientsChanged" -> { hotspotClients = args?.get(0) as? Int ?: 0; null }
                    // WifiManager keeps the callback in a map
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.get(0)
                    "toString" -> "SoftApCallback"
                    else -> null
                }
            }
            wm.javaClass.getMethod("registerSoftApCallback", type, android.os.Handler::class.java)
                .invoke(wm, callback, android.os.Handler(android.os.Looper.getMainLooper()))
            hotspotCallback = callback
        } catch (e: Throwable) { Log.w("Status", "hotspot clients: ${e.cause ?: e}") }
        return hotspotClients
    }

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
        val wifiLevel = try {
            WifiManager.calculateSignalLevel(wm?.connectionInfo?.rssi ?: -127, WIFI_LEVELS)
        } catch (_: Exception) { 0 }
        val (usbA, usbC) = usbPorts(storage, usb)
        return Connections(s.btOn, s.btConnected, btName, s.wifiOn, s.wifiConnected, ssid, ap, level, mode.coerceIn(0, 4), usb, wifiLevel, BtAudio.phoneIndicators(context), if (ap) watchHotspotClients(wm) else 0, usbA, usbC)
    }

    fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
