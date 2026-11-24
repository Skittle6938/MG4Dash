package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.cards.HomeCard
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots.Card
import app.mg4dash.weather.Geocoding
import org.junit.Assert.assertEquals
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

/**
 * The weather cards' options (24/11): the fallback city searched by its name (Open-Meteo's geocoding), a city saved
 * from the old fixed list kept as it is, and a long choice (the models) that scrolls instead of being cut.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class WeatherOptionsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }
    private fun texts(v: View): List<String> = if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull((v as? TextView)?.text?.toString())

    @Test fun parsesThePlacesFound() {
        val json = """{"results":[
            {"name":"Lyon","latitude":45.74846,"longitude":4.84671,"country":"France","admin1":"Auvergne-Rhône-Alpes"},
            {"name":"Lyons","latitude":43.0898,"longitude":-76.99,"country":"United States","admin1":"New York"},
            {"name":"Nowhere"},
            {"name":"Lyon-sur-mer","latitude":1.0,"longitude":2.0}]}"""
        val p = Geocoding.parse(json)
        assertEquals(listOf("Lyon", "Lyons", "Lyon-sur-mer"), p.map { it.name })
        assertEquals("Lyon — Auvergne-Rhône-Alpes, France", p[0].label)
        assertEquals("Lyon-sur-mer", p[2].label)
        assertEquals(45.74846, p[0].lat, 1e-6)
        assertTrue(Geocoding.parse("""{"generationtime_ms":0.5}""").isEmpty())
    }

    private fun home(): MainActivity {
        prefs.raw.edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", "full").putString("slots_full", "tl=climate;tc=consumption,map;tr=media;bl=energy;bc=hourly;br=trip,power,phone").commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cards(a: MainActivity) = MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(a) as Map<Card, HomeCard>

    /** A city chosen in the old list (name and position saved) shows as it is, and stays until another is chosen. */
    @Test fun anOldCityIsKept() {
        prefs.raw.edit().clear().commit()
        prefs.weatherCity = "Strasbourg"; prefs.weatherLat = 48.5734f; prefs.weatherLon = 7.7521f
        val a = home()
        cards(a).getValue(Card.HOURLY).view.performLongClick(); idle()
        val t = texts(ShadowDialog.getLatestDialog().window!!.decorView)
        assertTrue(t.toString(), t.any { it.startsWith("Strasbourg") })
        assertEquals("Strasbourg", prefs.weatherCity); assertEquals(48.5734f, prefs.weatherLat, 1e-4f)
    }

    /** The seven models from a row low on the screen: the menu stays within it, scrolls, faded at its edges. */
    @Test fun aLongChoiceScrolls() {
        val a = home()
        cards(a).getValue(Card.HOURLY).view.performLongClick(); idle()
        val d = ShadowDialog.getLatestDialog()
        val model = texts(d.window!!.decorView).first { it.startsWith(a.getString(R.string.wx_model_auto)) }
        fun find(v: View): View? = if (v is TextView && v.text.toString() == model) v else (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val anchor = find(d.window!!.decorView)!!
        // The menu as if the row sat low on the screen
        val items = (1..12).map { "Choix $it" }
        app.mg4dash.ui.SourceMenu.choose(a, anchor, items, 6) {}
        idle()
        val p = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
        assertTrue("within the screen: ${p.height}", p.height <= a.window.decorView.height)
        val v = p.contentView
        v.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, Bitmap.Config.ARGB_8888).also { val c = Canvas(it); c.drawColor(0xFF0E0E10.toInt()); v.draw(c) }
        File(File("build/screenshots/moves").apply { mkdirs() }, "long_choice.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("it scrolls", v is ScrollView)
        val sv = v as ScrollView
        assertTrue("its scroll bar stays", !sv.isScrollbarFadingEnabled)
        // A finger dragged up moves the list
        sv.scrollTo(0, 0)
        val before = sv.scrollY
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, y: Float, dt: Long) = android.view.MotionEvent.obtain(t0, t0 + dt, action, 100f, y, 0)
        sv.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, 300f, 0))
        for (k in 1..10) sv.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_MOVE, 300f - k * 20f, k * 16L))
        sv.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, 100f, 200))
        assertTrue("dragging scrolls: ${sv.scrollY}", sv.scrollY > before)
    }
}
