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
    /** Closed at once in maintenance mode (its onCreate went no further). */
    private var closedForMaintenance = false
    private val edgeSwipe by lazy { app.mg4dash.ui.EdgeSwipe(this) }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean =
        edgeSwipe.onTouch(ev, window.decorView.width) { super.dispatchTouchEvent(it) } || super.dispatchTouchEvent(ev)


    private lateinit var prefs: Prefs
    private lateinit var homeState: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.mg4dash.system.Maintenance.closeIfOn(this)) { closedForMaintenance = true; return }
        prefs = Prefs(this)
        app.mg4dash.ui.Themes.apply(this)
        themeSig = app.mg4dash.ui.Themes.signature(this)
        // The speed camera alerter's own page (17/11): a long press on its card
        if (intent.getBooleanExtra(EXTRA_HA, false)) { haPage(); return }
        // A single scrolling area: two columns of categories, under a fixed banner
        // (title, log, close) that leaves room for the fog light button.
        // The screen's margins as home's (Grand affichage: enlarged like its bar, which then sits where home's does)
        val bd = app.mg4dash.ui.Dock.sized(this).resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * bd).toInt(), (12 * bd).toInt(), (16 * bd).toInt(), (12 * bd).toInt()) }
        val vertical = app.mg4dash.ui.Dock.vertical(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = getString(R.string.st_settings); setTextColor(color(R.color.text_value))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        var touches = 0; var firstTouch = 0L
        titleBox.addView(TextView(this).apply {
            text = "MG4Dash " + BuildConfig.VERSION_NAME; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            // Five touches within 3 s: the diagnostic mode on or off (the energy recorder in System)
            setOnClickListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - firstTouch > 3_000) { firstTouch = now; touches = 0 }
                if (++touches == 5) {
                    val on = !prefs.raw.getBoolean("diag_mode", false)
                    prefs.raw.edit().putBoolean("diag_mode", on).apply()
                    app.mg4dash.ui.Toasts.show(this@SettingsActivity, if (on) R.string.st_diag_on else R.string.st_diag_off)
                    recreate()
                }
            }
        })
        top.addView(titleBox, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = if (vertical) dp(12) else dp(app.mg4dash.ui.Kit.fogSafeDp(this@SettingsActivity) - 8); rightMargin = dp(36) })
        // Tabs (setting themes) in the banner, next to the title: text underlined in the theme's colour when chosen
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tabBar, LinearLayout.LayoutParams(0, dp(72), 1f))
        // Profiles (study v4, step 10): the current one, its menu on every tab
        top.addView(ProfileWindow.button(this) { recreate() }, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(16) })
        top.addView(app.mg4dash.ui.Rows.closeButton(this) { finish() })
        root.addView(top, LinearLayout.LayoutParams(-1, -2))
        root.addView(app.mg4dash.ui.Rows.divider(this), LinearLayout.LayoutParams(-1, maxOf(1, dp(1))).apply { bottomMargin = dp(12) })
        val pages = android.widget.FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabViews = mutableListOf<View>(); val tabChips = mutableListOf<TextView>()
        fun showTab(i: Int) {
            tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabChips.forEachIndexed { k, c -> c.isSelected = k == i; c.typeface = Typeface.create(if (k == i) "sans-serif-medium" else "sans-serif", Typeface.NORMAL) }
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
            // Room under the last panel (24/11: its bottom edge was cut), its outline and shadow in sight at the end
            val sv = ScrollView(this).apply {
                isVerticalScrollBarEnabled = true; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(28))
                addView(cols); setPadding(0, 0, 0, dp(24)); clipToPadding = false
            }
            pages.addView(sv, android.widget.FrameLayout.LayoutParams(-1, -1)); tabViews += sv
            val chip = tabLabel(getString(label)).apply { setOnClickListener { showTab(i) } }
            tabBar.addView(chip, LinearLayout.LayoutParams(-2, -1).apply { if (i > 0) leftMargin = dp(10) }); tabChips += chip
            return l to r
        }
        // Five tabs (24/11), one rule each: Screen = what home shows and how; Appearance = colours, light, day and
        // night; Media and sounds = what plays and what is heard; General = formats, typing, mobile data; System = the app
        val (homeL, homeR) = tab(R.string.st_tab_screen)
        val (lookL, lookR) = tab(R.string.st_tab_look)
        val (mediaL, mediaR) = tab(R.string.st_tab_media)
        val (genL, genR) = tab(R.string.st_tab_general)
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
        // Three styles (study v4, step 8): Standard ▾ and Grand affichage ▾ each open their own menu of layouts (with
        // their drawing), Simple stays as it is; each style keeps its layout and cards, the edit mode changes them
        lateinit var paintStyle: () -> Unit
        fun styleMenu(v: View, big: Boolean) = app.mg4dash.ui.LayoutPicker.show(this, v,
            app.mg4dash.ui.HomeSlots.layoutOf(prefs, big).takeIf { (prefs.homeStyle == Prefs.STYLE_BIG) == big && prefs.homeStyle != 1 }, big = big) {
            app.mg4dash.ui.HomeSlots.choose(prefs, it); paintStyle()
        }
        val (layoutBtn) = choiceButtons(listOf(getString(R.string.st_style_standard) + "   ▾"), { if (prefs.homeStyle != 1 && prefs.homeStyle != Prefs.STYLE_BIG) 0 else -1 }) {}
        layoutBtn.setOnClickListener { styleMenu(it, big = false) }
        // Grand affichage (accessibility): everything bigger; Settings itself keeps its size
        val (bigBtn) = choiceButtons(listOf(getString(R.string.st_style_big) + "   ▾"), { if (prefs.homeStyle == Prefs.STYLE_BIG) 0 else -1 }) {}
        bigBtn.setOnClickListener { styleMenu(it, big = true) }
        val (simpleBtn) = choiceButtons(listOf(getString(R.string.st_style_simple)), { if (prefs.homeStyle == 1) 0 else -1 }) { prefs.homeStyle = 1; paintStyle() }
        row(style, layoutBtn, bigBtn, simpleBtn)
        val editBtn = button(getString(R.string.ly_edit)) {
            startActivity(android.content.Intent(this, app.mg4dash.MainActivity::class.java)
                .putExtra(app.mg4dash.ui.CardsEditor.EXTRA_EDIT, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        // Cards offered: the cards switched off leave the edit mode and the home screen (with many cards, a shorter list)
        val cardsBtn = button(getString(R.string.st_cards_offered)) { cardsOffered() }
        row(style, editBtn, cardsBtn)
        paintStyle = {
            simpleBtn.isSelected = prefs.homeStyle == 1
            layoutBtn.isSelected = prefs.homeStyle != 1 && prefs.homeStyle != Prefs.STYLE_BIG
            bigBtn.isSelected = prefs.homeStyle == Prefs.STYLE_BIG
            listOf(layoutBtn to false, bigBtn to true).forEach { (btn, big) ->
                btn.setCompoundDrawablesRelativeWithIntrinsicBounds(app.mg4dash.ui.LayoutPicker.glyph(this, app.mg4dash.ui.HomeSlots.layoutOf(prefs, big)), null, null, null)
                btn.setPadding(dp(18), 0, dp(16), 0)
            }
            (editBtn.parent as View).visibility = if (prefs.homeStyle == 1) View.GONE else View.VISIBLE
        }
        paintStyle()
        // Menu bar: at the bottom, or vertical on the driver's side (read from the car), left or right (#75)
        val barP = panel(homeR, getString(R.string.st_bar_section))
        dropdown(barP, getString(R.string.st_bar), listOf(R.string.st_bar_bottom, R.string.st_bar_auto, R.string.st_bar_left, R.string.st_bar_right).map { getString(it) },
            { if (!prefs.verticalDock) 0 else 1 + prefs.barSide.coerceIn(0, 2) }) {
            prefs.verticalDock = it > 0
            if (it > 0) prefs.barSide = it - 1
            app.mg4dash.ui.Themes.publish(this); recreate()
        }
        toggle(barP, R.string.st_wide_bar, R.string.st_wide_bar_desc, { prefs.wideBar }) { prefs.wideBar = it; recreate() }
        // Shortcuts menu pulled down from the top; also hides MG's fog light button (gives its corner back)
        toggle(barP, R.string.st_shortcuts_menu, R.string.st_shortcuts_menu_desc, { prefs.shortcutsMenu }) {
            prefs.shortcutsMenu = it; app.mg4dash.system.FogOverlay.settingChanged(this); app.mg4dash.ui.ShortcutsPanel.GlobalStrip.update(this)
            app.mg4dash.ui.Toasts.show(this, getString(R.string.st_shortcuts_menu_restart), long = true)
            app.mg4dash.ui.Themes.publish(this); recreate()
        }
        // Shared by every profile: MG's fog button is hidden by an overlay the car only reads at its start
        text(barP, getString(R.string.pf_all), 14f, R.color.text_title)
        val fullP = panel(homeR, getString(R.string.st_full_section))
        toggle(fullP, R.string.st_fullscreen, null, { prefs.fullscreen }) { prefs.fullscreen = it; Fullscreen.apply(this); app.mg4dash.ui.ShortcutsPanel.GlobalStrip.update(this); app.mg4dash.ui.EmbeddedApp.refreshImmersive(this) }
        // The SAIC call window in full screen too: no top bar during calls
        toggle(fullP, R.string.st_call_bar, R.string.st_call_bar_desc, { prefs.callBarHidden }) { prefs.callBarHidden = it; app.mg4dash.ui.EmbeddedApp.refreshImmersive(this) }
        // Audio source: drop-down menu (same style as the card menus)
        // Media (24/11): the source and the playback at startup, and the bar's Android Auto button
        val src = panel(mediaL, getString(R.string.st_media).uppercase())
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
        row(style, button(getString(R.string.tu_show)) {
            startActivity(android.content.Intent(this, app.mg4dash.MainActivity::class.java)
                .putExtra(app.mg4dash.ui.Tutorial.EXTRA_SHOW, true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })

        // ════ Vehicle tab: units, equipment, climate, and startup sound ════
        val units = panel(genL, getString(R.string.st_formats))
        dropdown(units, getString(R.string.st_units), listOf(getString(R.string.st_units_car), "km · kWh/100 km", "miles · mi/kWh"), { prefs.units }) { prefs.units = it }
        dropdown(units, getString(R.string.st_pressure), listOf(getString(R.string.st_mode_auto_short), "bar", "psi", "kPa"), { prefs.pressureUnit }) { prefs.pressureUnit = it }
        // Heated seats and wheel, climate hold, SAIC climate window: in the climate card's options (24/11)
        // Drive mode and regeneration by profile: in the profiles' window (24/11)
        dropdown(units, getString(R.string.st_clock), listOf(R.string.st_clock_24, R.string.st_clock_12).map { getString(it) }, { if (prefs.clock12) 1 else 0 }) { prefs.clock12 = it == 1 }
        val typing = panel(genL, getString(R.string.st_input))
        toggle(typing, R.string.st_own_keyboard, R.string.st_own_keyboard_desc, { prefs.ownKeyboard }) { prefs.ownKeyboard = it }


        // ════ Appearance tab: theme and effects ════
        val theme = panel(lookL, getString(R.string.st_theme))
        // Brightness (smart brightness, 20/11): right under the theme, with the rest of the look
        dropdown(theme, getString(R.string.st_theme_mode), listOf(R.string.st_mode_auto, R.string.st_mode_dark, R.string.st_mode_light).map { getString(it) }, { prefs.themeMode }) {
            if (prefs.themeMode != it) { prefs.themeMode = it; onThemeChanged() }
        }
        toggle(theme, R.string.st_night_sun, R.string.st_night_sun_desc, { prefs.nightBySun }) { prefs.nightBySun = it; app.mg4dash.ui.Themes.publish(this) }
        // Today's times at the car's position, for information
        text(theme, app.mg4dash.system.SunTimes.position(this)?.let { (lat, lon) ->
            app.mg4dash.system.SunTimes.of(lat, lon)?.let { (rise, set) ->
                val f = android.text.format.DateFormat.getTimeFormat(this)
                getString(R.string.st_night_sun_today, f.format(java.util.Date(set)), f.format(java.util.Date(rise)))
            }
        } ?: getString(R.string.st_night_sun_nopos), 15f, R.color.text_sub)
        // Only in Automatic (24/11): greyed in Light or Dark
        // the night row and today's times under it
        val auto = prefs.themeMode == 0
        listOf(theme.getChildAt(theme.childCount - 2), theme.getChildAt(theme.childCount - 1)).forEach { it.alpha = if (auto) 1f else 0.4f; it.isEnabled = auto }
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
        swatches(bgCol, app.mg4dash.ui.Themes.backgroundDots(this), prefs.themeBackground, getString(R.string.st_theme_bg)) { prefs.themeBackground = it; onThemeChanged() }
        text(accentCol, getString(R.string.st_theme_accent), 18f, R.color.text_value)
        swatches(accentCol, app.mg4dash.ui.Themes.ACCENT_SWATCHES, prefs.themeAccent, getString(R.string.st_theme_accent)) { prefs.themeAccent = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_desc), 16f, R.color.text_title)

        // Battery (model, reference capacity, health): on the Energy page (24/11)
        app.mg4dash.energy.BatteryCapacity.apply(this)
        // MG4 XPower: in the power card's options (24/11)
        // V2L on plugging in, consumption when stopped and the kWh price: in the energy and consumption cards' options and the Trips page (24/11)
        // Brightness at the top of the right column, above the live power (20/11)
        brightnessSetting(panel(lookR, getString(R.string.br_title)))
        // The live power's effects and sounds: in the power card's options (24/11); the startup sound here, under Sounds
        soundTriggersSetting(panel(mediaR, getString(R.string.st_sound_trigger).uppercase()))

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
        val upd = panel(sysL, getString(R.string.st_update))
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
                    lateinit var w: android.app.AlertDialog
                    w = app.mg4dash.ui.Windows.show(this@SettingsActivity, getString(R.string.st_update_available, BuildConfig.VERSION_NAME, newer.version), notes,
                        widthDp = 1100, buttons = listOf(app.mg4dash.ui.Rows.actionButton(this@SettingsActivity, getString(R.string.st_update_install)) { w.dismiss(); install(newer) }))
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
        // Uninstalling MG4Dash, MG4Radio or MG4Link (19/11, #111): one button, its window explains each one
        row(upd, button(getString(R.string.un_button)) { app.mg4dash.ui.UninstallWindow.show(this) { if (!isFinishing && !isDestroyed) recreate() } }.apply { setTextColor(color(R.color.accent_danger)) })

        // ── Settings backup on the USB stick (#129): under Update ──
        val bk = panel(sysR, getString(R.string.bk_title))
        text(bk, getString(R.string.bk_desc), 15f, R.color.text_title)
        val bkStatus = text(bk, "", 15f, R.color.text_value).apply { visibility = View.GONE }
        fun bkSay(s: String) { bkStatus.text = s; bkStatus.visibility = View.VISIBLE }
        val bkSave = button(getString(R.string.bk_save)) {}
        val bkRestore = button(getString(R.string.bk_restore)) {}
        row(bk, bkSave, bkRestore)
        toggle(bk, R.string.bk_history, R.string.bk_history_desc, { prefs.raw.getBoolean("backup_history", true) }) { prefs.raw.edit().putBoolean("backup_history", it).apply() }
        toggle(bk, R.string.bk_token, R.string.bk_token_desc, { prefs.raw.getBoolean("backup_token", false) }) { prefs.raw.edit().putBoolean("backup_token", it).apply() }
        // The status line under the options
        (bkStatus.parent as LinearLayout).removeView(bkStatus); bk.addView(bkStatus)
        fun paintBk() {
            val stick = app.mg4dash.system.UsbExport.stick(this) != null
            listOf(bkSave, bkRestore).forEach { it.isEnabled = stick; it.setTextColor(color(if (stick) R.color.text_value else R.color.text_disabled)) }
            if (!stick && bkStatus.visibility != View.VISIBLE) bkSay(getString(R.string.bk_no_usb))
            else if (stick && bkStatus.text == getString(R.string.bk_no_usb)) bkStatus.visibility = View.GONE
        }
        paintBk()
        bk.viewTreeObserver.addOnWindowFocusChangeListener { if (it) paintBk() }
        bkSave.setOnClickListener {
            val o = app.mg4dash.system.SettingsBackup.Options(prefs.raw.getBoolean("backup_history", true), prefs.raw.getBoolean("backup_token", false))
            bkSave.isEnabled = false
            Thread {
                val w = try { app.mg4dash.system.SettingsBackup.save(this, o, BuildConfig.VERSION_NAME) } catch (e: Exception) { Log.w("Backup", "save: ${e.message}"); null }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bkSave.isEnabled = true
                    when {
                        w == null -> { bkSay(getString(R.string.bk_failed)); paintBk() }
                        w.internal -> bkSay(getString(R.string.usb_saved_internal, w.path))
                        else -> bkSay(getString(R.string.bk_saved, w.path))
                    }
                }
            }.start()
        }
        bkRestore.setOnClickListener {
            val found = app.mg4dash.system.SettingsBackup.find(this)
            if (found.isEmpty()) { bkSay(getString(R.string.bk_none)); return@setOnClickListener }
            val df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT)
            val items = found.map { f ->
                listOf(df.format(java.util.Date(f.created)), f.version.ifEmpty { "?" },
                    (if (f.profiles == 1) getString(R.string.bk_profile_one) else getString(R.string.bk_profiles, f.profiles)),
                    getString(if (f.history) R.string.bk_with_history else R.string.bk_settings_only),
                    android.text.format.Formatter.formatShortFileSize(this, f.file.length())).joinToString("  ·  ")
            }.toTypedArray()
            app.mg4dash.ui.Windows.pick(this, getString(R.string.bk_list_title), items.toList()) { chosen ->
                val f = found[chosen]
                val msg = getString(R.string.bk_confirm, df.format(java.util.Date(f.created))) +
                    (if (!f.haToken) "\n\n" + getString(R.string.bk_confirm_token) else "")
                app.mg4dash.ui.Windows.confirm(this, getString(R.string.bk_confirm_title), msg, getString(R.string.bk_confirm_ok), danger = false) {
                    val ok = try { app.mg4dash.system.SettingsBackup.restore(this, org.json.JSONObject(f.file.readText())) } catch (e: Exception) { Log.w("Backup", "restore: ${e.message}"); false }
                    if (ok) app.mg4dash.system.SettingsBackup.restart(this) else bkSay(getString(R.string.bk_unreadable))
                }
            }
        }

        // Weather (the cards' options) and Home Assistant (its own page) left the Connections tab, which is gone (24/11)

        // Home Assistant: its own page, from the shortcuts card's options and a shortcut's menu (24/11)

        // ── Diagnostic: compatibility, log, recorder ──
        val diag = panel(sysR, getString(R.string.st_diag))
        text(diag, getString(R.string.st_compat_fw, app.mg4dash.system.Firmware.version, app.mg4dash.system.Firmware.gen.name), 18f, R.color.text_value)
            .apply { setOnLongClickListener { sprChrgTest(); true } }   // hidden test, reserved for trials
        text(diag, getString(if (app.mg4dash.system.Firmware.tested) R.string.st_compat_tested else R.string.st_compat_beta), 16f, R.color.text_title)
        row(diag,
            button(getString(R.string.st_compat_run)) { Thread { app.mg4dash.car.CarProps.dumpAll() }.start(); Compat.show(this) },
            button(getString(R.string.st_log)) { showLogDialog() },
        )
        // ADB over the USB port at each start (as the adbutil app), off by default
        toggle(diag, R.string.st_adb_start, R.string.st_adb_start_desc, { prefs.adbAtStart }) {
            prefs.adbAtStart = it; if (it) app.mg4dash.system.AdbSwitch.enable(this, "setting turned on")
        }
        // Diagnostic only (24/11): shown in the diagnostic mode, five touches on the version in the banner
        if (prefs.raw.getBoolean("diag_mode", false) || prefs.energyLog)
            toggle(diag, R.string.st_energy_log, R.string.st_energy_log_desc, { prefs.energyLog }) { prefs.energyLog = it }

        // ════ 4G data (24/11, once a tab): the last 30 days here, the whole detail (days, alert, reset) in a window ════
        val data = panel(genR, getString(R.string.du_tab).uppercase())
        val last30 = TextView(this)
        app.mg4dash.ui.Rows.add(data, app.mg4dash.ui.Rows.row(this, getString(R.string.du_last30), null, last30.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setTextColor(color(R.color.text_value))
            text = DataUsage.last?.let { DataUsage.format(this@SettingsActivity, it.total) } ?: "—"
        }, null))
        app.mg4dash.ui.Rows.add(data, app.mg4dash.ui.Rows.row(this, getString(R.string.du_detail_open), getString(R.string.du_detail_open_desc), app.mg4dash.ui.Rows.chevron(this)) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val r = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            cols.addView(l, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
            cols.addView(r, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
            dataTab(l, r)
            app.mg4dash.ui.Windows.show(this, getString(R.string.du_tab), cols, widthDp = 1760)
        })

        refreshHome()
        // The 4G data tab comes before System (1.7.3): a saved System tab stays System
        if (!prefs.raw.contains("settings_tabs_v2")) {
            val t = prefs.raw.getInt("settings_tab", 0)
            prefs.raw.edit().putBoolean("settings_tabs_v2", true).putInt("settings_tab", if (t >= 4) t + 1 else t).apply()
        }
        // The Connections tab is gone (24/11): a saved later tab stays the same tab, a saved Connections opens Home
        if (!prefs.raw.contains("settings_tabs_v3")) {
            val t = prefs.raw.getInt("settings_tab", 0)
            prefs.raw.edit().putBoolean("settings_tabs_v3", true).putInt("settings_tab", if (t > 3) t - 1 else if (t == 3) 0 else t).apply()
        }
        if (!prefs.raw.contains("settings_tabs_v4")) {
            val t = prefs.raw.getInt("settings_tab", 0)
            prefs.raw.edit().putBoolean("settings_tabs_v4", true).putInt("settings_tab", if (t == 2) 3 else t).apply()
        }
        val asked = intent.getIntExtra(EXTRA_TAB, -1)
        showTab((if (asked >= 0) asked else prefs.raw.getInt("settings_tab", 0)).coerceIn(0, tabViews.size - 1))
    }

    companion object {
        /** Tab to open (index), e.g. [TAB_DATA] from the 4G data alert. */
        const val EXTRA_TAB = "tab"
        /** The 4G data's settings, in General (24/11). */
        const val TAB_DATA = 3
        const val TAB_CAR = 2
        /** Opens the Home Assistant page (address, token, test) instead of the settings. */
        const val EXTRA_HA = "ha"

        fun openHa(context: android.content.Context) = context.startActivity(android.content.Intent(context, SettingsActivity::class.java).putExtra(EXTRA_HA, true))
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
            app.mg4dash.ui.Windows.confirm(this, getString(R.string.du_reset), getString(R.string.du_reset_confirm), getString(R.string.du_reset)) { DataUsage.reset(this) }
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
                app.mg4dash.ui.Windows.show(this, dayFormat.format(DataUsage.calendarOf(day.date).time).replaceFirstChar { it.uppercase() },
                    app.mg4dash.ui.Windows.message(this, lines), sub = netName(g, w) + " · " + DataUsage.format(this, bytes.sum()), widthDp = 760) { chart.selected = -1 }
                    .also { app.mg4dash.ui.Dialogs.hideBars(it) }
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
        if (closedForMaintenance) { super.onDestroy(); return }
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
        if (closedForMaintenance) { super.onResume(); return }
        super.onResume()
        Fullscreen.apply(this)
        themeHandler.postDelayed(themeWatch, 5000)
    }

    override fun onPause() {
        if (closedForMaintenance) { super.onPause(); return }
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
        app.mg4dash.ui.Windows.show(this, "Tests (charge)", ScrollView(this).apply { addView(col) })
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
                run { var dialogRef: android.app.AlertDialog? = null; dialogRef = app.mg4dash.ui.Windows.show(this@SettingsActivity, getString(R.string.st_aa_available, r.version),
                    app.mg4dash.ui.Windows.message(this@SettingsActivity, android.text.SpannableStringBuilder(app.mg4dash.system.Updater.dataWarning(this@SettingsActivity, r) + "\n" + getString(R.string.st_aa_session_warning)).apply {
                        setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_heat)), 0, length, 0)
                        append("\n\n").append(app.mg4dash.system.Updater.plainNotes(r.notes))
                    }), widthDp = 1100,
                    onDismiss = { if (!started) { A.end(); showState() } },
                    buttons = listOf(app.mg4dash.ui.Rows.actionButton(this@SettingsActivity, getString(R.string.st_update_install)) { dialogRef?.dismiss();
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
                                    if (!isFinishing) app.mg4dash.ui.Windows.show(this@SettingsActivity, getString(R.string.st_update),
                                        app.mg4dash.ui.Windows.message(this@SettingsActivity, getString(if (ok) R.string.st_aa_done else R.string.st_update_error)), widthDp = 760)
                                } }
                                withContext(Dispatchers.IO) { A.install(this@SettingsActivity, apk, r.version) }
                            } catch (e: Exception) {
                                Log.w("Update", "${A.NAME}: ${e.message}")
                                A.end()
                                state.text = getString(R.string.st_update_error)
                            }
                        }
                    })) }
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
        // Not validated on this firmware (#111): neither offered nor updated
        val blocker = if (!R2.validated) R.string.st_rd_not_validated else R2.blocker(this)
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
                run { var dialogRef: android.app.AlertDialog? = null; dialogRef = app.mg4dash.ui.Windows.show(this@SettingsActivity, getString(R.string.st_rd_available, r.version),
                    app.mg4dash.ui.Windows.message(this@SettingsActivity, android.text.SpannableStringBuilder(app.mg4dash.system.Updater.dataWarning(this@SettingsActivity, r)).apply {
                        setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_heat)), 0, length, 0)
                        append("\n\n").append(app.mg4dash.system.Updater.plainNotes(r.notes))
                    }), widthDp = 1100,
                    onDismiss = {},
                    buttons = listOf(app.mg4dash.ui.Rows.actionButton(this@SettingsActivity, getString(R.string.st_update_install)) { dialogRef?.dismiss();
                        lifecycleScope.launch {
                            try {
                                val apk = withContext(Dispatchers.IO) {
                                    R2.download(this@SettingsActivity, r) { pct -> runOnUiThread { state.text = getString(R.string.st_update_downloading, pct) } }
                                }
                                state.text = getString(R.string.st_rd_installing)
                                R2.listener = { ok -> runOnUiThread {
                                    showState()
                                    if (!isFinishing) app.mg4dash.ui.Windows.show(this@SettingsActivity, getString(R.string.st_update),
                                        app.mg4dash.ui.Windows.message(this@SettingsActivity, getString(if (ok) R.string.st_rd_done else R.string.st_update_error)), widthDp = 760)
                                } }
                                withContext(Dispatchers.IO) { R2.install(this@SettingsActivity, apk, r.version) }
                            } catch (e: Exception) {
                                Log.w("Update", "Radio Dash: ${e.message}")
                                state.text = getString(R.string.st_update_error)
                            }
                        }
                    })) }
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
                    app.mg4dash.ui.Windows.pick(this, getString(R.string.st_sound_pick_file), titles.toList()) { which ->
                            val chosen = items[which]
                            if (chosen.file.length() > app.mg4dash.sound.SoundImporter.MAX_FILE_SIZE_BYTES) {
                                app.mg4dash.ui.Toasts.show(this, R.string.st_sound_file_too_large)
                                return@pick
                            }
                            val imported = app.mg4dash.sound.SoundImporter.importSound(this, chosen.file)
                            if (imported != null) {
                                curFileView.text = imported.name
                                app.mg4dash.ui.Toasts.show(this, getString(R.string.st_sound_imported, imported.name))
                            } else {
                                app.mg4dash.ui.Toasts.show(this, R.string.st_sound_import_failed, long = true)
                            }
                    }
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
        volumeHeader.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsActivity).apply { text = getString(R.string.st_sound_volume); setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.TITLE_SP); setTextColor(color(R.color.text_value)) })
            addView(TextView(this@SettingsActivity).apply { text = getString(R.string.st_sound_volume_desc); setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.SUB_SP); setTextColor(color(R.color.text_sub)) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val volumeValue = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.VALUE_SP)
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
    private fun confirmReplace() {
        app.mg4dash.ui.Windows.confirm(this, getString(R.string.st_replace), getString(R.string.st_replace_msg), getString(R.string.st_replace_btn), danger = false) {
            val ok = HomeSwitcher.replaceStock(this)
            Log.i("Valet", "replacing SAIC home → $ok")
            app.mg4dash.ui.ShortcutsPanel.GlobalStrip.update(this)
            refreshHome(); toastState(ok)
        }
    }

    private fun confirmValet() {
        app.mg4dash.ui.Windows.confirm(this, getString(R.string.st_valet), getString(R.string.st_valet_msg), getString(R.string.activate), danger = false) {
            val ok = HomeSwitcher.switchToStock(this)
            // Phone connection cut by a long press: restored for the dealership
            app.mg4dash.system.ConnectionToggle.restore(this)
            Log.i("Valet", "Maintenance mode → $ok")
            app.mg4dash.ui.ShortcutsPanel.GlobalStrip.update(this)
            refreshHome(); toastState(ok)
            // From now on nothing but the three-finger gesture (19/11): restarted in its light form
            if (ok) app.mg4dash.system.Maintenance.restartLight(this)
        }
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
        val eject = button(R.string.st_log_eject)
        fun space() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(refresh); addView(space()); addView(export); addView(eject)
        }
        val d = app.mg4dash.ui.Windows.show(this, getString(R.string.st_log), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@SettingsActivity).apply { addView(view) }, LinearLayout.LayoutParams(-1, dp(430)))
            addView(status)
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }, sub = "MG4Dash ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}", widthDp = 1500)
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
        export.setOnClickListener {
            val text = LogExport.text(title, Compat.report(this), Log.snapshot(radioLines))
            val w = app.mg4dash.system.UsbExport.write(this, LogExport.fileName(System.currentTimeMillis()), text)
            // The car refused every place on the stick: saved in its own memory, and said so (#110, 21/11)
            if (w == null) paintStick()
            else if (w.internal) say(getString(R.string.usb_saved_internal, w.path), ok = false)
            else say(getString(R.string.st_log_exported, w.path), ok = true)
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

    /**
     * Row of colour dots (the first five, and the chosen one when it comes from the grid), circled in white when
     * chosen; "More colours…" opens the grid of all 24 (24/11).
     */
    private fun swatches(parent: LinearLayout, colors: IntArray, selected: Int, title: String, onPick: (Int) -> Unit) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val shown = (0 until app.mg4dash.ui.Themes.FIRST_SWATCHES) + listOfNotNull(selected.takeIf { it >= app.mg4dash.ui.Themes.FIRST_SWATCHES })
        shown.forEachIndexed { k, i ->
            r.addView(dot(colors[i], i == selected) { if (i != selected) onPick(i) }, LinearLayout.LayoutParams(dp(56), dp(56)).apply { if (k > 0) leftMargin = dp(14) })
        }
        parent.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        parent.addView(TextView(this).apply {
            text = getString(R.string.st_more_colors) + "  ›"; setTextColor(app.mg4dash.ui.Themes.select(this@SettingsActivity)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(14), 0); app.mg4dash.ui.Rows.touchable(this)
            setOnClickListener { colorGrid(colors, selected, title, onPick) }
        }, LinearLayout.LayoutParams(-2, dp(56)).apply { topMargin = dp(4); bottomMargin = dp(6) })
    }

    private fun dot(c: Int, chosen: Boolean, onClick: () -> Unit) = View(this).apply {
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(c)
            setStroke(dp(if (chosen) 4 else 1), if (chosen) color(R.color.text_value) else 0xFF4A5366.toInt())
        }
        contentDescription = String.format("#%06X", c and 0xFFFFFF)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** All 24 colours in the settings' window, six by row: a touch picks one and closes it. */
    private fun colorGrid(colors: IntArray, selected: Int, title: String, onPick: (Int) -> Unit) {
        lateinit var d: android.app.AlertDialog
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
        colors.toList().chunked(6).forEachIndexed { rowIndex, line ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            line.forEachIndexed { k, c ->
                val i = rowIndex * 6 + k
                r.addView(dot(c, i == selected) { d.dismiss(); if (i != selected) onPick(i) }, LinearLayout.LayoutParams(dp(72), dp(72)).apply { setMargins(dp(14), dp(10), dp(14), dp(10)) })
            }
            grid.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        val group = app.mg4dash.ui.Rows.group(this).apply { addView(grid, LinearLayout.LayoutParams(-1, -2)) }
        d = app.mg4dash.ui.Windows.show(this, title, group, widthDp = 820)
    }

    // ── Petites briques d'UI ──

    /** Entry of a text (address, masked token) or a number. */
    private fun ask(title: String, initial: String, secret: Boolean, number: Boolean = false, onOk: (String) -> Unit) =
        app.mg4dash.ui.Ask.show(this, title, initial, secret, number, onOk)

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
                app.mg4dash.ui.Windows.confirm(this, getString(R.string.st_web_engine_revert_ok), getString(R.string.st_web_engine_revert_confirm), getString(R.string.st_web_engine_revert_ok), danger = false) {
                    busy = true; we.revert(this) { finished(it) }
                }
                return@setOnClickListener
            }
            if (app.mg4dash.system.DataUsage.updatesBlocked(this)) { state.text = getString(R.string.du_updates_wait); return@setOnClickListener }
            app.mg4dash.ui.Windows.confirm(this, getString(R.string.st_update_install), getString(R.string.st_web_engine_confirm, we.VERSION, we.SIZE / 1_000_000), getString(R.string.st_update_install), danger = false) {
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
        }
    }

    /**
     * The speed camera alerter's own page (the owner's mockups, 17/11), opened by a long press on its card, apart from the
     * settings: two tabs (Alertes, Données) built by [app.mg4dash.radar.RadarPage].
     */
    /** Home Assistant's page (24/11): the address, the token, a test and the import from a USB stick. */
    private fun haPage() {
        val bd = app.mg4dash.ui.Dock.sized(this).resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * bd).toInt(), (12 * bd).toInt(), (16 * bd).toInt(), (12 * bd).toInt()) }
        val vertical = app.mg4dash.ui.Dock.vertical(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "Home Assistant"; setTextColor(color(R.color.text_value))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (vertical) dp(12) else dp(app.mg4dash.ui.Kit.fogSafeDp(this@SettingsActivity) - 8) })
        top.addView(app.mg4dash.ui.Rows.closeButton(this) { finish() })
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val parent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(parent, LinearLayout.LayoutParams(0, -2, 1f))
        cols.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        root.addView(ScrollView(this).apply { addView(cols) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val ha = panel(parent, "HOME ASSISTANT")
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

        val dock = LinearLayout(this)
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(12) })
        app.mg4dash.ui.Dock.mount(this, root, dock, app.mg4dash.ui.Dock.Page.SETTINGS)
        setContentView(root)
        app.mg4dash.ui.FadeOutline.applyTo(root)
    }

    /**
     * Smart brightness (20/11, mockup v3): compact lines, the text on the left and small buttons on the right.
     * The automatic mode, Android's own (default) or the smart one; the learnt curve, shown on demand for the dark or
     * the light look and edited by hand; a long press on the graph erases this curve or both of this profile.
     */
    private fun brightnessSetting(p: LinearLayout) {
        val smart = app.mg4dash.brightness.SmartBrightness
        // The same buttons as everywhere (24/11: they were smaller here)
        fun small(v: TextView) = v.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.BUTTON_SP); minWidth = 0; minimumWidth = 0
            setPadding(dp(20), 0, dp(20), 0)
        }
        /** A line: [title] and [sub] on the left, [views] (40 dp high) on the right; returns the sub text. */
        fun line(parent: LinearLayout, title: String?, sub: String?, vararg views: View): TextView {
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(app.mg4dash.ui.Rows.ROW_DP); setPadding(0, dp(12), 0, dp(12)) }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            title?.let { texts.addView(TextView(this).apply { text = it; setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.TITLE_SP); setTextColor(color(R.color.text_value)) }) }
            val s = TextView(this).apply {
                text = sub ?: ""; setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.SUB_SP); setTextColor(color(R.color.text_sub))
                visibility = if (sub == null) View.GONE else View.VISIBLE
            }
            texts.addView(s)
            l.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(12) })
            views.forEach { v -> l.addView(v, LinearLayout.LayoutParams(-2, dp(app.mg4dash.ui.Rows.CONTROL_DP)).apply { leftMargin = dp(8) }) }
            parent.addView(l, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            return s
        }
        // The mode
        val modeBtns = choiceButtons(listOf(getString(R.string.br_original), getString(R.string.br_smart)), { if (smart.enabled(this)) 1 else 0 }) { i ->
            smart.setEnabled(this, i == 1)
        }.onEach { small(it) }
        androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_smart)?.mutate()?.let { star ->
            star.setTint(color(R.color.text_value)); star.setBounds(0, 0, dp(18), dp(18))
            modeBtns[1].text = android.text.SpannableString("*" + getString(R.string.br_smart)).apply {
                setSpan(app.mg4dash.ui.CenteredIconSpan(star, dp(6)), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        line(p, getString(R.string.br_auto), getString(R.string.br_auto_desc), *modeBtns)
        // The learnt curve: the current look's at first, the other one by Sombre | Clair (its own curve, edited too)
        val activeLight = smart.isLight(this)
        var shownLight = activeLight
        fun countText() = getString(R.string.br_curve_counts, smart.curve(this, false).points.size, smart.curve(this, true).points.size)
        lateinit var deleteBtn: View
        lateinit var view: app.mg4dash.brightness.CurveView
        val looks = choiceButtons(listOf(getString(R.string.br_look_dark), getString(R.string.br_look_light)).mapIndexed { i, s ->
            if ((i == 1) == activeLight) "$s  •" else s   // the active look's mark
        }, { if (shownLight) 1 else 0 }) { i -> shownLight = i == 1; view.set(smart.curve(this, shownLight)) }.onEach { small(it) }
        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        looks.forEachIndexed { i, b -> seg.addView(b, LinearLayout.LayoutParams(-2, -1).apply { if (i > 0) leftMargin = dp(4) }) }
        lateinit var showBtn: Button
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        showBtn = small(button(getString(R.string.br_curve_show)) {
            val open = box.visibility != View.VISIBLE
            box.visibility = if (open) View.VISIBLE else View.GONE
            seg.visibility = box.visibility
            showBtn.text = getString(if (open) R.string.br_curve_hide else R.string.br_curve_show)
            if (open) view.set(smart.curve(this, shownLight))
        }) as Button
        val count = line(p, getString(R.string.br_curve), countText(), seg, showBtn)
        view = app.mg4dash.brightness.CurveView(this, smart.curve(this, shownLight), { smart.levelNow(this) },
            onChange = { c -> smart.save(this, c, shownLight); count.text = countText() },
            onSelect = { on -> deleteBtn.isEnabled = on; deleteBtn.alpha = if (on) 1f else 0.4f })
        view.onLongPress = {
            app.mg4dash.ui.SourceMenu.ask(this, view, getString(R.string.br_erase_title), getString(R.string.br_erase_msg),
                listOf(getString(R.string.br_erase_this), getString(R.string.br_erase_all))) { i ->
                if (i == 0) smart.resetLook(this, shownLight) else smart.reset(this, all = true)
                view.set(smart.curve(this, shownLight)); count.text = countText()
            }
        }
        box.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        deleteBtn = small(button(getString(R.string.br_point_delete)) { view.deleteSelected() }).apply { isEnabled = false; alpha = 0.4f }
        line(box, null, getString(R.string.br_curve_hint), deleteBtn)
        p.addView(box, LinearLayout.LayoutParams(-1, -2))
    }
    /** Settings › Home › Cards offered: a box per card, all ticked at first; half of them in each column. */
    private fun cardsOffered() {
        // By their names, accents sorted with their letter (Énergie with E), in three columns (24/11)
        val collator = java.text.Collator.getInstance(resources.configuration.locales[0]).apply { strength = java.text.Collator.PRIMARY }
        val cards = app.mg4dash.ui.HomeSlots.Card.entries.sortedWith { x, y -> collator.compare(getString(x.label), getString(y.label)) }
        val popup = app.mg4dash.ui.OptionsPopup(this, getString(R.string.st_cards_offered).trimEnd('…', '.', ' '), R.drawable.ic_apps)
            .note(getString(R.string.st_cards_offered_note))
        val per = (cards.size + 2) / 3
        cards.forEachIndexed { i, card ->
            if (i > 0 && i % per == 0) popup.column()
            popup.check(getString(card.label), { card.id !in prefs.cardsOff }) { on ->
                prefs.cardsOff = if (on) prefs.cardsOff - card.id else prefs.cardsOff + card.id
            }
        }
        popup.show()
    }

    private fun panel(parent: LinearLayout, title: String, fill: Boolean = false): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            elevation = dp(6).toFloat()   // marked as a panel for the outline style (#40)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        p.addView(TextView(this).apply {
            text = title.uppercase(); setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.HEADER_SP)
            letterSpacing = 0.14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        parent.addView(p, LinearLayout.LayoutParams(-1, if (fill) -1 else -2).apply { bottomMargin = dp(12) })
        return p
    }

    private fun text(parent: LinearLayout, value: String, sp: Float, colorRes: Int): TextView =
        TextView(this).apply {
            // The rows' sizes everywhere (24/11): 20 sp for a title or a value, 15 sp for a description
            text = value; setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.textSp(sp))
            setPadding(0, dp(6), 0, dp(2))
        }.also { parent.addView(it) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            // White text on every button, the close one too: no accent colour in the settings (#98)
            text = label; isAllCaps = false; setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.BUTTON_SP)
            setBackgroundResource(R.drawable.bg_button); minHeight = dp(app.mg4dash.ui.Rows.CONTROL_DP); setPadding(dp(20), 0, dp(20), 0)
            stateListAnimator = null; elevation = 0f   // no relief or shadow (Material style), like the other buttons
            setOnClickListener { onClick() }
        }

    /**
     * Yes / no option on one line: label (the ": %1$s" of the string is removed) and optional
     * description on the left, a single button on the right showing YES or NO, highlighted when the option is on.
     */
    /** Returns what is needed to repaint the button, for a choice confirmed later in a window. */
    private fun toggle(parent: LinearLayout, labelRes: Int, descRes: Int?, get: () -> Boolean, set: (Boolean) -> Unit): () -> Unit {
        val sw = app.mg4dash.ui.SwitchView(this).apply { on = get() }
        val line = app.mg4dash.ui.Rows.row(this, getString(labelRes, "").trimEnd(' ', ':', ' ', ' '), descRes?.let { getString(it) }, sw) {
            set(!get()); sw.on = get()
        }
        app.mg4dash.ui.Rows.add(parent, line)
        return { sw.on = get() }
    }

    /** Single choice buttons: the active one is coloured, and the colour follows the choice at once. */
    /** "Label — drop-down menu" line (same menu as the card's sources). */
    private fun dropdown(parent: LinearLayout, label: String, labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): TextView {
        val drop = app.mg4dash.ui.Rows.value(this, labels[current().coerceIn(0, labels.lastIndex)])
        val line = app.mg4dash.ui.Rows.row(this, label, null, drop) {
            app.mg4dash.ui.SourceMenu.choose(this, drop, labels, current()) { onPick(it); drop.text = labels[it] + "  ▾" }
        }
        app.mg4dash.ui.Rows.add(parent, line)
        return drop
    }

    /** A tab's name in the banner: grey, white and underlined in the theme's colour when chosen. */
    private fun tabLabel(label: String): TextView = TextView(this).apply {
        val line = android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(app.mg4dash.ui.Themes.select(this@SettingsActivity)))).apply {
            setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL); setLayerHeight(0, dp(3))
        }
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_selected), line)
            addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setPadding(dp(8), 0, dp(8), 0)
        setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(color(R.color.text_value), color(R.color.text_sub))))
        isClickable = true
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
