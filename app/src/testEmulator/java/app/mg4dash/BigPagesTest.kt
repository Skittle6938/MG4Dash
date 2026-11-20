package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Grand affichage, step 5: the pages' panels that do not fit scroll, never cut; pages with their own lists keep them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BigPagesTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    private fun <T : android.app.Activity> open(cls: Class<T>): T = Robolectric.buildActivity(cls).setup().get().also {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = it.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    private fun bodyScroll(a: android.app.Activity): ScrollView? {
        fun find(v: View): ScrollView? {
            if (v is ScrollView && v.isFillViewport) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        return find(a.window.decorView)
    }

    @Test fun panelsScrollInTheMode() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putInt("home_style", Prefs.STYLE_BIG).commit()
        for (cls in listOf(app.mg4dash.pages.EnergyActivity::class.java, app.mg4dash.pages.VehicleActivity::class.java,
            app.mg4dash.pages.ClimateActivity::class.java, app.mg4dash.pages.SoundActivity::class.java)) {
            val sv = bodyScroll(open(cls))
            assertTrue("${cls.simpleName}: its body scrolls", sv != null)
            val content = sv!!.getChildAt(0)
            // Everything reachable: scrolled to the end, the content's end is in sight
            sv.scrollTo(0, content.height)
            assertTrue("${cls.simpleName}: ${content.height} in ${sv.height}", content.height <= sv.height || sv.scrollY + sv.height >= content.height - 1)
        }
        // The phone and trips pages scroll their own lists
        assertEquals(null, bodyScroll(open(app.mg4dash.pages.PhoneActivity::class.java)))
        assertEquals(null, bodyScroll(open(app.mg4dash.pages.TripsActivity::class.java)))
    }

    @Test fun normalSizeUnchanged() {
        prefs.raw.edit().putBoolean("vertical_dock", true).putInt("home_style", 0).commit()
        assertEquals(null, bodyScroll(open(app.mg4dash.pages.EnergyActivity::class.java)))
    }
}
