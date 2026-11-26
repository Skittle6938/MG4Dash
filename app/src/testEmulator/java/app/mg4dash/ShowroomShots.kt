package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.showroom.CarImage
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

/** The redesigned Vehicle page and "Colours and style" (26/11): build/screenshots/showroom_*.png. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ShowroomShots {
    private fun all(v: View): List<View> = listOf(v) + if (v is android.view.ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun waitPainted(root: View) {
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
            val cars = all(root).filterIsInstance<CarImage>()
            if (cars.isNotEmpty() && cars.all { c -> CarImage::class.java.getDeclaredField("image").apply { isAccessible = true }.get(c) != null }) break
            Thread.sleep(100)
        }
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
    }

    private fun save(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun energyPage() {
        val ctx = RuntimeEnvironment.getApplication()
        app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
        CarLook(ctx).stock()
        val a = Robolectric.buildActivity(app.mg4dash.pages.EnergyActivity::class.java).setup().get()
        val root = a.window.decorView
        save(root, "x.png")
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS); Thread.sleep(50) }
        save(root, "x.png"); shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        save(root, "showroom_energy.png")
    }

    @Test fun climatePage() {
        val ctx = RuntimeEnvironment.getApplication()
        app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
        for ((n, r) in listOf("lhd" to false, "rhd" to true)) {
            CarLook(ctx).rhd = r
            val a = Robolectric.buildActivity(app.mg4dash.pages.ClimateActivity::class.java).setup().get()
            val root = a.window.decorView
            save(root, "x.png")
            val end = System.currentTimeMillis() + 4_000
            while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS); Thread.sleep(50) }
            save(root, "x.png"); shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS)
            save(root, "showroom_climate_$n.png")
        }
    }

    @Test fun vehiclePage() {
        val ctx = RuntimeEnvironment.getApplication()
        app.mg4dash.system.Prefs(ctx).raw.edit().clear().putBoolean("vertical_dock", true).putInt("theme_mode", 1).commit()
        val look = CarLook(ctx)
        for ((n, q) in listOf("q34" to true, "side" to false)) {
            look.stock(); look.threeQuarter = q
            val a = Robolectric.buildActivity(app.mg4dash.pages.VehicleActivity::class.java).setup().get()
            val root = a.window.decorView
            save(root, "x.png"); waitPainted(root)
            save(root, "showroom_vehicle_$n.png")
        }
        look.choose(CarLook.Version.XPOWER); look.paint = CarPaint.byId("green"); look.threeQuarter = true
        val a = Robolectric.buildActivity(app.mg4dash.pages.VehicleActivity::class.java).setup().get()
        app.mg4dash.showroom.StyleWindow.show(a, look) {}
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val root = d.window!!.decorView
        save(root, "x.png"); waitPainted(root)
        save(root, "showroom_style_xpower.png")
    }
}
