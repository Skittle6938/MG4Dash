package app.mg4dash

import app.mg4dash.system.Download
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A check where no network resolves names ends with "no internet", not minutes of "checking" (27/11, #137). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateCheckBoundTest {
    private val nets = Download.networks
    @After fun restore() { Download.networks = nets }

    @Test fun noNameResolvedIsNoInternet() {
        Download.networks = { listOf(Download.Pick(null, "a", false), Download.Pick(null, "b", false)) }
        var calls = 0
        val e = try { Download.firstWorking(RuntimeEnvironment.getApplication(), "check", host = "no-such-host.invalid", totalMs = 20_000) { calls++ }; null }
            catch (x: Download.NoInternet) { x }
        assertTrue("said as no internet", e != null)
        assertEquals("no request made without a name", 0, calls)
    }

    @Test fun theCheckStopsOnceItsTimeIsGone() {
        Download.networks = { listOf(Download.Pick(null, "a", false), Download.Pick(null, "b", false)) }
        var calls = 0
        try { Download.firstWorking(RuntimeEnvironment.getApplication(), "check", totalMs = 50) { calls++; Thread.sleep(80); throw java.io.IOException("slow") } }
        catch (_: Exception) { }
        assertEquals("the second network left", 1, calls)
    }
}
