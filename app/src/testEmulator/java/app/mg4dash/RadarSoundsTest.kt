package app.mg4dash

import app.mg4dash.radar.RadarAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Every alert sound offered (24/11: six more) has its file and its name, and the names differ. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr")
class RadarSoundsTest {
    @Test fun everySoundHasItsFileAndName() {
        val c = RuntimeEnvironment.getApplication()
        val sounds = RadarAlert.Sound.values()
        assertEquals(10, sounds.size)
        for (s in sounds) {
            assertTrue(s.key, c.getString(s.label).isNotBlank())
            if (s != RadarAlert.Sound.NONE) c.resources.openRawResource(s.res!!).use { assertTrue(s.key, it.available() > 1000) }
        }
        assertEquals(sounds.size, sounds.map { c.getString(it.label) }.toSet().size)
    }
}
