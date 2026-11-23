package app.mg4dash.system

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Profiles (study v4, steps 9 to 12): several drivers, or several ways of driving for one person. Each profile keeps its
 * home, its look and its habits ([isPersonal]); what touches the car and the running of MG4Dash stays shared.
 *
 * All the settings stay in their one file (mg4launcher), read as always: a profile is a copy of its personal part, kept
 * in this object's own file (mg4_profiles) with the list of profiles (name, phone paired) and the current one. The start
 * sound's file (mg4_sound_triggers) follows the profile whole.
 *
 * A switch goes in four steps, each marked: the current profile's personal settings put away, taken off, the chosen
 * profile's put in their place, and then it is the current one. A cut in the middle (the car switched off) is finished at
 * the next start ([resume]). The open screens are rebuilt by the theme's signature ([generation]).
 */
object Profiles {
    private const val TAG = "Profiles"
    const val FILE = "mg4_profiles"
    private const val SETTINGS = "mg4launcher"
    private const val SOUND = "mg4_sound_triggers"
    const val MAX = 5
    const val NAME_MAX = 16

    data class Profile(val id: String, val name: String, val phone: String? = null)

    enum class Refusal { EMPTY, TOO_LONG, TAKEN, FULL, LAST }

    // ── Personal or shared: every setting written in the code is one or the other (ProfilesKeysTest) ──

    /** The settings that follow the profile, by name. */
    val PERSONAL = setOf(
        // Home: style, layout and cards of each place, the shortcuts card's tiles, the connection bar
        "home_style", "home_style_before_big", "home_layout", "home_layout_big", "home_widget", "secondary_widget", "stack_page",
        "stack_auto", "weather_page", "shortcuts_page", "energy_card_power", "conn_icons_hidden", "conn_usb_shown", "fav_phone_count",
        // Look: theme, background, colour, transitions, night at sunset, effects and their volume
        "theme_mode", "theme_bg", "theme_accent", "theme_smooth", "night_by_sun", "panel_fade", "climate_fx", "climate_fx_mode", "power_fx",
        "fx_sound", "fx_volume", "power_va", "power_fx_on",
        // Bar: at the bottom or vertical, side, wide, the Android Auto button's app
        "vertical_dock", "bar_side", "wide_bar", "dock_aa_target", "dock_slot",
        // Shortcuts menu: its tiles and the brightness bar (the menu itself stays shared, for MG's fog button)
        "shortcut_tiles", "shortcuts_brightness",
        // Screen: full screen, top bar during calls, MG4Dash's keyboard; the brightness
        "fullscreen", "call_bar_hidden", "own_keyboard", "bright_auto", "bright_adj", "bright_value", "bright_smart",
        // Media: start source and playback, MG4Radio's bands
        "start_source", "start_playback", "radio_dash_bands", "radio_dash_am",
        // Map and web
        "map_app", "map_apps_recent", "map_zoom", "map_heading_up", "map_theme", "map_auto_hide", "map_float", "map_float_rect",
        "map_floating", "map_offline_switch", "web_url", "web_urls", "web_float", "web_float_rect", "web_floating", "web_wake_s",
        // Services: Home Assistant, the weather's town and model
        "ha_url", "ha_token", "weather_city", "weather_lat", "weather_lon", "weather_model",
        // Units, 12 h clock, consumption period
        "units", "pressure_unit", "clock_12", "consumption_period",
        // Speed cameras: sounds, visual alerts, volumes, thresholds
        "radar_sound", "radar_visual", "radar_banner", "radar_percent", "radar_prepare_km", "radar_high", "radar_high_pct", "radar_low", "radar_low_pct",
        "radar_threshold_mode", "radar_mixed_low", "radar_mixed_low_pct", "radar_mixed_high", "radar_mixed_high_pct",
        // Synchronized lyrics: cache/network options and the overlay's size and position follow the driver
        "lyrics_cache_enabled", "lyrics_cache_max_mb", "lyrics_overlay_scale", "lyrics_overlay_x", "lyrics_overlay_y", "wifi_only_mode",
    )

