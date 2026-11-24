package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

/**
 * The climate card's settings (#34, 18/11: on another head unit its buttons did nothing; 24/11: the settings' window,
 * every setting a row whose choices open in a window of their own): each row changes its setting, the window is
 * MG4Dash's own and closes with its round button.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h720dp-land-mdpi")
class ClimateSettingsTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)

    private fun open(): android.app.Dialog {
        val act = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        app.mg4dash.ui.TempCalibration.show(act) {}
        idle()
        return ShadowDialog.getLatestDialog()
    }

    private fun all(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out += v; if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out); return out
    }
    private fun byText(d: android.app.Dialog, t: String) = all(d.window!!.decorView).filterIsInstance<TextView>().let { l -> l.firstOrNull { it.text.toString().equals(t, true) } ?: throw AssertionError("no \"$t\" in ${l.map { it.text }}") }

    /** Touches the row titled [title] and picks [choice] in the window it opens. */
    private fun choose(d: android.app.Dialog, title: String, choice: String) {
        var v: View = byText(d, title)
        while (!v.hasOnClickListeners()) v = v.parent as View
        v.performClick(); idle()
        val pick = ShadowDialog.getLatestDialog()
        assertTrue("$title: its choices' window", pick !== d && pick.isShowing)
        var c: View = byText(pick, choice)
        while (!c.hasOnClickListeners()) c = c.parent as View
        c.performClick(); idle()
    }

    @Test fun everyRowWorks() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.climateFxMode = 2; p.tempOffset = 0; p.acCycleOnSec = 300; p.acCycleOffSec = 300
        val d = open()
        choose(d, "Heating / cooling on the card", "Off"); assertEquals(0, p.climateFxMode)
        choose(d, "Heating / cooling on the card", "Text only"); assertEquals(1, p.climateFxMode)
        choose(d, "Temperature calibration", "+1°"); assertEquals(1, p.tempOffset)
        choose(d, "Temperature calibration", "0°"); assertEquals(0, p.tempOffset)
        choose(d, "Compressor on", "7:00"); assertEquals(420, p.acCycleOnSec)
        choose(d, "Compressor off", "2:00"); assertEquals(120, p.acCycleOffSec)
        // The window's own title, no OK: its round button closes it
        assertTrue(d.isShowing)
        assertTrue(all(d.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Climate settings" })
        assertFalse(all(d.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString().equals("OK", true) })
    }
}
