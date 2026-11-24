package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
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
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The full power animations' choice (21/11): on the right of its label, "Random" alone with several ticked, its
 * list open (build/screenshots/powerfx_*.png), fr/en/de/tr and Grand affichage.
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class PowerFxSettingShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    /** The effects and their sounds are in the power card's options (24/11): a box per effect, the sounds' switch and volume. */
    @Test fun inThePowerCardsOptions() {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", "full").putString("slots_full", "tl=climate;tc=consumption,map;tr=media;bl=energy;bc=radar;br=power,trip,phone")
            .putString("power_fx_on", "2,5").commit()
        val a = Robolectric.buildActivity(app.mg4dash.MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        @Suppress("UNCHECKED_CAST")
        val cards = app.mg4dash.MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(a) as Map<app.mg4dash.ui.HomeSlots.Card, app.mg4dash.cards.HomeCard>
        assertTrue(cards.getValue(app.mg4dash.ui.HomeSlots.Card.POWER).view.performLongClick())
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val texts = all(d.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
        for (n in listOf(R.string.fx_lightning, R.string.fx_flames, R.string.fx_hyperspace, R.string.st_fx_sound, R.string.st_xpower))
            assertTrue(a.getString(n), a.getString(n) in texts)
        assertTrue(all(d.window!!.decorView).any { it is android.widget.SeekBar })
    }
}