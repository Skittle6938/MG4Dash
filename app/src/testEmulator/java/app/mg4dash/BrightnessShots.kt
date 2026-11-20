package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.brightness.BrightnessCurve
import app.mg4dash.brightness.CurveView
import app.mg4dash.system.Prefs
import org.junit.Assert.assertNotNull
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

/** Settings › Look › Brightness with its curve open, a point selected (build/screenshots/brightness_*.png). */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BrightnessShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    private fun idle(n: Int = 5) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    @Test fun curveOpen() {
        val curve = BrightnessCurve().with(0.6, true, 0.08, 1).with(2.2, false, 0.30, 2).with(3.9, false, 0.62, 3)
        for (lang in listOf("fr", "en", "de", "tr")) for (theme in listOf(1, 2)) {
            if (theme == 2 && lang != "fr") continue
            RuntimeEnvironment.setQualifiers("$lang-w1920dp-h720dp-land-mdpi")
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true)
                .putInt("theme_mode", theme).putInt("settings_tab", 1).putBoolean("bright_smart", true).commit()
            val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get(); idle()
            prefs.raw.edit().putString(app.mg4dash.brightness.SmartBrightness.curveKey(a), curve.encode()).commit()
            val root = a.window.decorView
            layout(root)
            val show = all(root).filterIsInstance<TextView>().firstOrNull { it.text == a.getString(R.string.br_curve_show) }
            assertNotNull(show); show!!.performClick(); idle()
            layout(root)
            val cv = all(root).filterIsInstance<CurveView>().single()
            assertTrue(cv.isShown)
            // The middle point selected, as with a finger
            val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 44f + (cv.width - 54f) * 2.2f / 5f, (cv.height - 30f) - (cv.height - 40f) * 0.30f, 0)
            cv.onTouchEvent(e); e.recycle()
            // The panel scrolled so that the whole curve shows
            val sv = all(root).filterIsInstance<ScrollView>().first { it.isShown }
            var y = 0; var v: View = cv
            while (v !== sv) { y += v.top; v = v.parent as View }
            sv.scrollTo(0, (y - 160).coerceAtLeast(0)); idle()
            layout(root)
            val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(File("build/screenshots").apply { mkdirs() }, "brightness_curve_$lang${if (theme == 2) "_light" else ""}.png")
                .outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}