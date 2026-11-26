package app.mg4dash.cards

import android.content.Context
import app.mg4dash.ui.HomeSlots

/** Lyrics-card settings, stored per profile. */
class LyricsCardPrefs(context: Context) {
    private val card = CardPrefs(context, HomeSlots.Card.LYRICS)

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

    /** The line before the current one in the floating pill (off by default). */
    var overlayPrevious: Boolean
        get() = card.bool("overlay_previous", false)
        set(value) = card.setBool("overlay_previous", value)

    /** The lyrics' offset on the song, in tenths of a second: -4 shows each line 0.4 s early. */
    var offsetTenths: Int
        get() = card.int("offset_tenths", -4).coerceIn(-30, 30)
        set(value) = card.setInt("offset_tenths", value.coerceIn(-30, 30))

    /** The ▲▼ arrows that shift the current song's lyrics, in the karaoke and the card (on by default). */
    var shiftArrows: Boolean
        get() = card.bool("shift_arrows", true)
        set(value) = card.setBool("shift_arrows", value)

    /** How far ahead of the song the lyrics are, in ms. */
    val leadMs: Long get() = -offsetTenths * 100L

    var overlayX: Int
        get() = card.int("overlay_x", -1)
        set(value) = card.setInt("overlay_x", value)

    var overlayY: Int
        get() = card.int("overlay_y", -1)
        set(value) = card.setInt("overlay_y", value)
}
