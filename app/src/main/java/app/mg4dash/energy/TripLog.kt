package app.mg4dash.energy

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar

/**
 * The last trips (#102, Trips card): each one recorded when the next one starts (a start after the car's standby),
 * the 50 latest kept, read back after a restart. The trip in progress is saved every 30 s too, so a restart of
 * MG4Dash during it (an update) does not lose it: it is recorded at the next start.
 */
object TripLog {
    const val MAX = 50
    private const val KEY = "trip_log"
    private const val CURRENT = "trip_cur"
    /** Shorter than that, a trip is not kept (the car turned on to charge, a few metres in the car park). */
    const val MIN_KM = 0.1f

    /** One trip: its times (ms), the odometer at its start and its end, its distance (to the tenth) and net kWh. */
    data class Trip(
        val start: Long, val end: Long, val startKm: Int, val endKm: Int,
        val km: Float, val kwh: Float, val startSoc: Int? = null, val endSoc: Int? = null,
    ) {
        val minutes: Long get() = ((end - start) / 60_000L).coerceAtLeast(0L)
        /** kWh/100 from 0.5 km, as on the energy card; null below. */
        val per100: Float? get() = if (km >= 0.5f && kwh > 0.02f) (kwh / km * 100f).takeIf { it in 3f..60f } else null

        fun encode() = listOf(start, end, startKm, endKm, km, kwh, startSoc ?: -1, endSoc ?: -1).joinToString(",")

        companion object {
            fun decode(s: String): Trip? = try {
                val p = s.split(',')
                Trip(p[0].toLong(), p[1].toLong(), p[2].toInt(), p[3].toInt(), p[4].toFloat(), p[5].toFloat(),
                    p[6].toInt().takeIf { it >= 0 }, p[7].toInt().takeIf { it >= 0 })
            } catch (_: Exception) { null }
        }
    }

    private val _trips = MutableStateFlow<List<Trip>>(emptyList())
    /** The trips kept, the latest first (read back by [load]). */
    val trips: StateFlow<List<Trip>> = _trips
    private val _current = MutableStateFlow<Trip?>(null)
    /** The trip in progress, null before its first km is known. */
    val current: StateFlow<Trip?> = _current

    /** Totals of a day: its trips (and the one in progress if it started that day). */
    data class Day(val trips: Int, val km: Float, val minutes: Long, val firstKm: Int?)

    /** The trips kept, the latest first. */
    fun load(sp: SharedPreferences): List<Trip> =
        (sp.getString(KEY, null)?.split(';')?.mapNotNull { Trip.decode(it) } ?: emptyList()).also { _trips.value = it }

    /** [trip] first, the oldest beyond [MAX] dropped; too short, nothing is kept. */
    fun add(sp: SharedPreferences, trip: Trip) {
        if (trip.km < MIN_KM) return
        val all = (listOf(trip) + load(sp)).take(MAX)
        sp.edit().putString(KEY, all.joinToString(";") { it.encode() }).apply()
        _trips.value = all
    }

    fun clear(sp: SharedPreferences) { sp.edit().remove(KEY).apply(); _trips.value = emptyList() }

    /** The trip in progress, saved to be recorded at the next start even after a restart. */
    fun saveCurrent(sp: SharedPreferences, trip: Trip?) {
        _current.value = trip
        sp.edit().apply { if (trip == null) remove(CURRENT) else putString(CURRENT, trip.encode()) }.apply()
    }

    /** At a new start: the trip saved before it goes to the list. */
    fun closeCurrent(sp: SharedPreferences) {
        sp.getString(CURRENT, null)?.let { Trip.decode(it) }?.let { add(sp, it) }
        sp.edit().remove(CURRENT).apply()
        _current.value = null
    }

    /**
     * Manual reading (ride-hailing, deliveries): the odometer at its start and its end, whatever the trips between
     * them. Kept until the next one starts, the car turned off in between included.
     */
    data class Reading(val start: Long, val startKm: Int, val end: Long = 0L, val endKm: Int = -1) {
        val running: Boolean get() = end == 0L
        /** km since its start, at [km] now while it runs. */
        fun km(km: Int?): Int? = (if (running) km else endKm)?.takeIf { it >= startKm }?.minus(startKm)
    }

    fun reading(sp: SharedPreferences): Reading? {
        val start = sp.getLong("trip_rd_t", 0L).takeIf { it > 0L } ?: return null
        return Reading(start, sp.getInt("trip_rd_km", 0), sp.getLong("trip_rd_end", 0L), sp.getInt("trip_rd_km1", -1))
    }

    fun startReading(sp: SharedPreferences, now: Long, km: Int) {
        sp.edit().putLong("trip_rd_t", now).putInt("trip_rd_km", km).remove("trip_rd_end").remove("trip_rd_km1").apply()
    }

    fun stopReading(sp: SharedPreferences, now: Long, km: Int) {
        if (reading(sp)?.running != true) return
        sp.edit().putLong("trip_rd_end", now).putInt("trip_rd_km1", km).apply()
    }

    /** Midnight (local time) of the day of [at]. */
    fun dayStart(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Today's totals at [now]: the trips started since midnight, [current] included. */
    fun today(trips: List<Trip>, current: Trip?, now: Long): Day {
        val from = dayStart(now)
        val day = (listOfNotNull(current) + trips).filter { it.start >= from && it.km >= MIN_KM }
        return Day(day.size, day.sumOf { it.km.toDouble() }.toFloat(), day.sumOf { it.minutes }, day.minOfOrNull { it.startKm })
    }
}
