package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Prefs

/**
 * The battery left at the end of the Android Auto route (guidance card, 19/11): the charge now, less the
 * distance left at the consumption so far, over the battery's usable capacity. An estimate: neither the climbs, nor a
 * faster road, nor the climate ahead are known, hence the "≈" on the card.
 */
object ArrivalBattery {
    /** The trip's own consumption, only when there is no average yet, once it has this many km. */
    const val MIN_TRIP_KM = 5

    /**
     * The % left on arrival, rounded, never below 0; null when a figure is missing: [soc] (%), [remainingKm],
     * [kwhPer100] (kWh/100 km), [usableKwh].
     */
    fun percent(soc: Float?, remainingKm: Float?, kwhPer100: Float?, usableKwh: Float?): Int? {
        if (soc == null || remainingKm == null || kwhPer100 == null || usableKwh == null) return null
        if (remainingKm < 0f || kwhPer100 <= 0f || usableKwh <= 0f) return null
        val used = remainingKm * kwhPer100 / 100f / usableKwh * 100f
        return Math.round(soc - used).coerceAtLeast(0)
    }

    /**
     * The consumption to use (20/11): the average the energy card shows, since the last charge, which moves
     * slowly; the trip's own, which swings with each climb and each start, only while there is no average yet.
     */
    fun consumption(tripKm: Int, tripPer100: Float?, average: Float?): Float? =
        average ?: tripPer100?.takeIf { tripKm >= MIN_TRIP_KM }

    @Volatile private var capacity: Pair<Long, Float?> = 0L to null

    /** The usable capacity measured (cached a minute, it reads the stored measurements), else the one set. */
    private fun usable(context: Context): Float {
        val now = System.currentTimeMillis()
        val (at, kwh) = capacity
        if (now - at < 60_000L && kwh != null) return kwh
        val k = BatteryCapacity.estimate(context)?.first ?: Prefs(context).batteryKwh
        capacity = now to k
        return k
    }

    /** The % on arrival for [remainingMeters] left, from what the car and the energy counters say now. */
    fun now(context: Context, remainingMeters: Int?): Int? {
        val s = CarBridge.state.value
        val soc = s.socPrecise?.takeIf { v -> s.batteryPercent == null || Math.abs(v - s.batteryPercent) < 1.5f } ?: s.batteryPercent?.toFloat()
        val trip = EnergyMonitor.sinceStart.value
        val per100 = consumption(trip.km, trip.per100, ConsumptionEstimator.average.value)
        return percent(soc, remainingMeters?.takeIf { it >= 0 }?.let { it / 1000f }, per100, usable(context))
    }

}
