package app.mg4dash.system

import android.content.Context
import android.content.SharedPreferences

/** Persistent launcher settings. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    /** Usable battery capacity, for the consumption estimate; with no measurement, the chosen model's (51 kWh: 50.8 usable). */
    var batteryKwh: Float
        get() = sp.getFloat("battery_kwh", app.mg4dash.energy.BatteryCapacity.MODELS.firstOrNull { it.first == batteryModel }?.second ?: 50.8f)
        set(v) = sp.edit().putFloat("battery_kwh", v).apply()

    /** Model's original battery, in gross kWh: 51, 64 or 77 (battery health reference). */
    var batteryModel: Int
        get() = sp.getInt("battery_model", 51)
        set(v) = sp.edit().putInt("battery_model", v).apply()

    /** Map layout: vertical bar on the left instead of the bottom dock (issue #6). */
    var verticalDock: Boolean
        get() = sp.getBoolean("vertical_dock", false)
        set(v) = sp.edit().putBoolean("vertical_dock", v).apply()

    /** Vertical bar twice as wide, larger icons (#67). */
    var wideBar: Boolean
        get() = sp.getBoolean("wide_bar", false)
        set(v) = sp.edit().putBoolean("wide_bar", v).apply()

    /** Map layout: without the widget titles (issue #6). */
    /** Consumption widget period: 0 = since the charge, 1 = current trip, 2 = last 1,000 km. */
    var consumptionPeriod: Int
        get() = sp.getInt("consumption_period", 0)
        set(v) = sp.edit().putInt("consumption_period", v).apply()

    /** Tyre pressure: 0 = auto (psi with miles, otherwise bar), 1 = bar, 2 = psi, 3 = kPa. */
    var pressureUnit: Int
        get() = sp.getInt("pressure_unit", 0)
        set(v) = sp.edit().putInt("pressure_unit", v).apply()

    /** Intermittent A/C (#34): compressor on and off durations, in seconds. */
    var acCycleOnSec: Int
        get() = sp.getInt("ac_cycle_on", 300)
        set(v) = sp.edit().putInt("ac_cycle_on", v.coerceIn(30, 1800)).apply()
    var acCycleOffSec: Int
        get() = sp.getInt("ac_cycle_off", 300)
        set(v) = sp.edit().putInt("ac_cycle_off", v.coerceIn(30, 1800)).apply()
    /** Intermittent A/C turned on by the driver: stays active from one trip to the next until stopped (#61). */
    var acCycleOn: Boolean
        get() = sp.getBoolean("ac_cycle_active", false)
        set(v) = sp.edit().putBoolean("ac_cycle_active", v).apply()

    /** Calibration of the displayed set point, in degrees (−5 to +5): display only, the car receives its value. */
    var tempOffset: Int
        get() = sp.getInt("temp_offset", 0).coerceIn(-5, 5)
        set(v) = sp.edit().putInt("temp_offset", v.coerceIn(-5, 5)).apply()

    /** Map: default zoom (14 to 18) and theme (0 = like the app, 1 = light, 2 = dark). */
    var mapZoom: Int
        get() = sp.getInt("map_zoom", 16)
        set(v) = sp.edit().putInt("map_zoom", v).apply()
    var mapTheme: Int
        get() = sp.getInt("map_theme", 0)
        set(v) = sp.edit().putInt("map_theme", v).apply()
    /** With no connection (and no cached tiles), the map gives way to the energy widget (#15). */
    /** Zoom +, zoom − and full screen buttons hidden, shown for a few seconds on touching the map (#15). */
    var mapAutoHide: Boolean
        get() = sp.getBoolean("map_auto_hide", false)
        set(v) = sp.edit().putBoolean("map_auto_hide", v).apply()
    /** Shortcuts page in the map widget's rotation (#38); unticked: map ↔ consumption. */
    var shortcutsPage: Boolean
        get() = sp.getBoolean("shortcuts_page", true)
        set(v) = sp.edit().putBoolean("shortcuts_page", v).apply()
    /** App shown in the map widget instead of the map (#41, trial); null = OSM map. */
    var mapApp: String?
        get() = sp.getString("map_app", null)
        set(v) = sp.edit().putString("map_app", v).apply()
    /** New capacity entered by hand for the health calculation (#77); 0 = the model's default value. */
    var batteryNewKwh: Float
        get() = sp.getFloat("battery_new_kwh", 0f)
        set(v) = sp.edit().putFloat("battery_new_kwh", v).apply()

    /** Radio Dash installed: DAB and FM as two separate sources on home. */
    var radioDashBands: Boolean
        get() = sp.getBoolean("radio_dash_bands", true)
        set(v) = sp.edit().putBoolean("radio_dash_bands", v).apply()

    /** V2L discharge started on plugging in the adapter (#82), off by default. */
    var v2lAuto: Boolean
        get() = sp.getBoolean("v2l_auto", false)
        set(v) = sp.edit().putBoolean("v2l_auto", v).apply()
    var mapOfflineSwitch: Boolean
        get() = sp.getBoolean("map_offline_switch", true)
        set(v) = sp.edit().putBoolean("map_offline_switch", v).apply()

    /** Heated seats / wheel: 0 = auto (64 / 77 kWh battery), 1 = show, 2 = hide. */
    var heatedSeats: Int
        get() = sp.getInt("heated_seats", 0)
        set(v) = sp.edit().putInt("heated_seats", v).apply()

    /** Units: 0 = like the car, 1 = km, 2 = miles. */
    var units: Int
        get() = sp.getInt("units", 0)
        set(v) = sp.edit().putInt("units", v).apply()

    /** Widget titles: always hidden (less busy, the option was removed). */
    val hideTitles: Boolean get() = true

    /** Map layout weather: 0 = hourly forecast, 1 = daily. */
    var weatherPage: Int
        get() = sp.getInt("weather_page", 0)
        set(v) = sp.edit().putInt("weather_page", v).apply()

    /** Fallback weather position when the vehicle gives no location. */
    var weatherLat: Float
        get() = sp.getFloat("weather_lat", 48.8566f)
        set(v) = sp.edit().putFloat("weather_lat", v).apply()
    var weatherLon: Float
        get() = sp.getFloat("weather_lon", 2.3522f)
        set(v) = sp.edit().putFloat("weather_lon", v).apply()
    /** Open-Meteo weather model (#67): empty = Open-Meteo's automatic choice ("best match"). */
    var weatherModel: String
        get() = sp.getString("weather_model", "") ?: ""
        set(v) = sp.edit().putString("weather_model", v).apply()

    var weatherCity: String
        get() = sp.getString("weather_city", "Paris") ?: "Paris"
        set(v) = sp.edit().putString("weather_city", v).apply()

    /** Keep climate on (long press on the climate power button). Off by default. */
    var climateHoldEnabled: Boolean
        get() = sp.getBoolean("climhold_enabled", false)
        set(v) = sp.edit().putBoolean("climhold_enabled", v).apply()
    /** Maximum keep climate on duration in minutes; 0 = unlimited (only the battery threshold stops it). */
    /** Delay before locking the doors in keep climate on, in seconds (time to get out with the key). */
    var climateHoldLockSec: Int
        get() = sp.getInt("climhold_lock_sec", 20)
        set(v) = sp.edit().putInt("climhold_lock_sec", v).apply()
    /** Keep climate on at a set time (otherwise right away), first cycle time (minutes since midnight), cycles. */
    var climateHoldScheduled: Boolean
        get() = sp.getBoolean("climhold_scheduled", false)
        set(v) = sp.edit().putBoolean("climhold_scheduled", v).apply()
    var climateHoldStartMin: Int
        get() = sp.getInt("climhold_start_min", 18 * 60)
        set(v) = sp.edit().putInt("climhold_start_min", v).apply()
    /** Keep climate on: screen off once the doors are locked (sleeping in the car, discretion). */
    var climateHoldScreenOff: Boolean
        get() = sp.getBoolean("climhold_screen_off", false)
        set(v) = sp.edit().putBoolean("climhold_screen_off", v).apply()
    var climateHoldRuns: Int
        get() = sp.getInt("climhold_runs", 1)
        set(v) = sp.edit().putInt("climhold_runs", v).apply()
    var climateHoldRunMin: Int
        get() = sp.getInt("climhold_run_min", 15)
        set(v) = sp.edit().putInt("climhold_run_min", v).apply()
    var climateHoldGapMin: Int
        get() = sp.getInt("climhold_gap_min", 30)
        set(v) = sp.edit().putInt("climhold_gap_min", v).apply()
    var climateHoldMaxMin: Int
        get() = sp.getInt("climhold_max_min", 0)
        set(v) = sp.edit().putInt("climhold_max_min", v).apply()

    /** MG4 XPower (320 kW): recognised by itself above 350 hp measured, or chosen in the settings. */
    var xpower: Boolean
        get() = sp.getBoolean("xpower", false)
        set(v) = sp.edit().putBoolean("xpower", v).apply()

    /** Full power animation of the Power widget: 0 = none, 1 = rocket, 2 = lightning, 3 = flames, 4 = random. */
    var powerFx: Int
        get() = sp.getInt("power_fx", 4)
        set(v) = sp.edit().putInt("power_fx", v).apply()

    /**
     * "Power" energy card (#29): live power bar in the energy card, without
     * drive mode or KERS; the Power page then leaves the scrolling widget.
     */
    /** Audio source at startup: 0 automatic (default), 1 like the car, 2 radio, 3 Bluetooth, 4 USB, 5 CarPlay / Android Auto. */
    var startSource: Int
        get() = sp.getInt("start_source", 0)
        set(v) = sp.edit().putInt("start_source", v).apply()


    /** Phone connection app disabled by MG4Dash (long press on the Android Auto button). */
    var connectionOff: String?
        get() = sp.getString("connection_off", null)
        set(v) = sp.edit().putString("connection_off", v).apply()

    /** Connection icons on the weather card (#86). */
    var connectionIcons: Boolean
        get() = sp.getBoolean("connection_icons", true)
        set(v) = sp.edit().putBoolean("connection_icons", v).apply()

    /** Play at car start: 0 as when stopped (default), 1 always paused, 2 always playing. */
    var startPlayback: Int
        get() = sp.getInt("start_playback", 0)
        set(v) = sp.edit().putInt("start_playback", v).apply()

    /** Was music playing when the car stopped (for "as when stopped")? */
    var lastPlaying: Boolean
        get() = sp.getBoolean("last_playing", true)
        set(v) = sp.edit().putBoolean("last_playing", v).apply()

    /** Sources chosen by the user, most recent first (automatic mode). */
    var sourceHistory: List<String>
        get() = sp.getString("source_history", "").orEmpty().split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("source_history", v.joinToString(",")).apply()

    /** Power card: voltage and current (current, min / max) under the bar; off by default. */
    var powerVa: Boolean
        get() = sp.getBoolean("power_va", false)
        set(v) = sp.edit().putBoolean("power_va", v).apply()

    var energyCardPower: Boolean
        get() = sp.getBoolean("energy_card_power", false)
        set(v) = sp.edit().putBoolean("energy_card_power", v).apply()

    /** Secondary widget of the right column: 0 = trip, 1 = status, 2 = compact weather. */
    var secondaryWidget: Int
        get() = sp.getInt("secondary_widget", 0)
        set(v) = sp.edit().putInt("secondary_widget", v).apply()

    /** Briefly opens the SAIC launcher at startup so it registers its interface. */

    /** Service mode: the SAIC launcher stays home, MG4Dash does not take back control. */
    var valetMode: Boolean
        get() = sp.getBoolean("valet_mode", false)
        set(v) = sp.edit().putBoolean("valet_mode", v).commit().let { }

    /** Wanted charge limit (%), sent to the car. */
    var chargeLimit: Int
        get() = sp.getInt("charge_limit", 100)
        set(v) = sp.edit().putInt("charge_limit", v).apply()

    /** Hides the SAIC side bar and banner (immersive mode). */
    var fullscreen: Boolean
        get() = sp.getBoolean("fullscreen", true)
        set(v) = sp.edit().putBoolean("fullscreen", v).apply()

    /** Widget central haut : 0 = carte OpenStreetMap, 1 = raccourcis. */
    /** Old SDK: bring MG4Dash back in front when the SAIC launcher opens its climate screen (#48). */
    var hideSaicClimate: Boolean
        get() = sp.getBoolean("hide_saic_climate", true)
        set(v) = sp.edit().putBoolean("hide_saic_climate", v).apply()

    /** Day / night change: true = 5 s wait and fade (default), false = immediate. */
    /** Auto mode: night also between sunset and sunrise, even with lights off (#64); on by default. */
    var nightBySun: Boolean
        get() = sp.getBoolean("night_by_sun", true)
        set(v) = sp.edit().putBoolean("night_by_sun", v).apply()

    /** Vertical bar side: 0 = driver's side (read from the car), 1 = left, 2 = right (#75). */
    var barSide: Int
        get() = sp.getInt("bar_side", 0)
        set(v) = sp.edit().putInt("bar_side", v).apply()

    var themeSmooth: Boolean
        get() = sp.getBoolean("theme_smooth", true)
        set(v) = sp.edit().putBoolean("theme_smooth", v).apply()

    var homeWidget: Int
        get() = sp.getInt("home_widget", 0)
        set(v) = sp.edit().putInt("home_widget", v).apply()

    /** Dock's Android Auto button: component of another app, or null = stock Android Auto. */
    var dockAaTarget: String?
        get() = sp.getString("dock_aa_target", null)
        set(v) = sp.edit().putString("dock_aa_target", v).apply()

    /** Home Assistant: address (e.g. http://homeassistant.local:8123) and long-lived access token. */
    var haUrl: String
        get() = sp.getString("ha_url", "") ?: ""
        set(v) = sp.edit().putString("ha_url", v.trim()).apply()
    var haToken: String
        get() = sp.getString("ha_token", "") ?: ""
        set(v) = sp.edit().putString("ha_token", v.trim()).apply()

    /** Energy data recorder (diagnostic, CSV file). */
    /** "Map" layout: page shown in the stack (0 energy, 1 trip, 2 power, 3 phone). */
    var stackPage: Int
        get() = sp.getInt("stack_page", 0)
        set(v) = sp.edit().putInt("stack_page", v).apply()

    /** Auto stack: goes back to the battery when the car is plugged in. */
    var stackAuto: Boolean
        get() = sp.getBoolean("stack_auto", true)
        set(v) = sp.edit().putBoolean("stack_auto", v).apply()

    /** Dock's left slot: 0 = clock, 1 = Wi-Fi settings. */
    var dockSlot: Int
        get() = sp.getInt("dock_slot", 0)
        set(v) = sp.edit().putInt("dock_slot", v).apply()

    /** Home map oriented to the car's heading (otherwise north up). */
    var mapHeadingUp: Boolean
        get() = sp.getBoolean("map_heading_up", false)
        set(v) = sp.edit().putBoolean("map_heading_up", v).apply()

    /** Update: also offer beta versions (GitHub pre-releases). */
    var updateBeta: Boolean
        get() = sp.getBoolean("update_beta", true)
        set(v) = sp.edit().putBoolean("update_beta", v).apply()

    /** Non-beta release for which the user chose "do not notify again" at startup (version, e.g. 1.1.0). */
    var updateSkipped: String?
        get() = sp.getString("update_skipped", null)
        set(v) = sp.edit().putString("update_skipped", v).apply()

    var energyLog: Boolean
        get() = sp.getBoolean("energy_log", false)   // off by default: a write every 5 s wears the storage
        set(v) = sp.edit().putBoolean("energy_log", v).apply()

    /** Home style: 0 = full (3 columns), 1 = simple (3 panes + 2 shortcuts + weather). */
    var homeStyle: Int
        get() = sp.getInt("home_style", 0)
        set(v) = sp.edit().putInt("home_style", v).apply()

    /** Theme: background (0 navy, 1 black, 2 charcoal, 3 dark green, 4 burgundy) and buttons (0 blue, 1 grey, 2 red, 3 green, 4 orange). */
    var themeBackground: Int
        get() = sp.getInt("theme_bg", 1)   // black by default
        set(v) = sp.edit().putInt("theme_bg", v).commit().let { }
    /** 12 h clock without AM / PM; 24 h by default (#18). */
    var clock12: Boolean
        get() = sp.getBoolean("clock_12", false)
        set(v) = sp.edit().putBoolean("clock_12", v).apply()
    /** Home panel outline: faded downwards (dashboard style, #38) or full (#40). */
    var panelFade: Boolean
        get() = sp.getBoolean("panel_fade", true)
        set(v) = sp.edit().putBoolean("panel_fade", v).apply()
    /** Light / dark: 0 = auto (the car's day / night), 1 = dark, 2 = light. */
    var themeMode: Int
        get() = sp.getInt("theme_mode", 0)
        set(v) = sp.edit().putInt("theme_mode", v).commit().let { }
    var themeAccent: Int
        get() = sp.getInt("theme_accent", 0)
        set(v) = sp.edit().putInt("theme_accent", v).commit().let { }

    val raw: SharedPreferences get() = sp
}
