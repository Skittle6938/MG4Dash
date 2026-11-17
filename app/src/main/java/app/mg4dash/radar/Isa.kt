package app.mg4dash.radar

import android.content.Context
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import java.io.File

/**
 * The car's speed limit assistance (ISA), studied from the alerter (Marc, 17/11). Two parts, both off by default:
 * - [IsaLog], the read side: while driving, every change of the car's properties about speed limits, speed assistance
 *   and navigation goes to a file, with OSM's limit and the GPS speed beside it, to find a value that follows the signs;
 * - [IsaWrite], the write side: OSM's limit sent to the car as the navigation's speed limit. Its decision is complete
 *   and tested, the write itself is held back by [IsaWrite.WRITE_ALLOWED] until what the car does with it is known.
 */
object Isa {
    /** Properties of the car about speed limits, speed assistance and navigation (YFVehicleProperty names). */
    val PROPS: Map<String, Int> = linkedMapOf(
        "AAD_SLIF_WARNING" to 557883756,
        "AAD_SLIF_WARNING_VALID" to 557883759,
        "AAD_SPEED_ASST_SYS" to 557883649,
        "AAD_SPEED_ASST_MODE" to 557883650,
        "AAD_SPEED_ASST_MODE_FLAG" to 557883651,
        "NAVIGATION_SPEED_LIMIT_VALUE" to 559980933,
        "NAVIGATION_SPEED_LIMIT_VALUE_STATUS" to 557883779,
        "NAVIGATION_SPEED_LIMIT_VALUE_UNIT" to 557883780,
        "NAVIGATION_COUNTRY_CODE" to 557883777,
        "NAVIGATION_CRNT_ROAD_TYPE" to 557883778,
        "SETTING_NAVIGATION_DATA_STATE" to 289421335,
        "SENSOR_OVERSPEED_ALARM_HISTORY_STATE" to 557847911,
        "CAL_NAVIGATION" to 561005066,
        // Speed assistance of the VCU (firmware reading, 17/11): maybe the camera + map limit while the system is active
        "SPEED_AST_SYS_TRAGET_VCU" to 0x2160a212,
        "TRAGET_SPEED_SRC_STS_VCU" to 0x2140a211,
        "SPD_AST_MDECM_VCU" to 0x2140a1f8,
        "SPEED_AST_SYS_STS_ECM_VCU" to 0x2140a213,
    )

    /**
     * The car's own speed limit (km/h), once a property is known to carry it: none yet. When set, the card shows it,
     * marked "ISA", where OSM has no limit.
     */
    val CAR_LIMIT_PROP: Int? = null

    /** The car's limit from [CAR_LIMIT_PROP] (km/h), null when unknown or not a limit. */
    fun carLimit(read: (Int) -> String = { CarProps.readAny(it) }): Int? =
        CAR_LIMIT_PROP?.let { read(it).toFloatOrNull()?.toInt()?.takeIf { v -> v in 5..200 } }
}

/** Read side: changes of [Isa.PROPS] and, at each change of OSM's limit, every property that changed with it. */
object IsaLog {
    private const val TAG = "Isa"
    /** Each file at most this big; the previous one is kept as .old. */
    const val MAX_BYTES = 3_000_000L

    private val last = HashMap<Int, String>()
    private var snapshot: Map<Int, String>? = null
    private var lastOsm: Int? = null

    fun file(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "isa_log.csv")

    /** A line of the log: time, what, property, value before → after, OSM's limit, GPS speed. */
    internal fun line(time: Long, what: String, name: String, id: Int, before: String?, after: String, osm: Int?, gps: Float?): String =
        String.format(java.util.Locale.US, "%tF %<tT,%s,%s,0x%x,%s,%s,%s,%s", time, what, name, id, before ?: "", after,
            osm?.toString() ?: "", gps?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "")

    /**
     * Called on the alerter's thread at each position while the option is on ([osm]: OSM's limit now, [gps] km/h).
     * [readAll]: every property of the car (id → value), read only when OSM's limit changed.
     */
    fun sample(c: Context, now: Long, osm: Int?, gps: Float?, read: (Int) -> String = { CarProps.readAny(it) },
               readAll: () -> Map<Int, String> = { allProps() }) {
        val out = ArrayList<String>()
        for ((name, id) in Isa.PROPS) {
            val v = read(id)
            val before = last[id]
            if (v != before) { out += line(now, "prop", name, id, before, v, osm, gps); last[id] = v }
        }
        if (osm != lastOsm) {
            // OSM's limit changed: any property of the car that changed since the previous change of OSM's limit
            val all = readAll()
            snapshot?.let { prev ->
                for ((id, v) in all) if (prev[id] != null && prev[id] != v && Isa.PROPS.values.none { it == id })
                    out += line(now, "with-osm", "?", id, prev[id], v, osm, gps)
            }
            snapshot = all
            out += line(now, "osm", "OSM", 0, lastOsm?.toString(), osm?.toString() ?: "", osm, gps)
            lastOsm = osm
        }
        if (out.isNotEmpty()) write(file(c), out)
    }

    internal fun write(f: File, lines: List<String>) {
        try {
            if (f.length() > MAX_BYTES) { val old = File(f.path.removeSuffix(".csv") + ".old.csv"); old.delete(); f.renameTo(old) }
            if (!f.exists()) f.appendText("time,what,name,id,before,after,osm_kmh,gps_kmh\n")
            f.appendText(lines.joinToString("\n", postfix = "\n"))
        } catch (e: Exception) { Log.w(TAG, "log: ${e.message}") }
    }

