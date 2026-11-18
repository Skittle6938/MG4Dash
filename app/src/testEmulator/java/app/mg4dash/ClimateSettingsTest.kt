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
import java.util.concurrent.TimeUnit

/**
 * The climate card's settings (#34, 18/11: on another head unit its buttons did nothing and the bottom ones were badly
 * drawn): every button changes its setting, the choices are drawn alike, and the window is MG4Dash's own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "en-w1920dp-h720dp-land-mdpi")
class ClimateSettingsTest {

    private fun open(): android.app.Dialog {
        val act = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        app.mg4dash.ui.TempCalibration.show(act) {}
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        return org.robolectric.shadows.ShadowDialog.getLatestDialog()
    }

    private fun all(v: View, out: MutableList<View> = ArrayList()): List<View> {
        out += v; if (v is ViewGroup) for (i in 0 until v.childCount) all(v.getChildAt(i), out); return out
    }
    private fun byText(d: android.app.Dialog, t: String) = all(d.window!!.decorView).filterIsInstance<TextView>().let { l -> l.firstOrNull { it.text.toString().equals(t, true) } ?: throw AssertionError("no \"$t\" in ${l.map { it.text }}") }

    @Test fun everyButtonWorks() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        p.climateFxMode = 2; p.tempOffset = 0; p.acCycleOnSec = 300
        val d = open()
        byText(d, "Off").performClick(); assertEquals(0, p.climateFxMode)
        byText(d, "Text only").performClick(); assertEquals(1, p.climateFxMode)
        byText(d, "Text and animation").performClick(); assertEquals(2, p.climateFxMode)
        assertTrue(byText(d, "Text and animation").isSelected && !byText(d, "Off").isSelected)
        // The set point offset and the compressor times: the round buttons
        val rounds = all(d.window!!.decorView).filter { it.isClickable && it !is TextView }
        assertTrue("round buttons ${rounds.size}", rounds.size >= 6)
        rounds[1].performClick(); assertEquals(1, p.tempOffset)
        rounds[0].performClick(); assertEquals(0, p.tempOffset)
        rounds[3].performClick(); assertEquals(330, p.acCycleOnSec)
        // Reset, then OK closes it
        rounds[1].performClick(); byText(d, "Reset").performClick(); assertEquals(0, p.tempOffset)
        assertTrue(d.isShowing)
        // (the OK of the framework, a pseudo-locale text under Robolectric): the last button
        all(d.window!!.decorView).filterIsInstance<TextView>().last().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(d.isShowing)
    }

    @Test fun choicesDrawnAlike() {
        val d = open()
        val dv = d.window!!.decorView
        for (w in listOf(900, 700, 560)) {
            dv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            dv.layout(0, 0, w, dv.measuredHeight)
            val chips = listOf("Off", "Text only", "Text and animation").map { byText(d, it) }
            fun at(v: View) = IntArray(2).also { v.getLocationInWindow(it) }
            assertTrue("$w: same width ${chips.map { it.width }}", chips.map { it.width }.distinct().size == 1 || chips.maxOf { it.width } - chips.minOf { it.width } <= 1)
            assertEquals("$w: same height", 1, chips.map { it.height }.distinct().size)
            assertEquals("$w: one line", 1, chips.map { at(it)[1] }.distinct().size)
            // Each label whole in its chip
            for (c in chips) assertTrue("$w: ${c.text} cut", c.layout != null && c.layout.lineCount == 1 && c.layout.getEllipsisCount(0) == 0)
            // The window's own title, not the car's
            assertTrue(all(dv).filterIsInstance<TextView>().any { it.text.toString() == "Climate settings" })
        }
    }
}
