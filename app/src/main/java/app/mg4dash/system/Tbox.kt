package app.mg4dash.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The T-box 4G on demand. With the car's Wi-Fi client on, Android drops the T-box network (an Ethernet
 * link to the head unit) because nothing needs it, though tbox0 keeps its address: asking for a wired
 * network with internet brings it back next to the Wi-Fi, for as long as the [Lease] is held. The Wi-Fi
 * client and Android's default network do not change.
 */
object Tbox {
    private const val TAG = "Update"
    const val WAIT_MS = 10_000L

    class Lease(val network: Network, private val cm: ConnectivityManager, private val callback: ConnectivityManager.NetworkCallback) : Closeable {
        override fun close() { try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {} }
    }

    /** The T-box network within [ms], null if Android has none to give. Off the main thread. */
    fun request(context: Context, ms: Long = WAIT_MS): Lease? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val found = AtomicReference<Network?>()
        val ready = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { found.compareAndSet(null, network); ready.countDown() }
            override fun onUnavailable() { ready.countDown() }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try { cm.requestNetwork(req, cb, ms.toInt()) } catch (e: Exception) { Log.w(TAG, "T-box request refused: ${e.message}"); return null }
        try { ready.await(ms + 1_000, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val n = found.get()
        if (n == null) {
            Log.w(TAG, "no T-box network within ${ms / 1000} s")
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
            return null
        }
        return Lease(n, cm, cb)
    }
}
