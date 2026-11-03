package app.mg4dash.sound

import android.content.Context
import android.content.SharedPreferences
import app.mg4dash.car.CarMediaVolume
import java.io.File

/**
 * Impostazioni del suono di avvio: abilitazione, gain, e file audio importato.
 */
object SoundTriggerSettings {
    private const val PREFS = "mg4_sound_triggers"
    private const val KEY_ENABLED = "sound_trigger_enabled"
    private const val KEY_BOOT_TRIGGER = "trigger_boot_sound"
    private const val KEY_STARTUP_SOUND = "startup_sound_enabled"
    private const val KEY_VOLUME_LEVEL = "trigger_volume_level"
    private const val KEY_LEGACY_VOLUME_PCT = "trigger_volume_pct"
    private const val KEY_SELECTED_FILE = "selected_sound_file"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isBootTriggerEnabled(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_STARTUP_SOUND)) return p.getBoolean(KEY_STARTUP_SOUND, false)
        // Migra l'impostazione effettiva dei due vecchi interruttori in un'unica preferenza.
        val wasEnabled = p.getBoolean(KEY_ENABLED, false) && p.getBoolean(KEY_BOOT_TRIGGER, true)
        p.edit().putBoolean(KEY_STARTUP_SOUND, wasEnabled).apply()
        return wasEnabled
    }

    fun setBootTriggerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STARTUP_SOUND, enabled).apply()
    }

    /** Livello assoluto media 0..32; le percentuali salvate dalla versione precedente vengono migrate. */
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
