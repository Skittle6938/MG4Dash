package app.mg4dash.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.system.DataUsage
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import app.mg4dash.ui.Fullscreen
import java.util.Locale

/**
 * Launcher settings, opened by a 3 s long press on the time.
 * Contains Valet / Service mode: the SAIC launcher becomes the default home again.
 */
class SettingsActivity : AppCompatActivity() {
    private val twoFingerPull by lazy { app.mg4dash.ui.TwoFingerPull(this) }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean =
        twoFingerPull.onTouch(ev) { super.dispatchTouchEvent(it) } || super.dispatchTouchEvent(ev)


    private lateinit var prefs: Prefs
    private lateinit var homeState: TextView

    private val cities = listOf(
        Triple("Paris", 48.8566f, 2.3522f), Triple("Lyon", 45.764f, 4.8357f),
        Triple("Marseille", 43.2965f, 5.3698f), Triple("Toulouse", 43.6047f, 1.4442f),
        Triple("Bordeaux", 44.8378f, -0.5792f), Triple("Lille", 50.6292f, 3.0573f),
        Triple("Nantes", 47.2184f, -1.5536f), Triple("Strasbourg", 48.5734f, 7.7521f),
        Triple("Nice", 43.7102f, 7.262f), Triple("Bruxelles", 50.8503f, 4.3517f),
        Triple("Genève", 46.2044f, 6.1432f),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        app.mg4dash.ui.Themes.apply(this)
        themeSig = app.mg4dash.ui.Themes.signature(this)
        // A single scrolling area: two columns of categories, under a fixed banner
        // (title, log, close) that leaves room for the fog light button.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val vertical = app.mg4dash.ui.Dock.vertical(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = getString(R.string.st_title, BuildConfig.VERSION_NAME); setTextColor(color(R.color.text_value))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (vertical) dp(12) else dp(app.mg4dash.ui.Kit.FOG_SAFE_DP - 8) })
        // Tabs (setting themes) in the banner, next to the title
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tabBar, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(16) })
        top.addView(button(getString(R.string.st_close)) { finish() }, LinearLayout.LayoutParams(dp(160), dp(52)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val pages = android.widget.FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabViews = mutableListOf<View>(); val tabChips = mutableListOf<TextView>()
        fun showTab(i: Int) {
            tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabChips.forEachIndexed { k, c -> c.isSelected = k == i }
            prefs.raw.edit().putInt("settings_tab", i).apply()
        }
        /** A tab: two columns of panels in a scrolling area. */
        fun tab(label: Int): Pair<LinearLayout, LinearLayout> {
            val i = tabViews.size
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cols.addView(l, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            cols.addView(r, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(cols) }
            pages.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1)); tabViews += sv
            val chip = app.mg4dash.ui.Kit(this).toggleTile(getString(label)) { showTab(i) }.apply { textSize = 17f; setPadding(dp(22), 0, dp(22), 0) }
            tabBar.addView(chip, LinearLayout.LayoutParams(-2, -1).apply { if (i > 0) leftMargin = dp(8) }); tabChips += chip
            return l to r
        }
        val (homeL, homeR) = tab(R.string.st_tab_home)
        val (lookL, lookR) = tab(R.string.st_tab_look)
        val (carL, carR) = tab(R.string.st_tab_car)
        val (netL, netR) = tab(R.string.st_tab_net)
        val (dataL, dataR) = tab(R.string.du_tab)
        val (sysL, sysR) = tab(R.string.st_tab_system)
        // Same dock (or vertical bar) as the other pages
        val dock = LinearLayout(this)
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(12) })
        app.mg4dash.ui.Dock.mount(this, root, dock, app.mg4dash.ui.Dock.Page.SETTINGS)
        setContentView(root)
        app.mg4dash.ui.FadeOutline.applyTo(root)

        // ════ Home tab: layout and map ════
        val style = panel(homeL, getString(R.string.st_style))
        text(style, getString(R.string.st_style_desc), 16f, R.color.text_title)
        // Simple, or one of the eight layouts with its drawing (#38); the cards of each place in the edit mode
        lateinit var paintStyle: () -> Unit
        val (simpleBtn) = choiceButtons(listOf(getString(R.string.st_style_simple)), { if (prefs.homeStyle == 1) 0 else -1 }) { prefs.homeStyle = 1; paintStyle() }
        val (layoutBtn) = choiceButtons(listOf(getString(R.string.ly_title) + "   ▾"), { if (prefs.homeStyle != 1) 0 else -1 }) {}
        layoutBtn.setOnClickListener { v ->
            app.mg4dash.ui.LayoutPicker.show(this, v, if (prefs.homeStyle == 1) null else app.mg4dash.ui.HomeSlots.layoutOf(prefs)) {
                app.mg4dash.ui.HomeSlots.choose(prefs, it); paintStyle()
            }
        }
        row(style, simpleBtn, layoutBtn)
        val editBtn = button(getString(R.string.ly_edit)) {
            startActivity(android.content.Intent(this, app.mg4dash.MainActivity::class.java)
                .putExtra(app.mg4dash.ui.CardsEditor.EXTRA_EDIT, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        row(style, editBtn)
        paintStyle = {
            simpleBtn.isSelected = prefs.homeStyle == 1
            layoutBtn.isSelected = prefs.homeStyle != 1
            layoutBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(app.mg4dash.ui.LayoutPicker.glyph(this, app.mg4dash.ui.HomeSlots.layoutOf(prefs)), null, null, null)
            layoutBtn.setPadding(dp(22), 0, dp(20), 0)
            (editBtn.parent as View).visibility = if (prefs.homeStyle == 1) View.GONE else View.VISIBLE
        }
        paintStyle()
        // Menu bar: at the bottom, or vertical on the driver's side (read from the car), left or right (#75)
        dropdown(style, getString(R.string.st_bar), listOf(R.string.st_bar_bottom, R.string.st_bar_auto, R.string.st_bar_left, R.string.st_bar_right).map { getString(it) },
            { if (!prefs.verticalDock) 0 else 1 + prefs.barSide.coerceIn(0, 2) }) {
            prefs.verticalDock = it > 0
            if (it > 0) prefs.barSide = it - 1
            app.mg4dash.ui.Themes.publish(this); recreate()
        }
        toggle(style, R.string.st_wide_bar, R.string.st_wide_bar_desc, { prefs.wideBar }) { prefs.wideBar = it; recreate() }
        toggle(style, R.string.st_fullscreen, null, { prefs.fullscreen }) { prefs.fullscreen = it; Fullscreen.apply(this) }
        toggle(style, R.string.st_own_keyboard, R.string.st_own_keyboard_desc, { prefs.ownKeyboard }) { prefs.ownKeyboard = it }
        toggle(style, R.string.st_energy_card_power, R.string.st_energy_card_power_desc, { prefs.energyCardPower }) { prefs.energyCardPower = it }
        toggle(style, R.string.st_power_va, R.string.st_power_va_desc, { prefs.powerVa }) { prefs.powerVa = it }
        toggle(style, R.string.st_stack_auto, null, { prefs.stackAuto }) { prefs.stackAuto = it }
        toggle(style, R.string.st_conn_icons, null, { prefs.connectionIcons }) { prefs.connectionIcons = it }
        // Audio source: drop-down menu (same style as the card menus)
        val src = panel(homeR, getString(R.string.st_audio_source).uppercase())
        val srcLabels = listOf(getString(R.string.st_src_auto), getString(R.string.st_src_car), "Radio", "Bluetooth", "USB", "CarPlay / Android Auto")
        dropdown(src, getString(R.string.st_start_source), srcLabels, { prefs.startSource }) { prefs.startSource = it }
        text(src, getString(R.string.st_start_source_desc), 16f, R.color.text_title)
        // Only for those with Radio Dash: DAB and FM as two home sources
        if (app.mg4dash.system.RadioDashUpdater.installed(this))
            toggle(src, R.string.st_radio_bands, R.string.st_radio_bands_desc, { prefs.radioDashBands }) { prefs.radioDashBands = it }
        // AM band in Radio Dash (Radio Dash #2): off by default; only on firmwares whose radio can have it
        // (SWI69 and its family have no AM)
        if (app.mg4dash.system.RadioDashUpdater.installed(this) && app.mg4dash.system.Firmware.family != app.mg4dash.system.Firmware.Family.A9)
            toggle(src, R.string.st_radio_am, R.string.st_radio_am_desc, { prefs.radioDashAm }) { prefs.radioDashAm = it; app.mg4dash.ui.Themes.publish(this) }
        // Play at startup (replaces Radio Dash's automatic resume)
        dropdown(src, getString(R.string.st_start_playback),
            listOf(getString(R.string.st_play_resume), getString(R.string.st_play_pause), getString(R.string.st_play_play)), { prefs.startPlayback }) { prefs.startPlayback = it }
        // Bar's Android Auto button: the app it opens (long press cuts its connection)
        val aaLabels = listOf(getString(R.string.dock_aa_stock), getString(R.string.dock_cp_stock), getString(R.string.dock_other_app))
        fun aaIndex(): Int = when (val t = prefs.dockAaTarget) { null -> 0; app.mg4dash.system.Apps.CARPLAY.flattenToString() -> 1; else -> 2 }
        dropdown(src, getString(R.string.st_aa_button), aaLabels, { aaIndex() }) { i ->
            fun apply(target: String?) {
                // App change: first restore the old one's cut connection
                app.mg4dash.system.ConnectionToggle.restore(this)
                prefs.dockAaTarget = target
                recreate()   // the bar (Android Auto button) takes the new app
            }
            when (i) {
                0 -> apply(null)
                1 -> apply(app.mg4dash.system.Apps.CARPLAY.flattenToString())
                else -> app.mg4dash.shortcuts.Shortcuts.pickApp(this, getString(R.string.dock_aa_pick)) { cn -> if (cn != null) apply(cn.flattenToString()) }
            }
        }
        text(src, getString(R.string.st_aa_button_desc), 16f, R.color.text_title)
        val map = panel(homeR, getString(R.string.st_style_map).uppercase())
        dropdown(map, getString(R.string.st_map_zoom), listOf("14", "15", "16", "17", "18"), { prefs.mapZoom - 14 }) { prefs.mapZoom = 14 + it }
        dropdown(map, getString(R.string.st_map_theme), listOf(R.string.st_mode_auto, R.string.st_mode_light, R.string.st_mode_dark).map { getString(it) }, { prefs.mapTheme }) { prefs.mapTheme = it }
        toggle(map, R.string.st_map_offline_switch, R.string.st_map_offline_switch_desc, { prefs.mapOfflineSwitch }) { prefs.mapOfflineSwitch = it }

        // ════ Vehicle tab: units, equipment, climate, and startup sound ════
        val units = panel(carL, getString(R.string.st_tab_car).uppercase())
        dropdown(units, getString(R.string.st_units), listOf(getString(R.string.st_units_car), "km · kWh/100 km", "miles · mi/kWh"), { prefs.units }) { prefs.units = it }
        dropdown(units, getString(R.string.st_pressure), listOf(getString(R.string.st_mode_auto_short), "bar", "psi", "kPa"), { prefs.pressureUnit }) { prefs.pressureUnit = it }
        dropdown(units, getString(R.string.st_heated), listOf(getString(R.string.st_mode_auto_short), getString(R.string.yes), getString(R.string.no)), { prefs.heatedSeats }) { prefs.heatedSeats = it }
        toggle(units, R.string.st_climhold, R.string.st_climhold_desc, { prefs.climateHoldEnabled }) { prefs.climateHoldEnabled = it }
        if (app.mg4dash.system.Firmware.family == app.mg4dash.system.Firmware.Family.LEGACY)
            toggle(units, R.string.st_hide_saic_climate, R.string.st_hide_saic_climate_desc, { prefs.hideSaicClimate }) { prefs.hideSaicClimate = it }

        soundTriggersSetting(panel(carL, getString(R.string.st_sound_trigger).uppercase()))

        // ════ Appearance tab: theme and effects ════
        val theme = panel(lookL, getString(R.string.st_theme))
        dropdown(theme, getString(R.string.st_theme_mode), listOf(R.string.st_mode_auto, R.string.st_mode_dark, R.string.st_mode_light).map { getString(it) }, { prefs.themeMode }) {
            if (prefs.themeMode != it) { prefs.themeMode = it; onThemeChanged() }
        }
        toggle(theme, R.string.st_theme_smooth, R.string.st_theme_smooth_desc, { prefs.themeSmooth }) { prefs.themeSmooth = it }
        toggle(theme, R.string.st_night_sun, R.string.st_night_sun_desc, { prefs.nightBySun }) { prefs.nightBySun = it; app.mg4dash.ui.Themes.publish(this) }
        // Today's times at the car's position, for information
        text(theme, app.mg4dash.system.SunTimes.position(this)?.let { (lat, lon) ->
            app.mg4dash.system.SunTimes.of(lat, lon)?.let { (rise, set) ->
                val f = android.text.format.DateFormat.getTimeFormat(this)
                getString(R.string.st_night_sun_today, f.format(java.util.Date(set)), f.format(java.util.Date(rise)))
            }
        } ?: getString(R.string.st_night_sun_nopos), 15f, R.color.text_sub)
        dropdown(theme, getString(R.string.st_clock), listOf(R.string.st_clock_24, R.string.st_clock_12).map { getString(it) }, { if (prefs.clock12) 1 else 0 }) { prefs.clock12 = it == 1 }
        dropdown(theme, getString(R.string.st_panel_outline), listOf(R.string.st_panel_fade, R.string.st_panel_full).map { getString(it) }, { if (prefs.panelFade) 0 else 1 }) {
            if (prefs.panelFade != (it == 0)) { prefs.panelFade = it == 0; recreate() }
        }
        // Background and button colour side by side
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bgCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val accentCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        colors.addView(bgCol, LinearLayout.LayoutParams(0, -2, 1f))
        colors.addView(accentCol, LinearLayout.LayoutParams(0, -2, 1f))
        theme.addView(colors, LinearLayout.LayoutParams(-1, -2))
        text(bgCol, getString(R.string.st_theme_bg), 18f, R.color.text_value)
        swatches(bgCol, app.mg4dash.ui.Themes.BACKGROUND_SWATCHES, prefs.themeBackground) { prefs.themeBackground = it; onThemeChanged() }
        text(accentCol, getString(R.string.st_theme_accent), 18f, R.color.text_value)
        swatches(accentCol, app.mg4dash.ui.Themes.ACCENT_SWATCHES, prefs.themeAccent) { prefs.themeAccent = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_desc), 16f, R.color.text_title)

        // ── Energy ──
        val energy = panel(carR, getString(R.string.title_energy))
        // One line: new capacity (unofficial reference, editable), current (measured, wear included) and health
        val cap = text(energy, "", 18f, R.color.text_value)
        val capSrc = text(energy, "", 15f, R.color.text_title)
        fun showCap() {
            val f = { v: Float -> String.format(Locale.getDefault(), "%.1f", v) }
            cap.text = getString(R.string.st_battery_line, f(app.mg4dash.energy.BatteryCapacity.nominal(this)), f(prefs.batteryKwh),
                app.mg4dash.energy.BatteryCapacity.health(this)?.let { String.format(Locale.getDefault(), "%.0f %%", it.percent) } ?: "—")
            val car = app.mg4dash.energy.BatteryCapacity.fromCar()
            val est = app.mg4dash.energy.BatteryCapacity.estimate(this)
            capSrc.text = when {
                car != null -> getString(R.string.st_cap_car, String.format(Locale.getDefault(), "%.1f", car))
                est != null -> getString(R.string.st_cap_est, String.format(Locale.getDefault(), "%.1f", est.first), est.second)
                else -> app.mg4dash.energy.BatteryCapacity.progress(this).provisional
                    ?.let { getString(R.string.st_cap_est_current, String.format(Locale.getDefault(), "%.1f", it)) }
                    ?: getString(R.string.st_cap_none)
            }
        }
        app.mg4dash.energy.BatteryCapacity.apply(this)
        showCap()
        // Battery model: reference for the estimated health (shown on the Vehicle page)
        val models = app.mg4dash.energy.BatteryCapacity.MODELS.map { it.first }
        lateinit var newCapDrop: TextView
        fun newCapLabel(): String {
            val f = { v: Float -> String.format(Locale.getDefault(), "%.1f", v) }
            return if (prefs.batteryNewKwh > 0f) getString(R.string.st_new_cap_custom, f(prefs.batteryNewKwh))
            else getString(R.string.st_new_cap_default, f(app.mg4dash.energy.BatteryCapacity.modelNominal(this)))
        }
        fun paintNewCap() { newCapDrop.text = newCapLabel() + "   ▾" }
        dropdown(energy, getString(R.string.st_battery_model), models.map { "$it kWh" }, { models.indexOf(prefs.batteryModel).coerceAtLeast(0) }) { prefs.batteryModel = models[it]; showCap(); paintNewCap() }
        // New reference capacity for health (#77): no official value, and the car does not give it
        newCapDrop = dropdown(energy, getString(R.string.st_new_cap), listOf(getString(R.string.st_new_cap_item_default), getString(R.string.st_new_cap_item_custom)),
            { if (prefs.batteryNewKwh > 0f) 1 else 0 }) { choice ->
            if (choice == 0) { prefs.batteryNewKwh = 0f; showCap() }
            else ask(getString(R.string.st_new_cap_title), if (prefs.batteryNewKwh > 0f) prefs.batteryNewKwh.toString() else "", false, number = true) { typed ->
                typed.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in 30f..120f }?.let { prefs.batteryNewKwh = Math.round(it * 10f) / 10f }
                showCap(); paintNewCap()
            }
            newCapDrop.post { paintNewCap() }
        }
        paintNewCap()
        toggle(energy, R.string.st_xpower, R.string.st_xpower_desc, { prefs.xpower }) { prefs.xpower = it }
        // V2L on plugging in (#82): warning on enabling, the discharge will start with no other action
        var repaintV2l: () -> Unit = {}
        repaintV2l = toggle(energy, R.string.st_v2l_auto, R.string.st_v2l_auto_desc, { prefs.v2lAuto }) { on ->
            if (!on) prefs.v2lAuto = false
            else AlertDialog.Builder(this)
                .setTitle(R.string.st_v2l_auto)
                .setMessage(R.string.st_v2l_auto_warning)
                .setPositiveButton(R.string.st_v2l_auto_enable) { _, _ -> prefs.v2lAuto = true; repaintV2l() }
                .setNegativeButton(R.string.cancel, null)
                .show().also { app.mg4dash.ui.Dialogs.fix(it) }
        }
        // Price of a kWh (#102): the cost of each trip and reading in the Trips page; empty: none
        lateinit var priceBtn: Button
        fun priceLabel() = getString(R.string.st_kwh_price, if (prefs.kwhPrice > 0f) app.mg4dash.ui.TripText.money(prefs.kwhPrice) else getString(R.string.st_kwh_price_none))
        priceBtn = button(priceLabel()) {
            ask(getString(R.string.st_kwh_price_title), if (prefs.kwhPrice > 0f) prefs.kwhPrice.toString() else "", secret = false, number = true) { v ->
                prefs.kwhPrice = v.replace(',', '.').trim().toFloatOrNull()?.takeIf { it > 0f && it < 100f } ?: 0f
                priceBtn.text = priceLabel()
            }
        }
        row(energy, priceBtn)
        text(energy, getString(R.string.st_kwh_price_desc), 15f, R.color.text_title)
        toggle(energy, R.string.st_cc_stopped, R.string.st_cc_stopped_desc, { prefs.raw.getBoolean("cc_stopped", true) }) { prefs.raw.edit().putBoolean("cc_stopped", it).apply() }
        // Speed × temperature consumption map (in testing, off by default)
        powerFxSetting(panel(lookR, getString(R.string.sw_power)))

        // ════ Right column: system, connections, diagnostic ════

        // ── Car home screen and startup ──
        val valet = panel(sysL, getString(R.string.st_home))
        homeState = text(valet, "", 19f, R.color.text_sub)
        text(valet, getString(R.string.st_home_desc), 16f, R.color.text_title)
        row(valet,
            button(getString(R.string.st_replace)) { confirmReplace() },
            button(getString(R.string.st_valet)) { confirmValet() },
        )
        text(valet, getString(R.string.st_rescue_note), 14f, R.color.text_title)

        // ── Update from GitHub ──
        val upd = panel(sysR, getString(R.string.st_update))
        val appNew = prefs.raw.getString("upd_new_app", null)?.takeIf { app.mg4dash.system.Updater.isNewer(it) }
        val checked = prefs.raw.getLong("upd_checked_at", 0L) > 0L
        val (updState, updButton) = updateLine(upd, "MG4Dash", when {
            appNew != null -> updateAvailable(BuildConfig.VERSION_NAME, appNew)
            checked -> getString(R.string.st_upd_current, BuildConfig.VERSION_NAME)
            else -> BuildConfig.VERSION_NAME
        }, appNew)
        // Progress of the download and the installation, under the line (the task goes on out of this screen)
        val updBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.text_value))
        }
        upd.addView(updBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
        val U = app.mg4dash.system.Updater
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                U.state.collect { s ->
                    U.stateText(this@SettingsActivity, s)?.let { updState.text = it }
                    updBar.visibility = if (s.busy) View.VISIBLE else View.GONE
                    updBar.isIndeterminate = s !is app.mg4dash.system.Updater.State.Downloading
                    if (s is app.mg4dash.system.Updater.State.Downloading) updBar.progress = s.pct
                    // Greyed while it runs; Try again after a failure
                    updButton.isEnabled = !s.busy
                    if (s is app.mg4dash.system.Updater.State.Failed) updButton.text = getString(R.string.upd_try_again)
                }
            }
        }
        fun install(r: app.mg4dash.system.Updater.Release) {
            if (!U.start(this, r)) updState.text = getString(R.string.st_update_busy)
        }
        updButton.setOnClickListener {
            // After a failure: the same release again, at once
            (U.state.value as? app.mg4dash.system.Updater.State.Failed)?.let { install(it.release); return@setOnClickListener }
            if (app.mg4dash.system.DataUsage.updatesBlocked(this)) { updState.text = getString(R.string.du_updates_wait); return@setOnClickListener }
            updState.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                var failed = false
                val r = withContext(Dispatchers.IO) { try { app.mg4dash.system.Updater.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("Update", "${e.message}"); failed = true; null } }
                val newer = r?.takeIf { app.mg4dash.system.Updater.isNewer(it.version) }
                if (!failed) app.mg4dash.ui.UpdatePrompt.remember(this@SettingsActivity, app = r?.version)
                if (newer != null) {
                    updButton.text = getString(R.string.st_upd_install_ver, newer.version)
                }
                updState.text = when {
                    failed -> getString(R.string.st_update_error)
                    newer != null -> updateAvailable(BuildConfig.VERSION_NAME, r.version)
                    else -> getString(R.string.st_upd_current, BuildConfig.VERSION_NAME)
                }
                // New version: release notes and Install / Cancel choice
                if (newer != null && !isFinishing) {
                    val notes = ScrollView(this@SettingsActivity).apply {
                        setPadding(dp(24), dp(8), dp(24), 0)
                        addView(LinearLayout(this@SettingsActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            // Download size and advised connection, in orange and before the notes
                            addView(TextView(this@SettingsActivity).apply {
                                text = app.mg4dash.system.Updater.dataWarning(this@SettingsActivity, newer); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                                setTextColor(color(R.color.accent_heat)); setPadding(0, 0, 0, dp(10))
                            })
                            addView(TextView(this@SettingsActivity).apply {
                                text = app.mg4dash.system.Updater.plainNotes(newer.notes); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                                setTextColor(color(R.color.text_value)); setLineSpacing(0f, 1.15f)
                            })
                        })
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.st_update_available, BuildConfig.VERSION_NAME, newer.version))
                        .setView(notes)
                        .setPositiveButton(R.string.st_update_install) { _, _ -> install(newer) }
                        .setNegativeButton(R.string.cancel, null)
                        .show().also { app.mg4dash.ui.Dialogs.fix(it) }
                }
            }
        }
        radioDashUpdate(upd)
        airlinkUpdate(upd)
        val engineShown = webEngine(upd)
        text(upd, getString(R.string.st_upd_note, app.mg4dash.system.RadioDashUpdater.TESTED_ON) +
            (if (engineShown) " " + getString(R.string.st_web_engine_note) else ""), 15f, R.color.text_title)
        toggle(upd, R.string.st_update_auto, R.string.st_update_auto_desc, { prefs.updateAuto }) { prefs.updateAuto = it }
        toggle(upd, R.string.st_update_beta, null, { prefs.updateBeta }) { prefs.updateBeta = it }

        // ── Weather ──
        val weather = panel(netL, getString(R.string.title_weather))
        val city = text(weather, "", 18f, R.color.text_value)
        fun showCity() { city.text = getString(R.string.st_city, prefs.weatherCity) }
        showCity()
        row(weather, button(getString(R.string.st_change_city)) {
            val idx = (cities.indexOfFirst { it.first == prefs.weatherCity } + 1) % cities.size
            val c = cities[idx]
            prefs.weatherCity = c.first; prefs.weatherLat = c.second; prefs.weatherLon = c.third
            showCity(); WeatherRepo.refreshNow(this)
        }, button(getString(R.string.st_use_location)) {
            // Fallback city = current position (#7); city name already found by the weather if it exists
            val loc = app.mg4dash.map.LocationRepo.location.value
            if (loc == null) {
                app.mg4dash.ui.Toasts.show(this, R.string.st_no_location, long = true)
            } else {
                prefs.weatherLat = loc.latitude.toFloat(); prefs.weatherLon = loc.longitude.toFloat()
                prefs.weatherCity = WeatherRepo.weather.value?.place?.takeIf { it.isNotBlank() } ?: getString(R.string.weather_here)
                showCity(); WeatherRepo.refreshNow(this)
            }
        })
        // Weather model (#67): another model can better match observed weather depending on the region
        val wxModels = listOf("" to getString(R.string.st_wx_model_auto), "ecmwf_ifs025" to "ECMWF", "icon_seamless" to "DWD ICON",
            "meteofrance_seamless" to "Météo-France", "ukmo_seamless" to "UK Met Office", "gfs_seamless" to "NOAA GFS", "gem_seamless" to "GEM (Canada)")
        lateinit var modelBtn: Button
        fun modelLabel() = getString(R.string.st_wx_model, wxModels.firstOrNull { it.first == prefs.weatherModel }?.second ?: wxModels[0].second)
        modelBtn = button(modelLabel()) {
            app.mg4dash.ui.SourceMenu.choose(this, modelBtn, wxModels.map { it.second }, wxModels.indexOfFirst { it.first == prefs.weatherModel }.coerceAtLeast(0)) { i ->
                prefs.weatherModel = wxModels[i].first
                modelBtn.text = modelLabel(); WeatherRepo.refreshNow(this)
            }
        }
        row(weather, modelBtn)

        // ── Home Assistant ──
        val ha = panel(netR, "HOME ASSISTANT")
        val haState = text(ha, "", 18f, R.color.text_value)
        fun showHa() {
            haState.text = getString(R.string.st_ha_url, prefs.haUrl.ifBlank { "—" }) + "\n" +
                getString(if (prefs.haToken.isBlank()) R.string.st_ha_no_token else R.string.st_ha_token_set)
        }
        showHa()
        text(ha, getString(R.string.st_ha_desc), 16f, R.color.text_title)
        row(ha,
            button(getString(R.string.st_ha_set_url)) {
                ask(getString(R.string.st_ha_set_url), prefs.haUrl.ifBlank { "http://homeassistant.local:8123" }, secret = false) { prefs.haUrl = it; showHa() }
            },
            button(getString(R.string.st_ha_set_token)) {
                ask(getString(R.string.st_ha_set_token), "", secret = true) { prefs.haToken = it; showHa() }
            },
            button(getString(R.string.st_ha_test)) {
                app.mg4dash.shortcuts.Shortcuts.testHa(this) { ok ->
                    app.mg4dash.ui.Toasts.show(this, if (ok) R.string.st_ha_ok else R.string.ha_error, long = true)
                }
            },
        )
        // #63: keyboard without "paste" (SWI68): address and token read from a text file on the USB stick
        row(ha, button(getString(R.string.st_ha_import)) { importHa { showHa() } })
        text(ha, getString(R.string.st_ha_import_desc), 14f, R.color.text_title)

        // ── Diagnostic: compatibility, log, recorder ──
        val diag = panel(sysL, getString(R.string.st_diag))
        text(diag, getString(R.string.st_compat_fw, app.mg4dash.system.Firmware.version, app.mg4dash.system.Firmware.gen.name), 18f, R.color.text_value)
            .apply { setOnLongClickListener { sprChrgTest(); true } }   // hidden test, reserved for trials
        text(diag, getString(if (app.mg4dash.system.Firmware.tested) R.string.st_compat_tested else R.string.st_compat_beta), 16f, R.color.text_title)
        row(diag,
            button(getString(R.string.st_compat_run)) { Thread { app.mg4dash.car.CarProps.dumpAll() }.start(); Compat.show(this) },
            button(getString(R.string.st_log)) { showLogDialog() },
        )
        row(diag, button(getString(R.string.tu_show)) {
            startActivity(android.content.Intent(this, app.mg4dash.MainActivity::class.java)
                .putExtra(app.mg4dash.ui.Tutorial.EXTRA_SHOW, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })
        toggle(diag, R.string.st_energy_log, R.string.st_energy_log_desc, { prefs.energyLog }) { prefs.energyLog = it }
        row(diag, button(getString(R.string.st_copy_sysui)) { copySystemUi() })
        text(diag, getString(R.string.st_copy_sysui_desc), 14f, R.color.text_title)

        // ════ 4G data tab ════
        dataTab(dataL, dataR)

        refreshHome()
        // The 4G data tab comes before System (1.7.3): a saved System tab stays System
        if (!prefs.raw.contains("settings_tabs_v2")) {
            val t = prefs.raw.getInt("settings_tab", 0)
            prefs.raw.edit().putBoolean("settings_tabs_v2", true).putInt("settings_tab", if (t >= TAB_DATA) t + 1 else t).apply()
        }
        val asked = intent.getIntExtra(EXTRA_TAB, -1)
        showTab((if (asked >= 0) asked else prefs.raw.getInt("settings_tab", 0)).coerceIn(0, tabViews.size - 1))
    }

    companion object {
        /** Tab to open (index), e.g. [TAB_DATA] from the 4G data alert. */
        const val EXTRA_TAB = "tab"
        const val TAB_DATA = 4
    }

    // ── 4G data: total over 30 days, by use, day by day, alert ──
    private var dataListener: ((DataUsage.Snapshot) -> Unit)? = null

    private fun dataTab(left: LinearLayout, right: LinearLayout) {
        val total = panel(left, getString(R.string.du_panel_total))
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        val amount = TextView(this).apply {
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); includeFontPadding = false
        }
        big.addView(amount)
        big.addView(TextView(this).apply {
            text = getString(R.string.du_used_of, DataUsage.formatRound(this@SettingsActivity, DataUsage.MG_LIMIT))
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(dp(12), 0, 0, dp(3))
        })
        total.addView(big, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val quota = app.mg4dash.ui.QuotaBar(this)
        total.addView(quota, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // Detail: on the car's 4G, the Wi-Fi client, or both (the quota above stays 4G only)
        var g = DataUsage.show4g(this); var w = DataUsage.showWifi(this)
        if (!g && !w) g = true
        val netRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        netRow.addView(TextView(this).apply {
            text = getString(R.string.du_detail); setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(16) })
        val kit = app.mg4dash.ui.Kit(this)
        lateinit var chip4g: TextView
        lateinit var chipWifi: TextView
        var rerender: () -> Unit = {}
        fun pick(new4g: Boolean, newWifi: Boolean) {
            if (!new4g && !newWifi) return   // always one network shown
            g = new4g; w = newWifi
            DataUsage.setShown(this, g, w)
            chip4g.isSelected = g; chipWifi.isSelected = w
            rerender()
        }
        chip4g = kit.toggleTile(getString(R.string.du_net_4g)) { pick(!g, w) }.apply { setPadding(dp(22), 0, dp(22), 0); isSelected = g }
        chipWifi = kit.toggleTile(getString(R.string.du_net_wifi)) { pick(g, !w) }.apply { setPadding(dp(22), 0, dp(22), 0); isSelected = w }
        netRow.addView(chip4g, LinearLayout.LayoutParams(-2, dp(48)))
        netRow.addView(chipWifi, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(8) })
        netRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val detailTotal = TextView(this).apply {
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        netRow.addView(detailTotal)
        total.addView(netRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val shares = DataUsage.Use.values().map { u ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(this).apply {
                text = getString(u.label); setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); maxLines = 1
            }, LinearLayout.LayoutParams(dp(180), -2))
            val useColor = color(u.colorRes)
            val bar = app.mg4dash.ui.ShareBar(this).also { it.color = useColor }
            line.addView(bar, LinearLayout.LayoutParams(0, dp(24), 1f))
            val value = TextView(this).apply {
                setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); gravity = Gravity.END; maxLines = 1
            }
            line.addView(value, LinearLayout.LayoutParams(dp(110), -2))
            total.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            Triple(u, bar, value)
        }
        val note = text(total, "", 15f, R.color.text_title).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) }
        val unavailable = text(total, getString(R.string.du_unavailable), 16f, R.color.accent_heat).apply { visibility = View.GONE }

        // Day by day
        val daily = panel(right, getString(R.string.du_daily))
        val dailyDesc = text(daily, "", 15f, R.color.text_title)
        val chart = app.mg4dash.ui.DailyBars(this)
        daily.addView(chart, LinearLayout.LayoutParams(-1, dp(230)).apply { topMargin = dp(8) })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        DataUsage.Use.values().forEach { u ->
            val useColor = color(u.colorRes)
            legend.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(useColor) }
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })
            legend.addView(TextView(this).apply {
                text = getString(u.label); setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(18) })
        }
        daily.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(legend) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Alert
        val alert = panel(right, getString(R.string.du_alert_panel))
        val thresholds = DataUsage.THRESHOLDS
        dropdown(alert, getString(R.string.du_threshold), thresholds.map { DataUsage.formatRound(this, it) },
            { thresholds.indexOf(DataUsage.threshold(this)).let { if (it < 0) thresholds.indexOf(DataUsage.DEFAULT_THRESHOLD) else it } }) {
            DataUsage.setThreshold(this, thresholds[it]); quota.threshold = thresholds[it]
        }
        toggle(alert, R.string.du_popup, null, { DataUsage.popup(this) }) { DataUsage.setPopup(this, it) }
        toggle(alert, R.string.du_wifi_updates, R.string.du_wifi_updates_desc, { DataUsage.updatesWifiOnly(this) }) { DataUsage.setUpdatesWifiOnly(this, it) }
        row(alert, button(getString(R.string.du_reset)) {
            AlertDialog.Builder(this).setMessage(R.string.du_reset_confirm)
                .setPositiveButton(R.string.du_reset) { _, _ -> DataUsage.reset(this) }
                .setNegativeButton(android.R.string.cancel, null).show().also { app.mg4dash.ui.Dialogs.fix(it); app.mg4dash.ui.Dialogs.hideBars(it) }
        })

        val dayFormat = java.text.SimpleDateFormat("EEEE d MMMM", Locale.getDefault())
        val shortFormat = java.text.SimpleDateFormat("d MMM", Locale.getDefault())
        var shown: DataUsage.Snapshot? = null
        chart.onDayTap = { i ->
            shown?.days?.getOrNull(i)?.let { day ->
                chart.selected = i
                val bytes = day.of(g, w)
                val lines = DataUsage.Use.values().filter { bytes[it.ordinal] > 0 }
                    .joinToString("\n") { getString(R.string.du_day_line, getString(it.label), DataUsage.format(this, bytes[it.ordinal])) }
                    .ifEmpty { getString(R.string.du_day_none) }
                AlertDialog.Builder(this)
                    .setTitle(dayFormat.format(DataUsage.calendarOf(day.date).time).replaceFirstChar { it.uppercase() } + " · " + netName(g, w) + " · " + DataUsage.format(this, bytes.sum()))
                    .setMessage(lines)
                    .setPositiveButton(R.string.st_close, null)
                    .setOnDismissListener { chart.selected = -1 }
                    .show().also { app.mg4dash.ui.Dialogs.fix(it); app.mg4dash.ui.Dialogs.hideBars(it) }
            }
        }
        val render: (DataUsage.Snapshot) -> Unit = { s ->
            shown = s
            amount.text = DataUsage.format(this, s.total)
            quota.used = s.total; quota.threshold = DataUsage.threshold(this)
            val max = DataUsage.Use.values().maxOf { s.of(it, g, w) }.coerceAtLeast(1L)
            shares.forEach { (u, bar, value) ->
                val b = s.of(u, g, w)
                bar.fraction = b.toFloat() / max
                value.text = DataUsage.format(this, b)
            }
            val shownTotal = s.total(g, w)
            detailTotal.text = DataUsage.format(this, shownTotal)
            note.text = getString(if (w) R.string.du_note_wifi else R.string.du_note)
            unavailable.visibility = if (s.available) View.GONE else View.VISIBLE
            chart.net4g = g; chart.netWifi = w
            chart.days = s.days
            val biggest = s.days.maxByOrNull { it.sum(g, w) }
            dailyDesc.text = if (biggest == null || biggest.sum(g, w) <= 0) getString(R.string.du_daily_empty) else {
                val bytes = biggest.of(g, w)
                val use = DataUsage.Use.values().maxByOrNull { bytes[it.ordinal] }!!
                getString(R.string.du_daily_desc, DataUsage.format(this, shownTotal / DataUsage.DAYS),
                    getString(R.string.du_biggest, DataUsage.format(this, bytes.sum()), shortFormat.format(DataUsage.calendarOf(biggest.date).time), getString(use.label)))
            }
        }
        rerender = { shown?.let(render) }
        dataListener = render
        DataUsage.listen(render)
        DataUsage.refreshSoon(this)
    }

    /** "4G", "Wi-Fi" or "4G + Wi-Fi". */
    private fun netName(g: Boolean, w: Boolean): String = listOfNotNull(
        getString(R.string.du_net_4g).takeIf { g }, getString(R.string.du_net_wifi).takeIf { w }).joinToString(" + ")

    override fun onDestroy() {
        dataListener?.let { DataUsage.unlisten(it) }
        super.onDestroy()
    }

    /** Auto mode: day / night switch also while the settings are open. */
    private var themeSig = 0
    private val themeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val themeWatch = object : Runnable {
        override fun run() {
            if (app.mg4dash.ui.Themes.signature(this@SettingsActivity) != themeSig && !app.mg4dash.ui.Themes.popupOpen(this@SettingsActivity)) { app.mg4dash.ui.Themes.publish(this@SettingsActivity); app.mg4dash.ui.Themes.recreateSmooth(this@SettingsActivity); return }
            themeHandler.postDelayed(this, 5000)
        }
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
        themeHandler.postDelayed(themeWatch, 5000)
    }

    override fun onPause() {
        super.onPause()
        themeHandler.removeCallbacks(themeWatch)
    }

    /**
     * Hidden test of the "SPR_CHRG" BMS property (unknown role, maybe fast charging):
     * read, write a chosen value, reread 2 s later. Everything is noted in the log.
     */
    /**
     * Hidden tests (long press on the firmware line): charge properties written directly,
     * to validate the fallback routes of old firmwares. Read, write, reread 2 s later.
     */
    private fun sprChrgTest() {
        val P = app.mg4dash.car.CarProps
        val view = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(color(R.color.text_value)); setPadding(dp(24), dp(12), dp(24), dp(8)) }
        val tests = listOf(
            Triple("SPR_CHRG", P.SPR_CHRG, listOf(0, 1)),
            Triple("CHRG_CTRL", 0x2140f412, listOf(1, 2)),
            Triple("ELEC_CLCK_CTRL", 0x2140f413, listOf(1, 2)),
        )
        fun show() {
            lifecycleScope.launch {
                val vals = withContext(Dispatchers.IO) { tests.map { it.first to P.getInt(it.second) } + ("BMS_CHRG_STS" to P.getInt(0x2140f409)) + ("SP_RSN" to P.getInt(P.BMS_CHRG_SP_RSN)) }
                val s = app.mg4dash.car.CarBridge.state.value
                view.text = vals.joinToString("\n") { "${it.first} = ${it.second ?: "illisible"}" } +
                    "\nCharge : ${app.mg4dash.car.VehicleState.chargeStatusLabel(s.chargeStatus)}"
                Log.i("SPR", "read: " + vals.joinToString { "${it.first}=${it.second}" })
            }
        }
        fun write(name: String, id: Int, value: Int) {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { P.setInt(id, value, name) }
                view.text = "$name ← $value : ${if (ok) "envoyé" else "refusé"}, relecture…"
                kotlinx.coroutines.delay(2_000); show()
            }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(view)
        tests.forEach { (name, id, values) ->
            row(col, *values.map { v -> button("$name ← $v") { write(name, id, v) } }.toTypedArray())
        }
        row(col, button("Relire") { show() })
        show()
        AlertDialog.Builder(this).setTitle("Tests (charge)").setView(ScrollView(this).apply { addView(col) })
            .setPositiveButton(R.string.st_close, null).show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    /** Name and state on the left, a single button on the right: Check, or Install when a newer version is known. */
    private fun updateLine(parent: LinearLayout, name: String, state: CharSequence, newVersion: String?): Pair<TextView, Button> {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text(texts, name, 18f, R.color.text_value)
        val stateView = text(texts, "", 17f, R.color.text_title)
        line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val b = button(if (newVersion != null) getString(R.string.st_upd_install_ver, newVersion) else getString(R.string.st_update_check)) {}
        line.addView(b, LinearLayout.LayoutParams(dp(300), dp(56)).apply { leftMargin = dp(16) })
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // An update available (text from [updateAvailable]): the line tinted with the theme's selection colour, a bar
        // on its left and the button as the OUI toggles; back to plain as soon as the state says something else
        val plainBg = b.background; val plainText = b.textColors
        stateView.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val on = (stateView.text as? android.text.Spanned)?.getSpans(0, stateView.text.length, NewMark::class.java)?.isNotEmpty() == true
                markAvailable(line, b, on, plainBg, plainText)
            }
        })
        stateView.text = state
        return stateView to b
    }

    /** Marks an update available text, for [updateLine]. */
    private class NewMark

    /** "Update available - current → new", the arrow larger, marked for [updateLine]'s highlight. */
    private fun updateAvailable(current: String, newer: String): CharSequence {
        val s = android.text.SpannableString(getString(R.string.st_upd_new, current, newer))
        val arrow = s.indexOf('→')
        if (arrow >= 0) s.setSpan(android.text.style.RelativeSizeSpan(1.5f), arrow, arrow + 1, 0)
        s.setSpan(NewMark(), 0, s.length, android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        return s
    }

    private fun markAvailable(line: LinearLayout, b: Button, on: Boolean, plainBg: android.graphics.drawable.Drawable?, plainText: android.content.res.ColorStateList) {
        if (on) {
            val accent = TypedValue().also { theme.resolveAttribute(R.attr.mgSelect, it, true) }.data
            val tint = android.graphics.drawable.GradientDrawable().apply { setColor((accent and 0x00ffffff) or 0x1f000000); cornerRadius = dp(10).toFloat() }
            val bar = android.graphics.drawable.GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() }
            line.background = android.graphics.drawable.LayerDrawable(arrayOf(tint, bar)).apply {
                setLayerSize(1, dp(4), -1); setLayerGravity(1, Gravity.START or Gravity.FILL_VERTICAL)
            }
            line.setPadding(dp(16), dp(8), dp(8), dp(8))
            b.setBackgroundResource(R.drawable.bg_chip); b.isSelected = true
            b.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, R.color.chip_text))
        } else {
            line.background = null; line.setPadding(0, 0, 0, 0)
            b.background = plainBg; b.isSelected = false; b.setTextColor(plainText)
        }
    }

    /**
     * MG4-AA-Airlink (wireless Android Auto, formerly DiAuto MG4+): checked and updated from here when it is
     * installed. One check or update at a time ([app.mg4dash.system.AirlinkUpdater.begin]).
     */
    private fun airlinkUpdate(parent: LinearLayout) {
        val A = app.mg4dash.system.AirlinkUpdater
        var cur = A.installedVersion(this)
        var found: app.mg4dash.system.Updater.Release? = null
        fun stateText() = when {
            cur == null -> getString(R.string.st_aa_none)
            A.running -> getString(R.string.st_aa_busy)
            found != null && app.mg4dash.system.Updater.isNewer(found!!.version, cur!!) -> updateAvailable(cur!!, found!!.version)
            found != null -> getString(R.string.st_upd_current, cur)
            else -> cur!!
        }
        val (state, button) = updateLine(parent, A.NAME, stateText(), null)
        fun showState() { state.text = stateText() }
        if (cur == null) { button.isEnabled = false; button.alpha = 0.4f; return }
        button.setOnClickListener {
            if (app.mg4dash.system.DataUsage.updatesBlocked(this)) { state.text = getString(R.string.du_updates_wait); return@setOnClickListener }
            if (!A.begin()) { state.text = getString(R.string.st_aa_busy); return@setOnClickListener }
            state.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { try { A.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("Update", "${A.NAME}: ${e.message}"); null } }
                if (r == null) { A.end(); state.text = getString(R.string.st_update_error); return@launch }
                found = r
                cur = A.installedVersion(this@SettingsActivity) ?: cur
                if (!app.mg4dash.system.Updater.isNewer(r.version, cur!!)) { A.end(); state.text = getString(R.string.st_upd_current, cur); return@launch }
                showState()
                var started = false
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.st_aa_available, r.version))
                    .setMessage(android.text.SpannableStringBuilder(app.mg4dash.system.Updater.dataWarning(this@SettingsActivity, r) + "\n" + getString(R.string.st_aa_session_warning)).apply {
                        setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_heat)), 0, length, 0)
                        append("\n\n").append(app.mg4dash.system.Updater.plainNotes(r.notes))
                    })
                    .setPositiveButton(R.string.st_update_install) { _, _ ->
                        started = true
                        lifecycleScope.launch {
                            try {
                                val apk = withContext(Dispatchers.IO) {
                                    A.download(this@SettingsActivity, r) { pct -> runOnUiThread { state.text = getString(R.string.st_update_downloading, pct) } }
                                }
                                state.text = getString(R.string.st_aa_installing)
                                A.listener = { ok -> runOnUiThread {
                                    cur = A.installedVersion(this@SettingsActivity) ?: cur
                                    showState()
                                    if (!isFinishing) AlertDialog.Builder(this@SettingsActivity)
                                        .setMessage(if (ok) R.string.st_aa_done else R.string.st_update_error)
                                        .setPositiveButton(android.R.string.ok, null).show().also { app.mg4dash.ui.Dialogs.fix(it) }
                                } }
                                withContext(Dispatchers.IO) { A.install(this@SettingsActivity, apk, r.version) }
                            } catch (e: Exception) {
                                Log.w("Update", "${A.NAME}: ${e.message}")
                                A.end()
                                state.text = getString(R.string.st_update_error)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener { if (!started) { A.end(); showState() } }
                    .show().also { app.mg4dash.ui.Dialogs.fix(it) }
            }
        }
    }

    /** Radio Dash: on every firmware, the firmwares already tested are named in the note below. */
    private fun radioDashUpdate(parent: LinearLayout) {
        val R2 = app.mg4dash.system.RadioDashUpdater
        val installed = R2.installed(this)
        val cur = R2.installedVersion(this)
        val rdNew = prefs.raw.getString("upd_new_rd", null)?.takeIf { !installed || cur == null || app.mg4dash.system.Updater.isNewer(it, cur) }
        fun stateText() = when {
            !installed && rdNew != null -> getString(R.string.st_upd_none_new, rdNew)
            !installed -> getString(R.string.st_rd_none)
            cur == null -> getString(R.string.st_rd_unknown)
            rdNew != null -> updateAvailable(cur, rdNew)
            prefs.raw.getLong("upd_checked_at", 0L) > 0L -> getString(R.string.st_upd_current, cur)
            else -> cur
        }
        val blocker = R2.blocker(this)
        val (state, rdButton) = updateLine(parent, "MG4Radio", blocker?.let { getString(it) } ?: stateText(), rdNew.takeIf { blocker == null })
        fun showState() { state.text = stateText() }
        if (blocker != null) { rdButton.isEnabled = false; rdButton.alpha = 0.4f; return }
        rdButton.setOnClickListener {
            if (app.mg4dash.system.DataUsage.updatesBlocked(this)) { state.text = getString(R.string.du_updates_wait); return@setOnClickListener }
            state.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { try { R2.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("Update", "Radio Dash: ${e.message}"); null } }
                if (r == null) { state.text = getString(R.string.st_update_error); return@launch }
                app.mg4dash.ui.UpdatePrompt.remember(this@SettingsActivity, rd = r.version)
                val cur = R2.installedVersion(this@SettingsActivity)
                if (cur != null && !app.mg4dash.system.Updater.isNewer(r.version, cur)) { state.text = getString(R.string.st_upd_current, cur); return@launch }
                showState()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.st_rd_available, r.version))
                    .setMessage(android.text.SpannableStringBuilder(app.mg4dash.system.Updater.dataWarning(this@SettingsActivity, r)).apply {
                        setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_heat)), 0, length, 0)
                        append("\n\n").append(app.mg4dash.system.Updater.plainNotes(r.notes))
                    })
                    .setPositiveButton(R.string.st_update_install) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val apk = withContext(Dispatchers.IO) {
                                    R2.download(this@SettingsActivity, r) { pct -> runOnUiThread { state.text = getString(R.string.st_update_downloading, pct) } }
                                }
                                state.text = getString(R.string.st_rd_installing)
                                R2.listener = { ok -> runOnUiThread {
                                    showState()
                                    if (!isFinishing) AlertDialog.Builder(this@SettingsActivity)
                                        .setMessage(if (ok) R.string.st_rd_done else R.string.st_update_error)
                                        .setPositiveButton(android.R.string.ok, null).show().also { app.mg4dash.ui.Dialogs.fix(it) }
                                } }
                                withContext(Dispatchers.IO) { R2.install(this@SettingsActivity, apk, r.version) }
                            } catch (e: Exception) {
                                Log.w("Update", "Radio Dash: ${e.message}")
                                state.text = getString(R.string.st_update_error)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show().also { app.mg4dash.ui.Dialogs.fix(it) }
            }
        }
    }

    /** Startup sound settings. */
    private fun soundTriggersSetting(parent: LinearLayout) {
        text(parent, getString(R.string.st_sound_trigger_desc), 15f, R.color.text_title)

        // A single switch controls playback when MG4Dash opens.
        toggle(parent, R.string.st_sound_boot_trigger, null,
            { app.mg4dash.sound.SoundTriggerSettings.isBootTriggerEnabled(this) },
            { app.mg4dash.sound.SoundTriggerSettings.setBootTriggerEnabled(this, it) }
        )

        // Select and import an audio file.
        val selectedPath = app.mg4dash.sound.SoundTriggerSettings.getSelectedSoundPath(this)
        val fileLabel = selectedPath?.let { java.io.File(it).name } ?: getString(R.string.st_sound_no_file)
        val curFileView = text(parent, fileLabel, 16f, R.color.text_sub)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pickBtn = button(getString(R.string.st_sound_pick_file)) {
            val progress = android.app.ProgressDialog.show(this, "", getString(R.string.st_sound_scanning), true)
            Thread {
                val items = app.mg4dash.sound.SoundImporter.findAvailableAudioFiles(this)
                runOnUiThread {
                    progress.dismiss()
                    if (items.isEmpty()) {
                        app.mg4dash.ui.Toasts.show(this, R.string.st_sound_none_found, long = true)
                        return@runOnUiThread
                    }
                    val titles = items.map { "${it.file.name} (${it.durationMs / 1000}s)" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(R.string.st_sound_pick_file)
                        .setItems(titles) { _, which ->
                            val chosen = items[which]
                            if (chosen.file.length() > app.mg4dash.sound.SoundImporter.MAX_FILE_SIZE_BYTES) {
                                app.mg4dash.ui.Toasts.show(this, R.string.st_sound_file_too_large)
                                return@setItems
                            }
                            val imported = app.mg4dash.sound.SoundImporter.importSound(this, chosen.file)
                            if (imported != null) {
                                curFileView.text = imported.name
                                app.mg4dash.ui.Toasts.show(this, getString(R.string.st_sound_imported, imported.name))
                            } else {
                                app.mg4dash.ui.Toasts.show(this, R.string.st_sound_import_failed, long = true)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show().also { app.mg4dash.ui.Dialogs.fix(it); app.mg4dash.ui.Dialogs.fullHeight(it) }
                }
            }.start()
        }
        btnRow.addView(pickBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })

        var isTesting = false
        lateinit var testBtn: Button
        testBtn = button(getString(R.string.st_sound_test)) {
            if (isTesting) {
                app.mg4dash.sound.SoundTrigger.stop(this)
                isTesting = false
                testBtn.text = getString(R.string.st_sound_test)
            } else {
                val p = app.mg4dash.sound.SoundTriggerSettings.getSelectedSoundPath(this)
                if (p == null) {
                    app.mg4dash.ui.Toasts.show(this, R.string.st_sound_no_file)
                    return@button
                }
                isTesting = true
                testBtn.text = getString(R.string.st_sound_stop)
                app.mg4dash.sound.SoundTrigger.playSoundFile(this, java.io.File(p), isTest = true) {
                    isTesting = false
                    testBtn.text = getString(R.string.st_sound_test)
                }
            }
        }
        btnRow.addView(testBtn, LinearLayout.LayoutParams(dp(130), -2))
        parent.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // Absolute car media level (0..32), restored after playback.
        val savedVolume = app.mg4dash.sound.SoundTriggerSettings.getVolumeLevel(this)
        val volumeLine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val volumeHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        volumeHeader.addView(TextView(this).apply {
            text = getString(R.string.st_sound_volume)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color(R.color.text_value))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val volumeValue = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color(R.color.accent_action))
        }
        volumeHeader.addView(volumeValue)
        volumeLine.addView(volumeHeader)
        val volumeSlider = SeekBar(this).apply {
            max = app.mg4dash.car.CarMediaVolume.UI_MAX
            progress = savedVolume
            progressTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent_action))
            thumbTintList = android.content.res.ColorStateList.valueOf(color(R.color.accent_action))
        }
        fun showVolume(value: Int) { volumeValue.text = getString(R.string.st_sound_volume_level, value) }
        showVolume(savedVolume)
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                showVolume(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                app.mg4dash.sound.SoundTriggerSettings.setVolumeLevel(this@SettingsActivity, seekBar.progress)
            }
        })
        volumeLine.addView(volumeSlider, LinearLayout.LayoutParams(-1, dp(48)))
        parent.addView(volumeLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    /** Full power animation of the live Power widget: drop-down list, preview on a mini map. */
    private fun powerFxSetting(parent: LinearLayout) {
        text(parent, getString(R.string.st_power_fx), 18f, R.color.text_value)
        // No preview: the surprise is for the road. Displayed order: random first (default).
        val fx = app.mg4dash.ui.PowerFx
        val choices = listOf(fx.RANDOM to R.string.fx_random, fx.LIGHTNING to R.string.fx_lightning,
            fx.FLAMES to R.string.fx_flames, fx.HYPERSPACE to R.string.fx_hyperspace, fx.NONE to R.string.fx_none).map { it.first to getString(it.second) }
        lateinit var pick: Button
        // Drop-down list in the page itself: a popup window would bring back the system bars
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        fun label() {
            val name = choices.firstOrNull { it.first == prefs.powerFx }?.second ?: choices[0].second
            pick.text = "$name   ${if (list.visibility == View.VISIBLE) "▴" else "▾"}"
        }
        fun paint() = choices.forEachIndexed { k, (v, _) ->
            (list.getChildAt(k) as Button).apply { isSelected = v == prefs.powerFx; setTextColor(color(if (isSelected) R.color.accent_action else R.color.text_value)) }
        }
        pick = button("") { list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE; label() }
            .apply { setBackgroundResource(R.drawable.bg_chip); setTextColor(color(R.color.accent_action)) }
        choices.forEach { (v, n) ->
            list.addView(button(n) { prefs.powerFx = v; list.visibility = View.GONE; label(); paint() }
                .apply { setBackgroundResource(R.drawable.bg_chip); gravity = Gravity.CENTER_VERTICAL or Gravity.START },
                LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(6) })
        }
        paint(); label()
        // Under the choice button only (left half of the line)
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(pick, LinearLayout.LayoutParams(-1, dp(56)))
        left.addView(list, LinearLayout.LayoutParams(-1, -2))
        line.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(10) })
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        text(parent, getString(R.string.st_power_fx_desc), 16f, R.color.text_title)
    }

    private fun confirmReplace() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_replace)
            .setMessage(R.string.st_replace_msg)
            .setPositiveButton(R.string.st_replace_btn) { _, _ ->
                val ok = HomeSwitcher.replaceStock(this)
                Log.i("Valet", "replacing SAIC home → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    private fun confirmValet() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_valet)
            .setMessage(R.string.st_valet_msg)
            .setPositiveButton(R.string.activate) { _, _ ->
                val ok = HomeSwitcher.switchToStock(this)
                // Phone connection cut by a long press: restored for the dealership
                app.mg4dash.system.ConnectionToggle.restore(this)
                Log.i("Valet", "Maintenance mode → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    private fun toastState(ok: Boolean) {
        app.mg4dash.ui.Toasts.show(this, getString(if (ok) R.string.st_home_changed else R.string.st_failed))
    }

    private fun refreshHome() {
        val cur = HomeSwitcher.currentHome(this)
        homeState.text = getString(R.string.st_current_home, when (cur) {
            HomeSwitcher.STOCK -> getString(R.string.st_home_stock)
            HomeSwitcher.ours(this) -> "MG4Dash"
            null -> getString(R.string.st_home_none)
            else -> cur.flattenToShortString()
        })
    }

    /** Log in a window, on demand. */
    private fun showLogDialog() {
        val view = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            text = Log.snapshot().joinToString("\n")
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        // Under the log: the export's result, or why it cannot be done (#36)
        val status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setPadding(dp(20), dp(10), dp(20), dp(4)); visibility = View.GONE
        }
        val title = getString(R.string.st_log) + " · MG4Dash ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}"
        val accent = app.mg4dash.ui.Themes.select(this)
        // Our own row of buttons (#36, #106): Refresh on the left, Export and Eject in the middle, Close on the right
        fun button(label: Int) = android.widget.Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(label); isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(accent)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val refresh = button(R.string.st_refresh); val export = button(R.string.st_log_export)
        val eject = button(R.string.st_log_eject); val close = button(R.string.st_close)
        fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(refresh); addView(space()); addView(export); addView(eject); addView(space()); addView(close)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(ScrollView(this@SettingsActivity).apply { addView(view) }, LinearLayout.LayoutParams(-1, 0, 1f))
                addView(status)
                addView(row, LinearLayout.LayoutParams(-1, -2))
            })
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
        logDialog = d
        var radioLines: List<Pair<Long, String>> = emptyList()
        // Radio Dash's lines among ours (#80): read from logcat, which takes a moment
        fun withRadio() = Thread {
            val radio = Log.radioLines()
            radioLines = radio
            if (radio.isNotEmpty()) runOnUiThread { if (d.isShowing) view.text = Log.snapshot(radio).joinToString("\n") }
        }.start()
        withRadio()
        var ejecting = false
        fun say(text: String, ok: Boolean) { status.visibility = View.VISIBLE; status.setTextColor(if (ok) accent else color(R.color.text_sub)); status.text = text }
        /** The buttons follow the stick: Export greyed with a line saying so, Eject hidden, without one. */
        fun paintStick() {
            val stick = !ejecting && app.mg4dash.system.UsbExport.stick(this) != null
            export.isEnabled = stick
            export.setTextColor(if (stick) accent else color(R.color.text_disabled))
            eject.visibility = if (stick || ejecting) View.VISIBLE else View.GONE
            eject.isEnabled = !ejecting
            eject.setTextColor(if (!ejecting) accent else color(R.color.text_disabled))
            val noUsb = getString(R.string.st_log_no_usb)
            if (!stick && !ejecting && status.text != getString(R.string.st_log_ejected)) say(noUsb, ok = false)
            else if (stick && status.text == noUsb) status.visibility = View.GONE
        }
        paintStick()
        val watch = object : Runnable { override fun run() { if (!d.isShowing) return; paintStick(); view.postDelayed(this, 2_000) } }
        view.postDelayed(watch, 2_000)
        refresh.setOnClickListener { view.text = Log.snapshot().joinToString("\n"); withRadio(); paintStick() }
        close.setOnClickListener { d.dismiss() }
        export.setOnClickListener {
            val text = LogExport.text(title, Compat.report(this), Log.snapshot(radioLines))
            val path = app.mg4dash.system.UsbExport.write(this, LogExport.fileName(System.currentTimeMillis()), text)
            if (path != null) say(getString(R.string.st_log_exported, path), ok = true) else paintStick()
        }
        eject.setOnClickListener {
            ejecting = true; paintStick(); say(getString(R.string.st_log_ejecting), ok = false)
            Thread {
                val failed = app.mg4dash.system.UsbExport.eject(this)
                runOnUiThread {
                    ejecting = false
                    if (failed == null) say(getString(R.string.st_log_ejected), ok = true) else say(getString(R.string.st_log_eject_failed), ok = false)
                    paintStick()
                }
            }.start()
        }
    }

    /** The log window shown (renders of the mockups). */
    internal var logDialog: AlertDialog? = null

    /** GitHub release notes (Markdown) as readable text. */
    private fun onThemeChanged() {
        app.mg4dash.ui.Themes.publish(this)
        recreate()
    }

    /** Row of colour dots; the chosen dot is circled in white. */
    private fun swatches(parent: LinearLayout, colors: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        colors.forEachIndexed { i, c ->
            val v = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(if (i == selected) 4 else 1), if (i == selected) 0xFFFFFFFF.toInt() else 0xFF4A5366.toInt())
                }
                isClickable = true
                setOnClickListener { if (i != selected) onPick(i) }
            }
            r.addView(v, LinearLayout.LayoutParams(dp(56), dp(56)).apply { if (i > 0) leftMargin = dp(18) })
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(6) })
    }

    // ── Petites briques d'UI ──

    /** Entry of a text (address, masked token) or a number. */
    private fun ask(title: String, initial: String, secret: Boolean, number: Boolean = false, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial); setSingleLine()
            setTextColor(color(R.color.text_value))
            inputType = if (number) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(input)
        }
        // Some keyboards (SWI68) have no "paste" (#63): dedicated button if the clipboard contains text
        val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (!clip.isNullOrEmpty()) box.addView(TextView(this).apply {
            text = getString(R.string.st_paste); setTextColor(color(R.color.accent_action)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(12), 0, dp(4))
            setOnClickListener { input.setText(clip); input.setSelection(input.text.length) }
        })
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show().also { d ->
                app.mg4dash.ui.Dialogs.fix(d)
                app.mg4dash.ui.CarKeyboard.attachInDialog(input, d, box, onDone = { d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick() })
            }
    }

    /**
     * Home Assistant address and token read from mg4dash_ha.txt at the root of a USB stick: the line that
     * starts with http is the address, the longest of the others the token.
     */
    private fun importHa(done: () -> Unit) {
        val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
        val file = sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .mapNotNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? java.io.File } catch (_: Throwable) { null } }
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.name.equals("mg4dash_ha.txt", ignoreCase = true) }
        if (file == null) { app.mg4dash.ui.Toasts.show(this, R.string.st_ha_import_none, long = true); return }
        val lines = try { file.readLines().map { it.trim().trimStart('\uFEFF') }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
        val url = lines.firstOrNull { it.startsWith("http", ignoreCase = true) }
        val token = lines.filter { it != url && !it.contains(' ') }.maxByOrNull { it.length }
        if (url != null) prefs.haUrl = url.trimEnd('/')
        if (token != null) prefs.haToken = token
        Log.i("Settings", "Home Assistant imported from ${file.name}: address ${url != null}, token ${token != null}")
        val found = listOfNotNull(url?.let { getString(R.string.st_ha_set_url) }, token?.let { getString(R.string.st_ha_set_token) })
        if (found.isEmpty()) { app.mg4dash.ui.Toasts.show(this, R.string.st_ha_import_none, long = true); return }
        app.mg4dash.ui.Toasts.show(this, getString(R.string.st_ha_import_done, found.joinToString(", ")), long = true)
        done()
    }

    /**
     * #62: copies the SAIC system UI (and SAIC climate apps) to the USB stick, to find out how to
     * stop the climate page coming over MG4Dash. Read only: nothing is changed in the car.
     */
    private fun copySystemUi() {
        val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
        val usb = sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? java.io.File } catch (_: Throwable) { null } }
        if (usb == null) { app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_nousb, long = true); return }
        app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_busy)
        Thread {
            // Stick root not writable (beta 10, SWI165: "No such file or directory"):
            // MG4Dash's own folder on the stick (Android/data/app.mg4dash/files), always allowed
            val own = getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(usb.absolutePath) }
            val out = (if (java.io.File(usb, "mg4dash_systemui").let { it.mkdirs() || it.isDirectory } && java.io.File(usb, "mg4dash_systemui").canWrite())
                java.io.File(usb, "mg4dash_systemui") else java.io.File(own ?: usb, "mg4dash_systemui").apply { mkdirs() })
            Log.i("Settings", "system UI copy to ${out.absolutePath}")
            val pkgs = packageManager.getInstalledApplications(0).filter { a ->
                a.packageName == "com.android.systemui" ||
                    a.packageName.startsWith("com.saicmotor") && listOf("hvac", "aircondition", "climate", "systemui").any { it in a.packageName.lowercase() }
            }
            var n = 0
            for (a in pkgs) for (path in listOf(a.sourceDir) + a.splitSourceDirs.orEmpty()) try {
                val src = java.io.File(path)
                src.copyTo(java.io.File(out, "${a.packageName}${if (path == a.sourceDir) "" else "_" + src.nameWithoutExtension}.apk"), overwrite = true)
                n++
                Log.i("Settings", "copied ${a.packageName} ($path, ${src.length() / 1024} KB) to USB")
            } catch (e: Exception) { Log.w("Settings", "copy ${a.packageName}: ${e.message}") }
            runOnUiThread {
                if (n > 0) app.mg4dash.ui.Toasts.show(this, getString(R.string.st_copy_sysui_done, n) + " : " + out.absolutePath.removePrefix(usb.absolutePath).trimStart('/'), long = true)
                else app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_fail, long = true)
            }
        }.start()
    }

    /**
     * Recent web engine (#98): the car's WebView or Chromium 138 for the web app card, downloaded from the
     * MG4Dash-WebView repository; the way back to the car's own. Only on head units that can take it.
     */
    private fun webEngine(parent: LinearLayout): Boolean {
        val we = app.mg4dash.system.WebEngine
        if (!we.installed(this) && !we.possible(this)) return false
        // Built again after an install or a revert, from the engine then in use
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box, LinearLayout.LayoutParams(-1, -2))
        webEngineRows(box, null)
        return true
    }

    /** One line like MG4Dash's and Radio Dash's: name and state on the left, one button on the right. */
    private fun webEngineRows(box: LinearLayout, message: String?) {
        val we = app.mg4dash.system.WebEngine
        box.removeAllViews()
        val on = we.installed(this)
        fun major(v: String?) = v?.substringBefore('.') ?: "?"
        val (state, b) = updateLine(box, getString(R.string.st_web_engine_name), message ?: if (on)
            getString(R.string.st_web_engine_state_on, major(we.current(this)))
        else getString(R.string.st_web_engine_state_orig, major(we.current(this)), major(we.VERSION), we.SIZE / 1_000_000), null)
        b.text = if (on) getString(R.string.st_web_engine_revert) else getString(R.string.st_upd_install_ver, major(we.VERSION))
        var busy = false
        fun finished(ok: Boolean) {
            busy = false
            if (!ok) { state.text = getString(R.string.st_update_error); return }
            // Installed: says so; taken off: the line shows the car's own engine again
            webEngineRows(box, if (we.installed(this)) getString(R.string.st_web_engine_done) else null)
        }
        b.setOnClickListener {
            if (busy) return@setOnClickListener
            if (on) {
                AlertDialog.Builder(this).setMessage(R.string.st_web_engine_revert_confirm)
                    .setPositiveButton(R.string.st_web_engine_revert_ok) { _, _ -> busy = true; we.revert(this) { finished(it) } }
                    .setNegativeButton(R.string.cancel, null).show().also { app.mg4dash.ui.Dialogs.fix(it); app.mg4dash.ui.Dialogs.hideBars(it) }
                return@setOnClickListener
            }
            if (app.mg4dash.system.DataUsage.updatesBlocked(this)) { state.text = getString(R.string.du_updates_wait); return@setOnClickListener }
            AlertDialog.Builder(this).setMessage(getString(R.string.st_web_engine_confirm, we.VERSION, we.SIZE / 1_000_000))
                .setPositiveButton(R.string.st_update_install) { _, _ ->
                    busy = true
                    we.install(this, { step, pct ->
                        runOnUiThread {
                            state.text = when (step) {
                                app.mg4dash.system.WebEngine.Step.DOWNLOAD -> getString(R.string.st_update_downloading, pct)
                                app.mg4dash.system.WebEngine.Step.INSTALL -> getString(R.string.st_update_installing)
                            }
                        }
                    }) { finished(it) }
                }
                .setNegativeButton(R.string.cancel, null).show().also { app.mg4dash.ui.Dialogs.fix(it); app.mg4dash.ui.Dialogs.hideBars(it) }
        }
    }

    private fun panel(parent: LinearLayout, title: String, fill: Boolean = false): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            elevation = dp(6).toFloat()   // marked as a panel for the outline style (#40)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        p.addView(TextView(this).apply {
            text = title; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            letterSpacing = 0.12f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        parent.addView(p, LinearLayout.LayoutParams(-1, if (fill) -1 else -2).apply { bottomMargin = dp(12) })
        return p
    }

    private fun text(parent: LinearLayout, value: String, sp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            text = value; setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setPadding(0, dp(6), 0, dp(2))
        }.also { parent.addView(it) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            // White text on every button, the close one too: no accent colour in the settings (#98)
            text = label; isAllCaps = false; setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = dp(56); setPadding(dp(20), 0, dp(20), 0)
            stateListAnimator = null; elevation = 0f   // no relief or shadow (Material style), like the other buttons
            setOnClickListener { onClick() }
        }

    /**
     * Yes / no option on one line: label (the ": %1$s" of the string is removed) and optional
     * description on the left, a single button on the right showing YES or NO, highlighted when the option is on.
     */
    /** Returns what is needed to repaint the button, for a choice confirmed later in a window. */
    private fun toggle(parent: LinearLayout, labelRes: Int, descRes: Int?, get: () -> Boolean, set: (Boolean) -> Unit): () -> Unit {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text(texts, getString(labelRes, "").trimEnd(' ', ':', '\u00a0', '\u202f'), 18f, R.color.text_value)
        descRes?.let { text(texts, getString(it), 15f, R.color.text_title) }
        line.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
        lateinit var btn: Button
        fun paint() {
            val on = get()
            btn.text = getString(if (on) R.string.yes else R.string.no)
            btn.isSelected = on
        }
        btn = button("") { set(!get()); paint() }.apply {
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.04f
        }
        paint()
        line.addView(btn, LinearLayout.LayoutParams(dp(130), dp(56)))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return { paint() }
    }

    /** Single choice buttons: the active one is coloured, and the colour follows the choice at once. */
    /** "Label — drop-down menu" line (same menu as the card's sources). */
    private fun dropdown(parent: LinearLayout, label: String, labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): TextView {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(app.mg4dash.ui.Themes.color(this@SettingsActivity, R.color.text_value))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val drop = TextView(this).apply {
            text = labels[current().coerceIn(0, labels.lastIndex)] + "   ▾"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_chip); isSelected = true
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_text))
        }
        drop.setOnClickListener { v -> app.mg4dash.ui.SourceMenu.choose(this, v, labels, current()) { onPick(it); drop.text = labels[it] + "   ▾" } }
        row.addView(drop, LinearLayout.LayoutParams((340 * dp).toInt(), (56 * dp).toInt()))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        return drop
    }

    private fun choiceButtons(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): Array<Button> {
        lateinit var buttons: List<Button>
        fun paint() = buttons.forEachIndexed { i, btn ->
            btn.isSelected = i == current()
        }
        // Same style as the tabs (chips): text in the selection colour when chosen
        buttons = labels.mapIndexed { i, l -> button(l) { onPick(i); paint() }.apply {
            setBackgroundResource(R.drawable.bg_chip)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SettingsActivity, R.color.chip_text))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); letterSpacing = 0.04f
        } }
        paint()
        return buttons.toTypedArray()
    }

    private fun styleButtons(labels: List<Int>, current: () -> Int, onPick: (Int) -> Unit) =
        choiceButtons(labels.map { getString(it) }, current, onPick)

    private fun row(parent: LinearLayout, vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        views.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(0, dp(56), 1f).apply { if (i > 0) leftMargin = dp(10) })
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
