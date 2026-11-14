package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.settings.SettingsActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit
import app.mg4dash.ui.HomeSlots.Layout as HL

/**
 * Full screen rendering of the screens on the MG4's panel (1920 × 720 dp, 160 dpi),
 * with simulated data. PNGs are written to build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class ScreenshotTest {

    /**
     * Vertical bar on the left, as on a new install; one test puts it back at the bottom. The shortcuts menu's tour step
     * (shown once after the update) already seen: else it covers home and takes the touches meant for it.
     */
    @org.junit.Before fun verticalBarByDefault() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).commit()
    }

    /** Connection icons with the phone's network shown: framed with its battery (#86). */
    @Test fun homeConnectionsPhoneNet() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putStringSet("conn_icons_hidden", emptySet()).commit()
        shoot(MainActivity::class.java, "home_connections_phone_net.png")
    }

    /** Consumption widget in the map slot, Full layout. */
    @Test fun homeConsumptionFull() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("home_widget", 2).commit()
        shoot(MainActivity::class.java, "home_consumption_full.png")
    }

    @Test fun homeConsumptionMap() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("home_widget", 2).putInt("home_style", 2).commit()
        shoot(MainActivity::class.java, "home_consumption_map.png")
    }

    /** USB ports in the bar: A then C, here with a stick on C only (#86). */
    @Test fun homeUsbPorts() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("home_widget", 2).putInt("home_style", 2).putStringSet("conn_usb_shown", setOf("usb_a", "usb_c")).commit()
        shoot(MainActivity::class.java, "home_usb_ports.png")
    }

    @Test fun homeBottomBar() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).commit()
        shoot(MainActivity::class.java, "home_bottom_bar.png")
    }

    /** Shorter screen (SAIC bars visible, #43): bar at the bottom. */
    @Test @Config(qualifiers = "w1790dp-h640dp-land-mdpi") fun homeBottomBarShort() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).putInt("theme_mode", 1).putBoolean("fullscreen", false).commit()
        shoot(MainActivity::class.java, "home_bottom_bar_short.png")
    }

    @Test @Config(qualifiers = "w1790dp-h640dp-land-mdpi") fun energyBottomBarShort() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).putInt("theme_mode", 1).putBoolean("fullscreen", false).commit()
        shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_bottom_bar_short.png")
    }

    @Test @Config(qualifiers = "w1790dp-h640dp-land-mdpi") fun homeVerticalShort() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 1).putBoolean("fullscreen", false).commit()
        shoot(MainActivity::class.java, "home_vertical_short.png")
    }

    @Test fun homeBottomBarPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).putInt("theme_mode", 1).putBoolean("energy_card_power", true).commit()
        shoot(MainActivity::class.java, "home_bottom_bar_power.png")
    }

    /** Turc (#42). */
    @Test @Config(qualifiers = "tr-w1920dp-h720dp-land-mdpi") fun homeTurkish() = shoot(MainActivity::class.java, "home_tr.png")
    @Test @Config(qualifiers = "tr-w1920dp-h720dp-land-mdpi") fun energyTurkish() = shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_tr.png")
    @Test @Config(qualifiers = "tr-w1920dp-h720dp-land-mdpi") fun climateTurkish() = shoot(ClimateActivity::class.java, "climate_tr.png")

    /**
     * The cards' corners (#15), position in Paris: the App card in its card at rest, detached at rest, detached awake.
     * Each with its top left corner enlarged 6 times, under the screen.
     */
    @Test fun mapCorners() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 0)
            .putBoolean("map_float", true).putBoolean("map_floating", false).commit()
        val repo = app.mg4dash.map.LocationRepo
        @Suppress("UNCHECKED_CAST")
        (repo::class.java.getDeclaredField("_location").apply { isAccessible = true }.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<android.location.Location?>).value =
            android.location.Location("gps").apply { latitude = 48.8566; longitude = 2.3522; accuracy = 5f }
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        fun idle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        idle()
        val root = act.window.decorView
        fun method(name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }
        val fw = MainActivity::class.java.getDeclaredField("floatMap").apply { isAccessible = true }.get(act) as app.mg4dash.ui.FloatWindow
        fun corner(name: String) {
            save(root, name)
            val f = File("build/screenshots", name)
            val shot = android.graphics.BitmapFactory.decodeFile(f.path)
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
            val at = IntArray(2).also { fw.frameView.getLocationInWindow(it) }
            val zoom = 6; val side = 90
            val out = Bitmap.createBitmap(1920, 720 + side * zoom, Bitmap.Config.ARGB_8888)
            val c = Canvas(out); c.drawColor(0xFF000000.toInt()); c.drawBitmap(shot, 0f, 0f, null)
            val x0 = (at[0] - 50).coerceIn(0, 1920 - side); val y0 = (at[1] - 12).coerceIn(0, 720 - side)
            c.drawBitmap(Bitmap.createScaledBitmap(Bitmap.createBitmap(shot, x0, y0, side, side), side * zoom, side * zoom, false), 0f, 720f, null)
            f.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        corner("corners_map_card_rest.png")
        method("detachMap").invoke(act); idle()
        corner("corners_map_detached_rest.png")
        app.mg4dash.ui.FloatWindow::class.java.getDeclaredMethod("setAwake", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(fw, true)
        idle()
        corner("corners_map_detached_awake.png")
    }

    /** Sample trips (#102), today from 8:02, at the simulated car's odometer (18,344 km). */
    private fun seedTrips(): android.content.SharedPreferences {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val day = app.mg4dash.energy.TripLog.dayStart(now)
        fun t(dayOffset: Int, h: Int, m: Int, minutes: Int, km0: Int, km: Float, per100: Float, soc0: Int, soc1: Int): app.mg4dash.energy.TripLog.Trip {
            val start = day + dayOffset * 86_400_000L + (h * 60 + m) * 60_000L
            return app.mg4dash.energy.TripLog.Trip(start, start + minutes * 60_000L, km0, km0 + Math.round(km), km, km * per100 / 100f, soc0, soc1, 0.25f)
        }
        app.mg4dash.energy.TripLog.clear(sp)
        listOf(
            t(-2, 9, 15, 16, 18079, 9.3f, 18.0f, 99, 97), t(-2, 14, 20, 102, 18088, 121.7f, 17.3f, 97, 68),
            t(-1, 7, 51, 42, 18210, 26.0f, 14.1f, 68, 62), t(-1, 18, 30, 34, 18236, 20.8f, 16.4f, 62, 57),
            t(0, 8, 2, 55, 18257, 22.1f, 15.2f, 99, 94), t(0, 10, 40, 41, 18279, 21.0f, 14.9f, 94, 88), t(0, 13, 12, 46, 18300, 29.4f, 13.7f, 88, 81),
        ).forEach { app.mg4dash.energy.TripLog.add(sp, it) }
        app.mg4dash.energy.TripLog.saveCurrent(sp, t(0, 16, 5, 19, 18329, 13.2f, 15.9f, 81, 78))
        // Two finished readings, then one in progress since 8:00 (#102)
        fun at(d: Int, h: Int, m: Int) = day + d * 86_400_000L + (h * 60 + m) * 60_000L
        sp.edit().remove("trip_readings").commit()
        app.mg4dash.energy.TripLog.startReading(sp, at(-2, 9, 10), 18079); app.mg4dash.energy.TripLog.stopReading(sp, at(-2, 16, 10), 18210)
        app.mg4dash.energy.TripLog.startReading(sp, at(-1, 7, 45), 18210); app.mg4dash.energy.TripLog.stopReading(sp, at(-1, 19, 10), 18257)
        app.mg4dash.energy.TripLog.startReading(sp, at(0, 8, 0), 18257)
        return sp
    }

    /** Places the Paris position (simulated). */
    private fun paris() {
        val repo = app.mg4dash.map.LocationRepo
        @Suppress("UNCHECKED_CAST")
        (repo::class.java.getDeclaredField("_location").apply { isAccessible = true }.get(repo) as kotlinx.coroutines.flow.MutableStateFlow<android.location.Location?>).value =
            android.location.Location("gps").apply { latitude = 48.8566; longitude = 2.3522; accuracy = 5f }
    }

    /** The Trips card (#102) in the top middle place, without page dots. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tripsCard() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 0)
            .putString("slots_full", "tl=climate;tc=trips;tr=media;bl=energy;bc=hourly,daily;br=consumption").commit()
        paris()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        // After the energy monitor's first pass (which closes the trip it found): the sample trips
        seedTrips()
        (MainActivity::class.java.getDeclaredField("trips").apply { isAccessible = true }.get(act) as app.mg4dash.ui.TripsCard).show()
        save(act.window.decorView, "trips_card_button_real.png")
    }

    /** The Trips card and page with a kWh price of 0.25 € (#102): the costs. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tripsCost() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 0).putFloat("kwh_price", 0.25f)
            .putString("slots_full", "tl=climate;tc=trips;tr=media;bl=energy;bc=hourly,daily;br=consumption").commit()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        seedTrips()
        (MainActivity::class.java.getDeclaredField("trips").apply { isAccessible = true }.get(home) as app.mg4dash.ui.TripsCard).show()
        save(home.window.decorView, "trips_card_cost2_real.png")
        val page = Robolectric.buildActivity(app.mg4dash.pages.TripsActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        seedTrips()
        app.mg4dash.pages.TripsActivity::class.java.getDeclaredMethod("refresh", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(page, System.currentTimeMillis())
        save(page.window.decorView, "trips_page_cost_real.png")
    }
    /** Today's trips on the Vehicle page (#102). */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tripsLine() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        seedTrips()
        val act = Robolectric.buildActivity(app.mg4dash.pages.VehicleActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        seedTrips()
        app.mg4dash.pages.VehicleActivity::class.java.getDeclaredMethod("renderTrips", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(act, System.currentTimeMillis())
        save(act.window.decorView, "trips_line_real.png")
    }

    /** The Trips page (#102): today, the manual reading, the list. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tripsPage() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        seedTrips()
        val act = Robolectric.buildActivity(app.mg4dash.pages.TripsActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        seedTrips()
        app.mg4dash.pages.TripsActivity::class.java.getDeclaredMethod("refresh", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(act, System.currentTimeMillis())
        save(act.window.decorView, "trips_page_readings_real.png")
    }

    /** Simulation of MG's fog light button (56 dp at x = 20 dp, #38) placed on the bar. */
    @Test fun homeFogSim() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); root.draw(c)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8BCC4.toInt() }
        c.drawCircle(48f, 14f + 28f, 28f, p)
        p.color = 0xFF30343C.toInt(); p.textSize = 22f; p.textAlign = android.graphics.Paint.Align.CENTER
        c.drawText("FOG", 48f, 50f, p)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "home_fog_sim.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Sound page (mockup): balance and equaliser. */
    @Test fun sound() = shoot(app.mg4dash.pages.SoundActivity::class.java, "sound.png")
    @Test fun soundLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 2).commit()
        shoot(app.mg4dash.pages.SoundActivity::class.java, "sound_light.png")
    }
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun soundFr() = shoot(app.mg4dash.pages.SoundActivity::class.java, "sound_fr.png")
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun vehicleFr() = shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle_fr.png")

    /** Startup animation: raw frames (640 × 240, 20 fps) to make a GIF, and a still image. */
    @Test fun splashFrames() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val v = app.mg4dash.ui.SplashView(ctx).apply { version = "1.4.1" }
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 720)
        val dir = File("build/screenshots").apply { mkdirs() }
        val fps = 20; val frames = (app.mg4dash.ui.SplashView.CYCLE * fps).toInt()
        java.io.DataOutputStream(File(dir, "splash.rgb").outputStream().buffered()).use { out ->
            out.writeInt(640); out.writeInt(240); out.writeInt(frames); out.writeInt(100 / fps)
            for (f in 0 until frames) {
                v.time = f / fps.toFloat()
                val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888); v.draw(Canvas(bmp))
                if (f == 50) File(dir, "splash.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val small = Bitmap.createScaledBitmap(bmp, 640, 240, true)
                val px = IntArray(640 * 240); small.getPixels(px, 0, 640, 0, 0, 640, 240)
                px.forEach { out.writeInt(it) }
            }
        }
    }

    /** Settings in tabs (mockup): one render per tab, in French. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun settingsTabs() {
        for (t in 0..5) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("settings_tabs_v2", true).putInt("settings_tab", t).commit()
            shoot(SettingsActivity::class.java, "settings_tab$t.png")
        }
    }

    /** Mock-up of the update panel (System tab): MG4Dash with a newer version known, Radio Dash not installed. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun settingsUpdatesMock() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("settings_tabs_v2", true).putInt("settings_tab", 5)
            .putString("upd_new_app", "1.7.2-beta99").putString("upd_new_rd", "1.6.2-beta.1").putLong("upd_checked_at", 1L).commit()
        shoot(SettingsActivity::class.java, "settings_updates_mock.png")
    }

    /** System tab, checked and up to date: "Up to date – version" as the other apps; the rescue line. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun settingsUpToDate() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("settings_tabs_v2", true).putInt("settings_tab", 5).putLong("upd_checked_at", 1L).commit()
        shoot(SettingsActivity::class.java, "settings_uptodate_fr.png")
    }

    /**
     * Update panel mock-ups, the three apps in the same state (MG4Radio and MG4Link are not installed under Robolectric:
     * their state lines are given the texts the screen shows once checked).
     */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun settingsUpdateStates() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        fun lines(newer: Boolean, name: String, theme: Int = 1) {
            app.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().remove("upd_new_app")
                .putInt("theme_mode", theme).putBoolean("settings_tabs_v2", true).putInt("settings_tab", 5).putLong("upd_checked_at", 1L)
                .apply { if (newer) putString("upd_new_app", "1.7.3-beta16") }.commit()
            val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            val available = SettingsActivity::class.java.getDeclaredMethod("updateAvailable", String::class.java, String::class.java).apply { isAccessible = true }
            val versions = mapOf("MG4Radio" to ("1.6.2" to "1.6.3"), "MG4Link" to ("2.2.4" to "2.2.5"))
            fun walk(v: View) {
                if (v is ViewGroup) {
                    val first = v.getChildAt(0) as? android.widget.TextView
                    val pair = versions[first?.text?.toString()]
                    if (pair != null && v.childCount == 2 && v.getChildAt(1) is android.widget.TextView) {
                        val button = ((v.parent as ViewGroup).getChildAt(1) as android.widget.Button)
                        button.isEnabled = true; button.alpha = 1f
                        if (newer) button.text = act.getString(R.string.st_upd_install_ver, pair.second)
                        (v.getChildAt(1) as android.widget.TextView).text = if (newer) available.invoke(act, pair.first, pair.second) as CharSequence
                            else act.getString(R.string.st_upd_current, pair.first)
                        return
                    }
                    for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
            }
            walk(act.window.decorView)
            save(act.window.decorView, name)
        }
        lines(false, "settings_updates_uptodate_fr.png")
        lines(true, "settings_updates_available_fr.png")
        lines(true, "settings_updates_available_fr_light.png", theme = 2)
    }

    /** Settings tabs in English, for the README. */
    @Test fun settingsTabsEn() {
        for (t in 0..5) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("settings_tabs_v2", true).putInt("settings_tab", t).commit()
            shoot(SettingsActivity::class.java, "settings_en_tab$t.png")
        }
    }

    /** Update popup: still there after home is rebuilt (light / dark change). */
    @Test fun updatePromptSurvivesRecreate() {
        val f = app.mg4dash.ui.UpdatePrompt::class.java.getDeclaredField("pending").apply { isAccessible = true }
        f.set(app.mg4dash.ui.UpdatePrompt, app.mg4dash.system.Updater.Release("9.9.9", "", 0, "Notes"))
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        fun shown(a: Activity) = a.findViewById<ViewGroup>(android.R.id.content).let { root ->
            (0 until root.childCount).any { i -> (root.getChildAt(i) as? ViewGroup)?.let { hasText(it, "9.9.9") } == true } }
        org.junit.Assert.assertTrue(shown(ctl.get()))
        ctl.recreate()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertTrue(shown(ctl.get()))
        save(ctl.get().window.decorView, "update_prompt_recreated.png")
        f.set(app.mg4dash.ui.UpdatePrompt, null)
    }
    private fun hasText(v: View, t: String): Boolean = when (v) {
        is android.widget.TextView -> v.text.contains(t)
        is ViewGroup -> (0 until v.childCount).any { hasText(v.getChildAt(it), t) }
        else -> false
    }

    /** FM radio without an image: only the text (larger RDS) and buttons, dark and light. */
    @Test fun homeFmDark() = fmShot(1, "home_fm_dark.png", "NRJ", "FM 100.30")
    @Test fun homeFmLight() = fmShot(2, "home_fm_light.png", "FM 98.20", "")
    private fun fmShot(mode: Int, name: String, title: String, sub: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", mode).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val f = app.mg4dash.media.MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.media.MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.media.MediaHub.NowPlaying>).value =
            app.mg4dash.media.MediaHub.NowPlaying(true, "com.saicmotor.radio", "RADIO FM", title, sub, "", null, true)
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(activity.window.decorView, name)
    }

    /** Dialog windows in MG4Dash style (temperature calibration), dark and light. */
    @Test fun dialogDark() = dialogShot(1, "dialog_dark.png")
    @Test fun dialogLight() = dialogShot(2, "dialog_light.png")
    private fun dialogShot(mode: Int, name: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", mode).commit()
        val act = Robolectric.buildActivity(ClimateActivity::class.java).setup().get()
        app.mg4dash.ui.TempCalibration.show(act) {}
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        val dv = dlg.window!!.decorView
        dv.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dv.layout(0, 0, 900, dv.measuredHeight)
        val bmp = Bitmap.createBitmap(900, dv.measuredHeight, Bitmap.Config.ARGB_8888); bmp.eraseColor(0xFF777777.toInt()); dv.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Map in full screen beside the vertical bar (#98): same top and bottom as the bar, put back after. */
    @Test fun mapFullscreenBarLeft() = fullscreenBar(1, "home_full_bar_left.png")
    @Test fun mapFullscreenBarRight() = fullscreenBar(2, "home_full_bar_right.png")
    private fun fullscreenBar(side: Int, name: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("bar_side", side).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = act.window.decorView
        fun lay() { save(root, name) }
        lay()
        val bar = act.findViewById<View>(R.id.dock)
        val card = act.findViewById<View>(R.id.mapCard)
        fun top(v: View) = IntArray(2).also { v.getLocationInWindow(it) }[1]
        val padding = act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).let { it.paddingTop to it.paddingBottom }
        val full = MainActivity::class.java.getDeclaredMethod("setMapFullscreen", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        full.invoke(act, true, false)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        lay()
        org.junit.Assert.assertEquals("top", top(bar), top(card))
        org.junit.Assert.assertEquals("bottom", top(bar) + bar.height, top(card) + card.height)
        full.invoke(act, false, false)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val back = act.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).let { it.paddingTop to it.paddingBottom }
        org.junit.Assert.assertEquals("margins put back", padding, back)
        full.invoke(act, true, false)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(root, name)
    }

    /** README screenshot: a third party app in the map (demonstration drawing, Map layout). */
    @Test fun homeMapAppPlanner() = mapApp({ FakePlannerView(it) }, "home_map_app_planner.png")
    @Test fun homeMapAppGuidance() = mapApp({ FakeGuidanceView(it) }, "home_map_app_guidance.png")
    private fun mapApp(make: (android.content.Context) -> View, name: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putInt("stack_page", 0).putBoolean("energy_card_power", true).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val card = act.findViewById<android.widget.FrameLayout>(R.id.mapCard)
        // Like a real embedded app: map buttons and speed hidden, the map stays underneath
        listOf(R.id.mapButtons).forEach { act.findViewById<View>(it)?.visibility = View.GONE }
        (act.findViewById<View>(R.id.mapSpeed)?.parent as? View)?.visibility = View.GONE
        card.addView(make(act), android.widget.FrameLayout.LayoutParams(-1, -1))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(act.window.decorView, name)
    }

    @Test fun settingsBottomBar() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).commit()
        shoot(SettingsActivity::class.java, "settings_bottom_bar.png")
    }

    @Test fun home() = shoot(MainActivity::class.java, "home.png", bluetooth("Midnight Boulevard", "Velvet Avenue", "Neon Skyline", neonSkyline()))
    @Test fun climate() = shoot(ClimateActivity::class.java, "climate.png")
    @Test fun vehicle() = shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle.png")
    @Test fun appsPage() = shoot(app.mg4dash.apps.AppsActivity::class.java, "apps_page.png")
    @Test fun energy() = shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy.png")
    @Test fun settings() = shoot(SettingsActivity::class.java, "settings.png")
    @Test fun homeLight() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(MainActivity::class.java, "home_light.png", androidAuto("Salt & Sunlight", "Coastline Club", "Golden Hours", goldenHours()))
    }

    @Test fun climateLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(app.mg4dash.pages.ClimateActivity::class.java, "climate_light.png")
    }

    @Test fun vehicleLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle_light.png")
    }

    @Test fun phoneLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(app.mg4dash.pages.PhoneActivity::class.java, "phone_light.png")
    }

    @Test fun settingsLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(app.mg4dash.settings.SettingsActivity::class.java, "settings_light.png")
    }

    @Test fun homeSimpleLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).putInt("home_style", 1).apply()
        shoot(MainActivity::class.java, "home_simple_light.png")
    }

    @Test fun energyLight() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).apply()
        shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_light.png")
    }

    @Test fun homeThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 2).putInt("theme_accent", 4).apply()
        shoot(MainActivity::class.java, "home_theme_charcoal_orange.png", bluetooth("Polar Drift", "Aurora Drive", "Northern Lights", northernLights()))
    }

    @Test fun energyThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 4).putInt("theme_accent", 2).apply()
        shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_theme_burgundy_red.png")
    }

    @Test fun settingsThemed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_bg", 3).putInt("theme_accent", 3).apply()
        shoot(app.mg4dash.settings.SettingsActivity::class.java, "settings_theme_forest_green.png")
    }

    @Test fun homeSimple() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 1)
            .putString("sc_100", "ha|cover.gate|Gate")
            .putString("sc_101", "ha|cover.garage_door|Garage")
            .apply()
        shoot(MainActivity::class.java, "home_simple.png", androidAuto("Polar Drift", "Aurora Drive", "Northern Lights", northernLights()))
    }

    @Test fun phone() = shoot(app.mg4dash.pages.PhoneActivity::class.java, "phone.png")
    /** List with a search field and the keyboard open (#93): full height, keyboard on two thirds. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun searchListKeyboard() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).commit()
        val act = Robolectric.buildActivity(app.mg4dash.pages.PhoneActivity::class.java).setup().get()
        app.mg4dash.shortcuts.Shortcuts.searchList(act, "Action Home Assistant", (1..12).map { "Example action $it  ·  script.example_action_$it" }) {}
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView
        save(root, "search_list_keyboard.png")
    }

    /** Mock-up of the full width keyboard over the phone page (#93). */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun phoneKeyboard() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(app.mg4dash.pages.PhoneActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val content = act.findViewById<android.widget.FrameLayout>(android.R.id.content)
        // Over the Recent and Contacts cards, edge to edge, as on the car
        fun fields(v: android.view.View): List<android.widget.EditText> = when (v) {
            is android.widget.EditText -> listOf(v)
            is android.view.ViewGroup -> (0 until v.childCount).flatMap { fields(v.getChildAt(it)) }
            else -> emptyList()
        }
        val book = fields(content).first().parent as android.view.View
        val recent = (book.parent as android.view.ViewGroup).getChildAt(0)
        val origin = IntArray(2).also { content.getLocationOnScreen(it) }
        val span = app.mg4dash.ui.CarKeyboard.spanOf(recent, book) ?: intArrayOf(96, 96 + 1184)
        content.addView(app.mg4dash.ui.CarKeyboard(act, "fr"), android.widget.FrameLayout.LayoutParams(span[1] - span[0], -2, android.view.Gravity.BOTTOM or android.view.Gravity.LEFT).apply { leftMargin = span[0] - origin[0]; bottomMargin = 10 })
        save(act.window.decorView, "phone_keyboard.png")
    }

    @Test fun compatReport() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val report = app.mg4dash.settings.Compat.report(activity)
        val url = app.mg4dash.settings.Compat.issueUrl(report)
        println(report); println("URL length: ${url.length}")
        org.junit.Assert.assertTrue("URL trop longue pour un QR code", url.length < 2500)
        app.mg4dash.settings.Compat.show(activity)
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        save(dlg.window!!.decorView, "compat_dialog.png")
    }

    @Test fun homeMapLayout() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("home_style", 2).apply()
        shoot(MainActivity::class.java, "home_map_layout.png")
    }

    @Test fun homeTripStart() = tripStart(0, "home_trip_start.png")
    @Test fun homeSimpleTripStart() = tripStart(1, "home_simple_trip_start.png")

    private fun tripStart(style: Int, name: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("home_style", style).apply()
        val f = app.mg4dash.energy.EnergyMonitor::class.java.getDeclaredField("_tripStartSoc").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.energy.EnergyMonitor) as kotlinx.coroutines.flow.MutableStateFlow<Int?>).value = 92
        shoot(MainActivity::class.java, name)
    }

    /** "Power" energy card (#29), Full and Map layouts. */
    @Test fun homeEnergyCardPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("energy_card_power", true).commit()
        val f = app.mg4dash.energy.EnergyMonitor::class.java.getDeclaredField("_tripStartSoc").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.energy.EnergyMonitor) as kotlinx.coroutines.flow.MutableStateFlow<Int?>).value = 92
        shoot(MainActivity::class.java, "home_energy_card_power.png")
    }

    /** Same card, cable unplugged (driving): plug with lightning bolt under the km. */
    /** Same card charging (#29): charge current and its max since plugging in. */
    @Test fun homeEnergyCardPowerCharging() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("energy_card_power", true).putBoolean("power_va", true).commit()
        val mf = app.mg4dash.car.CarProps::class.java.getDeclaredField("mock").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val mock = mf.get(app.mg4dash.car.CarProps) as MutableMap<Int, Number>
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sf = app.mg4dash.car.CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = sf.get(app.mg4dash.car.CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.car.VehicleState>
        flow.value = flow.value.copy(chargeStatus = 1)
        mock[app.mg4dash.car.CarProps.BMS_PACK_VOL] = 401.2f
        for (a in listOf(-98.4f, -117.9f, -112.3f)) {
            mock[app.mg4dash.car.CarProps.BMS_PACK_CRNT] = a
            repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS) }
        }
        save(act.window.decorView, "home_energy_card_power_charging.png")
    }

    @Test fun homeEnergyCardPowerDriving() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("energy_card_power", true).putBoolean("power_va", true).commit()
        val f = app.mg4dash.energy.EnergyMonitor::class.java.getDeclaredField("_tripStartSoc").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.energy.EnergyMonitor) as kotlinx.coroutines.flow.MutableStateFlow<Int?>).value = 92
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val sf = app.mg4dash.car.CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = sf.get(app.mg4dash.car.CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.car.VehicleState>
        flow.value = flow.value.copy(chargeStatus = 0)
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(act.window.decorView, "home_energy_card_power_driving.png")
    }

    /** Tap on the power card's kWh/100 (#29): next consumption, its logo before it and before the range, its name for 2 s. */
    @Test fun homeEnergyCardPowerSources() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("energy_card_power", true).putBoolean("power_va", true).putInt("power_cons_source", 1).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sf = app.mg4dash.car.CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = sf.get(app.mg4dash.car.CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.car.VehicleState>
        flow.value = flow.value.copy(chargeStatus = 0)
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(act.window.decorView, "power_cons_0.png")
        val avg = MainActivity::class.java.getDeclaredField("powerAvg").apply { isAccessible = true }.get(act) as android.widget.TextView
        val seen = mutableListOf<Int>()
        for (n in 1..4) {
            avg.performClick()
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(150, TimeUnit.MILLISECONDS) }
            seen += app.mg4dash.system.Prefs(act).consSource
            save(act.window.decorView, "power_cons_$n.png")
        }
        // Every consumption is in the loop, known or not yet (#29)
        org.junit.Assert.assertTrue(seen.toString(), 3 in seen && 1 in seen)
    }

    @Test fun homeMapEnergyCardPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putInt("stack_page", 0).putBoolean("energy_card_power", true).commit()
        shoot(MainActivity::class.java, "home_map_energy_card_power.png")
    }

    /** Right-hand drive: vertical bar on the right, full height. */
    @Test fun homeRightHand() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("force_rhd", true).commit()
        shoot(MainActivity::class.java, "home_right_hand.png", androidAuto("Midnight Boulevard", "Velvet Avenue", "Neon Skyline", neonSkyline()))
    }

    @Test fun homeMapRightHand() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putBoolean("force_rhd", true).commit()
        shoot(MainActivity::class.java, "home_map_right_hand.png")
    }

    @Test fun homeMapAutoHide() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("map_auto_hide", true).apply()
        shoot(MainActivity::class.java, "home_map_auto_hide.png")
    }

    @Test fun homeMapLayoutWifi() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("dock_slot", 1).putInt("stack_page", 2).apply()
        shoot(MainActivity::class.java, "home_map_layout_wifi.png")
    }

    @Test fun homeNavFull() {
        app.mg4dash.map.NavRepo.setForTest(app.mg4dash.map.NavRepo.Nav(300, 25, "Rue de Rivoli", 4, "Turn right", 2, null, null, 12400, 1260L, "17:42"))
        shoot(MainActivity::class.java, "home_nav.png")
        app.mg4dash.map.NavRepo.setForTest(null)
    }

    @Test fun homeNavMapRoundabout() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("home_style", 2).apply()
        app.mg4dash.map.NavRepo.setForTest(app.mg4dash.map.NavRepo.Nav(850, 60, "Avenue de la Grande Armée", 13, "", 2, 3, 90, 12400, 1260L, "17:42"))
        shoot(MainActivity::class.java, "home_map_layout_nav.png")
        app.mg4dash.map.NavRepo.setForTest(null)
    }

    @Test fun homeMapLayoutShortcuts() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("home_widget", 1)
            .putString("sc_0", "ha|cover.gate|Gate").putString("sc_1", "ha|cover.garage_door|Garage")
            .putString("sc_8", "ha|scene.arriving_home|Arriving").apply()
        shoot(MainActivity::class.java, "home_map_layout_shortcuts.png")
    }

    /** Live state of the Home Assistant shortcuts (#97): garage open, gate without state, scene without state. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun homeMapLayoutShortcutsState() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("home_widget", 1)
            .putString("sc_0", "ha|script.portail|Portail").putString("sc_1", "ha|cover.garage_door|Garage")
            .putString("sc_2", "ha|light.allee|Allée").putString("sc_3", "ha|lock.entree|Entrée")
            .putString("sc_8", "ha|scene.arriving_home|Arrivée").apply()
        app.mg4dash.shortcuts.HaLive.seedForTest("cover.garage_door", "open")
        app.mg4dash.shortcuts.HaLive.seedForTest("light.allee", "off")
        app.mg4dash.shortcuts.HaLive.seedForTest("lock.entree", "unavailable")
        shoot(MainActivity::class.java, "home_map_layout_shortcuts_state.png")
    }

    /** Cards' edit mode (#97), opened by a pinch: Map layout, web app on. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun editCardsMapLayout() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putString("web_url", "http://homeassistant.local:8123/mg4").apply()
        editCards("edit_cards_map.png")
    }

    /** Same in the Full layout: weather, bottom right widget and map widget. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun editCardsFullLayout() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 0).apply()
        editCards("edit_cards_full.png")
    }

    /** Detached web app window (#98): no border at rest; "Move / resize" shows its frame, handles and the hint with OK. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun webWindowDetached() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 3)
            .putString("web_url", "http://homeassistant.local:8123").putBoolean("web_float", true).putBoolean("web_floating", true)
            .putString("web_float_rect", "640,140,880,470").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val fw = MainActivity::class.java.getDeclaredField("floatWeb").apply { isAccessible = true }.get(act) as app.mg4dash.ui.FloatWindow
        org.junit.Assert.assertTrue("detached", fw.floating)
        save(act.window.decorView, "web_window_rest.png")
        fw.startMoving()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertTrue(fw.moving)
        save(act.window.decorView, "web_window_moving.png")
        // A touch far outside the window no longer ends the mode: only OK does (#98)
        val t0 = android.os.SystemClock.uptimeMillis()
        act.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0, android.view.MotionEvent.ACTION_DOWN, 300f, 80f, 0))
        act.dispatchTouchEvent(android.view.MotionEvent.obtain(t0, t0 + 80, android.view.MotionEvent.ACTION_UP, 300f, 80f, 0))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertTrue("still moving after a touch outside", fw.moving)
        fw.stopMoving()
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertFalse(fw.moving)
    }

    /** Map card detached (#98), as the web app: at rest, in the move mode, both windows at once, and both menus. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun mapWindowDetached() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 3)
            .putString("web_url", "http://homeassistant.local:8123").putBoolean("web_float", true).putBoolean("web_floating", false)
            .putString("web_float_rect", "1100,300,800,408")
            .putBoolean("map_float", true).putBoolean("map_floating", true).putString("map_float_rect", "96,297,594,411").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(act)
        val map = field("floatMap") as app.mg4dash.ui.FloatWindow
        org.junit.Assert.assertTrue("map detached", map.floating)
        save(act.window.decorView, "map_window_rest.png")
        map.startMoving()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(act.window.decorView, "map_window_moving.png")
        map.stopMoving()
        // The web app detached too: both windows over home
        MainActivity::class.java.getDeclaredMethod("detachWeb").apply { isAccessible = true }.invoke(act)
        repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val web = field("floatWeb") as app.mg4dash.ui.FloatWindow
        org.junit.Assert.assertTrue("both detached", web.floating && map.floating)
        save(act.window.decorView, "map_web_windows.png")
        // The map card's menu, with Move / resize
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putString("map_apps_recent", "com.waze/.FreeMapAppActivity").commit()
        MainActivity::class.java.getDeclaredMethod("mapMenu", Function0::class.java).apply { isAccessible = true }.invoke(act, { } as () -> Unit)
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "map_menu.png")
        org.robolectric.shadows.ShadowDialog.getLatestDialog().dismiss()
        (field("webCard") as app.mg4dash.ui.WebCard).menu(onMove = {})
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "web_menu.png")
    }

    /** Window almost as tall as the screen (#98): the hint in its middle, and a touch on OK ends the move mode. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun webWindowTallOk() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 3)
            .putString("web_url", "http://homeassistant.local:8123").putBoolean("web_float", true).putBoolean("web_floating", true)
            .putString("web_float_rect", "96,12,1810,696").commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val fw = MainActivity::class.java.getDeclaredField("floatWeb").apply { isAccessible = true }.get(act) as app.mg4dash.ui.FloatWindow
        fw.startMoving()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(act.window.decorView, "web_window_moving_tall.png")
        val hint = app.mg4dash.ui.FloatWindow::class.java.getDeclaredField("hint").apply { isAccessible = true }.get(fw) as android.view.ViewGroup
        val ok = hint.getChildAt(1)
        val at = IntArray(2).also { ok.getLocationInWindow(it) }
        val x = at[0] + ok.width / 2f; val y = at[1] + ok.height / 2f
        val t = android.os.SystemClock.uptimeMillis()
        act.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
        act.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t + 80, android.view.MotionEvent.ACTION_UP, x, y, 0))
        repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertFalse("OK ends the move mode", fw.moving)
    }

    /** Web address dialog in the light theme (#97): the field's text dark, MG4Dash's keyboard with its larger letters. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun webAddressLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 2).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val input = android.widget.EditText(act).apply { setText("http://homeassistant.local:8123"); setSingleLine() }
        val box = android.widget.LinearLayout(act).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 8, 24, 0); addView(input) }
        val dlg = android.app.AlertDialog.Builder(act).setTitle(R.string.wc_web_address).setView(box)
            .setPositiveButton(android.R.string.ok, null).setNegativeButton(R.string.cancel, null).show()
        app.mg4dash.ui.Dialogs.fix(dlg)
        app.mg4dash.ui.CarKeyboard.attachInDialog(input, dlg, box)
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        org.junit.Assert.assertEquals(app.mg4dash.ui.Themes.color(act, R.color.text_title), input.currentTextColor)
        save(dlg.window!!.decorView, "web_address_light.png")
    }

    /** Web app card without an address yet (#97). */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun webCardNoAddress() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 3).apply()
        shoot(MainActivity::class.java, "home_map_layout_web.png")
    }

    private fun editCards(name: String) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        if (!sp.contains("theme_mode")) sp.edit().putInt("theme_mode", 1).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        // Laid out first: the edit mode places its boxes over the widgets
        save(activity.window.decorView, "tmp_$name")
        activity.editCards()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(activity.window.decorView, name)
        File("build/screenshots/tmp_$name").delete()
    }

    @Test fun vehicleSoh() {
        val now = System.currentTimeMillis()
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putString("cap_samples", "[{\"t\":${now - 86400000L * 40},\"k\":48.6,\"d\":40,\"m\":260},{\"t\":${now - 86400000L * 10},\"k\":47.9,\"d\":55,\"m\":350},{\"t\":${now - 86400000L * 2},\"k\":48.2,\"d\":30,\"m\":190}]")
            .putBoolean("battery_kwh_manual", false).apply()
        app.mg4dash.energy.BatteryCapacity.apply(org.robolectric.RuntimeEnvironment.getApplication())
        shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle_soh.png")
    }

    @Test fun homeMapLayoutVertical() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putBoolean("vertical_dock", true).putBoolean("hide_titles", true).putInt("stack_page", 2).putInt("weather_page", 1).apply()
        shoot(MainActivity::class.java, "home_map_layout_vertical.png")
    }

    @Test fun homeMapLayoutConsumption() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("home_widget", 2).apply()
        shoot(MainActivity::class.java, "home_map_layout_consumption.png")
    }

    /** Current trip (#44): its duration in the top right corner, the average and the last km on the chart's title line. */
    @Test fun homeMapLayoutConsumptionTrip() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("home_widget", 2).putInt("consumption_period", 1).apply()
        app.mg4dash.energy.EnergyMonitor.tripStartForTest(System.currentTimeMillis() - 72 * 60_000L)
        shoot(MainActivity::class.java, "home_map_layout_consumption_trip.png")
        app.mg4dash.energy.EnergyMonitor.tripStartForTest(0L)
    }

    @Test fun homeMapLayoutConsumptionYear() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).putInt("home_widget", 2).putInt("consumption_period", 2).apply()
        shoot(MainActivity::class.java, "home_map_layout_consumption_year.png")
    }

    @Test fun homeMiles() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("units", 2).apply()
        shoot(MainActivity::class.java, "home_miles.png")
    }

    private fun offline(name: String) {
        val f = app.mg4dash.weather.WeatherRepo::class.java.getDeclaredField("_weather").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(app.mg4dash.weather.WeatherRepo) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        val saved = flow.value
        flow.value = null
        try { shoot(MainActivity::class.java, name) } finally { flow.value = saved }
    }

    @Test fun homeOffline() = offline("home_offline.png")

    @Test fun homeSimpleOffline() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 1).apply()
        offline("home_simple_offline.png")
    }

    @Test fun homeMapLayoutOffline() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).apply()
        // No weather (offline): empty the simulated weather
        val f = app.mg4dash.weather.WeatherRepo::class.java.getDeclaredField("_weather").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = f.get(app.mg4dash.weather.WeatherRepo) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        val saved = flow.value
        flow.value = null
        try { shoot(MainActivity::class.java, "home_map_layout_offline.png") } finally { flow.value = saved }
    }

    @Test fun homeHeatedSeats() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("heated_seats", 1).putBoolean("vertical_dock", true).apply()
        shoot(MainActivity::class.java, "home_heated_seats.png", bluetooth("Salt & Sunlight", "Coastline Club", "Golden Hours", goldenHours()))
    }

    @Test fun homeSimpleHeatedSeats() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("heated_seats", 1).putInt("home_style", 1).apply()
        shoot(MainActivity::class.java, "home_simple_heated_seats.png")
    }

    @Test fun homeVerticalFull() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).apply()
        shoot(MainActivity::class.java, "home_vertical_full.png")
    }

    @Test fun homeVerticalSimple() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).putInt("home_style", 1).apply()
        shoot(MainActivity::class.java, "home_vertical_simple.png")
    }

    @Test fun energyVertical() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("vertical_dock", true).apply()
        shoot(app.mg4dash.pages.EnergyActivity::class.java, "energy_vertical.png")
    }

    @Test fun homeWidgetPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("secondary_widget", 1).apply()
        shoot(MainActivity::class.java, "home_widget_power.png")
    }

    /** Phone shortcuts: 2, 3 (one line), 4, 6, 8 (two lines), and the number choice. */
    @Test fun phoneFavs2() = phoneFavs(2)
    @Test fun phoneFavs4() = phoneFavs(4)
    @Test fun phoneFavs6() = phoneFavs(6)
    @Test fun phoneFavs8() = phoneFavs(8)
    private fun phoneFavs(n: Int) {
        val names = listOf("Claire Martin", "Garage MG", "Home", "Office", "Paul", "Sophie", "School", "Dr Bernard")
        val e = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("secondary_widget", 2).putInt("fav_phone_count", n)
        names.forEachIndexed { i, name -> if (i != n - 1) e.putString("fav_phone_$i", "$name+3360000000$i") }
        e.apply()
        shoot(MainActivity::class.java, "home_widget_phone_$n.png")
    }
    @Test fun phoneFavsCount() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("fav_phone_count", 3).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        app.mg4dash.shortcuts.PhoneFavorites.chooseCount(activity) {}
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView, "phone_fav_count_dialog.png")
    }

    /** Tutorial: animated Music step (swipe up / down then left / right), frames for a GIF. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tutorialSwipeFrames() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        app.mg4dash.ui.Tutorial.show(activity, app.mg4dash.ui.Tutorial.musicStep)
        val dir = File("build/screenshots").apply { mkdirs() }
        val fps = 20; val frames = 132
        java.io.DataOutputStream(File(dir, "tuto_swipe.rgb").outputStream().buffered()).use { out ->
            out.writeInt(960); out.writeInt(360); out.writeInt(frames); out.writeInt(100 / fps)
            for (f in 0 until frames) {
                shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
                val big = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888); activity.window.decorView.draw(Canvas(big))
                val small = Bitmap.createScaledBitmap(big, 960, 360, true)
                val px = IntArray(960 * 360); small.getPixels(px, 0, 960, 0, 0, 960, 360); px.forEach { out.writeInt(it) }
            }
        }
    }

    /** Gesture tutorial: a few steps, in French. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tutorialSteps() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        for (step in 0 until app.mg4dash.ui.Tutorial.stepCount) {
            app.mg4dash.ui.Tutorial.show(activity, step)
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(activity.window.decorView, "tutorial_$step.png")
        }
    }

    /** Gesture tour with the bottom bar and cards moved (#97): each step points at the bar in use and at the widgets where they are. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun tutorialStepsMovedCards() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1)
            .putBoolean("vertical_dock", false)
            .putString("slots_full", "tl=media;tc=map,consumption,trip;tr=climate;bl=energy,power;bc=hourly,daily;br=shortcuts,phone").commit()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        for (step in 3 until 9) {
            app.mg4dash.ui.Tutorial.show(activity, step)
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(activity.window.decorView, "tutorial_moved_$step.png")
        }
    }

    @Test fun homeWidgetPhone() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("secondary_widget", 2)
            .putString("fav_phone_0", "Claire Martin\u001F+33612345678").putString("fav_phone_1", "Garage MG\u001F+33123456789").apply()
        shoot(MainActivity::class.java, "home_widget_phone.png")
    }

    @Test fun homeShortcuts() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_widget", 1)
            .putString("sc_0", "ha|cover.gate|Gate")
            .putString("sc_1", "ha|cover.garage_door|Garage")
            .putString("sc_2", "ha|script.driveway_lights|Driveway")
            .putString("sc_3", "ha|scene.arriving_home|Arriving")
            .putString("sc_4", "ha|switch.heating|Heating")
            .putString("sc_5", "ha|automation.alarm|Alarm")
            .apply()
        shoot(MainActivity::class.java, "home_shortcuts.png")
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun homeShortcutsFr() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_widget", 1)
            .putString("sc_0", "ha|cover.portail|Portail")
            .putString("sc_1", "ha|cover.porte_garage|Garage")
            .putString("sc_2", "ha|script.eclairage_allee|Allée")
            .putString("sc_3", "ha|scene.arrivee|Arrivée")
            .putString("sc_4", "ha|switch.chauffage|Chauffage")
            .putString("sc_5", "ha|automation.alarme|Alarme")
            .apply()
        shoot(MainActivity::class.java, "home_shortcuts_fr.png")
    }

    /** Keep climate on: window on home, then its screen (readable from outside). */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun climateHold() = climateHoldShots("")

    @Test fun climateHoldEn() = climateHoldShots("_en")

    private fun climateHoldShots(sfx: String) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", 1).putBoolean("climhold_enabled", true).putInt("climhold_max_min", 0).commit()
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        app.mg4dash.climate.ClimateHoldPanel.show(home)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        save(home.window.decorView, "climhold_menu$sfx.png")
        sp.edit().putBoolean("climhold_scheduled", true).putInt("climhold_runs", 3).commit()
        app.mg4dash.climate.ClimateHoldPanel.show(home)
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        save(home.window.decorView, "climhold_menu_at$sfx.png")
        app.mg4dash.climate.ClimateHold.start(home, 0, 20)
        val armed = Robolectric.buildActivity(app.mg4dash.climate.ClimateHoldActivity::class.java).setup().get()
        repeat(3) { Thread.sleep(200); shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS) }
        save(armed.window.decorView, "climhold_countdown$sfx.png")
        app.mg4dash.climate.ClimateHold.start(home, 0, 0)
        val screen = Robolectric.buildActivity(app.mg4dash.climate.ClimateHoldActivity::class.java).setup().get()
        repeat(6) { Thread.sleep(300); shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS) }
        save(screen.window.decorView, "climhold_screen$sfx.png")
        app.mg4dash.climate.ClimateHold.schedule(home, System.currentTimeMillis() + 42 * 60_000L, 3, 15, 30, 0)
        val waiting = Robolectric.buildActivity(app.mg4dash.climate.ClimateHoldActivity::class.java).setup().get()
        repeat(6) { Thread.sleep(300); shadowOf(Looper.getMainLooper()).idleFor(1000, TimeUnit.MILLISECONDS) }
        save(waiting.window.decorView, "climhold_waiting$sfx.png")
        app.mg4dash.climate.ClimateHold.stop("fin du test")
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun homeFr() = shoot(MainActivity::class.java, "home_fr.png")

    /** Preview of the icons offered for climate buttons (idle / selected). */
    @Test fun climateIconsPreview() {
        val act = Robolectric.buildActivity(app.mg4dash.apps.AppsActivity::class.java).setup().get()
        val kit = app.mg4dash.ui.Kit(act)
        val root = kit.column().apply { setBackgroundResource(R.drawable.bg_root); setPadding(kit.dp(30), kit.dp(24), kit.dp(30), kit.dp(24)) }
        root.addView(kit.title("PROPOSITION · BOUTON RECYCLAGE (3 états) ET BOUTON DIRECTION DE L'AIR (5 états)").apply { textSize = 20f })
        val rows = listOf(
            listOf(R.drawable.ic_air_recirc to "Air recyclé", R.drawable.ic_air_outside to "Air extérieur"),
            listOf(R.drawable.ic_flow_face to "Visage", R.drawable.ic_flow_face_feet to "Visage + pieds", R.drawable.ic_flow_feet to "Pieds",
                R.drawable.ic_flow_feet_screen to "Pieds + pare-brise"),
        )
        rows.forEach { items ->
            val row = kit.row()
            items.forEachIndexed { i, (icon, label) ->
                val col = kit.column().apply { gravity = android.view.Gravity.CENTER }
                val chip = android.widget.FrameLayout(act).apply { setBackgroundResource(R.drawable.bg_chip); isSelected = i == 0 }
                val iv = android.widget.ImageView(act).apply {
                    setImageResource(icon); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setColorFilter(if (i == 0) 0xFF00D2FF.toInt() else 0xFFA0AEC0.toInt())
                }
                chip.addView(iv, android.widget.FrameLayout.LayoutParams(kit.dp(58), kit.dp(58), android.view.Gravity.CENTER))
                col.addView(chip, kit.lp(kit.dp(150), kit.dp(72)))
                col.addView(kit.text(label, 18f, R.color.text_sub).apply { gravity = android.view.Gravity.CENTER }, kit.lp(-1, -2, t = 8))
                row.addView(col, kit.lp(0, -2, 1f, l = if (i > 0) 16 else 0))
            }
            if (items.size < 5) repeat(5 - items.size) { row.addView(android.view.View(act), kit.lp(0, 1, 1f, l = 16)) }
            root.addView(row, kit.lp(-1, -2, t = 26))
        }
        act.setContentView(root)
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val w = 1200; val h = 420
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp)); roundClipped(root, bmp)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "climate_icons_preview.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Bar's Vehicle icon: normal and skidding (ESC off), in red. */
    @Test fun carDriftIcon() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val bmp = Bitmap.createBitmap(260, 120, Bitmap.Config.ARGB_8888); bmp.eraseColor(0xFF0B0F17.toInt())
        val c = Canvas(bmp)
        listOf(R.drawable.ic_car to 0xFFA0AEC0.toInt(), R.drawable.ic_car_drift to 0xFFFF5252.toInt()).forEachIndexed { i, (res, col) ->
            val dr = androidx.core.content.ContextCompat.getDrawable(ctx, res)!!.mutate()
            dr.setTint(col); dr.setBounds(20 + i * 130, 10, 120 + i * 130, 110); dr.draw(c)
        }
        File(File("build/screenshots").apply { mkdirs() }, "car_drift_icon.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Full power from home: switch to the Power widget, animation, then back. */
    @Test fun boostFlames() = boost(3, "flames")
    @Test fun boostLightning() = boost(2, "lightning")
    @Test fun boostHyperspace() = boost(5, "hyperspace")
    @Test fun boostHyperspaceLight() = boost(5, "hyperspace_light", theme = 2)

    private fun boost(style: Int, name: String, theme: Int = 1) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", theme).putInt("secondary_widget", 0).putInt("power_fx", style).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val looper = shadowOf(Looper.getMainLooper())
        val scratch = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        fun run(ms: Long) { var t = 0L; while (t < ms) { looper.idleFor(32, TimeUnit.MILLISECONDS); Thread.sleep(1); t += 32; act.window.decorView.draw(Canvas(scratch)) } }
        run(1500)
        app.mg4dash.car.CarProps.setInt(app.mg4dash.car.CarProps.BMS_PACK_CRNT, 330, "test")
        run(2600)
        save(act.window.decorView, "boost_${name}_on.png")
        app.mg4dash.car.CarProps.setInt(app.mg4dash.car.CarProps.BMS_PACK_CRNT, 20, "test")
        run(13000)
        save(act.window.decorView, "boost_${name}_after.png")
    }

    /** Full power animation: successive frames of the settings preview, one sheet per style. */
    @Test fun powerFx() {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val names = mapOf(2 to "lightning", 3 to "flames", 5 to "hyperspace")
        val times = longArrayOf(150, 300, 500, 900, 1300, 2500, 2800, 3200, 4200)
        for ((style, name) in names) {
            val root = android.widget.FrameLayout(act)
            val pv = app.mg4dash.ui.PowerFxPreview(act)
            root.addView(pv, android.widget.FrameLayout.LayoutParams(-1, -2))
            act.setContentView(root)
            val w = 900; val h = 190
            fun lay() {
                root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, w, h)
            }
            lay()
            pv.play(style)
            val sheet = Bitmap.createBitmap(w, h * times.size, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet)
            var t = 0L
            times.forEachIndexed { i, at ->
                while (t < at) { shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS); t += 16; lay(); root.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))) }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(0xFF0B0F17.toInt())
                root.draw(Canvas(bmp)); roundClipped(root, bmp)
                c.drawBitmap(bmp, 0f, (i * h).toFloat(), null)
            }
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "powerfx_$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Issue #29 mockup: range and energy bars in the power bar style. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun barsMockup() { barsSheet(1, "bars_mockup_dark.png"); }
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi")
    fun barsMockupLight() { barsSheet(2, "bars_mockup_light.png"); }

    private fun barsSheet(mode: Int, name: String) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", mode).commit()
        val act = Robolectric.buildActivity(app.mg4dash.pages.ClimateActivity::class.java).setup().get()
        val kit = app.mg4dash.ui.Kit(act)
        val root = kit.column().apply { setBackgroundResource(R.drawable.bg_root); setPadding(kit.dp(20), kit.dp(16), kit.dp(20), kit.dp(16)) }
        fun card(title: String, body: android.view.View.(android.widget.LinearLayout) -> Unit): android.widget.LinearLayout =
            kit.panel(title).apply { body(this) }
        fun battery(soc: Float, start: Float?, km: Int, charging: Boolean, label: String) = card(label) { p ->
            val r = kit.row()
            r.addView(kit.text(String.format(java.util.Locale.FRANCE, "%.1f %%", soc), 52f, light = true))
            r.addView(kit.column().apply {
                addView(kit.text("$km km", 28f)); addView(kit.text("autonomie", 18f, R.color.text_sub))
            }, kit.lp(-2, -2, l = 16))
            p.addView(r)
            p.addView(app.mg4dash.ui.SocBar(act).apply { this.soc = soc; tripStart = start; this.charging = charging }, kit.lp(-1, kit.dp(96), t = 4))
        }
        fun energy() = card("ÉNERGIE DU TRAJET") { p ->
            p.addView(app.mg4dash.ui.StackedBar(act).apply {
                parts = listOf(app.mg4dash.ui.StackedBar.Part(5.2f, kit.color(R.color.accent_energy)),
                    app.mg4dash.ui.StackedBar.Part(0.9f, kit.color(R.color.accent_cold)), app.mg4dash.ui.StackedBar.Part(0.4f, kit.color(R.color.accent_heat)))
            }, kit.lp(-1, kit.dp(40), t = 30))
            val r = kit.row()
            listOf(Triple("80 %", "CONDUITE", R.color.accent_energy), Triple("14 %", "CLIM", R.color.accent_cold), Triple("6 %", "ACCESSOIRES", R.color.accent_heat)).forEach { (v, t, c) ->
                r.addView(kit.column().apply {
                    gravity = android.view.Gravity.CENTER
                    addView(kit.text(v, 38f, c)); addView(kit.text(t, 16f, R.color.text_title).apply { letterSpacing = 0.1f })
                }, kit.lp(0, -2, 1f))
            }
            p.addView(r, kit.lp(-1, -2, t = 12))
        }
        fun power() = card("PUISSANCE EN DIRECT") { p ->
            p.addView(app.mg4dash.ui.PowerBar(act).apply { configureFor(51, false); kw = 72f }, kit.lp(-1, kit.dp(40), t = 30))
            val r = kit.row()
            r.addView(kit.column().apply { gravity = android.view.Gravity.CENTER; addView(kit.text("72,0 kW", 38f)); addView(kit.text("CONSOMMÉE", 16f, R.color.text_title).apply { letterSpacing = 0.1f }) }, kit.lp(0, -2, 1f))
            r.addView(kit.column().apply { gravity = android.view.Gravity.CENTER; addView(kit.text("17,9 kWh/100", 38f)); addView(kit.text("MOYENNE TRAJET", 16f, R.color.text_title).apply { letterSpacing = 0.1f }) }, kit.lp(0, -2, 1f))
            p.addView(r, kit.lp(-1, -2, t = 12))
        }
        val row1 = kit.row().apply { gravity = android.view.Gravity.TOP }
        row1.addView(battery(82.1f, 90f, 297, false, "BATTERIE · EN ROUTE"), kit.lp(0, kit.dp(310), 1f))
        row1.addView(energy(), kit.lp(0, kit.dp(310), 1f, l = 12))
        row1.addView(power(), kit.lp(0, kit.dp(310), 1f, l = 12))
        val row2 = kit.row().apply { gravity = android.view.Gravity.TOP }
        row2.addView(battery(24f, 41f, 86, false, "BATTERIE · BASSE"), kit.lp(0, kit.dp(300), 1f))
        row2.addView(battery(63f, null, 228, true, "BATTERIE · EN CHARGE"), kit.lp(0, kit.dp(300), 1f, l = 12))
        row2.addView(battery(9f, 18f, 31, false, "BATTERIE · TRÈS BASSE"), kit.lp(0, kit.dp(300), 1f, l = 12))
        root.addView(row1, kit.lp(-1, -2)); root.addView(row2, kit.lp(-1, -2, t = 12))
        val w = 1920; val h = 690
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp)); roundClipped(root, bmp)
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Shortcuts menu pulled down from the top edge (the real gesture, through the screen's touches). */
    @Test fun shortcutsMenuDark() = shortcutsMenu(1, "shortcuts_menu_dark.png")
    @Test fun shortcutsMenuLight() = shortcutsMenu(2, "shortcuts_menu_light.png")
    @Test fun shortcutsMenuHalf() = shortcutsMenu(2, "shortcuts_menu_half.png", release = false)

    private fun shortcutsMenu(theme: Int, name: String, release: Boolean = true) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", theme).putBoolean("fullscreen", false).putBoolean("tutorial_shortcuts_seen", true).commit()   // the screen's own gesture (outside full screen)
        app.mg4dash.ui.ShortcutsPanel.init(org.robolectric.RuntimeEnvironment.getApplication())
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val cb = activity.window.callback
        fun touch(action: Int, y: Float, t: Long) = cb.dispatchTouchEvent(android.view.MotionEvent.obtain(0, t, action, 960f, y, 0))
        touch(android.view.MotionEvent.ACTION_DOWN, 10f, 0)
        var y = 10f
        for (i in 1..12) { y += if (release) 40f else 15f; touch(android.view.MotionEvent.ACTION_MOVE, y, i * 16L) }
        if (release) touch(android.view.MotionEvent.ACTION_UP, y, 300)
        // The tiles' states are read in the background: let them come back
        repeat(12) { Thread.sleep(150); shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        save(activity.window.decorView, name)
    }

    private fun <T : Activity> shoot(cls: Class<T>, name: String, media: app.mg4dash.media.MediaHub.NowPlaying? = null) {
        // Screenshots in dark mode, unless the test chose another mode
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        if (!sp.contains("theme_mode")) sp.edit().putInt("theme_mode", 1).commit()
        val activity = Robolectric.buildActivity(cls).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        if (media != null) {
            val f = app.mg4dash.media.MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(app.mg4dash.media.MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.media.MediaHub.NowPlaying>).value = media
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        }
        save(activity.window.decorView, name)
    }

    // ── Sample music for README screenshots: fictional albums and artists, drawn covers ──

    private fun bluetooth(title: String, artist: String, album: String, art: Bitmap) = app.mg4dash.media.MediaHub.NowPlaying(
        true, "com.android.bluetooth", "BLUETOOTH", title, artist, album, art, true, durationMs = 214_000, positionMs = 83_000)

    private fun androidAuto(title: String, artist: String, album: String, art: Bitmap) = app.mg4dash.media.MediaHub.NowPlaying(
        true, "com.allgo.remoteui.mediabrowserservice", "ANDROID AUTO", title, artist, album, art, true,
        durationMs = 247_000, positionMs = 141_000, androidAuto = true)

    private fun cover(draw: (Canvas, android.graphics.Paint, Float) -> Unit): Bitmap {
        val size = 480f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG), size)
        return bmp
    }

    /** "Neon Skyline": striped sun on a purple sky and a pink grid. */
    private fun neonSkyline() = cover { c, p, n ->
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, n * 0.62f, intArrayOf(0xFF1A0B3D.toInt(), 0xFF6B1E7A.toInt(), 0xFFF0567A.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n, n * 0.62f, p)
        p.shader = android.graphics.LinearGradient(0f, n * 0.2f, 0f, n * 0.62f, 0xFFFFD25A.toInt(), 0xFFFF4F8B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawCircle(n / 2, n * 0.42f, n * 0.24f, p)
        p.shader = null; p.color = 0xFF2A0F4A.toInt()
        for (i in 0 until 5) c.drawRect(0f, n * (0.44f + i * 0.04f), n, n * (0.44f + i * 0.04f) + 3f + i * 2f, p)
        p.color = 0xFF14082E.toInt(); c.drawRect(0f, n * 0.62f, n, n, p)
        p.color = 0xFFFF3EA5.toInt(); p.strokeWidth = 3f
        for (i in 0..8) { val y = n * 0.62f + (n * 0.38f) * (i * i) / 64f; c.drawLine(0f, y, n, y, p) }
        for (i in -8..8) c.drawLine(n / 2 + i * n * 0.04f, n * 0.62f, n / 2 + i * n * 0.22f, n, p)
    }

    /** "Golden Hours": sunset on a turquoise sea, a palm tree. */
    private fun goldenHours() = cover { c, p, n ->
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, n * 0.6f, intArrayOf(0xFFFFB347.toInt(), 0xFFFF7E5F.toInt(), 0xFFE0567A.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n, n * 0.6f, p)
        p.shader = null; p.color = 0xFFFFF1C1.toInt(); c.drawCircle(n * 0.62f, n * 0.5f, n * 0.17f, p)
        p.shader = android.graphics.LinearGradient(0f, n * 0.6f, 0f, n, 0xFF1F9EA8.toInt(), 0xFF0B4F6C.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, n * 0.6f, n, n, p)
        p.shader = null; p.color = 0x66FFF1C1
        for (i in 0 until 7) { val w = n * (0.30f - i * 0.035f); val y = n * (0.64f + i * 0.045f); c.drawRect(n * 0.62f - w / 2, y, n * 0.62f + w / 2, y + 5f, p) }
        p.color = 0xFF3B1F2B.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(n * 0.16f, n); quadTo(n * 0.2f, n * 0.62f, n * 0.27f, n * 0.36f); lineTo(n * 0.29f, n * 0.37f); quadTo(n * 0.23f, n * 0.63f, n * 0.2f, n); close() }, p)
        for (a in listOf(-160f, -125f, -60f, -25f, 15f)) {
            val r = Math.toRadians(a.toDouble())
            val ex = n * 0.28f + (Math.cos(r) * n * 0.2f).toFloat(); val ey = n * 0.36f + (Math.sin(r) * n * 0.1f).toFloat() + n * 0.05f
            c.drawPath(android.graphics.Path().apply { moveTo(n * 0.28f, n * 0.36f); quadTo((n * 0.28f + ex) / 2, ey - n * 0.09f, ex, ey); quadTo((n * 0.28f + ex) / 2, ey - n * 0.05f, n * 0.28f, n * 0.37f); close() }, p)
        }
    }

    /** "Northern Lights": green aurora over mountains and a starry sky. */
    private fun northernLights() = cover { c, p, n ->
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, n, 0xFF050B24.toInt(), 0xFF0E2A4A.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, n, n, p)
        p.shader = null; p.color = 0xCCFFFFFF.toInt()
        val rnd = java.util.Random(7)
        repeat(60) { c.drawCircle(rnd.nextFloat() * n, rnd.nextFloat() * n * 0.6f, 1f + rnd.nextFloat() * 1.8f, p) }
        listOf(0x9938F2A0.toInt() to 0.30f, 0x7722D3C5.toInt() to 0.40f, 0x55A46BFF.toInt() to 0.22f).forEach { (col, y) ->
            p.shader = android.graphics.LinearGradient(0f, n * (y - 0.12f), 0f, n * (y + 0.12f), intArrayOf(0x00000000, col, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP)
            c.drawPath(android.graphics.Path().apply {
                moveTo(0f, n * (y + 0.1f)); cubicTo(n * 0.3f, n * (y - 0.2f), n * 0.6f, n * (y + 0.25f), n, n * (y - 0.05f))
                lineTo(n, n * (y + 0.12f)); cubicTo(n * 0.6f, n * (y + 0.35f), n * 0.3f, n * (y - 0.05f), 0f, n * (y + 0.22f)); close()
            }, p)
        }
        p.shader = null; p.color = 0xFF07101F.toInt()
        c.drawPath(android.graphics.Path().apply { moveTo(0f, n); lineTo(0f, n * 0.74f); lineTo(n * 0.22f, n * 0.58f); lineTo(n * 0.38f, n * 0.72f); lineTo(n * 0.58f, n * 0.52f); lineTo(n * 0.8f, n * 0.7f); lineTo(n, n * 0.6f); lineTo(n, n); close() }, p)
    }

    /**
     * Robolectric does not crop views to their outline (clipToOutline): the map and player
     * came out with square corners. Corners outside the rounding are repainted with the colour taken just
     * beside the view, as on the car.
     */
    private fun roundClipped(root: View, bmp: Bitmap) {
        val outline = android.graphics.Outline()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val canvas = Canvas(bmp)
        fun pixel(x: Int, y: Int) = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
        fun walk(v: View, ox: Int, oy: Int) {
            if (v.visibility != View.VISIBLE) return
            val x = ox + v.left; val y = oy + v.top
            if (v.clipToOutline && v.width > 0 && v.height > 0) {
                outline.setEmpty()
                v.outlineProvider?.getOutline(v, outline)
                val r = outline.radius
                if (r > 0f) {
                    val l = x.toFloat(); val t = y.toFloat(); val rr = (x + v.width).toFloat(); val b = (y + v.height).toFloat()
                    // Corner, sampling point just outside the view
                    listOf(
                        Triple(android.graphics.RectF(l, t, l + r, t + r), android.graphics.PointF(l + r, t + r), pixel(x - 3, y + r.toInt())),
                        Triple(android.graphics.RectF(rr - r, t, rr, t + r), android.graphics.PointF(rr - r, t + r), pixel(x + v.width + 2, y + r.toInt())),
                        Triple(android.graphics.RectF(l, b - r, l + r, b), android.graphics.PointF(l + r, b - r), pixel(x - 3, y + v.height - r.toInt())),
                        Triple(android.graphics.RectF(rr - r, b - r, rr, b), android.graphics.PointF(rr - r, b - r), pixel(x + v.width + 2, y + v.height - r.toInt())),
                    ).forEach { (square, centre, color) ->
                        val corner = android.graphics.Path().apply { addRect(square, android.graphics.Path.Direction.CW) }
                        val round = android.graphics.Path().apply { addCircle(centre.x, centre.y, r, android.graphics.Path.Direction.CW) }
                        corner.op(round, android.graphics.Path.Op.DIFFERENCE)
                        paint.color = color
                        canvas.drawPath(corner, paint)
                    }
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), x - v.scrollX, y - v.scrollY)
        }
        walk(root, -root.left, -root.top)
    }

    // ── The eight layouts and the Driving card (#38), in build/screenshots/final/ ──

    private var lastHome: org.robolectric.android.controller.ActivityController<MainActivity>? = null

    @org.junit.After fun dropHome() { lastHome?.let { runCatching { it.pause().stop().destroy() } }; lastHome = null }

    private fun layoutHome(layout: app.mg4dash.ui.HomeSlots.Layout, slots: String?, mode: Int, drive: Int = 3): MainActivity {
        val e = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", mode).putInt("home_style", 0).putString("home_layout", layout.key)
        if (slots != null) e.putString("slots_" + layout.key, slots) else e.remove("slots_" + layout.key)
        e.commit()
        paris()
        // One home at a time: the previous one destroyed, its memory freed
        lastHome?.let { runCatching { it.pause().stop().destroy() } }
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        lastHome = ctl
        val act = ctl.get()
        @Suppress("UNCHECKED_CAST")
        val flow = app.mg4dash.car.CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(app.mg4dash.car.CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.car.VehicleState>
        flow.value = flow.value.copy(driveMode = drive, regenLevel = 3, onePedal = 0, steeringMode = 2, brakePedalMode = 1, lasMode = 3, fcwState = 2, fcwBrakeMode = 2)
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        return act
    }

    /** [root] with [over] (a popup's or a dialog's content) drawn over it at [x], [y]. */
    private fun saveOver(root: View, over: View, x: Int, y: Int, w: Int, name: String) {
        save(root, name)
        val file = File("build/screenshots", name)
        val bmp = android.graphics.BitmapFactory.decodeFile(file.path).copy(Bitmap.Config.ARGB_8888, true)
        over.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        over.layout(0, 0, over.measuredWidth, over.measuredHeight)
        val c = Canvas(bmp); c.translate(x.toFloat(), y.toFloat()); over.draw(c)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The gesture tour's miniatures of home (Marc, 14/11): Simple and three layouts, dark, 528 × 198 (264 × 99 dp at
     * 2×), in build/screenshots/tutorial/, to copy into res/drawable-nodpi.
     */
    @Test fun tutorialMiniatures() {
        val dir = File("build/screenshots/tutorial").apply { mkdirs() }
        fun mini(act: Activity, name: String) {
            save(act.window.decorView, "tmp_$name.png")
            val tmp = File("build/screenshots", "tmp_$name.png")
            val full = android.graphics.BitmapFactory.decodeFile(tmp.path)
            val small = Bitmap.createScaledBitmap(full, 528, 198, true)
            File(dir, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
            tmp.delete()
        }
        listOf(HL.FULL to "tu_layout_full", HL.BAND_TOP to "tu_layout_band_top", HL.SQUARE_RIGHT to "tu_layout_square_right")
            .forEach { (layout, name) -> mini(layoutHome(layout, null, 1), name) }
        // Simple: three panes, its two shortcuts (filled, as in home_simple.png) and the weather
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 1).remove("home_layout")
            .putString("sc_100", "ha|cover.gate|Gate").putString("sc_101", "ha|cover.garage_door|Garage").commit()
        lastHome?.let { runCatching { it.pause().stop().destroy() } }
        val ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        lastHome = ctl
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        mini(ctl.get(), "tu_layout_simple")
        org.junit.Assert.assertEquals(4, dir.listFiles { f -> f.name.startsWith("tu_layout_") }!!.size)
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun layoutsFinal() {
        File("build/screenshots/final").mkdirs()
        app.mg4dash.ui.HomeSlots.Layout.entries.forEachIndexed { i, layout ->
            save(layoutHome(layout, null, 1).window.decorView, "final/layout_${i + 1}_${layout.key}_dark.png")
        }
        save(layoutHome(app.mg4dash.ui.HomeSlots.Layout.SQUARE_RIGHT, null, 2).window.decorView, "final/layout_5_square_right_light.png")
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun drivingCardFinal() {
        File("build/screenshots/final").mkdirs()
        val places = listOf(
            Triple("M", HL.FULL, "tl=climate;tc=driving;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone"),
            Triple("P", HL.FULL, "tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=driving;br=trip,power,phone"),
            Triple("H", HL.MAP_CENTER, "tl=hourly,daily;bl=climate;c=driving;tr=media;br=energy,trip,power,phone"),
            Triple("G", HL.SQUARE_LEFT, "g=driving;tr=climate;br=energy,trip,power,phone,hourly,daily"),
            Triple("L", HL.BAND_TOP, "w=driving;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone"),
        )
        for (mode in listOf(1, 2)) for ((size, layout, slots) in places) {
            val act = layoutHome(layout, slots, mode, drive = if (size == "G" || size == "H") 7 else 3)
            save(act.window.decorView, "final/driving_${size}_${if (mode == 1) "dark" else "light"}.png")
        }
        // "Tout ›" of the medium card: the whole card in a window over home
        for (mode in listOf(1, 2)) {
            val act = layoutHome(HL.FULL, places[0].third, mode, drive = 7)
            app.mg4dash.ui.DrivingCard.openAll(act)
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val panel = ((dlg.window!!.decorView as ViewGroup).findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)
            saveOver(act.window.decorView, panel, (1920 - 1100) / 2, 40, 1100, "final/driving_all_${if (mode == 1) "dark" else "light"}.png")
            dlg.dismiss()
        }
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun layoutEditorFinal() {
        File("build/screenshots/final").mkdirs()
        for ((mode, layout) in listOf(1 to HL.FULL, 2 to HL.FULL, 2 to HL.SQUARE_LEFT, 1 to HL.BAND_BOTTOM)) {
            val act = layoutHome(layout, null, mode)
            act.editCards()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(act.window.decorView, "final/edit_${layout.key}_${if (mode == 1) "dark" else "light"}.png")
        }
        // Bottom bar: the hint and the three buttons over it, the layout menu opened above them
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putBoolean("vertical_dock", false).commit()
        for (mode in listOf(2, 1)) {
            val act = layoutHome(HL.MAP, null, mode)
            act.editCards()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(act.window.decorView, "final/edit_bottom_${if (mode == 1) "dark" else "light"}.png")
            app.mg4dash.ui.LayoutPicker.show(act, act.window.decorView, HL.MAP) {}
            val pop = org.robolectric.shadows.ShadowApplication.getInstance().latestPopupWindow
            saveOver(act.window.decorView, pop.contentView, (1920 - 880) / 2, 150, 880, "final/edit_bottom_menu_${if (mode == 1) "dark" else "light"}.png")
            pop.dismiss()
        }
    }

    /** Pinch mode v2: every widget of each place, the media alone in its place, the options of the widgets ticked. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun pinchV2() {
        File("build/screenshots/pinch_v2").mkdirs()
        val shots = listOf(
            Triple(1, HL.FULL, null), Triple(2, HL.FULL, null),
            // The climate swiped with the map in the middle place, the media alone on the right
            Triple(2, HL.FULL, "tl=web,trips;tc=map,climate,consumption;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone"),
            Triple(1, HL.MAP_CENTER, null),
        )
        // The last trips in the tall place: more trips listed
        seedTrips()
        for (mode in listOf(1, 2)) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putString("slot_now_map_center_c", "trips").commit()
            val act = layoutHome(HL.MAP_CENTER, "tl=hourly,daily;bl=climate;c=trips,map;tr=media;br=energy,trip,power,phone", mode)
            seedTrips()
            (MainActivity::class.java.getDeclaredField("trips").apply { isAccessible = true }.get(act) as app.mg4dash.ui.TripsCard).show()
            save(act.window.decorView, "pinch_v2/trips_tall_${if (mode == 1) "dark" else "light"}.png")
        }
        shots.forEachIndexed { i, (mode, layout, slots) ->
            val act = layoutHome(layout, slots, mode)
            act.editCards()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(act.window.decorView, "pinch_v2/${i + 1}_${layout.key}_${if (mode == 1) "dark" else "light"}.png")
        }
    }

    /** The accent chosen in Settings › Look on the edit mode, the Driving card and the layout menu: blue (0) and orange (4). */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun accentFinal() {
        File("build/screenshots/accent").mkdirs()
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        for (accent in listOf(0, 4)) for (mode in listOf(1, 2)) {
            sp.edit().putInt("theme_accent", accent).putString("slot_now_full_tc", "driving").commit()
            val tag = "${if (accent == 0) "blue" else "orange"}_${if (mode == 1) "dark" else "light"}"
            val act = layoutHome(HL.FULL, "tl=climate;tc=driving,map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone", mode, drive = 7)
            save(act.window.decorView, "accent/driving_$tag.png")
            act.editCards()
            repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(act.window.decorView, "accent/edit_$tag.png")
            app.mg4dash.ui.LayoutPicker.show(act, act.window.decorView, HL.FULL) {}
            val pop = org.robolectric.shadows.ShadowApplication.getInstance().latestPopupWindow
            saveOver(act.window.decorView, pop.contentView, (1920 - 880) / 2, 150, 880, "accent/menu_$tag.png")
            pop.dismiss()
        }
        sp.edit().remove("theme_accent").commit()
    }

    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun layoutSettingsFinal() {
        File("build/screenshots/final").mkdirs()
        for (mode in listOf(1, 2)) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
                .putInt("theme_mode", mode).putInt("home_style", 0).putString("home_layout", "map_center").remove("settings_tab").commit()
            val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
            save(act.window.decorView, "final/settings_${if (mode == 1) "dark" else "light"}.png")
            val anchor = act.window.decorView
            app.mg4dash.ui.LayoutPicker.show(act, anchor, app.mg4dash.ui.HomeSlots.Layout.MAP_CENTER) {}
            val pop = org.robolectric.shadows.ShadowApplication.getInstance().latestPopupWindow
            saveOver(act.window.decorView, pop.contentView, 470, 238, 880, "final/settings_menu_${if (mode == 1) "dark" else "light"}.png")
            pop.dismiss()
        }
    }

    private fun save(root: View, name: String) {
        val cfg = root.resources.configuration
        val w = cfg.screenWidthDp.takeIf { it in 1000..1920 } ?: 1920; val h = cfg.screenHeightDp.takeIf { it in 400..720 } ?: 720
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp)); roundClipped(root, bmp)
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