    private fun allProps(): Map<Int, String> = try {
        CarProps.propertyIds().associateWith { CarProps.readAny(it) }
    } catch (_: Throwable) { emptyMap() }

    fun reset() { last.clear(); snapshot = null; lastOsm = null }
}

/**
 * Write side: when and what to send to the car as the navigation's speed limit, mirroring the stock map's
 * AdasManager.setSpdLmtVal (firmware reading, 17/11): the value to NAVIGATION_SPEED_LIMIT_VALUE and NAVIGATION_ROAD_SPD,
 * the status to both status properties, then the unit (km/h: 1, sent as 2 when the car has no ISA of its own, as the
 * stock code does). The car ignores the value without status and unit. Off by default, and held back entirely by
 * [WRITE_ALLOWED]: a wrong value could restrict the car or hide a regulatory ISA alert.
 */
object IsaWrite {
    private const val TAG = "Isa"
    /** Held back until Marc decides on a test in the car: with false, nothing is ever written, whatever the option. */
    const val WRITE_ALLOWED = false
    const val PROP = 0x2160a185          // NAVIGATION_SPEED_LIMIT_VALUE (float)
    const val ROAD_SPD = 0x2160a1a8      // NAVIGATION_ROAD_SPD (float)
    const val STATUS = 0x2140a183        // NAVIGATION_SPEED_LIMIT_VALUE_STATUS
    const val ROAD_STATUS = 0x2140a1a9
    const val UNIT = 0x2140a184          // NAVIGATION_SPEED_LIMIT_VALUE_UNIT
    /** Status values as the stock map sends them: a limit given, or none (to confirm in the car before any write). */
    const val STATUS_VALID = 1
    const val STATUS_NONE = 0
    /** At most one write every this long. */
    const val MIN_GAP_MS = 5_000L

    sealed class Decision {
        object Nothing : Decision()
        /** Send [value], in the car's unit. */
        data class Send(val value: Float) : Decision()
        /** OSM has no valid limit any more: clear what was sent. */
        object Clear : Decision()
    }

    /** One property write: float ([f]) or integer ([i]). */
    data class Write(val id: Int, val f: Float? = null, val i: Int? = null)

    /**
     * [osm]: OSM's limit (km/h) and whether it is only estimated (no tag); [car]: the car's own limit if known (km/h);
     * [sent]: what was sent last (km/h, 0 = cleared, null = nothing yet); [carMiles]: the car's limit unit is mph.
     */
    fun decide(osm: Int?, estimated: Boolean, car: Int?, sent: Int?, lastAt: Long, now: Long, carMiles: Boolean): Decision {
        if (now - lastAt < MIN_GAP_MS) return Decision.Nothing
        val valid = osm != null && !estimated && osm in 5..200
        if (!valid) return if (sent != null && sent != 0) Decision.Clear else Decision.Nothing
        if (osm == sent) return Decision.Nothing
        if (car != null && car == osm) return Decision.Nothing
        val v = if (carMiles) osm!! / Thresholds.KM_PER_MI else osm!!.toFloat()
        return Decision.Send(Math.round(v).toFloat())
    }

    /** The writes of a decision, in the stock order: values, statuses, unit ([hasIsa]: the car has its own ISA). */
    fun writes(d: Decision, hasIsa: Boolean, miles: Boolean): List<Write> {
        val unit = if (miles) 2 else 1
        val sentUnit = if (!hasIsa && unit == 1) 2 else unit
        return when (d) {
            Decision.Nothing -> emptyList()
            is Decision.Send -> listOf(Write(PROP, f = d.value), Write(ROAD_SPD, f = d.value),
                Write(STATUS, i = STATUS_VALID), Write(ROAD_STATUS, i = STATUS_VALID), Write(UNIT, i = sentUnit))
            Decision.Clear -> listOf(Write(PROP, f = 0f), Write(ROAD_SPD, f = 0f),
                Write(STATUS, i = STATUS_NONE), Write(ROAD_STATUS, i = STATUS_NONE), Write(UNIT, i = sentUnit))
        }
    }

    private var sent: Int? = null
    private var lastAt = 0L
    /** Last decision taken (time, km/h sent, 0 = cleared) and whether it reached the car; null: none yet. */
    @Volatile var last: Triple<Long, Int, Boolean>? = null
        private set

    /** Called with the alerter's state while the option is on; writes only with [WRITE_ALLOWED]. */
    fun apply(osm: Int?, estimated: Boolean, car: Int?, now: Long, carMiles: Boolean, hasIsa: Boolean, write: (Write) -> Boolean) {
        val d = decide(osm, estimated, car, sent, lastAt, now, carMiles)
        if (d == Decision.Nothing) return
        lastAt = now
        val ws = writes(d, hasIsa, carMiles)
        if (WRITE_ALLOWED) ws.forEach { write(it) }
        else Log.i(TAG, "held back: " + ws.joinToString { "0x${Integer.toHexString(it.id)}=${it.f ?: it.i}" })
        sent = if (d is Decision.Send) osm else 0
        last = Triple(System.currentTimeMillis(), sent ?: 0, WRITE_ALLOWED)
    }
}
