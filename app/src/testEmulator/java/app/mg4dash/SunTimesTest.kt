package app.mg4dash

import app.mg4dash.system.SunTimes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Sunrise and sunset for the automatic night mode (#64), against published times (± 10 min). */
class SunTimesTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    private fun assertNear(expected: Long, actual: Long, toleranceMin: Int = 10) =
        assertTrue("expected ${expected} ± $toleranceMin min, got $actual (${(actual - expected) / 60_000} min)",
            kotlin.math.abs(actual - expected) <= toleranceMin * 60_000L)

    @Test fun parisSummerSolstice() {
        // Paris 21 June: sunrise 03:47 UTC, sunset 19:58 UTC
        val (rise, set) = SunTimes.of(48.8566, 2.3522, utc(2026, 6, 21))!!
        assertNear(utc(2026, 6, 21, 3, 47), rise)
        assertNear(utc(2026, 6, 21, 19, 58), set)
    }

    @Test fun parisWinterSolstice() {
        // Paris 21 December: sunrise 07:41 UTC, sunset 15:56 UTC
        val (rise, set) = SunTimes.of(48.8566, 2.3522, utc(2026, 12, 21))!!
        assertNear(utc(2026, 12, 21, 7, 41), rise)
        assertNear(utc(2026, 12, 21, 15, 56), set)
    }

    @Test fun sameDayWhateverTheHour() {
        // Early in the morning and late in the evening (UTC) still give the times of that local day
        val noon = SunTimes.of(48.8566, 2.3522, utc(2026, 3, 20))!!
        val morning = SunTimes.of(48.8566, 2.3522, utc(2026, 3, 20, 0, 5))!!
        val evening = SunTimes.of(48.8566, 2.3522, utc(2026, 3, 20, 22, 30))!!
        assertNear(noon.first, morning.first, 2); assertNear(noon.first, evening.first, 2)
    }

    @Test fun riseBeforeSet() {
        for (month in 1..12) {
            val (rise, set) = SunTimes.of(45.0, 5.0, utc(2026, month, 15))!!
            assertTrue("month $month", rise < set)
        }
    }

    @Test fun polarNightAndMidnightSun() {
        assertNull(SunTimes.of(78.2, 15.6, utc(2026, 12, 21)))   // Svalbard, polar night
        assertNull(SunTimes.of(78.2, 15.6, utc(2026, 6, 21)))    // Svalbard, midnight sun
        assertNotNull(SunTimes.of(60.0, 10.0, utc(2026, 12, 21))) // Oslo still has a day
    }
}
