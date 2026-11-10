package app.mg4dash

import android.net.NetworkCapabilities
import app.mg4dash.system.Download
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

    @Test fun validatedNetworkChosen() {
        val wifi = ShadowNetwork.newInstance(1); val mobile = ShadowNetwork.newInstance(2)
        assertSame("a working Wi-Fi first", wifi, Download.choose(listOf(mobile to caps(false, true), wifi to caps(true, true)), wifiOnly = false))
        assertSame("a Wi-Fi client without internet: the 4G", mobile, Download.choose(listOf(wifi to caps(true, false), mobile to caps(false, true)), wifiOnly = false))
        assertNull("nothing validated: Android's default", Download.choose(listOf(wifi to caps(true, false)), wifiOnly = false))
        val noWifi = try { Download.choose(listOf(wifi to caps(true, false), mobile to caps(false, true)), wifiOnly = true); null } catch (e: Download.Failure) { e.reason }
        assertEquals("Wi-Fi only, its internet not working: said so", Download.Reason.NO_WIFI, noWifi)
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
