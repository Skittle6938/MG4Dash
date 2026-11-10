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
    /**
     * Sizes of the shared grid (#38): three equal columns, 60 / 40 % of the height or the full height. SMALL ≈ 594 × 274,
     * MEDIUM ≈ 594 × 410, TALL ≈ 595 × 696; LARGE (two columns, full height) ≈ 1189 × 696, WIDE (two columns, 60 %) ≈ 1197 × 410.
     */
    enum class Size {
        SMALL, MEDIUM, TALL, LARGE, WIDE;

        /** Columns it spans. */
        val span: Int get() = if (this == LARGE || this == WIDE) 2 else 1
    }

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
        /** The last trips (#102): only where the driver places it, never put in a place by itself. */
        TRIPS("trips", R.string.wc_trips, R.drawable.ic_car),
        /** Driving settings (#38): only where the driver places it, as the last trips. */
        DRIVING("driving", R.string.wc_driving, R.drawable.ic_car);

        /** Fills a big place (two columns): the others would look lost in it, they are greyed there in the edit mode. */
        val big: Boolean get() = this == MAP || this == WEB || this == CONSUMPTION || this == SHORTCUTS || this == DRIVING

        /** Only placed by the driver, never put in an empty place by itself. */
        val manual: Boolean get() = this == TRIPS || this == DRIVING
    }

    /** [card] may go in a place of [size]. */
    fun fits(card: Card, size: Size): Boolean = size.span == 1 || card.big

    /** What a place shows. */
    sealed class Fill {
        data object Climate : Fill()
        data object Media : Fill()
        data class Cards(val cards: List<Card>) : Fill()
    }

    /** Choice of a medium place in the edit mode. */
    enum class Kind { CLIMATE, MEDIA, CARDS }

    /** A place: its id, its size and its share of its line (the column's height; in a layout of rows, its share of the row is its span). */
    class Slot(val id: String, val size: Size, val weight: Float = 100f)

    /** A column (or a row of a layout of [Layout.rows]): its places, its share of the height for a row. */
    class Line(val slots: List<Slot>, val weight: Float = 100f) {
        /** Columns it spans (a column with the big square spans two). */
        val span: Int get() = slots.maxOf { it.size.span }
    }

    private fun col(vararg s: Slot) = Line(s.toList())
    private fun P(id: String) = Slot(id, Size.SMALL, 40f)
    private fun M(id: String) = Slot(id, Size.MEDIUM, 60f)
    private fun H(id: String) = Slot(id, Size.TALL)
    private fun G(id: String) = Slot(id, Size.LARGE)

    /**
     * The eight layouts of the shared grid (#38, mockups v5). [lines]: columns from left to right, or rows from top to
     * bottom when [rows] (the bands, whose wide place spans two columns of the row). [label]: its name in the menus.
     */
    enum class Layout(val key: String, val label: Int, val lines: List<Line>, val defaults: String, val rows: Boolean = false) {
        FULL("full", R.string.ly_full, listOf(col(M("tl"), P("bl")), col(M("tc"), P("bc")), col(M("tr"), P("br"))),
            "tl=climate;tc=map,consumption,shortcuts;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone"),
        /** Small places on top, medium ones at the bottom, the map at full height on the right (#6). */
        MAP("map", R.string.ly_map_right, listOf(col(P("tl"), M("bl")), col(P("tc"), M("bc")), col(H("r"))),
            "tl=hourly,daily;tc=energy,trip,power,phone;r=map,consumption,shortcuts;bl=climate;bc=media"),
        MAP_LEFT("map_left", R.string.ly_map_left, listOf(col(H("l")), col(P("tc"), M("bc")), col(P("tr"), M("br"))),
            "l=map,consumption,shortcuts;tc=hourly,daily;tr=energy,trip,power,phone;bc=climate;br=media"),
        MAP_CENTER("map_center", R.string.ly_map_center, listOf(col(P("tl"), M("bl")), col(H("c")), col(M("tr"), P("br"))),
            "tl=hourly,daily;bl=climate;c=map,consumption,shortcuts;tr=media;br=energy,trip,power,phone"),
        SQUARE_LEFT("square_left", R.string.ly_square_left, listOf(col(G("g")), col(M("tr"), P("br"))),
            "g=map,consumption,shortcuts;tr=climate;br=energy,trip,power,phone,hourly,daily"),
        SQUARE_RIGHT("square_right", R.string.ly_square_right, listOf(col(M("tl"), P("bl")), col(G("g"))),
            "tl=climate;bl=energy,trip,power,phone,hourly,daily;g=map,consumption,shortcuts"),
        BAND_TOP("band_top", R.string.ly_band_top, listOf(
            Line(listOf(Slot("w", Size.WIDE, 2f), Slot("tr", Size.MEDIUM, 1f)), 60f),
            Line(listOf(Slot("bl", Size.SMALL, 1f), Slot("bc", Size.SMALL, 1f), Slot("br", Size.SMALL, 1f)), 40f),
        ), "w=map,consumption,shortcuts;tr=climate;bl=energy;bc=hourly,daily;br=trip,power,phone", rows = true),
        BAND_BOTTOM("band_bottom", R.string.ly_band_bottom, listOf(
            Line(listOf(Slot("tl", Size.SMALL, 1f), Slot("tc", Size.SMALL, 1f), Slot("tr", Size.SMALL, 1f)), 40f),
            Line(listOf(Slot("bl", Size.MEDIUM, 1f), Slot("w", Size.WIDE, 2f)), 60f),
        ), "tl=hourly,daily;tc=energy;tr=trip,power,phone;bl=climate;w=map,consumption,shortcuts", rows = true);

        val slots: List<Slot> get() = lines.flatMap { it.slots }
        fun slot(id: String): Slot? = slots.firstOrNull { it.id == id }

        /**
         * Each place in a 1 × 1 square ([gapX], [gapY]: the gap between places as a share of the width, the height):
         * the drawing of the layout, the same grid as the home screen.
         */
        fun cells(gapX: Float, gapY: Float): Map<String, android.graphics.RectF> {
            val out = LinkedHashMap<String, android.graphics.RectF>()
            val unit = (1f - 2 * gapX) / 3
            fun width(span: Int) = span * unit + (span - 1) * gapX
            if (!rows) {
                var x = 0f
                lines.forEach { line ->
                    val w = width(line.span)
                    val total = line.slots.sumOf { it.weight.toDouble() }.toFloat()
                    val room = 1f - gapY * (line.slots.size - 1)
                    var y = 0f
                    line.slots.forEach { s -> val h = room * s.weight / total; out[s.id] = android.graphics.RectF(x, y, x + w, y + h); y += h + gapY }
                    x += w + gapX
                }
            } else {
                val total = lines.sumOf { it.weight.toDouble() }.toFloat()
                val room = 1f - gapY * (lines.size - 1)
                var y = 0f
                lines.forEach { line ->
                    val h = room * line.weight / total
                    var x = 0f
                    line.slots.forEach { s -> val w = width(s.size.span); out[s.id] = android.graphics.RectF(x, y, x + w, y + h); x += w + gapX }
                    y += h + gapY
                }
            }
            return out
        }

        companion object {
            fun of(key: String?): Layout? = entries.firstOrNull { it.key == key }
        }
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
    enum class Refusal { KEEP_ONE, ONLY_CARD_THERE, NO_CARD, TOO_BIG }

    class Change(val config: Config, val refused: Refusal? = null)

    private fun Fill.ordered(): Fill = if (this is Fill.Cards) Fill.Cards(cards.distinct().sortedBy { it.ordinal }) else this

    /** The layout chosen; before the eight layouts (#38), the Map home style was the Map layout. */
    fun layoutOf(prefs: Prefs): Layout = Layout.of(prefs.homeLayout) ?: if (prefs.homeStyle == 2) Layout.MAP else Layout.FULL

    /** Chooses [layout] (Settings › Home, the edit mode): the home screen leaves the Simple style. */
    fun choose(prefs: Prefs, layout: Layout) {
        prefs.homeLayout = layout.key
        prefs.homeStyle = 0
    }

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
                else -> Fill.Cards(v.split(',').mapNotNull { id -> Card.entries.firstOrNull { it.id == id } }.filter { fits(it, slot.size) && used.add(it) }).ordered()
            }
        }
        return Config(layout, fills)
    }

    /**
     * Choice made per widget before the places (wc_*, Shortcuts page setting #38): the same cards where
     * they were; the Energy card stays alone at the bottom left of the Full layout.
     */
    private fun legacy(prefs: Prefs, layout: Layout): Config {
        // The six newer layouts (#38) came after: their defaults
        if (layout != Layout.FULL && layout != Layout.MAP) return defaults(layout)
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
        val size = config.layout.slot(slot)?.size ?: Size.SMALL
        Card.entries.filter { it !in placed && it !in unavailable && !it.manual && fits(it, size) }.sortedBy { if (it == Card.WEB) 1 else 0 }.firstOrNull()?.let { return config to it }
        val from = config.layout.slots.map { it.id }.filter { it != slot }
            .maxByOrNull { id -> config.cards(id).count { it !in unavailable && fits(it, size) } } ?: return null
        val shown = config.cards(from).filter { it !in unavailable }
        if (shown.size < 2) return null
        val card = shown.lastOrNull { fits(it, size) } ?: return null
        return config.with(from, Fill.Cards(config.cards(from) - card)) to card
    }

    /**
     * Touch on a card in [slot]'s box: here, it leaves; elsewhere, it moves here (it leaves its old place).
     * Refused if a place would have no card left.
     */
    fun toggle(config: Config, slot: String, card: Card, unavailable: Set<Card> = emptySet()): Change {
        val here = config.fill(slot) as? Fill.Cards ?: return Change(config)
        if (card !in here.cards && !fits(card, config.layout.slot(slot)?.size ?: Size.SMALL)) return Change(config, Refusal.TOO_BIG)
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
