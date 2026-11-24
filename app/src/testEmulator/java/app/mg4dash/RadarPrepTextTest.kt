package app.mg4dash

import app.mg4dash.radar.RadarRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The preparation row with nothing running (24/11): what is stored, not "no preparation" when zones are there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr")
class RadarPrepTextTest {
    private val c get() = RuntimeEnvironment.getApplication()

    @Test fun nothingStored() = assertEquals(c.getString(R.string.rp_prep_none), RadarRepo.storedPreparation(c, 0, 0L))

    @Test fun zonesWithoutDate() = assertEquals("640 zones déjà téléchargées", RadarRepo.storedPreparation(c, 640, 0L))

    @Test fun zonesWithTheirDate() {
        val at = java.util.GregorianCalendar(2026, 10, 20, 9, 0).timeInMillis
        val t = RadarRepo.storedPreparation(c, 640, at)
        assertTrue(t, t.startsWith("Préparé le ") && t.contains("2026") && t.endsWith("· 640 zones"))
    }

    /** The day is kept when a preparation ends, and read back. */
    @Test fun theDayIsKept() {
        c.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putLong(RadarRepo.PREPARED_AT_KEY, 1234L).commit()
        assertEquals(1234L, RadarRepo.preparedAt(c))
    }
}
