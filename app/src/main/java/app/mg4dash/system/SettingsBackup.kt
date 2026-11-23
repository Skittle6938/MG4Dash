package app.mg4dash.system

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings backup on the USB stick (#129): every setting, the profiles, the start sound (its settings and its files)
 * and the web addresses in one JSON file, to put back after a reset of the head unit.
 *
 * What is saved: the settings known to MG4Dash ([Profiles.classified]), less the state of this install (Rescue, boot
 * marks, pending updates, the media playing, maintenance mode, open floating windows). The history (trips, consumption,
 * battery health) only with [Options.history]; the Home Assistant token only with [Options.haToken], since the file is
 * readable by anyone holding the stick.
 *
 * Restoring replaces the same scope and nothing else: a key unknown to this version, or of the wrong type, is skipped.
 */
object SettingsBackup {
    private const val TAG = "Backup"
    const val FORMAT = 1
    private const val SETTINGS = "mg4launcher"
    private const val SOUND = "mg4_sound_triggers"
    private const val PREFIX = "mg4dash_settings_"
    /** A start sound file larger than this is not copied (a file of the stick, mistaken for a jingle). */
    private const val SOUND_MAX = 4 * 1024 * 1024L

    data class Options(val history: Boolean = true, val haToken: Boolean = false)

    /** A backup found on the stick, for the restore list. */
    data class Found(val file: File, val created: Long, val version: String, val profiles: Int, val history: Boolean, val haToken: Boolean)

    // ── Scope ──

    /** The state of this install: never saved, never replaced. */
    internal val LOCAL = setOf(
        "update_skipped", "upd_checked_at", "upd_new_app", "upd_new_rd", "radio_dash_pending", "radio_dash_version",
        "valet_mode", "fell_back", "stock_home", "alive_at", "warmup_boot", "settings_tab", "conn_migrated", "last_night",
        "mock_offline", "last_playing", "last_playing_at", "playing_before", "tutorial_pending", "map_floating", "web_floating",
        "rhd_cached", "hvac", "manual_dir", "manual_fan", "manual_recirc", "ac_cycle_active", "climhold_scheduled", "last_soc",
        "weather_code", "radar_isa_log", "radar_isa_write",
    )

    /** The history: saved and replaced only with [Options.history]. */
    internal val HISTORY = setOf(
        "battery_new_kwh", "cap_current", "cap_current_drop", "cap_samples", "soh_drop", "soh_km", "soh_kwh", "ref_km", "ref_low",
        "ref_soc", "source_history", "stop_frozen", "trip_rd_end", "trip_rd_km", "trip_rd_km1", "trip_rd_t",
    )
    internal val HISTORY_PREFIXES = listOf("em_", "cc_", "trip_", "trips_")

    internal const val TOKEN = "ha_token"

    internal fun isHistory(key: String) = key in HISTORY || HISTORY_PREFIXES.any { key.startsWith(it) }

    /** [key] of the settings is in a backup made with [o]. */
    internal fun inScope(key: String, o: Options): Boolean = Profiles.classified(key) && key !in LOCAL &&
        (o.history || !isHistory(key)) && (o.haToken || key != TOKEN)

    /** [key] of the profiles' file: the list, the current one, each profile's settings and drive memory; not a switch underway. */
    private fun profileKey(key: String) = key == "list" || key == "current" || key.startsWith("data_") || key.startsWith("sound_") || key.startsWith("drive_")

    // ── Writing ──

    fun fileName(now: Long): String = PREFIX + java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date(now)) + ".json"

    /** The backup's text. */
    fun build(c: Context, o: Options, version: String, now: Long = System.currentTimeMillis()): String {
        val settings = typed(prefs(c, SETTINGS)) { inScope(it, o) }
        val profiles = typed(prefs(c, Profiles.FILE)) { profileKey(it) }
        // Each profile's own copy of its settings: the same scope (no token unless asked)
        profiles.keys().asSequence().toList().filter { it.startsWith("data_") }.forEach { k ->
            val a = profiles.getJSONArray(k)
            val d = try { JSONObject(a.getString(1)) } catch (e: Exception) { return@forEach }
            d.keys().asSequence().toList().filter { !inScope(it, o) }.forEach { d.remove(it) }
            profiles.put(k, JSONArray().put("s").put(d.toString()))
        }
        val sound = typed(prefs(c, SOUND)) { Profiles.isSoundSetting(it) }
        val files = JSONObject()
        app.mg4dash.sound.SoundTriggerSettings.soundDir(c).listFiles()?.filter { it.isFile && it.length() in 1..SOUND_MAX }?.forEach {
            files.put(it.name, android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP))
        }
        val profileCount = try { JSONArray(prefs(c, Profiles.FILE).getString("list", null) ?: "[]").length() } catch (e: Exception) { 0 }
        return JSONObject()
            .put("app", "MG4Dash").put("format", FORMAT).put("version", version).put("created", now)
            .put("history", o.history).put("ha_token", o.haToken).put("profiles_count", maxOf(1, profileCount))
            .put("settings", settings).put("profiles", profiles).put("sound", sound).put("sound_files", files)
            .toString(1)
    }

    /** The backup written on the stick (see [UsbExport.write]); null without a stick or with nothing writable. */
    fun save(c: Context, o: Options, version: String): UsbExport.Written? {
        val now = System.currentTimeMillis()
        return UsbExport.write(c, fileName(now), build(c, o, version, now)).also { Log.i(TAG, "saved: ${it?.path} history=${o.history} token=${o.haToken}") }
    }

    // ── Reading ──

    /** The backups on the stick (and in the car's memory, where a refused stick sent them), the newest first. */
    fun find(c: Context): List<Found> {
        val dirs = UsbExport.readDirs(c) + listOfNotNull(UsbExport.internalDir())
        return dirs.flatMap { d -> d.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".json") }?.toList().orEmpty() }
            .distinctBy { it.canonicalPath }
            .mapNotNull { f -> try { describe(f, JSONObject(f.readText())) } catch (e: Exception) { Log.w(TAG, "${f.name}: ${e.message}"); null } }
            .sortedByDescending { it.created }
    }

    internal fun describe(f: File, j: JSONObject): Found? {
        if (j.optString("app") != "MG4Dash" || j.optInt("format", 0) !in 1..FORMAT) return null
        return Found(f, j.optLong("created", f.lastModified()), j.optString("version"), j.optInt("profiles_count", 1),
            j.optBoolean("history"), j.optBoolean("ha_token"))
    }

    // ── Restoring ──

    /** [j] put in place of the current settings, in the backup's scope. False if it is not a backup MG4Dash can read. */
    fun restore(c: Context, j: JSONObject): Boolean {
        if (j.optString("app") != "MG4Dash" || j.optInt("format", 0) !in 1..FORMAT) return false
        val o = Options(history = j.optBoolean("history"), haToken = j.optBoolean("ha_token"))
        val settings = j.optJSONObject("settings") ?: return false
        // The sound files first: the start sound's settings point to them
        val dir = app.mg4dash.sound.SoundTriggerSettings.soundDir(c)
        j.optJSONObject("sound_files")?.let { files ->
            files.keys().forEach { name ->
                if (name.contains('/') || name.contains('\\') || name.startsWith(".")) return@forEach
                try { File(dir, name).writeBytes(android.util.Base64.decode(files.getString(name), android.util.Base64.NO_WRAP)) }
                catch (e: Exception) { Log.w(TAG, "sound $name: ${e.message}") }
            }
        }
        val skipped = replace(prefs(c, SETTINGS), settings) { inScope(it, o) } +
            replace(prefs(c, Profiles.FILE), j.optJSONObject("profiles") ?: JSONObject()) { profileKey(it) } +
            replace(prefs(c, SOUND), j.optJSONObject("sound") ?: JSONObject()) { Profiles.isSoundSetting(it) }
        // The open screens rebuild with the restored theme (Profiles' signature)
        prefs(c, Profiles.FILE).let { it.edit().putInt("generation", it.getInt("generation", 0) + 1).remove("switching").commit() }
        Log.i(TAG, "restored: from ${j.optString("version")}, ${skipped} unknown or bad values skipped")
        return true
    }

    /** Restarts MG4Dash on its home screen, with the restored settings. */
    fun restart(c: Context) {
        val intent = android.content.Intent(c, app.mg4dash.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = android.app.PendingIntent.getActivity(c, 2, intent, android.app.PendingIntent.FLAG_CANCEL_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        (c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1000, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    // ── Typed values ──

    private fun prefs(c: Context, name: String): SharedPreferences = c.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** [p]'s values kept by [keep], each as [type, value] (the profiles' own format). */
    internal fun typed(p: SharedPreferences, keep: (String) -> Boolean): JSONObject {
        val o = JSONObject()
        p.all.forEach { (k, v) ->
            if (v == null || !keep(k)) return@forEach
            val t = when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "S"; else -> return@forEach }
            o.put(k, JSONArray().put(t).put(if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
        }
        return o
    }

    /** [p]'s values in [keep] replaced by [data]'s; a value of the wrong type or unknown key skipped. The number skipped. */
    internal fun replace(p: SharedPreferences, data: JSONObject, keep: (String) -> Boolean): Int {
        val before = p.all
        val e = p.edit()
        before.keys.filter(keep).forEach { e.remove(it) }
        var skipped = 0
        data.keys().forEach { k ->
            if (!keep(k)) { skipped++; return@forEach }
            // A value this version keeps with another type would make its reader fail: the current one stays
            val t = try { data.getJSONArray(k).getString(0) } catch (ex: Exception) { null }
            val now = before[k]
            if (now != null && t != when (now) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "S"; else -> null }) {
                e.putValue(k, now); skipped++; return@forEach
            }
            try {
                val a = data.getJSONArray(k)
                when (a.getString(0)) {
                    "b" -> e.putBoolean(k, a.get(1) as Boolean)
                    "i" -> e.putInt(k, (a.get(1) as Number).also { require(it.toDouble() % 1.0 == 0.0) }.toInt())
                    "l" -> e.putLong(k, (a.get(1) as Number).toLong())
                    "f" -> e.putFloat(k, (a.get(1) as Number).toFloat())
                    "s" -> e.putString(k, a.get(1) as String)
                    "S" -> e.putStringSet(k, a.getJSONArray(1).let { s -> (0 until s.length()).map { s.getString(it) }.toSet() })
                    else -> skipped++
                }
            } catch (ex: Exception) { skipped++ }
        }
        e.commit()
        return skipped
    }
}

/** [v] put back as it was (a value kept on restore). */
private fun SharedPreferences.Editor.putValue(k: String, v: Any) {
    @Suppress("UNCHECKED_CAST")
    when (v) { is Boolean -> putBoolean(k, v); is Int -> putInt(k, v); is Long -> putLong(k, v); is Float -> putFloat(k, v); is String -> putString(k, v); is Set<*> -> putStringSet(k, v as Set<String>) }
}
