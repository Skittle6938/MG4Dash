package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
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
import org.robolectric.shadows.ShadowApplication
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Settings › Look › Brightness, mockup v3 (build/screenshots/brightness_*.png): closed, open on each look, the long
 * press's erase window; fr/en/de/tr, dark and light, and with Grand affichage on.
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class BrightnessShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    private fun idle(ms: Long = 1000) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
    }

    /** [v]'s place in the window (scrolls included). */
    private fun at(v: View): Pair<Int, Int> {
        var x = 0; var y = 0; var c: View? = v
        while (c != null) { x += c.left - c.scrollX; y += c.top - c.scrollY; c = c.parent as? View }
        return x + v.scrollX to y + v.scrollY
    }

    private fun save(root: View, name: String, popup: PopupWindow? = null, over: View? = null) {
        layout(root)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        root.draw(canvas)
        if (popup != null && over != null) {
            val box = popup.contentView
            box.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            box.layout(0, 0, box.measuredWidth, box.measuredHeight)
            val (x, y) = at(over)
            canvas.save(); canvas.translate((x + over.width / 2 - box.measuredWidth / 2).toFloat(), (y + over.height / 2 - box.measuredHeight / 2).toFloat())
            box.draw(canvas); canvas.restore()
        }
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun panelV3() {
        val dark = BrightnessCurve().with(0.6, true, 0.08, 1).with(2.2, false, 0.30, 2).with(3.9, false, 0.62, 3)
        val light = BrightnessCurve().with(1.2, false, 0.30, 1).with(3.4, false, 0.80, 2)
        val runs = listOf("fr" to 1, "en" to 1, "de" to 1, "tr" to 1, "fr" to 2)
        for ((lang, theme) in runs) for (big in listOf(false, true)) {
            if (big && (lang != "fr" || theme != 1)) continue
            RuntimeEnvironment.setQualifiers("$lang-w1920dp-h720dp-land-mdpi")
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true)
                .putInt("theme_mode", theme).putInt("settings_tab", 1).putBoolean("bright_smart", true)
                .putString("bright_curve_dark", dark.encode()).putString("bright_curve_light", light.encode())
                .apply { if (big) putInt("home_style", Prefs.STYLE_BIG) }.commit()
            val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get(); idle()
            val root = a.window.decorView
            val tag = "${lang}${if (theme == 2) "_light" else ""}${if (big) "_big" else ""}"
            layout(root)
            // Closed: the panel scrolled into view
            val show = all(root).filterIsInstance<TextView>().firstOrNull { it.text == a.getString(R.string.br_curve_show) }
            assertNotNull(show)
            val sv = all(root).filterIsInstance<ScrollView>().first { it.isShown }
            fun scrollTo(v: View, above: Int) {
                var y = 0; var c: View = v
                while (c !== sv) { y += c.top; c = c.parent as View }
                sv.scrollTo(0, (y - above).coerceAtLeast(0)); layout(root)
            }
            scrollTo(show!!, 260)
            save(root, "brightness_v3_closed_$tag.png")
            // Open, the active look's curve, a point selected
            show.performClick(); idle(); layout(root)
            val cv = all(root).filterIsInstance<CurveView>().single()
            assertTrue(cv.isShown)
            fun press(action: Int, px: Float, py: Float) { val e = MotionEvent.obtain(0, 0, action, px, py, 0); cv.onTouchEvent(e); e.recycle() }
            press(MotionEvent.ACTION_DOWN, 44f + (cv.width - 54f) * 2.2f / 5f, (cv.height - 30f) - (cv.height - 40f) * 0.30f)
            press(MotionEvent.ACTION_UP, 44f + (cv.width - 54f) * 2.2f / 5f, (cv.height - 30f) - (cv.height - 40f) * 0.30f)
            scrollTo(cv, 130)
            save(root, "brightness_v3_open_$tag.png")
            if (big) continue
            // The other look's curve
            val other = a.getString(if (theme == 2) R.string.br_look_dark else R.string.br_look_light)
            all(root).filterIsInstance<android.widget.Button>().first { it.text.toString().startsWith(other) && it.isShown }.performClick(); idle()
            save(root, "brightness_v3_other_$tag.png")
            // The long press: the erase window
            press(MotionEvent.ACTION_DOWN, cv.width * 0.75f, cv.height * 0.15f); idle(1_100)
            val popup = ShadowApplication.getInstance().latestPopupWindow
            assertNotNull(popup); assertTrue(popup!!.isShowing)
            press(MotionEvent.ACTION_UP, cv.width * 0.75f, cv.height * 0.15f)
            save(root, "brightness_v3_erase_$tag.png", popup, cv)
            popup.dismiss()
        }
    }
}