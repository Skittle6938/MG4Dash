package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AllGo's position units (#135, diagnosed by Geekicedev): ms on SWI69 with CarPlay, seconds on older firmwares. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AllgoPositionTest {
    private val allgo = "com.allgo.remoteui.mediabrowserservice"

    @Test fun millisecondsOnSwi69() {
        assertEquals(20_618L, MediaHub.allgoPosition(allgo, 20_618, 219_456))
        assertEquals(5_000L, MediaHub.allgoPosition(allgo, 5_000, 219_456))
    }

    @Test fun secondsOnOlderFirmwares() {
        assertEquals(20_000L, MediaHub.allgoPosition(allgo, 20, 219))
        assertEquals(95_000L, MediaHub.allgoPosition(allgo, 95, 219))
        // A small position that fits a seconds scale within a duration given in ms
        assertEquals(42_000L, MediaHub.allgoPosition(allgo, 42, 219_000))
    }

    @Test fun othersUntouched() {
        assertEquals(20L, MediaHub.allgoPosition("com.android.bluetooth", 20, 219))
        assertEquals(0L, MediaHub.allgoPosition(allgo, 0, 219_456))
        assertEquals(-1L, MediaHub.allgoPosition(allgo, -1, 219_456))
    }
}
