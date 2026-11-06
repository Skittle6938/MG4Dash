package app.mg4dash.ui

import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * Cards each widget goes through (#97), chosen in the edit mode (pinch the home screen) or from
 * Settings › Home. At least one card stays on per widget; the order is fixed.
 *
 * Storage: "wc_<widget>" = ids of the cards on, comma separated; absent = the defaults.
 * The map widget's shortcuts page stays the "Shortcuts page in the map card" setting (#38).
 */
object WidgetCards {
    /** [page]: the number the widget already uses for this card (homeWidget, stackPage, secondaryWidget, weatherPage). */
    enum class Card(val id: String, val label: Int, val page: Int) {
        MAP("map", R.string.wc_map, 0), CONSUMPTION("consumption", R.string.wc_consumption, 2),
        SHORTCUTS("shortcuts", R.string.wc_shortcuts, 1), WEB("web", R.string.wc_web, 3),
        ENERGY("energy", R.string.wc_energy, 0), TRIP("trip", R.string.wc_trip, 1),
        POWER("power", R.string.wc_power, 2), PHONE("phone", R.string.wc_phone, 3),
        BOTTOM_TRIP("trip", R.string.wc_trip, 0), BOTTOM_POWER("power", R.string.wc_power, 1), BOTTOM_PHONE("phone", R.string.wc_phone, 2),
        HOURLY("hourly", R.string.wc_hourly, 0), DAILY("daily", R.string.wc_daily, 1),
    }

    /** A widget whose cards go by: its cards in order, and those on by default (the web app is off). */
    enum class Widget(val key: String, val title: Int, val cards: List<Card>) {
        MAP("map", R.string.wc_w_map, listOf(Card.MAP, Card.CONSUMPTION, Card.SHORTCUTS, Card.WEB)),
        /** "Map" layout: energy card and the bottom widget's pages, in one stack. */
        STACK("stack", R.string.wc_w_energy, listOf(Card.ENERGY, Card.TRIP, Card.POWER, Card.PHONE)),
        /** "Full" layout: the bottom right widget. */
        BOTTOM("bottom", R.string.wc_w_bottom, listOf(Card.BOTTOM_TRIP, Card.BOTTOM_POWER, Card.BOTTOM_PHONE)),
        WEATHER("weather", R.string.wc_w_weather, listOf(Card.HOURLY, Card.DAILY));

        val defaults: List<Card> get() = cards.filter { it != Card.WEB }
    }

    private fun key(w: Widget) = "wc_" + w.key

    /** Cards on, in the widget's order; never empty. */
    fun on(prefs: Prefs, w: Widget): List<Card> {
        val stored = prefs.raw.getString(key(w), null)
        var list = if (stored == null) w.defaults else stored.split(',').mapNotNull { id -> w.cards.firstOrNull { it.id == id } }
        if (w == Widget.MAP) list = list.filter { it != Card.SHORTCUTS || prefs.shortcutsPage } +
            (if (prefs.shortcutsPage && Card.SHORTCUTS !in list) listOf(Card.SHORTCUTS) else emptyList())
        list = w.cards.filter { it in list }
        return list.ifEmpty { listOf(w.cards.first()) }
    }

    fun isOn(prefs: Prefs, w: Widget, c: Card) = c in on(prefs, w)

    /** Turns [c] on or off; the last card on stays on (false is returned). */
    fun toggle(prefs: Prefs, w: Widget, c: Card): Boolean {
        val now = on(prefs, w)
        val next = if (c in now) now - c else now + c
        if (next.isEmpty()) return false
        prefs.raw.edit().putString(key(w), w.cards.filter { it in next }.joinToString(",") { it.id }).apply()
        if (w == Widget.MAP && c == Card.SHORTCUTS) prefs.shortcutsPage = c in next
        return true
    }

    /** Pages on, as the numbers the widget uses. */
    fun pages(prefs: Prefs, w: Widget): List<Int> = on(prefs, w).map { it.page }

    /** Next page on after [cur] in direction [dir]; [skip] removes pages that do not apply (power in the energy card…). */
    fun next(prefs: Prefs, w: Widget, cur: Int, dir: Int, skip: Set<Int> = emptySet()): Int {
        val pages = pages(prefs, w).filter { it !in skip }.ifEmpty { pages(prefs, w) }
        val i = pages.indexOf(cur)
        return if (i < 0) pages.first() else pages[Math.floorMod(i + dir, pages.size)]
    }

    /** [cur] if it is on, otherwise the first page on. */
    fun valid(prefs: Prefs, w: Widget, cur: Int, skip: Set<Int> = emptySet()): Int {
        val pages = pages(prefs, w).filter { it !in skip }.ifEmpty { pages(prefs, w) }
        return if (cur in pages) cur else pages.first()
    }
}
