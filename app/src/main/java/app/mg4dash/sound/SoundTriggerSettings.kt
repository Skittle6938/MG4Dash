package app.mg4dash.sound

import android.content.Context
import android.content.SharedPreferences
import app.mg4dash.car.CarMediaVolume
import java.io.File

/** Startup sound preferences and recovery state. */
object SoundTriggerSettings {
    private const val PREFS = "mg4_sound_triggers"
    private const val KEY_ENABLED = "sound_trigger_enabled"
    private const val KEY_BOOT_TRIGGER = "trigger_boot_sound"
    private const val KEY_STARTUP_SOUND = "startup_sound_enabled"
    private const val KEY_VOLUME_LEVEL = "trigger_volume_level"
    private const val KEY_LEGACY_VOLUME_PCT = "trigger_volume_pct"
    private const val KEY_SELECTED_FILE = "selected_sound_file"
    private const val KEY_RESTORE_PENDING = "volume_restore_pending"
    private const val KEY_RESTORE_CHANNEL = "volume_restore_channel"
    private const val KEY_RESTORE_MAX = "volume_restore_max"
    private const val KEY_RESTORE_GROUP = "volume_restore_group"
    private const val KEY_RESTORE_BEFORE = "volume_restore_before"
    private const val KEY_RESTORE_WRITTEN = "volume_restore_written"

    data class PendingVolumeRestore(
        val channel: String,
        val max: Int,
        val group: Int,
        val before: Int,
        val written: Int,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isBootTriggerEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_STARTUP_SOUND)) return p.getBoolean(KEY_STARTUP_SOUND, false)
        // Migrate the effective state of the two previous switches into one preference.
        val wasEnabled = p.getBoolean(KEY_ENABLED, false) && p.getBoolean(KEY_BOOT_TRIGGER, true)
        p.edit().putBoolean(KEY_STARTUP_SOUND, wasEnabled).apply()
        return wasEnabled
    }

    fun setBootTriggerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STARTUP_SOUND, enabled).apply()
    }

    /** Return the absolute 0..32 media level, migrating a saved percentage if needed. */
    fun getVolumeLevel(context: Context): Int {
        val p = prefs(context)
        if (p.contains(KEY_VOLUME_LEVEL)) return p.getInt(KEY_VOLUME_LEVEL, CarMediaVolume.UI_MAX / 2).coerceIn(0, CarMediaVolume.UI_MAX)
        val oldPct = p.getInt(KEY_LEGACY_VOLUME_PCT, -1)
        val level = if (oldPct < 0) CarMediaVolume.UI_MAX / 2
            else ((oldPct.coerceIn(0, 100) * CarMediaVolume.UI_MAX + 50) / 100).coerceIn(0, CarMediaVolume.UI_MAX)
        p.edit().putInt(KEY_VOLUME_LEVEL, level).apply()
        return level
    }

    fun setVolumeLevel(context: Context, level: Int) {
        prefs(context).edit().putInt(KEY_VOLUME_LEVEL, level.coerceIn(0, CarMediaVolume.UI_MAX)).apply()
    }

    /** Persist the original volume before changing the car, so a later app start can recover it. */
    fun savePendingVolumeRestore(
        context: Context,
        channel: String,
        max: Int,
        group: Int,
        before: Int,
        written: Int,
    ): Boolean = prefs(context).edit()
        .putBoolean(KEY_RESTORE_PENDING, true)
        .putString(KEY_RESTORE_CHANNEL, channel)
        .putInt(KEY_RESTORE_MAX, max)
        .putInt(KEY_RESTORE_GROUP, group)
        .putInt(KEY_RESTORE_BEFORE, before)
        .putInt(KEY_RESTORE_WRITTEN, written)
        .commit()

    fun getPendingVolumeRestore(context: Context): PendingVolumeRestore? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_RESTORE_PENDING, false)) return null
        val channel = p.getString(KEY_RESTORE_CHANNEL, null) ?: run {
            clearPendingVolumeRestore(context)
            return null
        }
        val max = p.getInt(KEY_RESTORE_MAX, -1)
        val before = p.getInt(KEY_RESTORE_BEFORE, -1)
        val written = p.getInt(KEY_RESTORE_WRITTEN, -1)
        if (max <= 0 || before < 0 || written < 0) {
            clearPendingVolumeRestore(context)
            return null
        }
        return PendingVolumeRestore(channel, max, p.getInt(KEY_RESTORE_GROUP, 0), before, written)
    }

    fun clearPendingVolumeRestore(context: Context) {
        prefs(context).edit()
            .remove(KEY_RESTORE_PENDING)
            .remove(KEY_RESTORE_CHANNEL)
            .remove(KEY_RESTORE_MAX)
            .remove(KEY_RESTORE_GROUP)
            .remove(KEY_RESTORE_BEFORE)
            .remove(KEY_RESTORE_WRITTEN)
            .apply()
    }

    fun getSelectedSoundPath(context: Context): String? {
        val path = prefs(context).getString(KEY_SELECTED_FILE, null) ?: return null
        val file = File(path)
        return if (file.exists() && file.canRead()) path else null
    }

    fun setSelectedSoundPath(context: Context, path: String?) {
        prefs(context).edit().putString(KEY_SELECTED_FILE, path).apply()
    }

    fun soundDir(context: Context): File =
        File(context.filesDir, "sounds").apply { mkdirs() }
}
