package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.showroom.CarLook
import app.mg4dash.showroom.CarPaint
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

/** The Sound page's car from above in the driver's colour and roof (27/11): build/screenshots/sound_car_*.png. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class SoundCarShots {
    private fun save(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun colours() {
        val ctx = RuntimeEnvironment.getApplication()
        for ((paint, roof, liv) in listOf(Triple("blue", false, "none"), Triple("red", true, "none"), Triple("rainbow", false, "zebra"), Triple("galaxy", true, "flames"))) {
            app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
            CarLook(ctx).choose(if (paint == "galaxy") CarLook.Version.XPOWER else CarLook.Version.STANDARD)
            CarLook(ctx).paint = CarPaint.byId(paint); CarLook(ctx).roof = roof
            CarLook(ctx).livery = app.mg4dash.showroom.Livery.byId(liv)
            val a = Robolectric.buildActivity(app.mg4dash.pages.SoundActivity::class.java).setup().get()
            val end = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS); save(a.window.decorView, "x.png"); Thread.sleep(100) }
            save(a.window.decorView, "sound_car_${paint}_$liv${if (roof) "_roof" else ""}.png")
        }
    }
}
