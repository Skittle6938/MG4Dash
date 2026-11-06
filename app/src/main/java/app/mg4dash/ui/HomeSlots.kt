package app.mg4dash.ui

import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * Places of the home screen and what each one shows (#97): the climate, the media, or cards in turn,
 * chosen in the edit mode (pinch the home screen, or Settings › Home). A card is in one place at most;
 * the climate and the media only go to a medium place, once each, and can be left out. Every place
 * with cards keeps at least one. Kept apart for the Full and Map layouts; the defaults are the home
 * screen as it always was, and with nothing stored the choice made per widget before (wc_*) is kept.
 *
 * Storage: "slots_<layout>" = "tl=climate;tc=map,consumption;…" and "slot_now_<layout>_<place>" = card shown.
 */
object HomeSlots {
    enum class Size { SMALL, MEDIUM, TALL }

    /** Cards, in the order they go by in a place. [icon]: on the button that changes card over a web page or an app. */
    enum class Card(val id: String, val label: Int, val icon: Int) {
        MAP("map", R.string.wc_map, R.drawable.ic_map),
        CONSUMPTION("consumption", R.string.wc_consumption, R.drawable.ic_bolt),
        SHORTCUTS("shortcuts", R.string.wc_shortcuts, R.drawable.ic_apps),
        WEB("web", R.string.wc_web, R.drawable.ic_web),
        ENERGY("energy", R.string.wc_energy, R.drawable.ic_battery),
        TRIP("trip", R.string.wc_trip, R.drawable.ic_car),
        POWER("power", R.string.wc_power, R.drawable.ic_bolt),
        PHONE("phone", R.string.wc_phone, R.drawable.ic_phone),
        HOURLY("hourly", R.string.wc_hourly, R.drawable.ic_sun),
        DAILY("daily", R.string.wc_daily, R.drawable.ic_cloud),
    }

    /** What a place shows. */
    sealed class Fill {
        data object Climate : Fill()
        data object Media : Fill()
        data class Cards(val cards: List<Card>) : Fill()
    }

    /** Choice of a medium place in the edit mode. */
    enum class Kind { CLIMATE, MEDIA, CARDS }

    /** A place: its id, its size and its share of the column's height. */
    class Slot(val id: String, val size: Size, val weight: Float)

    /** [columns]: the places of each column, from top to bottom. */
    enum class Layout(val key: String, val columns: List<List<Slot>>, val defaults: String) {
        FULL("full", listOf(
            listOf(Slot("tl", Size.MEDIUM, 60f), Slot("bl", Size.SMALL, 40f)),
            listOf(Slot("tc", Size.MEDIUM, 60f), Slot("bc", Size.SMALL, 40f)),
            listOf(Slot("tr", Size.MEDIUM, 60f), Slot("br", Size.SMALL, 40f)),
        ), "tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone"),
        /** Small places on top, medium ones at the bottom, the map at full height on the right (#6). */
        MAP("map", listOf(
            listOf(Slot("tl", Size.SMALL, 40f), Slot("bl", Size.MEDIUM, 60f)),
            listOf(Slot("tc", Size.SMALL, 40f), Slot("bc", Size.MEDIUM, 60f)),
            listOf(Slot("r", Size.TALL, 100f)),
        ), "tl=hourly,daily;tc=energy,trip,power,phone;r=map,consumption,shortcuts;bl=climate;bc=media");

        val slots: List<Slot> get() = columns.flatten()
        fun slot(id: String): Slot? = slots.firstOrNull { it.id == id }
    }

    /** What each place of [layout] shows. */
    class Config(val layout: Layout, val fills: Map<String, Fill>) {
        fun fill(slot: String): Fill = fills[slot] ?: Fill.Cards(emptyList())
        fun cards(slot: String): List<Card> = (fills[slot] as? Fill.Cards)?.cards ?: emptyList()
        fun slotOf(card: Card): String? = fills.entries.firstOrNull { (it.value as? Fill.Cards)?.cards?.contains(card) == true }?.key
        fun slotOf(fill: Fill): String? = fills.entries.firstOrNull { it.value == fill }?.key
        fun placed(): Set<Card> = fills.values.flatMap { (it as? Fill.Cards)?.cards ?: emptyList() }.toSet()
        fun with(slot: String, fill: Fill): Config = Config(layout, LinkedHashMap(fills).apply { put(slot, fill.ordered()) })
        fun encode(): String = layout.slots.joinToString(";") { s ->
            s.id + "=" + when (val f = fill(s.id)) {
                Fill.Climate -> "climate"
                Fill.Media -> "media"
                is Fill.Cards -> f.cards.joinToString(",") { it.id }
            }
        }
        override fun equals(other: Any?) = other is Config && other.layout == layout && other.encode() == encode()
        override fun hashCode() = encode().hashCode()
        override fun toString() = encode()
    }

    /** Why a change was refused: the place would have no card left, here or where the card was. */
    enum class Refusal { KEEP_ONE, ONLY_CARD_THERE, NO_CARD }

    class Change(val config: Config, val refused: Refusal? = null)

    private fun Fill.ordered(): Fill = if (this is Fill.Cards) Fill.Cards(cards.distinct().sortedBy { it.ordinal }) else this

    fun layoutOf(prefs: Prefs): Layout = if (prefs.homeStyle == 2) Layout.MAP else Layout.FULL

    private fun key(layout: Layout) = "slots_" + layout.key

    /** The layout's defaults: the home screen as it always was, the web app left out. */
    fun defaults(layout: Layout): Config = decode(layout, layout.defaults)

    /** Places of [layout], never a place of cards without one ([unavailable]: cards that cannot be shown now). */
    fun load(prefs: Prefs, layout: Layout, unavailable: Set<Card> = emptySet()): Config {
        val stored = prefs.raw.getString(key(layout), null)
        return fillEmpty(if (stored != null) decode(layout, stored) else legacy(prefs, layout), unavailable)
    }

    fun save(prefs: Prefs, config: Config) {
        prefs.raw.edit().putString(key(config.layout), config.encode()).apply()
    }

    /** Back to the defaults (edit mode's button); the cards shown start again from the first. */
    fun reset(prefs: Prefs, layout: Layout) {
        val e = prefs.raw.edit().putString(key(layout), defaults(layout).encode())
        layout.slots.forEach { e.remove(nowKey(layout, it.id)) }
        e.apply()
    }

    /** Reads a stored choice; unknown or repeated entries are left out, a missing place is empty. */
    fun decode(layout: Layout, s: String): Config {
        val parts = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val used = mutableSetOf<Card>()
        var climate = false
        var media = false
        val fills = LinkedHashMap<String, Fill>()
        layout.slots.forEach { slot ->
            val v = parts[slot.id] ?: ""
            fills[slot.id] = when {
                v == "climate" && slot.size == Size.MEDIUM && !climate -> { climate = true; Fill.Climate }
                v == "media" && slot.size == Size.MEDIUM && !media -> { media = true; Fill.Media }
                else -> Fill.Cards(v.split(',').mapNotNull { id -> Card.entries.firstOrNull { it.id == id } }.filter { used.add(it) }).ordered()
            }
        }
        return Config(layout, fills)
    }

    /**
     * Choice made per widget before the places (wc_*, Shortcuts page setting #38): the same cards where
     * they were; the Energy card stays alone at the bottom left of the Full layout.
     */
    private fun legacy(prefs: Prefs, layout: Layout): Config {
        fun stored(widget: String, all: List<String>, def: List<String>): List<String> {
            val s = prefs.raw.getString("wc_$widget", null) ?: return def
            return all.filter { it in s.split(',') }.ifEmpty { listOf(all.first()) }
        }
        val mapCards = listOf("map", "consumption", "shortcuts", "web")
        val chosen = stored("map", mapCards, listOf("map", "consumption", "shortcuts"))
        // The shortcuts followed the Shortcuts page setting, which the edit mode changed too
        val map = mapCards.filter { if (it == "shortcuts") prefs.shortcutsPage else it in chosen }.ifEmpty { listOf("map") }
        val weather = stored("weather", listOf("hourly", "daily"), listOf("hourly", "daily"))
        val spec = if (layout == Layout.MAP) {
            val stack = stored("stack", listOf("energy", "trip", "power", "phone"), listOf("energy", "trip", "power", "phone"))
            "tl=${weather.joinToString(",")};tc=${stack.joinToString(",")};r=${map.joinToString(",")};bl=climate;bc=media"
        } else {
            val bottom = stored("bottom", listOf("trip", "power", "phone"), listOf("trip", "power", "phone"))
            "tl=climate;tc=${map.joinToString(",")};tr=media;bl=energy;bc=${weather.joinToString(",")};br=${bottom.joinToString(",")}"
        }
        return decode(layout, spec)
    }

    /** Each place of cards gets one it can show, if it has none. */
    fun fillEmpty(config: Config, unavailable: Set<Card> = emptySet()): Config {
        var c = config
        c.layout.slots.forEach { slot ->
            val f = c.fill(slot.id)
            if (f is Fill.Cards && f.cards.none { it !in unavailable }) {
                val (next, card) = take(c, slot.id, unavailable) ?: return@forEach
                c = next.with(slot.id, Fill.Cards(next.cards(slot.id) + card))
            }
        }
        return c
    }

    /**
     * A card for [slot]: one left out (the web app last, it needs an address), otherwise the last
     * card of the place that has the most, taken from there.
     */
    private fun take(config: Config, slot: String, unavailable: Set<Card>): Pair<Config, Card>? {
        val placed = config.placed()
        Card.entries.filter { it !in placed && it !in unavailable }.sortedBy { if (it == Card.WEB) 1 else 0 }.firstOrNull()?.let { return config to it }
        val from = config.layout.slots.map { it.id }.filter { it != slot }
            .maxByOrNull { id -> config.cards(id).count { it !in unavailable } } ?: return null
        val shown = config.cards(from).filter { it !in unavailable }
        if (shown.size < 2) return null
        val card = shown.last()
        return config.with(from, Fill.Cards(config.cards(from) - card)) to card
    }

    /**
     * Touch on a card in [slot]'s box: here, it leaves; elsewhere, it moves here (it leaves its old place).
     * Refused if a place would have no card left.
     */
    fun toggle(config: Config, slot: String, card: Card, unavailable: Set<Card> = emptySet()): Change {
        val here = config.fill(slot) as? Fill.Cards ?: return Change(config)
        fun shown(cards: List<Card>) = cards.count { it !in unavailable }
        if (card in here.cards) {
            if (shown(here.cards - card) == 0) return Change(config, Refusal.KEEP_ONE)
            return Change(config.with(slot, Fill.Cards(here.cards - card)))
        }
        var c = config
        val from = c.slotOf(card)
        if (from != null) {
            if (shown(c.cards(from) - card) == 0) return Change(config, Refusal.ONLY_CARD_THERE)
            c = c.with(from, Fill.Cards(c.cards(from) - card))
        }
        return Change(c.with(slot, Fill.Cards(c.cards(slot) + card)))
    }

    /**
     * Medium place: climate, media or cards. The climate or the media swaps places with what was here;
     * cards start with one (left out, or taken from the place that has the most).
     */
    fun setKind(config: Config, slot: String, kind: Kind, unavailable: Set<Card> = emptySet()): Change {
        if (config.layout.slot(slot)?.size != Size.MEDIUM) return Change(config)
        val old = config.fill(slot)
        val fill = when (kind) { Kind.CLIMATE -> Fill.Climate; Kind.MEDIA -> Fill.Media; Kind.CARDS -> null }
        if (fill != null) {
            if (old == fill) return Change(config)
            var c = config
            c.slotOf(fill)?.let { other -> c = c.with(other, old) }
            return Change(c.with(slot, fill))
        }
        if (old is Fill.Cards) return Change(config)
        val empty = config.with(slot, Fill.Cards(emptyList()))
        val (c, card) = take(empty, slot, unavailable) ?: return Change(config, Refusal.NO_CARD)
        return Change(c.with(slot, Fill.Cards(listOf(card))))
    }

    fun kindOf(fill: Fill): Kind = when (fill) { Fill.Climate -> Kind.CLIMATE; Fill.Media -> Kind.MEDIA; is Fill.Cards -> Kind.CARDS }

    private fun nowKey(layout: Layout, slot: String) = "slot_now_${layout.key}_$slot"

    /** Card shown in [slot] among [cards]: the last one shown there, otherwise the page its widget showed before. */
    fun current(prefs: Prefs, layout: Layout, slot: String, cards: List<Card>): Card? {
        if (cards.isEmpty()) return null
        val stored = prefs.raw.getString(nowKey(layout, slot), null)
        if (stored != null) return cards.firstOrNull { it.id == stored } ?: cards.first()
        return legacyPages(prefs, layout).firstOrNull { it in cards } ?: cards.first()
    }

    fun setCurrent(prefs: Prefs, layout: Layout, slot: String, card: Card) {
        prefs.raw.edit().putString(nowKey(layout, slot), card.id).apply()
    }

    /** Pages the widgets showed before the places (homeWidget, stackPage, secondaryWidget, weatherPage). */
    private fun legacyPages(prefs: Prefs, layout: Layout): List<Card> = listOfNotNull(
        when (prefs.homeWidget) { 1 -> Card.SHORTCUTS; 2 -> Card.CONSUMPTION; 3 -> Card.WEB; else -> Card.MAP },
        if (layout == Layout.MAP) when (prefs.stackPage) { 1 -> Card.TRIP; 2 -> Card.POWER; 3 -> Card.PHONE; else -> Card.ENERGY }
        else when (prefs.secondaryWidget) { 1 -> Card.POWER; 2 -> Card.PHONE; else -> Card.TRIP },
        if (prefs.weatherPage == 1) Card.DAILY else Card.HOURLY,
    )

    /** Card after [cur] in [cards], in direction [dir] (±1), going round. */
    fun next(cards: List<Card>, cur: Card?, dir: Int): Card? {
        if (cards.isEmpty()) return null
        val i = cards.indexOf(cur)
        return if (i < 0) cards.first() else cards[Math.floorMod(i + dir, cards.size)]
    }
}
