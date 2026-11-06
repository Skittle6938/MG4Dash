package app.mg4dash

import app.mg4dash.system.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Radio Dash's logcat lines in MG4Dash's log window (#80). */
@RunWith(RobolectricTestRunner::class)
class RadioLogTest {
    private val at = 1_762_448_295_123L
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))

    @Test fun radioDashLine() {
        val l = "1762448295.123  2101  2101 I RadioDash: bande FM demandée : 4 → 1"
        assertEquals(at to "$time I/RadioDash  bande FM demandée : 4 → 1", Log.radioLine(l))
    }

    @Test fun radioOnItsBand() {
        val l = "1762448295.123456  2101  2133 I RadioMBService: changeSource: curBand = 4, isNext = true"
        assertEquals(at to "$time I/RadioMBService  changeSource: curBand = 4, isNext = true", Log.radioLine(l))
    }

    @Test fun othersLeftOut() {
        assertNull(Log.radioLine("1762448295.123  2101  2133 D RadioMBService: onPlaybackStateChanged"))
        assertNull(Log.radioLine("1762448295.123  1000  1000 I MG4L.Media: radio band: x, wanted y"))
        assertNull(Log.radioLine("1762448295.123  900  901 I ActivityManager: Start proc radio"))
        assertNull(Log.radioLine("--------- beginning of main"))
    }

    @Test fun mergedNewestFirst() {
        Log.i("Media", "source picked: com.saicmotor.radio")
        val later = System.currentTimeMillis() + 1000
        val merged = Log.snapshot(listOf(later to "RADIO LINE", 0L to "TOO OLD"))
        assertEquals("RADIO LINE", merged.first())
        assert(merged.none { it == "TOO OLD" })
        assert(merged[1].endsWith("source picked: com.saicmotor.radio"))
    }
}
