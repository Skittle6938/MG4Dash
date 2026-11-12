package app.mg4dash

import android.net.NetworkCapabilities
import app.mg4dash.system.Download
import app.mg4dash.system.Download.Pick
import app.mg4dash.system.Updater
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress

/** Updates that start at once (Wi-Fi client): IPv4 first, a validated network, bounded waits, one at a time, a clear failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateDownloadTest {
    @Test fun ipv4First() {
        val v6 = InetAddress.getByName("2606:50c0:8000::154"); val v4a = InetAddress.getByName("185.199.108.133"); val v4b = InetAddress.getByName("185.199.109.133")
        assertEquals(listOf(v4a, v4b, v6), Download.ipv4First(listOf(v6, v4a, v4b)))
    }

    private fun caps(wifi: Boolean, validated: Boolean): NetworkCapabilities = ShadowNetworkCapabilities.newInstance().also {
        val s = org.robolectric.Shadows.shadowOf(it)
        s.addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        s.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) s.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @Test fun wifiFirstEvenNotValidatedThenMobileThenDefault() {
        val wifi = ShadowNetwork.newInstance(1); val mobile = ShadowNetwork.newInstance(2); val wifi2 = ShadowNetwork.newInstance(3)
        fun nets(vararg p: Pick) = p.map { it.net }
        val o = Download.order(listOf(mobile to caps(false, true), wifi to caps(true, false), wifi2 to caps(true, true)), wifiOnly = false)
        assertEquals("connected Wi-Fi first (validated ones first), then 4G, then the default", listOf(wifi2, wifi, mobile, null), o.map { it.net })
        assertEquals(listOf(true, false, true, false), o.map { it.validated })
        assertEquals("no Wi-Fi: 4G then default", listOf(mobile, null), Download.order(listOf(mobile to caps(false, false)), wifiOnly = false).map { it.net })
        assertEquals("Wi-Fi only: the Wi-Fi alone, even not validated", listOf(wifi), Download.order(listOf(wifi to caps(true, false), mobile to caps(false, true)), wifiOnly = true).map { it.net })
        assertEquals("Wi-Fi without the T-box listed: Wi-Fi, then the T-box asked, then the default", listOf(false, true, false),
            Download.order(listOf(wifi to caps(true, true)), wifiOnly = false).map { it.tbox })
        assertEquals("no network listed: the T-box asked first", listOf(true, false), Download.order(emptyList(), wifiOnly = false).map { it.tbox })
        assertEquals("Wi-Fi only: never the T-box", listOf(false), Download.order(listOf(wifi to caps(true, true)), wifiOnly = true).map { it.tbox })
        val noWifi = try { Download.order(listOf(mobile to caps(false, true)), wifiOnly = true); null } catch (e: Download.Failure) { e.reason }
        assertEquals("Wi-Fi only without a Wi-Fi: said so", Download.Reason.NO_WIFI, noWifi)
    }

    private val defaults = Triple(Download.networks, Download.awaitValidated, Download.attempt)
    @org.junit.After fun restore() { Download.networks = defaults.first; Download.awaitValidated = defaults.second; Download.attempt = defaults.third; Download.sleep = { Thread.sleep(it) } }

    private fun out() = java.io.File(RuntimeEnvironment.getApplication().cacheDir, "t.apk").also { it.delete(); java.io.File(it.path + ".part").delete() }

    @Test fun aNetworkGoneIsReplacedAtOnceWithoutATry() {
        val wifi = ShadowNetwork.newInstance(106); val mobile = ShadowNetwork.newInstance(107)
        val used = mutableListOf<android.net.Network?>()
        var waits = 0
        Download.sleep = { waits++ }
        Download.networks = { listOf(Download.Pick(wifi, "Wi-Fi #106", true), Download.Pick(mobile, "mobile #107", true), Download.Pick(null, "default", false)) }
        Download.attempt = { n, _, part, _, _, _ ->
            used += n
            if (n === wifi) throw Download.Failure(Download.Reason.NETWORK_GONE, cause = java.net.SocketException("ENONET"))
            part.writeText("ok")
        }
        val steps = mutableListOf<Download.Step>()
        Download.fetch(RuntimeEnvironment.getApplication(), "https://example.org/a.apk", out(), 2) { steps += it }
        assertEquals("the Wi-Fi gone: the 4G right away", listOf(wifi, mobile), used)
        assertEquals("no wait, no second try", 0, waits)
        assertTrue(steps.none { it is Download.Step.Connecting && it.attempt > 1 })
    }

    @Test fun wifiNotAnsweringFallsBackToMobileThenDefault() {
        val wifi = ShadowNetwork.newInstance(1); val mobile = ShadowNetwork.newInstance(2)
        val used = mutableListOf<android.net.Network?>()
        Download.sleep = {}
        Download.networks = { listOf(Download.Pick(wifi, "Wi-Fi #1", false), Download.Pick(mobile, "mobile #2", true), Download.Pick(null, "default", false)) }
        Download.attempt = { n, _, part, _, _, _ ->
            used += n
            if (n != null) throw Download.Failure(Download.Reason.NO_ANSWER)
            part.writeText("ok")
        }
        Download.fetch(RuntimeEnvironment.getApplication(), "https://example.org/b.apk", out(), 2) {}
        assertEquals(listOf(wifi, mobile, null), used)
    }

    @Test fun noNetworkWaitsForOneInsteadOfAFixedWait() {
        val wifi = ShadowNetwork.newInstance(5)
        var back = false
        var slept = 0
        val waited = mutableListOf<Long>()
        Download.sleep = { slept++ }
        // Nothing but the default at first; the Wi-Fi comes back while waiting
        Download.networks = { if (back) listOf(Download.Pick(wifi, "Wi-Fi #5", true), Download.Pick(null, "default", false)) else listOf(Download.Pick(null, "default", false)) }
        Download.awaitValidated = { _, ms -> waited += ms; back = waited.size >= 2; back }
        var tries = 0
        Download.attempt = { n, _, part, _, _, _ ->
            tries++
            if (n == null) throw Download.Failure(Download.Reason.NO_ANSWER)
            part.writeText("ok")
        }
        val steps = mutableListOf<Download.Step>()
        Download.fetch(RuntimeEnvironment.getApplication(), "https://example.org/c.apk", out(), 2) { steps += it }
        assertEquals("waited before the first try, then for the network to come back", listOf(Download.FIRST_WAIT_MS, Download.waitBefore(2)), waited)
        assertEquals("no fixed wait", 0, slept)
        assertTrue("said in the update's screen", Download.Step.Waiting in steps)
        assertEquals(2, tries)
        assertEquals(RuntimeEnvironment.getApplication().getString(R.string.upd_waiting_network), Updater.stateText(RuntimeEnvironment.getApplication(), Updater.State.Waiting))
    }
    @Test fun boundedWaits() {
        assertEquals(5_000L, Download.waitBefore(2)); assertEquals(25_000L, Download.waitBefore(6))
        assertTrue(Download.tryFits(2, startedAt = 0, now = 10_000))
        assertFalse("past 3 minutes: no more try", Download.tryFits(3, startedAt = 0, now = Download.TOTAL_MS - 5_000))
        assertFalse("6 tries at most", Download.tryFits(Download.TRIES + 1, 0, 0))
    }

    @Test fun headersAndChunks() {
        val raw = "HTTP/1.1 302 Found\r\nLocation: https://example.org/f\r\n\r\n5\r\nhello\r\n0\r\n\r\n".byteInputStream(Charsets.ISO_8859_1)
        assertEquals("HTTP/1.1 302 Found", Download.line(raw)); assertEquals("Location: https://example.org/f", Download.line(raw)); assertEquals("", Download.line(raw))
        assertEquals("hello", Download.Chunked(raw).readBytes().toString(Charsets.ISO_8859_1))
    }

    @Test fun oneAtATimeThenAClearFailure() {
        Download.sleep = {}
        Download.awaitValidated = { _, _ -> false }
        // No T-box here: Robolectric never answers the network request, which would wait 10 s a try.
        Download.tboxLease = { null }
        val app = RuntimeEnvironment.getApplication()
        // Nothing answers on this port: the download fails at once, with a reason, and can be tried again
        val r = Updater.Release("9.9.9", "http://127.0.0.1:9/MG4Dash.apk", 10_000_000, "")
        assertTrue(Updater.start(app, r))
        assertFalse("a second press while it runs does nothing", Updater.start(app, r))
        val failed = runBlocking { withTimeout(20_000) { Updater.state.first { it is Updater.State.Failed } } } as Updater.State.Failed
        assertEquals(app.getString(R.string.upd_err_no_answer), failed.message)
        assertSame("tried again with the same release", r, failed.release)
        assertFalse(failed.busy)
        assertTrue("free again after the failure", Updater.start(app, r))
        runBlocking { withTimeout(20_000) { Updater.state.first { it is Updater.State.Failed } } }
    }
}
