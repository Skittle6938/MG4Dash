package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import app.mg4dash.cards.HomeCard
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots.Card
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Every card's options window and the other windows fit the 1920 × 720 screen (24/11: the driving window did not, and
 * did not scroll): the window is no taller than the screen, and when its content is taller it scrolls to its last row.
 * Each one is drawn scrolled to its end in build/screenshots/fit/, and those that needed to scroll are listed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class WindowsFitTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }
    private val scrolled = mutableListOf<String>()

    private fun scrollers(v: View): List<ScrollView> = (if (v is ScrollView) listOf(v) else emptyList()) +
        ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { scrollers(g.getChildAt(it)) } } ?: emptyList())

    /** The latest window at its own width and the screen's height: within it, and scrolled to its end it shows its last row. */
    private fun check(a: Activity, name: String) {
        idle()
        val d = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing } ?: return
        val w = d.window!!; val dv = w.decorView
        val screenH = 720
        dv.measure(View.MeasureSpec.makeMeasureSpec(if (w.attributes.width > 0) w.attributes.width else 1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        assertTrue("$name: ${dv.measuredHeight} within $screenH", dv.measuredHeight <= screenH)
        for (s in scrollers(dv)) {
            val child = s.getChildAt(0) ?: continue
            if (child.height > s.height) {
                scrolled += name
                s.scrollTo(0, child.height)
                assertTrue("$name: scrolled to its end", s.scrollY + s.height >= child.height - 2)
            }
        }
        val bmp = Bitmap.createBitmap(dv.measuredWidth, dv.measuredHeight, Bitmap.Config.ARGB_8888).also { dv.draw(Canvas(it)) }
        File(File("build/screenshots/fit").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        d.dismiss(); idle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun cards(a: MainActivity) = MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(a) as Map<Card, HomeCard>

    @Test fun everyWindowFits() {
        for (big in listOf(false, true)) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
                .putInt("home_style", if (big) Prefs.STYLE_BIG else 0).putString("home_layout", "full")
                .putString("slots_full", "tl=climate,hourly;tc=consumption,map,radar;tr=shortcuts;bl=energy,driving;bc=power,daily;br=trip,phone").commit()
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
            val tag = if (big) "_big" else ""
            for ((card, h) in cards(a)) {
                ShadowDialog.reset()
                if (h.view.isLongClickable && h.view.performLongClick()) check(a, "options_${card.id}$tag")
            }
            ShadowDialog.reset(); app.mg4dash.ui.DrivingCard.openAll(a); check(a, "driving_all$tag")
            ShadowDialog.reset(); app.mg4dash.settings.ProfileWindow.edit(a, null) {}; check(a, "profile$tag")
            ShadowDialog.reset(); app.mg4dash.ui.UninstallWindow.show(a); check(a, "uninstall$tag")
            // The Settings page's own windows (24/11: Cards offered was cut on the car and this test did not open it)
            val s = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get().also { idle() }
            ShadowDialog.reset()
            app.mg4dash.settings.SettingsActivity::class.java.getDeclaredMethod("cardsOffered").apply { isAccessible = true }.invoke(s)
            check(s, "cards_offered$tag")
            ShadowDialog.reset()
            app.mg4dash.settings.HaWindow.show(s); idle()
            check(s, "home_assistant$tag")
        }
        File("build/screenshots/fit/scrolled.txt").writeText(scrolled.joinToString("\n"))
    }
}
