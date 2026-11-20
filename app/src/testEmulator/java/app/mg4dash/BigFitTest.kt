package app.mg4dash

import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.system.Prefs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Grand affichage, Marc's first car test (20/11): buttons wholly inside their window or their place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigFitTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun home(layout: String = "big_quad", spec: String? = null): MainActivity {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG)
            .putString("home_layout_big", layout).apply { if (spec != null) putString("slots_$layout", spec) }.commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle(); lay(it) }
    }

    private fun lay(a: android.app.Activity) {
        val root = a.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    private fun rect(v: View) = IntArray(2).also { v.getLocationInWindow(it) }.let { Rect(it[0], it[1], it[0] + v.width, it[1] + v.height) }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    /** Keep climate on: Close and Start inside the screen, its settings scroll. */
    @Test fun keepClimateOnButtonsInSight() {
        val a = home()
        for (scheduled in listOf(false, true)) {
            prefs.raw.edit().putBoolean("climhold_scheduled", scheduled).commit()
            app.mg4dash.climate.ClimateHoldPanel.show(a); idle(); lay(a)
            val screen = Rect(0, 0, 1920, 720)
            for (label in listOf(R.string.dm_close, R.string.dm_start).map { a.getString(it) }) {
                val b = texts(a.window.decorView).last { it.text.toString() == label }
                assertTrue("$label ${rect(b)} inside the screen (scheduled $scheduled)", b.isShown && screen.contains(rect(b)))
            }
        }
    }

    /**
     * The connection bar placed on another card than the weather (Marc, 20/11): shown at the top of that card, over it,
     * in every size of place of the mode.
     */
    @Test fun connectionBarOnAnyCard() {
        for ((layout, spec, slot) in listOf(
            Triple("big_quad", "tl=climate;bl=energy;tr=media;br=hourly", "bl"),
            Triple("big_quad", "tl=climate;bl=trip;tr=media;br=hourly", "bl"),
            Triple("big_quad", "tl=climate;bl=energy;tr=media;br=hourly", "tl"),
            Triple("big_left", "l=map,consumption;tr=climate,energy;br=media", "l"),
            Triple("big_band_top", "t=energy;bl=climate,hourly;br=media", "t"),
        )) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", Prefs.STYLE_BIG)
                .putString("home_layout_big", layout).putString("slots_$layout", spec).putString("conn_slot_$layout", slot)
                .putStringSet("conn_icons_hidden", emptySet()).commit()
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle(); lay(it) }
            val row = MainActivity::class.java.getDeclaredField("connections").apply { isAccessible = true }.get(a) as View?
            assertTrue("$layout/$slot: a bar", row != null)
            @Suppress("UNCHECKED_CAST")
            val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(a) as Map<String, Any>
            val box = frames.getValue(slot).let { f -> f.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(f) as ViewGroup }
            assertTrue("$layout/$slot: in its place", row!!.parent === box && row.isShown && row.width > 0 && row.height > 0)
            assertTrue("$layout/$slot: inside it", rect(box).contains(rect(row)))
            // Drawn over the card shown there (the cards have an elevation of their own)
            val shown = (0 until box.childCount).map { box.getChildAt(it) }.filter { it !== row && it.visibility == View.VISIBLE }
            assertTrue("$layout/$slot: over the card (z ${row.z} vs ${shown.map { it.z }})", shown.all { it.z <= row.z })
        }
    }

    /** The media card in a quarter, a long title and an artist (Android Auto): its buttons stay inside the place. */
    @Test fun mediaButtonsInsideTheQuarter() {
        val a = home(spec = "tl=climate;bl=energy;tr=media;br=hourly")
        val f = app.mg4dash.media.MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.media.MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.media.MediaHub.NowPlaying>).value =
            app.mg4dash.media.MediaHub.NowPlaying(true, "com.google.android.projection.gearhead", "Android Auto",
                "A very long song title that would take two lines in a small place", "An artist with a long name", "Album", null, true, 240_000, 60_000)
        idle(); lay(a)
        val card = rect(a.findViewById<View>(R.id.mediaInfo))
        for (id in listOf(R.id.mediaPlay, R.id.mediaPrev, R.id.mediaNext)) {
            val r = rect(a.findViewById(id))
            assertTrue("button $r inside the media card $card", card.contains(r))
        }
    }

    /** Settings kept at their normal size in the mode, but their bar the same as home's: it does not jump (Marc, 20/11). */
    @Test fun settingsBarSameAsHome() {
        val home = home()
        val homeBar = rect(home.findViewById(R.id.dock))
        val s = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get().also { idle(); lay(it) }
        assertTrue("Settings at their own size", s.resources.displayMetrics.density < 1.01f)
        val bar = rect(s.findViewById(R.id.dock))
        assertTrue("bar $bar vs home's $homeBar", Math.abs(bar.width() - homeBar.width()) <= 1 && Math.abs(bar.left - homeBar.left) <= 1 &&
            Math.abs(bar.top - homeBar.top) <= 1 && Math.abs(bar.bottom - homeBar.bottom) <= 1)
    }
}