    /** The settings that follow the profile, by family. */
    val PERSONAL_PREFIXES = listOf("slots_", "slot_now_", "conn_slot_", "wc_", "sc_", "fav_phone_", "radar_snd_", "radar_vol_", "radar_rep_",
        // The smart brightness's learnt curves (one per light / dark look)
        "bright_curve_")

    /** Shared by every profile (the car, the system, the data, the climate, the state of the moment), by name. */
    val SHARED = setOf(
        // The car
        "battery_kwh", "battery_model", "battery_new_kwh", "heated_seats", "temp_offset", "charge_limit", "v2l_auto", "kwh_price", "xpower",
        "rhd_cached", "force_rhd", "heat_seen", "hvac", "manual_dir", "manual_fan", "manual_recirc", "cap_current", "cap_current_drop",
        "cap_samples", "soh_drop", "soh_km", "soh_kwh", "last_soc", "ref_km", "ref_low", "ref_soc", "power_cons_source", "power_scale_kw",
        // The shortcuts menu itself (MG's fog button), its aids memory
        "shortcuts_menu", "shortcut_fcw", "shortcut_lane_mode",
        // The system: updates, ADB, maintenance mode, Settings' tab, the option of the drive mode per profile
        "update_auto", "update_beta", "update_skipped", "upd_checked_at", "upd_new_app", "upd_new_rd", "radio_dash_pending", "radio_dash_version",
        "adb_at_start", "valet_mode", "valet_auto", "settings_tab", "settings_tabs_v2", "defaults_v1", "fell_back", "stock_home", "alive_at",
        "warmup_boot", "conn_migrated", "connection_icons", "connection_off", "last_night", "profiles_drive", "mock_offline",
        // The settings backup's two options (#129)
        "backup_history", "backup_token",
        // The data
        "energy_log", "source_history", "stop_frozen", "trip_rd_end", "trip_rd_km", "trip_rd_km1", "trip_rd_t", "radar_isa_fallback",
        "radar_isa_log", "radar_isa_write", "radar_refresh_hours", "radar_wifi_only", "sun_lat", "sun_lon", "city_lang", "city_lat",
        "city_lon", "city_name", "weather_code", "lyrics_network_bytes",
        // The climate: keep climate on, its cycles, the SAIC climate hidden
        "climhold_enabled", "climhold_fan", "climhold_gap_min", "climhold_lock_sec", "climhold_max_min", "climhold_run_min", "climhold_runs",
        "climhold_scheduled", "climhold_screen_off", "climhold_start_min", "climhold_temp", "ac_cycle_active", "ac_cycle_off", "ac_cycle_on",
        "hide_saic_climate",
        // The state of the moment
        "last_playing", "last_playing_at", "playing_before", "tutorial_pending", "tutorial_shortcuts_seen",
    )

    /** Shared, by family: the energy monitor, the consumption curve, the trips list, the tutorial's steps seen. */
    val SHARED_PREFIXES = listOf("em_", "cc_", "trip_", "trips_", "tutorial_", "profile_")

    /** [key] follows the profile; anything else in the settings stays shared. */
    fun isPersonal(key: String): Boolean = key in PERSONAL || PERSONAL_PREFIXES.any { key.startsWith(it) }

    /** A key of the start sound's file that follows the profile: its settings, not the car volume it is restoring after a cut. */
    fun isSoundSetting(key: String): Boolean = !key.startsWith("volume_restore_")

    /** What a profile started from the default settings gets: the first install's defaults (LauncherApp, defaults_v1). */
    internal fun firstInstall(): JSONObject = JSONObject()
        .put("fullscreen", JSONArray().put("b").put(true)).put("vertical_dock", JSONArray().put("b").put(true))

    /** [key] is known: personal or shared. */
    fun classified(key: String): Boolean = isPersonal(key) || key in SHARED || SHARED_PREFIXES.any { key.startsWith(it) }

    // ── The list and the current profile ──

