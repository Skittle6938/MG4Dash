package app.mg4dash.cards

import android.content.Context
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots

/** Lyrics-card settings stored per profile; old global keys are copied once for existing installs. */
class LyricsCardPrefs(context: Context) {
    private companion object {
        const val LEGACY_OVERLAY_SCALE = "lyrics_overlay_scale"
    }

    private val card = CardPrefs(context, HomeSlots.Card.LYRICS)
    private val legacy = Prefs(context).raw

    init {
        migrateBoolean("wifi_only", false, "lyrics_wifi_only", "wifi_only_mode")
        migrateBoolean("cache_enabled", true, "lyrics_cache_enabled")
        migrateInt("cache_max_mb", 20, "lyrics_cache_max_mb")
        migrateInt("overlay_scale_percent", (legacy.getFloat(LEGACY_OVERLAY_SCALE, 1f) * 100).toInt(), LEGACY_OVERLAY_SCALE)
        migrateInt("overlay_x", -1, "lyrics_overlay_x")
        migrateInt("overlay_y", -1, "lyrics_overlay_y")
    }

    var wifiOnly: Boolean
        get() = card.bool("wifi_only", false)
        set(value) = card.setBool("wifi_only", value)

    var cacheEnabled: Boolean
        get() = card.bool("cache_enabled", true)
        set(value) = card.setBool("cache_enabled", value)

    var cacheMaxMb: Int
        get() = card.int("cache_max_mb", 20).coerceIn(5, 100)
        set(value) = card.setInt("cache_max_mb", value.coerceIn(5, 100))

    var overlayScale: Float
        get() = card.int("overlay_scale_percent", 100).coerceIn(70, 140) / 100f
        set(value) = card.setInt("overlay_scale_percent", (value.coerceIn(.70f, 1.40f) * 100).toInt())

    var overlayX: Int
        get() = card.int("overlay_x", -1)
        set(value) = card.setInt("overlay_x", value)

    var overlayY: Int
        get() = card.int("overlay_y", -1)
        set(value) = card.setInt("overlay_y", value)

    private fun migrateBoolean(name: String, default: Boolean, vararg oldNames: String) {
        val key = card.key(name)
        if (!legacy.contains(key)) {
            val old = oldNames.firstOrNull(legacy::contains)
            card.setBool(name, old?.let { legacy.getBoolean(it, default) } ?: default)
        }
        legacy.edit().also { edit -> oldNames.forEach(edit::remove) }.apply()
    }

    private fun migrateInt(name: String, default: Int, vararg oldNames: String) {
        val key = card.key(name)
        if (!legacy.contains(key)) {
            val old = oldNames.firstOrNull(legacy::contains)
            val value = when (old) {
                LEGACY_OVERLAY_SCALE -> (legacy.getFloat(old, 1f) * 100).toInt()
                null -> default
                else -> legacy.getInt(old, default)
            }
            card.setInt(name, value)
        }
        legacy.edit().also { edit -> oldNames.forEach(edit::remove) }.apply()
    }
}
