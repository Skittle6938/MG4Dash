package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.mg4dash.ui.HomeSlots.Layout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Edit mode: in every place, every widget and option can be reached (they scroll when the place is too short). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class EditModeReachTest {
    private fun scrolls(v: View, out: MutableList<ScrollView> = mutableListOf()): List<ScrollView> {
        if (v is ScrollView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) scrolls(v.getChildAt(i), out)
        return out
    }

    @Test fun everyOptionReachableInEveryPlace() {
        // The map and the web app ticked together in places of each size: the most options at once
        val specs = mapOf(
            Layout.FULL to "tl=climate;tc=map,web,consumption;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone",
            Layout.MAP_CENTER to "tl=hourly,daily;bl=climate;c=map,web;tr=media;br=energy,trip,power,phone",
            Layout.SQUARE_LEFT to "g=map,web,shortcuts;tr=climate;br=energy,trip,power,phone,hourly,daily",
            Layout.BAND_TOP to "w=map,web;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone",
        )
        val sp = RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        for ((layout, spec) in specs) {
            sp.edit().clear().putBoolean("vertical_dock", true).putString("home_layout", layout.key).putString("slots_" + layout.key, spec).commit()
            val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
            val act = ctl.get()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            act.editCards()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            val list = scrolls(act.window.decorView)
            assertTrue("${layout.key}: a scroll area per place", list.size >= layout.slots.size)
            list.forEach { s ->
                val content = s.getChildAt(0)
                s.scrollTo(0, content.height)
                // The end of the options is in sight once scrolled
                assertTrue("${layout.key}: options reachable (${content.height} in ${s.height}, scrolled ${s.scrollY})",
                    content.height <= s.height || s.scrollY + s.height >= content.height - 1)
                assertTrue("${layout.key}: its bar shown when it scrolls", content.height <= s.height || s.isVerticalScrollBarEnabled)
            }
            ctl.pause().stop().destroy()
        }
    }
}
