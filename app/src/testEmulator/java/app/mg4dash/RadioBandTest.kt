package app.mg4dash

import app.mg4dash.media.MediaHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Radio band read from its metadata: DAB and FM are two sources with Radio Dash. */
@RunWith(RobolectricTestRunner::class)
class RadioBandTest {
    @Test fun dabFromTheAuthor() {
        assertEquals(MediaHub.RADIO_DAB, MediaHub.radioBandOf("DAB", "France Info"))
    }

    @Test fun fmAndAmFromTheFrequencyInTheTitle() {
        assertEquals(MediaHub.RADIO_FM, MediaHub.radioBandOf(null, "FM 98.20"))
        assertEquals("AM", MediaHub.radioBandOf(null, "AM 1098"))
    }

    @Test fun unknownWithoutEither() {
        assertNull(MediaHub.radioBandOf(null, ""))
        assertNull(MediaHub.radioBandOf(null, null))
    }
}
