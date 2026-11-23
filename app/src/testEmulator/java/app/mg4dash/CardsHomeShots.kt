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
 * The cards on the real home screen, in each size they accept, dark and light: their place cropped to
 * build/screenshots/cards_home/<card>_<size>_<theme>.png. Used to compare a card before and after a change.
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class CardsHomeShots {
    /** A place of each size: layout, slots with [X] for the card, the place's id. */
    private val places = listOf(
        Triple("small", "full", "tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=X;br=trip,power,phone") to "bc",
        Triple("medium", "full", "tl=climate;tc=X;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone") to "tc",
        Triple("tall", "map", "tl=hourly,daily;tc=energy,trip,power,phone;r=X;bl=climate;bc=media") to "r",
        Triple("wide", "band_top", "w=X;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone") to "w",
        Triple("large", "square_left", "g=X;tr=climate;br=energy,trip,power,phone,hourly,daily") to "g",
    )

    private fun shoot(card: String, sizes: Set<String>) {
        for ((p, slot) in places) {
            val (size, layout, slots) = p
            if (size !in sizes) continue
            for ((theme, name) in listOf(1 to "dark", 2 to "light")) {
                Prefs(RuntimeEnvironment.getApplication()).raw.edit().clear().putBoolean("vertical_dock", true)
                    .putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme).putInt("home_style", 0)
                    .putString("home_layout", layout).putString("slots_$layout", slots.replace("X", card)).commit()
                val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
                val act = ctl.get()
                repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS) }
                val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<*, *>
                val frame = frames[slot]!!
                val box = frame.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(frame) as View
                val root = act.window.decorView
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
                val xy = IntArray(2); box.getLocationInWindow(xy)
                val crop = Bitmap.createBitmap(bmp, xy[0], xy[1], minOf(box.width, bmp.width - xy[0]), minOf(box.height, bmp.height - xy[1]))
                File(File("build/screenshots/cards_home").apply { mkdirs() }, "${card}_${size}_$name.png").outputStream()
                    .use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ctl.pause().stop().destroy()
            }
        }
    }

    @Test fun trips() = shoot("trips", setOf("small", "medium", "tall"))
    @Test fun driving() = shoot("driving", setOf("small", "medium", "tall", "wide", "large"))
}
