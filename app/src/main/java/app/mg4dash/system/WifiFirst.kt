package app.mg4dash.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * MG4Dash's own traffic on the Wi-Fi first (owner's choice): the map, the weather, Home Assistant, the update checks.
 * As soon as a Wi-Fi is connected and a quick check (3 s at most) gets through it, the whole MG4Dash process uses
 * it, even before Android validates it (its check can lag behind a phone's hotspot); a Wi-Fi without internet (a
 * hotspot without data) leaves Android's default network (the 4G). Checked again every 2 minutes and when a
 * request fails, so that it goes back to the Wi-Fi once it works again. Other apps and the system settings are not
 * touched. A Home Assistant on the home network (private address, .local) is only reached through the Wi-Fi.
 */
object WifiFirst {
    private const val TAG = "WifiFirst"
    private const val CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204"
    const val CHECK_MS = 3_000
    const val RECHECK_MS = 120_000L

    /** The Wi-Fi now connected, whether it has internet or not. */
    @Volatile var wifi: Network? = null
        private set
    /** The network the process uses (the Wi-Fi), null: Android's default. */
    @Volatile var bound: Network? = null
        private set

    // Replaced by the tests
    internal var check: (Network) -> Boolean = { probe(it) }
    internal var bind: (Network?) -> Unit = { n -> cm?.bindProcessToNetwork(n) }
    internal var async: (Runnable) -> Unit = { r -> Thread(r, TAG).start() }

    private var cm: ConnectivityManager? = null
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val recheck = Runnable { evaluate("every ${RECHECK_MS / 60_000} min") }

    fun start(context: Context) {
        if (cm != null) return
        val c = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = c
        try {
            c.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = wifiUp(network)
                override fun onLost(network: Network) = wifiDown(network)
            })
        } catch (e: Exception) { Log.w(TAG, "no network callback: ${e.message}") }
    }

    /** A Wi-Fi is connected: checked at once. */
    internal fun wifiUp(network: Network) {
        wifi = network
        evaluate("Wi-Fi #$network connected")
    }

    internal fun wifiDown(network: Network) {
        if (wifi != network) return
        wifi = null
        if (bound == network) use(null, "Wi-Fi #$network lost")
    }

    /** A request failed: the Wi-Fi checked again now (back to the 4G if it no longer gets through, or to the Wi-Fi). */
    fun recheck() = evaluate("a request failed")

    private fun evaluate(why: String) {
        handler.removeCallbacks(recheck)
        val w = wifi ?: return
        async(Runnable {
            val ok = try { check(w) } catch (_: Exception) { false }
            if (wifi != w) return@Runnable
            if (ok && bound != w) use(w, "$why: internet through the Wi-Fi")
            else if (!ok && bound != null) use(null, "$why: no internet through the Wi-Fi")
            else if (!ok) Log.i(TAG, "$why: no internet through the Wi-Fi, Android's default network kept")
            handler.postDelayed(recheck, RECHECK_MS)
        })
    }

    private fun use(n: Network?, why: String) {
        bound = n
        try { bind(n) } catch (e: Exception) { Log.w(TAG, "bind: ${e.message}") }
        Log.i(TAG, "$why → MG4Dash on ${if (n != null) "the Wi-Fi #$n" else "Android's default network"}")
    }

    /** One answer through [n] within 3 s: Google's empty page (204), not a hotspot's login page. */
    private fun probe(n: Network): Boolean {
        val c = n.openConnection(URL(CHECK_URL)) as HttpURLConnection
        return try {
            c.connectTimeout = CHECK_MS; c.readTimeout = CHECK_MS; c.instanceFollowRedirects = false; c.useCaches = false
            c.responseCode == 204
        } finally { c.disconnect() }
    }

    /** An address of the home network: private IPv4 or IPv6, link-local, .local, or a name without a dot. */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") || (!h.contains('.') && !h.contains(':'))) return true
        val ip = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(h) || h.contains(':')
        if (!ip) return false
        return try { InetAddress.getByName(h).let { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress || (h.startsWith("fd") || h.startsWith("fc")) } }
        catch (_: Exception) { false }
    }

    /** The home network is out of reach: no Wi-Fi connected. */
    class NoWifi(host: String) : java.io.IOException("$host is on the home network: Wi-Fi needed")

    /**
     * A connection to [url]: through the Wi-Fi for an address of the home network (never the 4G, it cannot reach it;
     * [NoWifi] without one), otherwise the process's network.
     */
    fun open(url: URL): HttpURLConnection {
        if (isLocal(url.host)) {
            val w = wifi ?: throw NoWifi(url.host)
            return w.openConnection(url) as HttpURLConnection
        }
        return url.openConnection() as HttpURLConnection
    }

    /** For the tests. */
    internal fun reset() { wifi = null; bound = null }
}
