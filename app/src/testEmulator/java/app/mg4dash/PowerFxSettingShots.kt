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
 * The full power animations' choice (Marc, 21/11): on the right of its label, "Random" alone with several ticked, its
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

    @Test fun choiceOnTheRightOfItsLabel() {
        for ((lang, big) in listOf("fr" to false, "en" to false, "de" to false, "tr" to false, "fr" to true)) {
            RuntimeEnvironment.setQualifiers("$lang-w1920dp-h720dp-land-mdpi")
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).putInt("settings_tab", 1)
                .putString("power_fx_on", "2,5").apply { if (big) putInt("home_style", Prefs.STYLE_BIG) }.commit()
            val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get()
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
            val root = a.window.decorView
            layout(root)
            val random = a.getString(R.string.fx_random)
            val pick = all(root).filterIsInstance<Button>().first { it.text.startsWith(random) }
            assertTrue("only « Random »: ${pick.text}", pick.text.toString().substringBefore("  ").trim() == random)
            // Same line as its label
            val label = all(root).filterIsInstance<TextView>().first { it.text == a.getString(R.string.st_power_fx) }
            assertTrue(label.parent === (pick.parent as View).parent)
            pick.performClick(); layout(root)
            val sv = all(root).filterIsInstance<ScrollView>().first { it.isShown }
            var y = 0; var v: View = pick
            while (v !== sv) { y += v.top; v = v.parent as View }
            sv.scrollTo(0, (y - 120).coerceAtLeast(0)); layout(root)
            val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(File("build/screenshots").apply { mkdirs() }, "powerfx_$lang${if (big) "_big" else ""}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}