package app.mg4dash

import android.net.Network
import app.mg4dash.system.WifiFirst
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

/** MG4Dash's own traffic on the Wi-Fi first, the 4G when the Wi-Fi does not get through, the home network only by Wi-Fi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WifiFirstTest {
    private val binds = mutableListOf<Network?>()
    private var internet = true
    private val defaults = Triple(WifiFirst.check, WifiFirst.bind, WifiFirst.async)

    @Before fun fake() {
        WifiFirst.reset()
        WifiFirst.check = { internet }
        WifiFirst.bind = { binds += it }
        WifiFirst.async = { it.run() }
    }

    @After fun restore() { WifiFirst.check = defaults.first; WifiFirst.bind = defaults.second; WifiFirst.async = defaults.third; WifiFirst.reset() }

    @Test fun wifiWithInternetTakenAtOnceEvenNotValidated() {
        val w = ShadowNetwork.newInstance(106)
        WifiFirst.wifiUp(w)
        assertSame(w, WifiFirst.bound)
        assertEquals(listOf<Network?>(w), binds)
    }

    @Test fun hotspotWithoutDataLeavesTheDefault() {
        internet = false
        WifiFirst.wifiUp(ShadowNetwork.newInstance(7))
        assertNull("the 4G kept", WifiFirst.bound)
        assertTrue("nothing bound", binds.isEmpty())
    }

    @Test fun backTo4gWhenTheWifiStopsThenBackToTheWifi() {
        val w = ShadowNetwork.newInstance(8)
        WifiFirst.wifiUp(w)
        internet = false
        WifiFirst.recheck()
        assertNull(WifiFirst.bound)
        internet = true
        WifiFirst.recheck()
        assertSame(w, WifiFirst.bound)
        assertEquals(listOf(w, null, w), binds)
    }

    @Test fun wifiLostBackToTheDefault() {
        val w = ShadowNetwork.newInstance(9)
        WifiFirst.wifiUp(w)
        WifiFirst.wifiDown(w)
        assertNull(WifiFirst.bound)
        assertNull(WifiFirst.wifi)
        assertEquals(listOf(w, null), binds)
    }

    @Test fun homeNetworkAddresses() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.1", "homeassistant.local", "homeassistant", "nas.lan", "fe80::1", "fd12::5", "127.0.0.1")
            .forEach { assertTrue(it, WifiFirst.isLocal(it)) }
        listOf("abc123.ui.nabu.casa", "8.8.8.8", "my.duckdns.org", "2606:4700::1111").forEach { assertFalse(it, WifiFirst.isLocal(it)) }
    }

    @Test fun homeAssistantAtHomeNeedsTheWifi() {
        val e = try { WifiFirst.open(java.net.URL("http://192.168.1.20:8123/api/")); null } catch (x: WifiFirst.NoWifi) { x }
        assertTrue("a clear error, no pointless try on the 4G", e != null)
    }
}
