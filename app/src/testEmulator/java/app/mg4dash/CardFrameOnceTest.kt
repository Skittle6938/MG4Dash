package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.system.Prefs
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
 * One frame per card: the cards drawn over their content (Radar, Guidance, map, consumption…) had their outline drawn
 * twice, brighter than the others' (21/11). Their top edge now as bright as the climate's and energy's.
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class CardFrameOnceTest {
    @Test fun frames() {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        for ((name, theme) in listOf("dark" to 1, "light" to 2)) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme)
                .putInt("home_style", Prefs.STYLE_BIG).putString("home_layout_big", "big_quad")
                .putString("slots_big_quad", "tl=radar;bl=nav;tr=climate;br=energy").commit()
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            val root = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
            val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            File(File("build/screenshots").apply { mkdirs() }, "framecheck_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // The brightest point of each card's top edge: Radar (top left), Guidance (bottom left), climate, energy
            fun edge(x: Int, y0: Int) = (y0..y0 + 12).maxOf { android.graphics.Color.red(bmp.getPixel(x, it)).let { v -> if (theme == 2) 255 - v else v } }
            val radar = edge(560, 12); val nav = edge(560, 364); val climate = edge(1460, 12); val energy = edge(1460, 364)
            org.junit.Assert.assertTrue("$name: radar $radar nav $nav climate $climate energy $energy",
                listOf(radar, nav).all { Math.abs(it - climate) <= 8 && Math.abs(it - energy) <= 8 })
        }
    }
}