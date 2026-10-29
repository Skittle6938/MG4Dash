package app.mg4dash.settings

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import app.mg4dash.ui.Fullscreen
import java.util.Locale

/**
 * Paramètres du launcher, ouverts par un appui long de 3 s sur l'heure.
 * Contient le mode Valet / Entretien : le launcher SAIC redevient l'accueil par défaut.
 */
class SettingsActivity : AppCompatActivity() {

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
        // Une seule zone qui défile : deux colonnes de catégories, sous un bandeau fixe
        // (titre, journal, fermer) qui laisse la place au bouton antibrouillards.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val vertical = app.mg4dash.ui.Dock.vertical(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = getString(R.string.st_title, BuildConfig.VERSION_NAME); setTextColor(color(R.color.text_value))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (vertical) dp(12) else dp(app.mg4dash.ui.Kit.FOG_SAFE_DP - 8) })
        // Onglets (thèmes de réglages) dans le bandeau, à côté du titre
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tabBar, LinearLayout.LayoutParams(-2, dp(52)).apply { rightMargin = dp(16) })
        top.addView(button(getString(R.string.st_close), R.color.accent_action) { finish() }, LinearLayout.LayoutParams(dp(160), dp(52)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val pages = android.widget.FrameLayout(this)
        root.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val tabViews = mutableListOf<View>(); val tabChips = mutableListOf<TextView>()
        fun showTab(i: Int) {
            tabViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
            tabChips.forEachIndexed { k, c -> c.isSelected = k == i }
            prefs.raw.edit().putInt("settings_tab", i).apply()
        }
        /** Un onglet : deux colonnes de panneaux dans une zone qui défile. */
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
        val (sysL, sysR) = tab(R.string.st_tab_system)
        // Même dock (ou barre verticale) que les autres pages
        val dock = LinearLayout(this)
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(12) })
        app.mg4dash.ui.Dock.mount(this, root, dock, app.mg4dash.ui.Dock.Page.SETTINGS)
        setContentView(root)
        app.mg4dash.ui.FadeOutline.applyTo(root)

        // ════ Onglet Accueil : disposition et carte ════
        val style = panel(homeL, getString(R.string.st_style))
        text(style, getString(R.string.st_style_desc), 16f, R.color.text_title)
        row(style, *styleButtons(listOf(R.string.st_style_full, R.string.st_style_simple, R.string.st_style_map), { prefs.homeStyle }) { prefs.homeStyle = it })
        toggle(style, R.string.st_map_vdock, null, { prefs.verticalDock }) { prefs.verticalDock = it; app.mg4dash.ui.Themes.publish(this); recreate() }
        toggle(style, R.string.st_wide_bar, R.string.st_wide_bar_desc, { prefs.wideBar }) { prefs.wideBar = it; recreate() }
        toggle(style, R.string.st_fullscreen, null, { prefs.fullscreen }) { prefs.fullscreen = it; Fullscreen.apply(this) }
        toggle(style, R.string.st_energy_card_power, R.string.st_energy_card_power_desc, { prefs.energyCardPower }) { prefs.energyCardPower = it }
        toggle(style, R.string.st_power_va, R.string.st_power_va_desc, { prefs.powerVa }) { prefs.powerVa = it }
        toggle(style, R.string.st_stack_auto, null, { prefs.stackAuto }) { prefs.stackAuto = it }
        // Source audio : menu déroulant (même style que les menus de la carte)
        val src = panel(homeR, getString(R.string.st_audio_source).uppercase())
        val srcLabels = listOf(getString(R.string.st_src_auto), getString(R.string.st_src_car), "Radio", "Bluetooth", "USB", "CarPlay / Android Auto")
        dropdown(src, getString(R.string.st_start_source), srcLabels, { prefs.startSource }) { prefs.startSource = it }
        text(src, getString(R.string.st_start_source_desc), 16f, R.color.text_title)
        // Lecture au démarrage (remplace la reprise automatique de Radio Dash)
        dropdown(src, getString(R.string.st_start_playback),
            listOf(getString(R.string.st_play_resume), getString(R.string.st_play_pause), getString(R.string.st_play_play)), { prefs.startPlayback }) { prefs.startPlayback = it }
        // Bouton Android Auto de la barre : l'appli qu'il ouvre (l'appui long coupe sa connexion)
        val aaLabels = listOf(getString(R.string.dock_aa_stock), getString(R.string.dock_cp_stock), getString(R.string.dock_other_app))
        fun aaIndex(): Int = when (val t = prefs.dockAaTarget) { null -> 0; app.mg4dash.system.Apps.CARPLAY.flattenToString() -> 1; else -> 2 }
        dropdown(src, getString(R.string.st_aa_button), aaLabels, { aaIndex() }) { i ->
            fun apply(target: String?) {
                // Changement d'appli : on rétablit d'abord la connexion coupée de l'ancienne
                app.mg4dash.system.ConnectionToggle.restore(this)
                prefs.dockAaTarget = target
                recreate()   // la barre (bouton Android Auto) prend la nouvelle appli
            }
            when (i) {
                0 -> apply(null)
                1 -> apply(app.mg4dash.system.Apps.CARPLAY.flattenToString())
                else -> app.mg4dash.shortcuts.Shortcuts.pickApp(this, getString(R.string.dock_aa_pick)) { cn -> if (cn != null) apply(cn.flattenToString()) }
            }
        }
        text(src, getString(R.string.st_aa_button_desc), 16f, R.color.text_title)
        toggle(src, R.string.st_usb_kick, R.string.st_usb_kick_desc, { prefs.usbKick }) { prefs.usbKick = it }
        toggle(src, R.string.st_bt_projection, R.string.st_bt_projection_desc, { prefs.btDuringProjection }) { prefs.btDuringProjection = it }
        val map = panel(homeR, getString(R.string.st_style_map).uppercase())
        text(map, getString(R.string.st_map_zoom), 18f, R.color.text_value)
        row(map, *choiceButtons(listOf("14", "15", "16", "17", "18"), { prefs.mapZoom - 14 }) { prefs.mapZoom = 14 + it })
        text(map, getString(R.string.st_map_theme), 18f, R.color.text_value)
        row(map, *styleButtons(listOf(R.string.st_mode_auto, R.string.st_mode_light, R.string.st_mode_dark), { prefs.mapTheme }) { prefs.mapTheme = it })
        toggle(map, R.string.st_map_auto_hide, R.string.st_map_auto_hide_desc, { prefs.mapAutoHide }) { prefs.mapAutoHide = it }
        toggle(map, R.string.st_shortcuts_page, R.string.st_shortcuts_page_desc, { prefs.shortcutsPage }) { prefs.shortcutsPage = it }
        toggle(map, R.string.st_map_offline_switch, R.string.st_map_offline_switch_desc, { prefs.mapOfflineSwitch }) { prefs.mapOfflineSwitch = it }

        // ════ Onglet Véhicule : unités, équipements, climatisation ════
        val units = panel(carL, getString(R.string.st_tab_car).uppercase())
        text(units, getString(R.string.st_units), 18f, R.color.text_value)
        row(units, *choiceButtons(listOf(getString(R.string.st_units_car), "km · kWh/100 km", "miles · mi/kWh"), { prefs.units }) { prefs.units = it })
        text(units, getString(R.string.st_pressure), 18f, R.color.text_value)
        row(units, *choiceButtons(listOf(getString(R.string.st_mode_auto_short), "bar", "psi", "kPa"), { prefs.pressureUnit }) { prefs.pressureUnit = it })
        text(units, getString(R.string.st_heated), 18f, R.color.text_value)
        row(units, *choiceButtons(listOf(getString(R.string.st_mode_auto_short), getString(R.string.yes), getString(R.string.no)), { prefs.heatedSeats }) { prefs.heatedSeats = it })
        toggle(units, R.string.st_climhold, R.string.st_climhold_desc, { prefs.climateHoldEnabled }) { prefs.climateHoldEnabled = it }
        if (app.mg4dash.system.Firmware.family == app.mg4dash.system.Firmware.Family.LEGACY)
            toggle(units, R.string.st_hide_saic_climate, R.string.st_hide_saic_climate_desc, { prefs.hideSaicClimate }) { prefs.hideSaicClimate = it }

        // ════ Onglet Apparence : thème et effets ════
        val theme = panel(lookL, getString(R.string.st_theme))
        text(theme, getString(R.string.st_theme_mode), 18f, R.color.text_value)
        row(theme, *styleButtons(listOf(R.string.st_mode_auto, R.string.st_mode_dark, R.string.st_mode_light), { prefs.themeMode }) {
            if (prefs.themeMode != it) { prefs.themeMode = it; onThemeChanged() }
        })
        toggle(theme, R.string.st_theme_smooth, R.string.st_theme_smooth_desc, { prefs.themeSmooth }) { prefs.themeSmooth = it }
        text(theme, getString(R.string.st_clock), 18f, R.color.text_value)
        row(theme, *styleButtons(listOf(R.string.st_clock_24, R.string.st_clock_12), { if (prefs.clock12) 1 else 0 }) { prefs.clock12 = it == 1 })
        text(theme, getString(R.string.st_panel_outline), 18f, R.color.text_value)
        row(theme, *styleButtons(listOf(R.string.st_panel_fade, R.string.st_panel_full), { if (prefs.panelFade) 0 else 1 }) {
            if (prefs.panelFade != (it == 0)) { prefs.panelFade = it == 0; recreate() }
        })
        text(theme, getString(R.string.st_theme_bg), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.BACKGROUND_SWATCHES, prefs.themeBackground) { prefs.themeBackground = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_accent), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.ACCENT_SWATCHES, prefs.themeAccent) { prefs.themeAccent = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_desc), 16f, R.color.text_title)

        // ── Énergie ──
        val energy = panel(carR, getString(R.string.title_energy))
        val cap = text(energy, "", 18f, R.color.text_value)
        val capSrc = text(energy, "", 16f, R.color.text_title)
        fun showCap() {
            cap.text = getString(R.string.st_capacity, String.format(Locale.getDefault(), "%.1f", prefs.batteryKwh))
            val car = app.mg4dash.energy.BatteryCapacity.fromCar()
            val est = app.mg4dash.energy.BatteryCapacity.estimate(this)
            capSrc.text = when {
                prefs.batteryKwhManual -> getString(R.string.st_cap_manual)
                car != null -> getString(R.string.st_cap_car, String.format(Locale.getDefault(), "%.1f", car))
                est != null -> getString(R.string.st_cap_est, String.format(Locale.getDefault(), "%.1f", est.first), est.second)
                else -> app.mg4dash.energy.BatteryCapacity.progress(this).provisional
                    ?.let { getString(R.string.st_cap_est_current, String.format(Locale.getDefault(), "%.1f", it)) }
                    ?: getString(R.string.st_cap_none)
            }
        }
        showCap()
        row(energy,
            button("− 0.5 kWh") { prefs.batteryKwhManual = true; prefs.batteryKwh = (prefs.batteryKwh - 0.5f).coerceAtLeast(30f); showCap() },
            button("+ 0.5 kWh") { prefs.batteryKwhManual = true; prefs.batteryKwh = (prefs.batteryKwh + 0.5f).coerceAtMost(100f); showCap() },
            button(getString(R.string.st_cap_auto)) { prefs.batteryKwhManual = false; app.mg4dash.energy.BatteryCapacity.apply(this); showCap() },
        )
        text(energy, getString(R.string.st_capacity_desc), 16f, R.color.text_title)
        // Modèle de batterie : référence de la santé estimée (affichée sur la page Véhicule)
        text(energy, getString(R.string.st_battery_model), 18f, R.color.text_value)
        val models = app.mg4dash.energy.BatteryCapacity.MODELS.map { it.first }
        row(energy, *choiceButtons(models.map { "$it kWh" }, { models.indexOf(prefs.batteryModel) }) { prefs.batteryModel = models[it] })
        toggle(energy, R.string.st_xpower, R.string.st_xpower_desc, { prefs.xpower }) { prefs.xpower = it }
        toggle(energy, R.string.st_cc_stopped, R.string.st_cc_stopped_desc, { prefs.raw.getBoolean("cc_stopped", true) }) { prefs.raw.edit().putBoolean("cc_stopped", it).apply() }
        powerFxSetting(panel(lookR, getString(R.string.sw_power)))

        // ════ Colonne droite : système, connexions, diagnostic ════

        // ── Écran d'accueil de la voiture et démarrage ──
        val valet = panel(sysL, getString(R.string.st_home))
        homeState = text(valet, "", 19f, R.color.text_sub)
        text(valet, getString(R.string.st_home_desc), 16f, R.color.text_title)
        row(valet,
            button(getString(R.string.st_replace), R.color.accent_action) { confirmReplace() },
            button(getString(R.string.st_valet), R.color.accent_heat) { confirmValet() },
        )
        toggle(valet, R.string.st_warm, R.string.st_warm_desc, { prefs.warmUpStock }) { prefs.warmUpStock = it }
        text(valet, getString(R.string.st_adb), 14f, R.color.text_title)

        // ── Mise à jour depuis GitHub ──
        val upd = panel(sysR, getString(R.string.st_update))
        val updState = text(upd, getString(R.string.st_update_installed, BuildConfig.VERSION_NAME), 18f, R.color.text_value)
        text(upd, getString(R.string.st_update_desc), 16f, R.color.text_title)
        fun install(r: app.mg4dash.system.Updater.Release) {
            lifecycleScope.launch {
                try {
                    val apk = withContext(Dispatchers.IO) {
                        app.mg4dash.system.Updater.download(this@SettingsActivity, r) { pct ->
                            runOnUiThread { updState.text = getString(R.string.st_update_downloading, pct) }
                        }
                    }
                    updState.text = getString(R.string.st_update_installing)
                    withContext(Dispatchers.IO) { app.mg4dash.system.Updater.install(this@SettingsActivity, apk) }
                } catch (e: Exception) {
                    Log.w("Update", "failed: ${e.message}")
                    updState.text = getString(R.string.st_update_error)
                }
            }
        }
        row(upd, button(getString(R.string.st_update_check), R.color.accent_action) {
            updState.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                var failed = false
                val r = withContext(Dispatchers.IO) { try { app.mg4dash.system.Updater.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("Update", "${e.message}"); failed = true; null } }
                val newer = r?.takeIf { app.mg4dash.system.Updater.isNewer(it.version) }
                updState.text = when {
                    failed -> getString(R.string.st_update_error)
                    newer != null -> getString(R.string.st_update_available, BuildConfig.VERSION_NAME, r.version)
                    else -> getString(R.string.st_update_uptodate, BuildConfig.VERSION_NAME)
                }
                // Nouvelle version : notes de la release et choix Installer / Annuler
                if (newer != null && !isFinishing) {
                    val notes = ScrollView(this@SettingsActivity).apply {
                        setPadding(dp(24), dp(8), dp(24), 0)
                        addView(TextView(this@SettingsActivity).apply {
                            text = app.mg4dash.system.Updater.plainNotes(newer.notes); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                            setTextColor(color(R.color.text_value)); setLineSpacing(0f, 1.15f)
                        })
                    }
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.st_update_available, BuildConfig.VERSION_NAME, newer.version))
                        .setView(notes)
                        .setPositiveButton(R.string.st_update_install) { _, _ -> install(newer) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        })

        toggle(upd, R.string.st_update_beta, null, { prefs.updateBeta }) { prefs.updateBeta = it }
        radioDashUpdate(upd)

        // ── Météo ──
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
            // Ville de repli = position actuelle (#7) ; nom de ville déjà trouvé par la météo s'il existe
            val loc = app.mg4dash.map.LocationRepo.location.value
            if (loc == null) {
                app.mg4dash.ui.Toasts.show(this, R.string.st_no_location, long = true)
            } else {
                prefs.weatherLat = loc.latitude.toFloat(); prefs.weatherLon = loc.longitude.toFloat()
                prefs.weatherCity = WeatherRepo.weather.value?.place?.takeIf { it.isNotBlank() } ?: getString(R.string.weather_here)
                showCity(); WeatherRepo.refreshNow(this)
            }
        })
        // Modèle météo (#67) : un autre modèle peut mieux coller au temps observé selon la région
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
            button(getString(R.string.st_ha_test), R.color.accent_action) {
                app.mg4dash.shortcuts.Shortcuts.testHa(this) { ok ->
                    app.mg4dash.ui.Toasts.show(this, if (ok) R.string.st_ha_ok else R.string.ha_error, long = true)
                }
            },
        )
        // #63 : clavier sans « coller » (SWI68) : adresse et jeton lus dans un fichier texte sur la clé USB
        row(ha, button(getString(R.string.st_ha_import)) { importHa { showHa() } })
        text(ha, getString(R.string.st_ha_import_desc), 14f, R.color.text_title)

        // ── Diagnostic : compatibilité, journal, enregistreur ──
        val diag = panel(sysL, getString(R.string.st_diag))
        text(diag, getString(R.string.st_compat_fw, app.mg4dash.system.Firmware.version, app.mg4dash.system.Firmware.gen.name), 18f, R.color.text_value)
            .apply { setOnLongClickListener { sprChrgTest(); true } }   // test caché, réservé aux essais
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

        refreshHome()
        showTab(prefs.raw.getInt("settings_tab", 0).coerceIn(0, tabViews.size - 1))
    }

    /** Mode auto : bascule jour / nuit aussi pendant que les réglages sont ouverts. */
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
     * Test caché de la propriété BMS « SPR_CHRG » (rôle inconnu, peut-être une charge rapide) :
     * lecture, écriture d'une valeur choisie, relecture 2 s plus tard. Tout est noté dans le journal.
     */
    /**
     * Tests cachés (appui long sur la ligne du firmware) : propriétés de charge écrites directement,
     * pour valider les voies de secours des anciens firmwares. Lecture, écriture, relecture 2 s après.
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
            .setPositiveButton(R.string.st_close, null).show()
    }

    /** Radio Dash : seulement sur le firmware et l'appli radio pour lesquels elle est construite. */
    private fun radioDashUpdate(parent: LinearLayout) {
        val R2 = app.mg4dash.system.RadioDashUpdater
        if (!R2.compatible(this)) return
        val state = text(parent, "", 18f, R.color.text_value).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14) }
        fun showState() {
            state.text = getString(R.string.st_rd_state, when {
                !R2.installed(this) -> getString(R.string.st_rd_none)
                else -> R2.installedVersion(this) ?: getString(R.string.st_rd_unknown)
            })
        }
        showState()
        text(parent, getString(R.string.st_rd_desc), 16f, R.color.text_title)
        row(parent, button(getString(R.string.st_rd_check), R.color.accent_action) {
            state.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) { try { R2.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("Update", "Radio Dash: ${e.message}"); null } }
                if (r == null) { state.text = getString(R.string.st_update_error); return@launch }
                val cur = R2.installedVersion(this@SettingsActivity)
                if (cur != null && !app.mg4dash.system.Updater.isNewer(r.version, cur)) { state.text = getString(R.string.st_rd_uptodate, cur); return@launch }
                showState()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(getString(R.string.st_rd_available, r.version))
                    .setMessage(app.mg4dash.system.Updater.plainNotes(r.notes))
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
                                        .setPositiveButton(android.R.string.ok, null).show()
                                } }
                                withContext(Dispatchers.IO) { R2.install(this@SettingsActivity, apk, r.version) }
                            } catch (e: Exception) {
                                Log.w("Update", "Radio Dash: ${e.message}")
                                state.text = getString(R.string.st_update_error)
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        })
    }

    /** Animation pleine puissance du widget Puissance en direct : liste déroulante, aperçu sur une mini carte. */
    private fun powerFxSetting(parent: LinearLayout) {
        text(parent, getString(R.string.st_power_fx), 18f, R.color.text_value)
        // Pas d'aperçu : la surprise est pour la route. Ordre affiché : aléatoire d'abord (par défaut).
        val fx = app.mg4dash.ui.PowerFx
        val choices = listOf(fx.RANDOM to R.string.fx_random, fx.LIGHTNING to R.string.fx_lightning,
            fx.FLAMES to R.string.fx_flames, fx.NONE to R.string.fx_none).map { it.first to getString(it.second) }
        lateinit var pick: Button
        // Liste déroulante dans la page même : une fenêtre surgissante ferait réapparaître les barres système
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
        // Sous le bouton de choix seulement (moitié gauche de la ligne)
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
            .show()
    }

    private fun confirmValet() {
        AlertDialog.Builder(this)
            .setTitle(R.string.st_valet)
            .setMessage(R.string.st_valet_msg)
            .setPositiveButton(R.string.activate) { _, _ ->
                val ok = HomeSwitcher.switchToStock(this)
                // Connexion du téléphone coupée par un appui long : rétablie pour la concession
                app.mg4dash.system.ConnectionToggle.restore(this)
                Log.i("Valet", "Maintenance mode → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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

    /** Journal dans une fenêtre, à la demande. */
    private fun showLogDialog() {
        val view = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
            text = Log.snapshot().joinToString("\n")
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val d = AlertDialog.Builder(this)
            .setTitle(getString(R.string.st_log) + " · MG4Dash ${BuildConfig.VERSION_NAME} · ${BuildConfig.FLAVOR}")
            .setView(ScrollView(this).apply { addView(view) })
            .setNeutralButton(R.string.st_refresh, null)
            .setPositiveButton(R.string.st_close, null)
            .show()
        d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { view.text = Log.snapshot().joinToString("\n") }
    }

    /** Notes de release GitHub (Markdown) en texte lisible. */
    private fun onThemeChanged() {
        app.mg4dash.ui.Themes.publish(this)
        recreate()
    }

    /** Rangée de pastilles de couleur ; la pastille choisie est entourée de blanc. */
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

    /** Saisie d'un texte (adresse, jeton masqué). */
    private fun ask(title: String, initial: String, secret: Boolean, onOk: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(initial); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(input)
        }
        // Certains claviers (SWI68) n'ont pas « coller » (#63) : bouton dédié si le presse-papiers contient du texte
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
            .show()
    }

    /**
     * Adresse et jeton Home Assistant lus dans mg4dash_ha.txt à la racine d'une clé USB : la ligne qui
     * commence par http est l'adresse, la plus longue des autres le jeton.
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
     * #62 : copie sur la clé USB l'interface système SAIC (et les applis clim SAIC), pour chercher comment
     * empêcher la page clim de passer par-dessus MG4Dash. Lecture seule : rien n'est modifié dans la voiture.
     */
    private fun copySystemUi() {
        val sm = getSystemService(STORAGE_SERVICE) as android.os.storage.StorageManager
        val usb = sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? java.io.File } catch (_: Throwable) { null } }
        if (usb == null) { app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_nousb, long = true); return }
        app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_busy)
        Thread {
            // Racine de la clé non accessible en écriture (beta 10, SWI165 : « No such file or directory ») :
            // dossier propre à MG4Dash sur la clé (Android/data/app.mg4dash/files), toujours autorisé
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
                if (n > 0) app.mg4dash.ui.Toasts.show(this, getString(R.string.st_copy_sysui_done, n) + "
" + out.absolutePath.removePrefix(usb.absolutePath).trimStart('/'), long = true)
                else app.mg4dash.ui.Toasts.show(this, R.string.st_copy_sysui_fail, long = true)
            }
        }.start()
    }

    private fun panel(parent: LinearLayout, title: String, fill: Boolean = false): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            elevation = dp(6).toFloat()   // repéré comme panneau pour le style de contour (#40)
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

    private fun button(label: String, accentRes: Int = R.color.text_value, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; isAllCaps = false; setTextColor(color(accentRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = dp(56); setPadding(dp(20), 0, dp(20), 0)
            stateListAnimator = null; elevation = 0f   // pas de relief ni d'ombre (style Material), comme les autres boutons
            setOnClickListener { onClick() }
        }

    /**
     * Option oui / non sur une ligne : libellé (le « : %1$s » de la chaîne est retiré) et description
     * éventuelle à gauche, un seul bouton à droite qui affiche OUI ou NON et se surligne quand l'option est active.
     */
    private fun toggle(parent: LinearLayout, labelRes: Int, descRes: Int?, get: () -> Boolean, set: (Boolean) -> Unit) {
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
    }

    /** Boutons à choix unique : celui qui est actif est en couleur, et la couleur suit tout de suite le choix. */
    /** Ligne « libellé — menu déroulant » (même menu que les sources de la carte). */
    private fun dropdown(parent: LinearLayout, label: String, labels: List<String>, current: () -> Int, onPick: (Int) -> Unit) {
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
    }

    private fun choiceButtons(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): Array<Button> {
        lateinit var buttons: List<Button>
        fun paint() = buttons.forEachIndexed { i, btn ->
            btn.isSelected = i == current()
        }
        // Même style que les onglets (puces) : texte de la couleur de sélection quand choisi
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
