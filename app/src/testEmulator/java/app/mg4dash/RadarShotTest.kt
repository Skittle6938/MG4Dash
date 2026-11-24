package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import app.mg4dash.radar.CamKind
import app.mg4dash.radar.Level
import app.mg4dash.radar.RadarCard
import app.mg4dash.radar.RadarRepo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** The speed camera alerter's card (test) in its sizes, with simulated states; PNGs in build/screenshots/. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class RadarShotTest {
    /** North Boulevard Périphérique in Paris, 50: well over the limit in the zone of its camera. */
    private val inZoneStrong = RadarRepo.State(
        status = RadarRepo.Status.OK, speedKmh = 59f, limit = 50, level = Level.STRONG,
        zone = RadarRepo.InZone(CamKind.FIXED, 50, 300.0),
        next = listOf(RadarRepo.NextZone(CamKind.FIXED, 50, 1350.0)),
        nextLimit = 650.0 to 30, highway = "trunk",
    )
    private val light = RadarRepo.State(
        status = RadarRepo.Status.OK, speedKmh = 93f, limit = 90, level = Level.LIGHT,
        next = listOf(RadarRepo.NextZone(CamKind.FIXED, 70, 2350.0), RadarRepo.NextZone(CamKind.RED_LIGHT, 50, 4100.0)),
        nextLimit = 850.0 to 70, highway = "trunk",
    )
    private val estimated = RadarRepo.State(status = RadarRepo.Status.OK, speedKmh = 47f, limit = 50, estimated = true, highway = "residential")

    /** A preparation under way, as the page and the card show it. */
    private val preparing = RadarRepo.Prep(212, 560, 3, RadarRepo.PrepPhase.RUNNING, bytes = 23_400_000, minutesLeft = 5)

    @Suppress("UNCHECKED_CAST")
    private fun setPrepare(p: RadarRepo.Prep?) {
        val f = RadarRepo::class.java.getDeclaredField("_prepare").apply { isAccessible = true }
        (f.get(null) as kotlinx.coroutines.flow.MutableStateFlow<RadarRepo.Prep?>).value = p
    }

    private fun shot(slots: String, layout: String, state: RadarRepo.State, name: String, theme: Int = 1, prep: RadarRepo.Prep? = null) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true)
            .putInt("theme_mode", theme).putInt("home_style", 0).putString("home_layout", layout)
            .putString("slots_$layout", slots).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val card = (MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(act) as Map<*, *>)[app.mg4dash.ui.HomeSlots.Card.RADAR] as RadarCard
        card.show(state)
        card.downloads(prep, 0)
        val root = act.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        RadarRepo.stop()
    }

    @Test fun mediumInZone() = shot("tl=climate;tc=radar;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone", "full", inZoneStrong, "radar_medium_zone.png")
    @Test fun smallLight() = shot("tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=radar;br=trip,power,phone", "full", light, "radar_small_light.png")
    @Test fun wideLight() = shot("w=radar;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone", "band_top", light, "radar_wide_light.png")
    @Test fun largeInZone() = shot("g=radar;tr=climate;br=energy,trip,power,phone,hourly,daily", "square_left", inZoneStrong, "radar_large_zone.png")
    @Test fun tallEstimatedLight() = shot("tl=hourly,daily;tc=energy,trip,power,phone;r=radar;bl=climate;bc=media", "map", estimated, "radar_tall_estimated_light.png", theme = 2)

    /** The alerter's own page (long press on the card). */
    @Test fun smallDownloading() = shot("tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=radar;br=trip,power,phone", "full", light, "radar_small_download.png", prep = preparing)
    @Test fun mediumDownloading() = shot("tl=climate;tc=radar;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone", "full", inZoneStrong, "radar_medium_download.png", prep = preparing)
    @Test fun wideDownloading() = shot("w=radar;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone", "band_top", light, "radar_wide_download.png", prep = preparing)
    @Test fun largeDownloading() = shot("g=radar;tr=climate;br=energy,trip,power,phone,hourly,daily", "square_left", inZoneStrong, "radar_large_download.png", prep = preparing)

    /** The alerter's page while a preparation runs. */
    @Test fun settingsPageDownloading() {
        setPrepare(preparing)
        try { settingsWindow("radar_settings_download.png") } finally { setPrepare(null) }
    }

    @Test fun settingsPage() = settingsWindow("radar_settings_page.png")
    @Test fun settingsPageLight() = settingsWindow("radar_settings_light.png", theme = 2)
    /** Scrolled to its end: the sounds and the storage. */
    @Test fun settingsPageScrolled() = settingsWindow("radar_settings_scrolled.png", scrolled = true)

    /** The whole alerter in the card's options window (24/11): a long press on the card, home behind. */
    private fun settingsWindow(name: String, theme: Int = 1, scrolled: Boolean = false) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme)
            .putInt("home_style", 0).putString("home_layout", "full").putString("slots_full", "tl=climate;tc=consumption,map;tr=media;bl=energy;bc=radar;br=trip,power,phone").commit()
        val act = Robolectric.buildActivity(app.mg4dash.MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        @Suppress("UNCHECKED_CAST")
        val cards = app.mg4dash.MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(act) as Map<app.mg4dash.ui.HomeSlots.Card, app.mg4dash.cards.HomeCard>
        cards.getValue(app.mg4dash.ui.HomeSlots.Card.RADAR).view.performLongClick()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val w = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!
        val dv = w.decorView
        dv.measure(android.view.View.MeasureSpec.makeMeasureSpec(w.attributes.width, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(act.window.decorView.height, android.view.View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        if (scrolled) {
            fun find(v: android.view.View): android.widget.ScrollView? = (v as? android.widget.ScrollView)?.takeIf { it.childCount > 0 && it.getChildAt(0).height > it.height }
                ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            find(dv)?.let { it.scrollTo(0, it.getChildAt(0).height) }
        }
        val bmp = Bitmap.createBitmap(dv.measuredWidth, dv.measuredHeight, Bitmap.Config.ARGB_8888)
        dv.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
