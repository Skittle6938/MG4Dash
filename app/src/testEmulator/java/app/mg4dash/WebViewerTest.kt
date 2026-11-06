package app.mg4dash

import android.content.Intent
import app.mg4dash.system.WebViewer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WebViewerTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun pageIntentCarriesTheAddressColoursAndMessages() {
        val i = WebViewer.intent(app, "http://homeassistant.local:8123/mg4", 0xFF101010.toInt(), 0xFFCCCCCC.toInt())
        assertEquals("http://homeassistant.local:8123/mg4", i.getStringExtra("url"))
        assertEquals(0xFF101010.toInt(), i.getIntExtra("bg", 0))
        assertEquals(0xFFCCCCCC.toInt(), i.getIntExtra("fg", 0))
        assertEquals(app.getString(R.string.wc_web_loading), i.getStringExtra("loading"))
        assertEquals(app.getString(R.string.wc_web_error), i.getStringExtra("error"))
        // A new page in each new card: the old virtual display is gone
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }

    @Test fun emulatorBuildEmbedsNoViewer() {
        // Only the car version carries web.apk (src/car/assets), and does not have it installed here
        assertFalse(WebViewer.bundled(app))
        assertFalse(WebViewer.ready(app))
    }
}