    private fun store(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun settings(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
    private fun sound(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(SOUND, Context.MODE_PRIVATE)

    /** The profiles, in their order; at the first call the current settings become the profile "Principal". */
    fun list(c: Context): List<Profile> {
        val raw = store(c).getString("list", null)
        if (raw == null) {
            val main = Profile("p1", c.getString(app.mg4dash.R.string.pf_main))
            write(c, listOf(main)); store(c).edit().putString("current", main.id).commit()
            return listOf(main)
        }
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Profile(o.getString("id"), o.getString("name"), o.optString("phone").ifEmpty { null }) } }
        } catch (e: Exception) { null }?.takeIf { it.isNotEmpty() } ?: run {
            // Damaged list: the first profile again, the settings in place kept
            Log.w(TAG, "profiles list unreadable: the Main profile again")
            val main = Profile("p1", c.getString(app.mg4dash.R.string.pf_main)); write(c, listOf(main)); store(c).edit().putString("current", main.id).commit()
            listOf(main)
        }
    }

    private fun write(c: Context, list: List<Profile>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("phone", it.phone ?: "")) }
        store(c).edit().putString("list", a.toString()).commit()
    }

    fun current(c: Context): Profile = list(c).let { l -> l.firstOrNull { it.id == store(c).getString("current", null) } ?: l.first() }

    /** Changes at each switch: in the theme's signature, so that the open screens rebuild even with the same theme. */
    fun generation(c: Context): Int = store(c).getInt("generation", 0)

    /** Why [name] is refused for a profile ([except]: the one being renamed), or null. */
    fun refusal(c: Context, name: String, except: String? = null): Refusal? {
        val n = name.trim()
        return when {
            n.isEmpty() -> Refusal.EMPTY
            n.length > NAME_MAX -> Refusal.TOO_LONG
            list(c).any { it.id != except && it.name.equals(n, ignoreCase = true) } -> Refusal.TAKEN
            else -> null
        }
    }

    /**
     * A new profile named [name]: from the current profile's settings ([fromCurrent]) or from the defaults, with an
     * optional [phone] paired. Not switched to.
     */
    fun create(c: Context, name: String, fromCurrent: Boolean, phone: String? = null): Pair<Profile?, Refusal?> {
        val l = list(c)
        if (l.size >= MAX) return null to Refusal.FULL
        refusal(c, name)?.let { return null to it }
        // Never a deleted profile's id again (its trips and drive memory stay its own)
        val next = maxOf(store(c).getInt("next_id", 2), (l.mapNotNull { it.id.drop(1).toIntOrNull() }.maxOrNull() ?: 0) + 1)
        store(c).edit().putInt("next_id", next + 1).commit()
        val id = "p$next"
        val p = Profile(id, name.trim(), phone?.takeIf { it.isNotBlank() })
        val data = if (fromCurrent) snapshot(settings(c)) else firstInstall()
        val snd = if (fromCurrent) snapshot(sound(c), all = true) else JSONObject()
        store(c).edit().putString("data_$id", data.toString()).putString("sound_$id", snd.toString()).commit()
        write(c, l + p)
        Log.i(TAG, "profile ${p.name} created (${if (fromCurrent) "from ${current(c).name}" else "from the defaults"})")
        return p to null
    }

    /** [id]'s name and phone changed. */
    fun update(c: Context, id: String, name: String, phone: String?): Refusal? {
        refusal(c, name, except = id)?.let { return it }
        write(c, list(c).map { if (it.id == id) it.copy(name = name.trim(), phone = phone?.takeIf { p -> p.isNotBlank() }) else it })
        return null
    }

    /** [id] deleted: never the last; the current one hands over first (to the next in the list). */
    fun delete(c: Context, id: String, after: () -> Unit = {}): Refusal? {
        val l = list(c)
        if (l.size <= 1) return Refusal.LAST
        if (current(c).id == id) switchTo(c, l.first { it.id != id }.id, "deleted")
        write(c, list(c).filter { it.id != id })
        store(c).edit().remove("data_$id").remove("sound_$id").remove("drive_$id").commit()
        after()
        return null
    }

    /** The keys starting with [prefix] taken off every profile's stored settings (the smart brightness's "reset all"). */
    fun forgetEverywhere(c: Context, prefix: String) {
        val s = store(c)
        val e = s.edit()
        list(c).forEach { p ->
            val data = try { JSONObject(s.getString("data_${p.id}", null) ?: return@forEach) } catch (_: Exception) { return@forEach }
            data.keys().asSequence().toList().filter { it.startsWith(prefix) }.forEach { data.remove(it) }
            e.putString("data_${p.id}", data.toString())
        }
        e.commit()
    }

    // ── The switch ──

    /** Listeners told after each switch (the screens, the bar, the shortcuts menu, MG4Radio). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Profile) -> Unit>()
    fun onSwitch(l: (Profile) -> Unit) { listeners += l }

    /** A profile chosen by hand since this start (Settings, the tile): the paired phone no longer switches. */
    @Volatile var manualThisStart = false

    /** Switches to [id] ([why] for the log; "phone" for the paired phone); false if it is already the current one or unknown. */
    fun switchTo(c: Context, id: String, why: String): Boolean {
        val from = current(c)
        val to = list(c).firstOrNull { it.id == id } ?: return false
        if (why != "phone") manualThisStart = true
        if (to.id == from.id) return false
        val s = store(c)
        // The car's drive mode and regeneration noted for the profile left (option, step 12)
        try { DriveMemory.note(c, from.id) } catch (e: Throwable) { Log.w(TAG, "drive: ${e.message}") }
        // 1. The current profile's personal settings put away (marked: a cut after this is finished at the next start)
        s.edit().putString("data_${from.id}", snapshot(settings(c)).toString()).putString("sound_${from.id}", snapshot(sound(c), all = true).toString())
            .putString("switching", "${from.id}>${to.id}").commit()
        // 2 and 3. Taken off, the chosen profile's put in their place; 4. it is the current one
        finish(c, to.id)
        Log.i(TAG, "profile ${from.name} → ${to.name} ($why)")
        try { DriveMemory.putBack(c, to.id) } catch (e: Throwable) { Log.w(TAG, "drive: ${e.message}") }
        listeners.forEach { try { it(to) } catch (e: Throwable) { Log.w(TAG, "after the switch: ${e.message}") } }
        return true
    }

    private fun finish(c: Context, to: String) {
        val s = store(c)
        put(settings(c), JSONObject(s.getString("data_$to", null) ?: "{}"), all = false)
        put(sound(c), JSONObject(s.getString("sound_$to", null) ?: "{}"), all = true)
        s.edit().putString("current", to).putInt("generation", (generation(c) + 1) % 100).remove("switching").commit()
    }

    /** A switch cut in the middle (the car switched off): finished now, at the start. */
    fun resume(c: Context): Boolean {
        val mark = store(c).getString("switching", null) ?: return false
        val to = mark.substringAfter('>')
        Log.i(TAG, "a switch to $to was cut: finished now")
        finish(c, to)
        return true
    }

    /** [prefs]' personal settings ([all]: every one, for the start sound's file), with their types. */
    internal fun snapshot(prefs: SharedPreferences, all: Boolean = false): JSONObject {
        val o = JSONObject()
        prefs.all.forEach { (k, v) ->
            if (v == null || (if (all) !isSoundSetting(k) else !isPersonal(k))) return@forEach
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "S"; else -> return@forEach }
            o.put(k, JSONArray().put(t).put(if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        return o
    }

    /** The personal settings of [prefs] ([all]: every one) replaced by [data]. */
    internal fun put(prefs: SharedPreferences, data: JSONObject, all: Boolean) {
        val e = prefs.edit()
        prefs.all.keys.filter { if (all) isSoundSetting(it) else isPersonal(it) }.forEach { e.remove(it) }
        data.keys().forEach { k ->
            if (if (all) !isSoundSetting(k) else !isPersonal(k)) return@forEach
            val a = data.getJSONArray(k)
            when (a.getString(0)) {
                "b" -> e.putBoolean(k, a.getBoolean(1)); "i" -> e.putInt(k, a.getInt(1)); "l" -> e.putLong(k, a.getLong(1))
                "f" -> e.putFloat(k, a.getDouble(1).toFloat()); "s" -> e.putString(k, a.getString(1))
                "S" -> e.putStringSet(k, a.getJSONArray(1).let { s -> (0 until s.length()).map { s.getString(it) }.toSet() })
            }
        }
        e.commit()
    }
}
