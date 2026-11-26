package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.showroom.CarImage
import app.mg4dash.showroom.CarLook
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
 * The README's pictures of the redesigned pages (26/11), rendered by the "UI previews" workflow: Vehicle, Energy and
 * Climate by night, Vehicle and Climate by day (build/screenshots/readme_*.png).
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class PagesReadmeShots {
    private fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun settle(root: View, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
            val cars = all(root).filterIsInstance<CarImage>()
            val painted = cars.all { c -> CarImage::class.java.getDeclaredField("image").apply { isAccessible = true }.get(c) != null }
            if (cars.isNotEmpty() && painted && System.currentTimeMillis() > end - ms + 3000) break
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
    }

    private fun shot(cls: Class<out android.app.Activity>, theme: Int, name: String) {
        val ctx = RuntimeEnvironment.getApplication()
        app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", theme).commit()
        CarLook(ctx).apply { stock(); threeQuarter = true; rhd = false }
        val root = Robolectric.buildActivity(cls).setup().get().window.decorView
        fun draw(): Bitmap {
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
            return Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        draw(); settle(root, 6_000); draw()
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { draw().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pages() {
        shot(app.mg4dash.pages.VehicleActivity::class.java, 1, "readme_vehicle.png")
        shot(app.mg4dash.pages.EnergyActivity::class.java, 1, "readme_energy.png")
        shot(app.mg4dash.pages.ClimateActivity::class.java, 1, "readme_climate.png")
        shot(app.mg4dash.pages.VehicleActivity::class.java, 2, "readme_vehicle_light.png")
        shot(app.mg4dash.pages.ClimateActivity::class.java, 2, "readme_climate_light.png")
    }
}
