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
        // Une seule zone qui défile : deux colonnes de catégories, sous un bandeau fixe
        // (titre, journal, fermer) qui laisse la place au bouton antibrouillards.
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val vertical = app.mg4dash.ui.Dock.vertical(this)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = getString(R.string.st_title, BuildConfig.VERSION_NAME); setTextColor(color(R.color.text_value))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = if (vertical) dp(12) else dp(app.mg4dash.ui.Kit.FOG_SAFE_DP - 8) })
        top.addView(button(getString(R.string.st_close), R.color.accent_action) { finish() }, LinearLayout.LayoutParams(dp(200), dp(56)))
        root.addView(top, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        cols.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(cols) }, LinearLayout.LayoutParams(-1, 0, 1f))
        // Même dock (ou barre verticale) que les autres pages
        val dock = LinearLayout(this)
        root.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(12) })
        app.mg4dash.ui.Dock.mount(this, root, dock, app.mg4dash.ui.Dock.Page.SETTINGS)
        setContentView(root)

        // ════ Colonne gauche : ce qu'on voit à l'écran ════

        // ── Accueil : style et options d'affichage ──
        val style = panel(left, getString(R.string.st_style))
        text(style, getString(R.string.st_style_desc), 16f, R.color.text_title)
        row(style, *styleButtons(listOf(R.string.st_style_full, R.string.st_style_simple, R.string.st_style_map), { prefs.homeStyle }) { prefs.homeStyle = it })
        text(style, getString(R.string.st_units), 18f, R.color.text_value)
        row(style, *choiceButtons(listOf(getString(R.string.st_units_car), "km · kWh/100 km", "miles · mi/kWh"), { prefs.units }) { prefs.units = it })
        text(style, getString(R.string.st_heated), 18f, R.color.text_value)
        row(style, *choiceButtons(listOf(getString(R.string.st_mode_auto_short), getString(R.string.yes), getString(R.string.no)), { prefs.heatedSeats }) { prefs.heatedSeats = it })
        text(style, getString(R.string.st_pressure), 18f, R.color.text_value)
        row(style, *choiceButtons(listOf(getString(R.string.st_mode_auto_short), "bar", "psi", "kPa"), { prefs.pressureUnit }) { prefs.pressureUnit = it })
        text(style, getString(R.string.st_map_zoom), 18f, R.color.text_value)
        row(style, *choiceButtons(listOf("14", "15", "16", "17", "18"), { prefs.mapZoom - 14 }) { prefs.mapZoom = 14 + it })
        text(style, getString(R.string.st_map_theme), 18f, R.color.text_value)
        row(style, *styleButtons(listOf(R.string.st_mode_auto, R.string.st_mode_light, R.string.st_mode_dark), { prefs.mapTheme }) { prefs.mapTheme = it })
        toggle(style, R.string.st_map_auto_hide, R.string.st_map_auto_hide_desc, { prefs.mapAutoHide }) { prefs.mapAutoHide = it }
        toggle(style, R.string.st_map_offline_switch, R.string.st_map_offline_switch_desc, { prefs.mapOfflineSwitch }) { prefs.mapOfflineSwitch = it }
        toggle(style, R.string.st_map_vdock, null, { prefs.verticalDock }) { prefs.verticalDock = it; app.mg4dash.ui.Themes.publish(this); recreate() }
        toggle(style, R.string.st_fullscreen, null, { prefs.fullscreen }) { prefs.fullscreen = it; Fullscreen.apply(this) }
        toggle(style, R.string.st_stack_auto, null, { prefs.stackAuto }) { prefs.stackAuto = it }
        toggle(style, R.string.st_climhold, R.string.st_climhold_desc, { prefs.climateHoldEnabled }) { prefs.climateHoldEnabled = it }

        // ── Thème : fond et couleur des boutons ──
        val theme = panel(left, getString(R.string.st_theme))
        text(theme, getString(R.string.st_theme_mode), 18f, R.color.text_value)
        row(theme, *styleButtons(listOf(R.string.st_mode_auto, R.string.st_mode_dark, R.string.st_mode_light), { prefs.themeMode }) {
            if (prefs.themeMode != it) { prefs.themeMode = it; onThemeChanged() }
        })
        text(theme, getString(R.string.st_theme_bg), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.BACKGROUND_SWATCHES, prefs.themeBackground) { prefs.themeBackground = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_accent), 18f, R.color.text_value)
        swatches(theme, app.mg4dash.ui.Themes.ACCENT_SWATCHES, prefs.themeAccent) { prefs.themeAccent = it; onThemeChanged() }
        text(theme, getString(R.string.st_theme_desc), 16f, R.color.text_title)

        // ── Énergie ──
        val energy = panel(left, getString(R.string.title_energy))
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
        powerFxSetting(energy)

        // ════ Colonne droite : système, connexions, diagnostic ════

        // ── Écran d'accueil de la voiture et démarrage ──
        val valet = panel(right, getString(R.string.st_home))
        homeState = text(valet, "", 19f, R.color.text_sub)
        text(valet, getString(R.string.st_home_desc), 16f, R.color.text_title)
        row(valet,
            button(getString(R.string.st_replace), R.color.accent_action) { confirmReplace() },
            button(getString(R.string.st_valet), R.color.accent_heat) { confirmValet() },
        )
        toggle(valet, R.string.st_warm, R.string.st_warm_desc, { prefs.warmUpStock }) { prefs.warmUpStock = it }
        text(valet, getString(R.string.st_adb), 14f, R.color.text_title)

        // ── Mise à jour depuis GitHub ──
        val upd = panel(right, getString(R.string.st_update))
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
                    Log.w("MàJ", "échec : ${e.message}")
                    updState.text = getString(R.string.st_update_error)
                }
            }
        }
        row(upd, button(getString(R.string.st_update_check), R.color.accent_action) {
            updState.text = getString(R.string.st_update_checking)
            lifecycleScope.launch {
                var failed = false
                val r = withContext(Dispatchers.IO) { try { app.mg4dash.system.Updater.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("MàJ", "${e.message}"); failed = true; null } }
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
        val weather = panel(right, getString(R.string.title_weather))
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
                android.widget.Toast.makeText(this, R.string.st_no_location, android.widget.Toast.LENGTH_LONG).show()
            } else {
                prefs.weatherLat = loc.latitude.toFloat(); prefs.weatherLon = loc.longitude.toFloat()
                prefs.weatherCity = WeatherRepo.weather.value?.place?.takeIf { it.isNotBlank() } ?: getString(R.string.weather_here)
                showCity(); WeatherRepo.refreshNow(this)
            }
        })

        // ── Home Assistant ──
        val ha = panel(right, "HOME ASSISTANT")
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
                    android.widget.Toast.makeText(this, if (ok) R.string.st_ha_ok else R.string.ha_error, android.widget.Toast.LENGTH_LONG).show()
                }
            },
        )

        // ── Diagnostic : compatibilité, journal, enregistreur ──
        val diag = panel(right, getString(R.string.st_diag))
        text(diag, getString(R.string.st_compat_fw, app.mg4dash.system.Firmware.version, app.mg4dash.system.Firmware.gen.name), 18f, R.color.text_value)
            .apply { setOnLongClickListener { sprChrgTest(); true } }   // test caché, réservé aux essais
        text(diag, getString(if (app.mg4dash.system.Firmware.tested) R.string.st_compat_tested else R.string.st_compat_beta), 16f, R.color.text_title)
        row(diag,
            button(getString(R.string.st_compat_run)) { Thread { app.mg4dash.car.CarProps.dumpAll() }.start(); Compat.show(this) },
            button(getString(R.string.st_log)) { showLogDialog() },
        )
        toggle(diag, R.string.st_energy_log, R.string.st_energy_log_desc, { prefs.energyLog }) { prefs.energyLog = it }

        refreshHome()
    }

    override fun onResume() {
        super.onResume()
        Fullscreen.apply(this)
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
                Log.i("SPR", "lecture : " + vals.joinToString { "${it.first}=${it.second}" })
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
                val r = withContext(Dispatchers.IO) { try { R2.latest(prefs.updateBeta) } catch (e: Exception) { Log.w("MàJ", "Radio Dash : ${e.message}"); null } }
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
                                Log.w("MàJ", "Radio Dash : ${e.message}")
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
        val choices = listOf(fx.RANDOM to R.string.fx_random, fx.ROCKET to R.string.fx_rocket, fx.LIGHTNING to R.string.fx_lightning,
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
                Log.i("Valet", "remplacement de l'accueil SAIC → $ok")
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
                Log.i("Valet", "mode Entretien → $ok")
                refreshHome(); toastState(ok)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toastState(ok: Boolean) {
        android.widget.Toast.makeText(this, getString(if (ok) R.string.st_home_changed else R.string.st_failed), android.widget.Toast.LENGTH_SHORT).show()
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
        val box = android.widget.FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        android.app.AlertDialog.Builder(this)
            .setTitle(title).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun panel(parent: LinearLayout, title: String, fill: Boolean = false): LinearLayout {
        val p = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
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
            btn.setTextColor(color(if (on) R.color.accent_action else R.color.text_title))
        }
        btn = button("") { set(!get()); paint() }.apply { setBackgroundResource(R.drawable.bg_chip) }
        paint()
        line.addView(btn, LinearLayout.LayoutParams(dp(130), dp(56)))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    /** Boutons à choix unique : celui qui est actif est en couleur, et la couleur suit tout de suite le choix. */
    private fun choiceButtons(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): Array<Button> {
        lateinit var buttons: List<Button>
        fun paint() = buttons.forEachIndexed { i, btn ->
            btn.isSelected = i == current()
            btn.setTextColor(color(if (i == current()) R.color.accent_action else R.color.text_value))
        }
        buttons = labels.mapIndexed { i, l -> button(l) { onPick(i); paint() }.apply { setBackgroundResource(R.drawable.bg_chip) } }
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
