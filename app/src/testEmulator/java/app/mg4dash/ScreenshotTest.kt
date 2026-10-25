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

/**
 * Rendu des écrans en plein écran sur la dalle de la MG4 (1920 × 720 dp, 160 dpi),
 * avec les données simulées. Les PNG sont écrits dans build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class ScreenshotTest {

    /** Barre verticale à gauche, comme sur une nouvelle installation ; un test la remet en bas. */
    @org.junit.Before fun verticalBarByDefault() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", true).commit()
    }

    @Test fun homeBottomBar() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("vertical_dock", false).commit()
        shoot(MainActivity::class.java, "home_bottom_bar.png")
    }

    /** Ecran plus bas (barres SAIC visibles, #43) : barre en bas. */
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

    /** Simulation du bouton antibrouillards de MG (56 dp à x = 20 dp, #38) posé sur la barre. */
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

    /** Page Son (maquette) : répartition et égaliseur. */
    @Test fun sound() = shoot(app.mg4dash.pages.SoundActivity::class.java, "sound.png")
    @Test fun soundLight() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
            .edit().putInt("theme_mode", 2).commit()
        shoot(app.mg4dash.pages.SoundActivity::class.java, "sound_light.png")
    }
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun soundFr() = shoot(app.mg4dash.pages.SoundActivity::class.java, "sound_fr.png")
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun vehicleFr() = shoot(app.mg4dash.pages.VehicleActivity::class.java, "vehicle_fr.png")

    /** Animation de démarrage : images brutes (640 × 240, 20 i/s) pour en faire un GIF, et une image fixe. */
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

    /** Réglages en onglets (maquette) : un rendu par onglet, en français. */
    @Test @Config(qualifiers = "fr-w1920dp-h720dp-land-mdpi") fun settingsTabs() {
        for (t in 0..4) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("settings_tab", t).commit()
            shoot(SettingsActivity::class.java, "settings_tab$t.png")
        }
    }

    /** Onglets des réglages en anglais, pour le README. */
    @Test fun settingsTabsEn() {
        for (t in 0..4) {
            org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("settings_tab", t).commit()
            shoot(SettingsActivity::class.java, "settings_en_tab$t.png")
        }
    }

    /** Popup de mise à jour : toujours là après la reconstruction de l'accueil (passage clair / sombre). */
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

    /** Radio FM sans image : rien que le texte (RDS plus grand) et les boutons, sombre et clair. */
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

    /** Fenêtres de dialogue au style MG4Dash (calibration de la température), sombre et clair. */
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

    /** Capture README : une appli tierce dans la carte (dessin de démonstration, style Carte). */
    @Test fun homeMapAppPlanner() = mapApp({ FakePlannerView(it) }, "home_map_app_planner.png")
    @Test fun homeMapAppGuidance() = mapApp({ FakeGuidanceView(it) }, "home_map_app_guidance.png")
    private fun mapApp(make: (android.content.Context) -> View, name: String) {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putInt("stack_page", 0).putBoolean("energy_card_power", true).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val card = act.findViewById<android.widget.FrameLayout>(R.id.mapCard)
        val toggle = act.findViewById<View>(R.id.widgetToggle)
        // Comme une vraie appli intégrée : boutons et vitesse de la carte cachés, la carte reste dessous
        listOf(R.id.mapButtons).forEach { act.findViewById<View>(it)?.visibility = View.GONE }
        (act.findViewById<View>(R.id.mapSpeed)?.parent as? View)?.visibility = View.GONE
        card.addView(make(act), card.indexOfChild(toggle), android.widget.FrameLayout.LayoutParams(-1, -1))
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

    /** Carte énergie « puissance » (#29), styles Complet et Carte. */
    @Test fun homeEnergyCardPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putBoolean("energy_card_power", true).commit()
        val f = app.mg4dash.energy.EnergyMonitor::class.java.getDeclaredField("_tripStartSoc").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(app.mg4dash.energy.EnergyMonitor) as kotlinx.coroutines.flow.MutableStateFlow<Int?>).value = 92
        shoot(MainActivity::class.java, "home_energy_card_power.png")
    }

    /** Même carte, câble débranché (en roulant) : prise avec éclair sous les km. */
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

    @Test fun homeMapEnergyCardPower() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putInt("stack_page", 0).putBoolean("energy_card_power", true).commit()
        shoot(MainActivity::class.java, "home_map_energy_card_power.png")
    }

    /** Conduite à droite : barre verticale à droite, pleine hauteur. */
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

    @Test fun homeMapLayoutOffline() {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("home_style", 2).apply()
        // Pas de météo (hors connexion) : on vide la météo simulée
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

    /** Clim maintenue : fenêtre sur l'accueil, puis son écran (lisible de dehors). */
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

    /** Aperçu des icônes proposées pour les boutons de clim (repos / sélectionné). */
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
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "climate_icons_preview.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Icône Véhicule de la barre : normale et en dérive (ESC coupé), en rouge. */
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

    /** Pleine puissance depuis l'accueil : bascule sur le widget Puissance, animation, puis retour. */
    @Test fun boostFlames() = boost(3, "flames")
    @Test fun boostLightning() = boost(2, "lightning")
    @Test fun boostRocket() = boost(1, "rocket")

    private fun boost(style: Int, name: String) {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", 1).putInt("secondary_widget", 0).putInt("power_fx", style).commit()
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

    /** Animation pleine puissance : images successives de l'aperçu des réglages, une planche par style. */
    @Test fun powerFx() {
        val sp = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)
        sp.edit().putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val names = mapOf(1 to "rocket", 2 to "lightning", 3 to "flames", 5 to "pumpkin")
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
                root.draw(Canvas(bmp))
                c.drawBitmap(bmp, 0f, (i * h).toFloat(), null)
            }
            val dir = File("build/screenshots").apply { mkdirs() }
            File(dir, "powerfx_$name.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Maquette issue #29 : barres autonomie et énergie dans le style de la barre de puissance. */
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
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun <T : Activity> shoot(cls: Class<T>, name: String, media: app.mg4dash.media.MediaHub.NowPlaying? = null) {
        // Captures en mode sombre, sauf si le test a choisi un autre mode
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

    // ── Musique d'exemple pour les captures du README : albums et artistes fictifs, pochettes dessinées ──

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

    /** « Neon Skyline » : soleil rayé sur un ciel violet et une grille rose. */
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

    /** « Golden Hours » : soleil couchant sur une mer turquoise, un palmier. */
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

    /** « Northern Lights » : aurore verte sur des montagnes et un ciel étoilé. */
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

    private fun save(root: View, name: String) {
        val cfg = root.resources.configuration
        val w = cfg.screenWidthDp.takeIf { it in 1000..1920 } ?: 1920; val h = cfg.screenHeightDp.takeIf { it in 400..720 } ?: 720
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
