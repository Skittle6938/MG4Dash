package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.system.Prefs
import app.mg4dash.ui.OptionsPopup
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

/** A card's options window: one column while it fits in the screen's height, two (twice as wide) once too tall. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class OptionsColumnsTest {
    private fun act(): Activity {
        Prefs(RuntimeEnvironment.getApplication()).raw.edit().clear().putBoolean("vertical_dock", true)
            .putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun popup(a: Activity, left: Int, right: Int): OptionsPopup {
        val p = OptionsPopup(a, "Essai")
        repeat(left) { p.toggle("Réglage $it", "Une ligne d'explication sous le réglage", { it % 2 == 0 }) {} }
        p.column()
        repeat(right) { p.choice("Choix $it", null, listOf("Un", "Deux"), { 0 }) {} }
        return p
    }

    private fun shot(a: Activity, name: String): Int {
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS) }
        val w = ShadowDialog.getLatestDialog().window!!
        val dv = w.decorView
        val root = a.window.decorView
        dv.measure(View.MeasureSpec.makeMeasureSpec(w.attributes.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        val bmp = Bitmap.createBitmap(dv.measuredWidth, dv.measuredHeight, Bitmap.Config.ARGB_8888)
        dv.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return dv.measuredHeight
    }

    @Test fun tallGoesToTwoColumns() {
        val a = act()
        val p = popup(a, 6, 4).also { it.show() }
        assertTrue(p.twoColumns)
        assertEquals(1640, ShadowDialog.getLatestDialog().window!!.attributes.width)
        assertTrue("fits the screen", shot(a, "options_two_columns.png") <= a.window.decorView.height)
    }

    @Test fun shortStaysOneColumn() {
        val a = act()
        val p = popup(a, 2, 1).also { it.show() }
        assertFalse(p.twoColumns)
        assertEquals(900, ShadowDialog.getLatestDialog().window!!.attributes.width)
        shot(a, "options_one_column.png")
    }

    @Test fun noColumnMarkStaysOneColumn() {
        val a = act()
        val p = popup(a, 12, 0).also { it.show() }
        assertFalse(p.twoColumns)
    }
}
