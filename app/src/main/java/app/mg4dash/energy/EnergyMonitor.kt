package app.mg4dash.energy

import android.content.Context
import app.mg4dash.BuildConfig
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Measured consumption, split between driving, climate and accessories.
 *
 * Found on the car (log of 2026-10-14): the SAIC consumption counters stay
 * at zero, but battery voltage and current are reliable. So, every second:
 *  - P = voltage × current; P > 0 = energy consumed, P < 0 while moving = regeneration;
 *  - stopped, climate off: P = accessories (12 V, screen…) → learned, by state of the lights /
 *    wipers (difference measured in identical conditions);
 *  - stopped, climate on: P − accessories = climate → learned by setting (fan,
 *    heating / cooling, defrost);
 *  - moving: climate and accessories = learned values, the rest = driving.
 * Three periods: since the last charge, since the start (pause of more than 10 min), and the
 * last 12 months (one total per month, the oldest removed: bounded memory, and the trend through
 * the seasons reads month by month).
 */
object EnergyMonitor {
    private const val TAG = "EnergyMon"
    private const val SLEEP_GAP_MS = 10 * 60_000L
    /** % rise (points) that marks a charge; regeneration downhill stays below it. */
    const val RECHARGE_PCT = 3

    data class Totals(
        val outKwh: Float = 0f,      // energy out of the battery
        val regenKwh: Float = 0f,    // recovered while moving
        val climKwh: Float = 0f,
        val accKwh: Float = 0f,
        val km: Int = 0,
        val heatKwh: Float = 0f,     // battery heating (estimated)
    ) {
        val driveKwh: Float get() = (outKwh - climKwh - accKwh - heatKwh).coerceAtLeast(0f)
        val netKwh: Float get() = (outKwh - regenKwh).coerceAtLeast(0f)
        /** Net kWh/100 km (regeneration deducted), from 2 km. */
        val per100: Float? get() = if (km >= 2 && netKwh > 0.1f) (netKwh / km * 100f).takeIf { it in 3f..60f } else null
    }

    private val _sinceCharge = MutableStateFlow(Totals())
    val sinceCharge: StateFlow<Totals> = _sinceCharge
    private val _sinceStart = MutableStateFlow(Totals())
    val sinceStart: StateFlow<Totals> = _sinceStart
    /** Battery % at the start of the current trip (range bar marker, issue #29). */
    private val _tripStartSoc = MutableStateFlow<Int?>(null)
    val tripStartSoc: StateFlow<Int?> = _tripStartSoc
    /** Time (ms) the current trip started: its duration on the consumption card (#44); 0 before the first pass. */
    @Volatile var tripStartedAt = 0L; private set
    internal fun tripStartForTest(at: Long) { tripStartedAt = at }
    /** Trip number, increased at each new start (reset of min V / max A, #29). */
    @Volatile var tripSeq = 0; private set
    private val _lastYear = MutableStateFlow(Totals())
    /** The last 12 months (current month included). */
    val lastYear: StateFlow<Totals> = _lastYear
    private val _months = MutableStateFlow<List<Pair<String, Totals>>>(emptyList())
    /** Month by month totals ("2026-10"), oldest to newest: 12 at most. */
    val months: StateFlow<List<Pair<String, Totals>>> = _months
    private const val MONTHS = 12

    /** Reset requested with the curve's erase (#44): 0 charge, 1 trip, 2 12 months; -1 = nothing. */
    @Volatile private var resetRequest = -1
    fun reset(period: Int) { resetRequest = period }

    /** Period chosen on screen: 0 = since the charge, 1 = since the start, 2 = last 12 months. */
    fun totals(period: Int): Totals = when (period) { 1 -> sinceStart.value; 2 -> lastYear.value; else -> sinceCharge.value }

    /** kWh/100 km of each month driven enough (at least 20 km), for the year's curve. */
    fun monthlyPer100(): List<Float> = months.value.mapNotNull { (_, t) -> if (t.km >= 20) t.per100 else null }

    private fun monthKey(now: Long = System.currentTimeMillis()) =
        java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(now))

    private fun sum(list: Collection<Totals>) = list.fold(Totals()) { a, t ->
        Totals(a.outKwh + t.outKwh, a.regenKwh + t.regenKwh, a.climKwh + t.climKwh, a.accKwh + t.accKwh, a.km + t.km, a.heatKwh + t.heatKwh)
    }

    /** Learned powers (W), for the display and the log. */
    @Volatile var accessoriesW = 250f; private set
    @Volatile var climateW = 0f; private set
    /** Battery heating: learned average power (W) when it can heat, 0 otherwise. */
    @Volatile var batteryHeatW = 0f; private set
    /** Heating can run: option on and 10 °C or less outside (the car does not say if it is heating). */
    @Volatile var batteryHeatPossible = false; private set
    /** Manual's threshold: smart heating only starts if the battery is at 10 °C or less. */
    const val HEAT_MAX_OUTSIDE_C = 10f
    /** "Battery" share shown in the breakdowns from this total (kWh): nothing in summer. */
    const val HEAT_SHOWN_KWH = 0.05f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun start(context: Context) {
        val prefs = Prefs(context)
        val sp = prefs.raw
        if (BuildConfig.MOCK) {
            _sinceCharge.value = Totals(18.4f, 3.2f, 2.1f, 0.9f, 112, heatKwh = 1.2f)
            _sinceStart.value = Totals(3.6f, 0.7f, 0.5f, 0.2f, 21, heatKwh = 0.4f)
            val demo = listOf(19.8f, 19.1f, 17.6f, 16.0f, 15.1f, 14.4f, 14.2f, 14.6f, 15.3f, 16.4f, 17.9f, 19.2f)
            _months.value = demo.mapIndexed { i, v -> "2025-%02d".format(i + 1) to Totals(v * 10f, 1.5f, 1.2f, 0.4f, 1000).let { it.copy(outKwh = v * 10f + it.regenKwh) } }
            _lastYear.value = sum(_months.value.map { it.second })
            return
        }
        fun load(p: String) = Totals(sp.getFloat("${p}_out", 0f), sp.getFloat("${p}_rgn", 0f), sp.getFloat("${p}_clim", 0f), sp.getFloat("${p}_acc", 0f),
            heatKwh = sp.getFloat("${p}_heat", 0f))
        _sinceCharge.value = load("em_c")
        accessoriesW = sp.getFloat("em_base", 250f)
        // Months: "2026-10=out,rgn,clim,acc,km[,heat]" separated by ";" (12 at most)
        val months = java.util.TreeMap<String, Totals>()
        sp.getString("em_months", "")!!.split(';').filter { it.contains('=') }.forEach { e ->
            val (k, v) = e.split('=', limit = 2)
            val f = v.split(',').mapNotNull { it.toFloatOrNull() }
            // Months dated 2019 (head unit clock not yet reset at wake-up, before 1.1.8): discarded
            if (f.size in 5..6 && k >= "2024") months[k] = Totals(f[0], f[1], f[2], f[3], f[4].toInt(), f.getOrElse(5) { 0f })
        }
        fun publishMonths() {
            while (months.size > MONTHS) months.remove(months.firstKey())
            _months.value = months.entries.map { it.key to it.value }
            _lastYear.value = sum(months.values)
        }
        fun saveMonths() = sp.edit().putString("em_months",
            months.entries.joinToString(";") { (k, t) -> "$k=${t.outKwh},${t.regenKwh},${t.climKwh},${t.accKwh},${t.km},${t.heatKwh}" }).apply()
        publishMonths()

        scope.launch {
            // Internal clock: the head unit's system time goes back to 2019 at each wake-up then returns
            // (log of 2026-10-19), which gave negative durations and skewed the kWh.
            var last = android.os.SystemClock.elapsedRealtime()
            var chargeKm = sp.getInt("em_c_km0", -1)
            var monthKm = -1   // odometer at the last pass
            var pendingKm = 0  // km driven not yet counted in the current month
            var startKm = -1
            // Lowest % of the current period (before 1.1.4: last % seen, "em_last_soc")
            var lowSoc = sp.getInt("em_c_low", sp.getInt("em_last_soc", -1))
            Log.i(TAG, "since charge: start ${sp.getInt("em_c_soc0", -1)} % at $chargeKm km, lowest $lowSoc %, " +
                "${"%.2f".format(java.util.Locale.US, _sinceCharge.value.netKwh)} kWh net")
            var saveTick = 0
            var sohSoc: Float? = null      // % at the previous second, null outside observation
            var sohPrecise = false
            var sohKm: Int? = null         // odometer at the previous second
            val learned = HashMap<String, Float>()
            fun get(k: String, def: Float) = learned.getOrPut(k) { sp.getFloat(k, def) }
            var ptcCheckedAt = -60_000L
            var ptcOn = false
            fun put(k: String, v: Float) { learned[k] = v }
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val tick = android.os.SystemClock.elapsedRealtime()
                val dt = tick - last
                last = tick
                val s = CarBridge.state.value
                val km = s.odometerKm
                val soc = s.batteryPercent
                // Month's km: read at each pass (even when the power measurement is skipped)
                if (km != null) {
                    if (monthKm >= 0 && km > monthKm) pendingKm += (km - monthKm).coerceAtMost(1000)
                    monthKm = km
                }

                // New start: long pause (car in standby) or first pass
                if (dt > SLEEP_GAP_MS || startKm < 0) {
                    if (km != null) startKm = km
                    _sinceStart.value = Totals()
                    _tripStartSoc.value = soc
                    tripStartedAt = now
                    tripSeq++
                }
                if (_tripStartSoc.value == null && soc != null) {
                    _tripStartSoc.value = soc
                }
                // Reset of the period's totals, with the erase of its curve (#44)
                when (resetRequest.also { if (it >= 0) resetRequest = -1 }) {
                    0 -> {
                        Log.i(TAG, "since-charge totals cleared from the screen")
                        _sinceCharge.value = Totals()
                        if (km != null) chargeKm = km
                        sp.edit().putInt("em_c_km0", chargeKm)
                            .putFloat("em_c_out", 0f).putFloat("em_c_rgn", 0f).putFloat("em_c_clim", 0f).putFloat("em_c_acc", 0f).putFloat("em_c_heat", 0f).apply()
                    }
                    1 -> { _sinceStart.value = Totals(); if (km != null) startKm = km; tripStartedAt = now }
                    2 -> { Log.i(TAG, "12-month totals cleared from the screen"); months.clear(); pendingKm = 0; publishMonths(); saveMonths() }
                }
                // Charge: the % goes up at least RECHARGE_PCT points above the lowest of the
                // period → new "since the charge" period. Comparing to the lowest (and not to the previous
                // %) also covers a charge followed % by %: MG4Dash can run in standby
                // during charging (found on 2026-10-18, counters not reset).
                if (soc != null) {
                    // Safety net: % above the period's start % → a charge must have happened (period
                    // left stuck before 1.1.4, when a charge seen % by % was not detected)
                    val soc0 = sp.getInt("em_c_soc0", -1)
                    // Inconsistent period: more than 60 kWh/100 over at least 10 km (kWh of a previous period
                    // kept with the new one's km, see the save below)
                    val c = _sinceCharge.value
                    val bogus = c.km >= 10 && c.netKwh / c.km * 100f > 60f
                    if (lowSoc >= 0 && soc >= lowSoc + RECHARGE_PCT || soc0 >= 0 && soc >= soc0 + RECHARGE_PCT ||
                        chargeKm < 0 && km != null || bogus) {
                        Log.i(TAG, if (bogus) "inconsistent since-charge period (${"%.1f".format(java.util.Locale.US, c.netKwh)} kWh net " +
                            "for ${c.km} km): reset" else "new since-charge period ($lowSoc → $soc %)")
                        _sinceCharge.value = Totals()
                        chargeKm = km ?: -1
                        lowSoc = soc
                        // Reset counters saved at once with the start km: before 1.1.8,
                        // they were only saved at the next save (30 s, skipped with the car plugged in), and
                        // MG4Dash stopped in between reloaded the old kWh with the new km.
                        sp.edit().putInt("em_c_km0", chargeKm).putInt("em_c_soc0", soc).putInt("em_c_low", soc)
                            .putFloat("em_c_out", 0f).putFloat("em_c_rgn", 0f).putFloat("em_c_clim", 0f).putFloat("em_c_acc", 0f).putFloat("em_c_heat", 0f).apply()
                    } else if (lowSoc < 0 || soc < lowSoc) {
                        lowSoc = soc; sp.edit().putInt("em_c_low", soc).apply()
                    }
                }

                val v = CarProps.getFloat(CarProps.BMS_PACK_VOL)
                val i = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
                if (v == null || i == null || v < 100f || v > 900f || dt <= 0 || dt > 5000 ||
                    VehicleState.isPlugged(s.chargeStatus)) {   // charging or V2L: outside consumption
                    sohSoc = null; sohKm = null; continue
                }
                val p = v * i
                val h = dt / 3_600_000f
                // Battery health: energy of this second and % drop since the previous second.
                // The % to the tenth if the car gives it (same source throughout an observed sequence).
                val precise = s.socPrecise?.takeIf { soc != null && kotlin.math.abs(it - soc) < 1.5f }
                val cur = if (sohPrecise) precise else soc?.toFloat()
                val prev = sohSoc
                if (prev == null || cur == null || kotlin.math.abs(prev - cur) > 2f) {
                    sohPrecise = precise != null
                    sohSoc = precise ?: soc?.toFloat()
                    sohKm = km
                } else {
                    val prevKm = sohKm
                    val dKm = if (km != null && prevKm != null) (km - prevKm).coerceIn(0, 2) else 0
                    BatteryCapacity.accumulate(context, p * h / 1000f, prev - cur, dKm.toFloat())
                    sohSoc = cur
                    if (km != null) sohKm = km
                }
                // Old firmwares (SWI68, #19): no vehicle speed, regeneration stayed at 0 kWh;
                // fallback on recent GPS speed.
                val speed = s.speedKmh ?: app.mg4dash.map.LocationRepo.location.value
                    ?.takeIf { it.hasSpeed() && android.os.SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos < 10_000_000_000L }?.speed?.times(3.6f)
                val stopped = (speed ?: 0f) < 0.5f
                val hvacOn = s.hvacOn == true

                // Learning when stopped
                val accKey = "em_acc_" + accessoryKey(s)
                val accW = get(accKey, accessoriesW)
                val climKey = "em_clim_" + climateKey(s)
                // Battery heating: option reread every 30 s (read 0 = on)
                if (tick - ptcCheckedAt > 30_000) {
                    ptcCheckedAt = tick
                    ptcOn = CarProps.getInt(CarProps.DRVNG_PTC_HEAT) == 0
                }
                val heatPossible = ptcOn && (s.outsideTemp ?: 99f) <= HEAT_MAX_OUTSIDE_C
                batteryHeatPossible = heatPossible
                val heatLearnS = get("em_bheat_s", 0f)
                if (stopped && heatPossible && p in 30f..12000f) {
                    // It can heat: what exceeds learned accessories and climate is the heating
                    // (average, time when it is not heating included); accessories and climate not learned here
                    val climPart = if (hvacOn) get(climKey, get("em_clim_any", 1000f)) else 0f
                    val hw = (p - accW - climPart).coerceAtLeast(0f)
                    put("em_bheat", ema(get("em_bheat", hw), hw, 0.02f))
                    put("em_bheat_s", heatLearnS + 1f)
                } else if (stopped && p in 30f..8000f) {
                    if (!hvacOn) {
                        val nv = ema(accW, p, 0.02f)
                        put(accKey, nv)
                        if (accKey == "em_acc_off") { accessoriesW = nv; put("em_base", nv) }
                    } else {
                        val c = (p - accW).coerceAtLeast(0f)
                        put(climKey, ema(get(climKey, c), c, 0.05f))
                        put("em_clim_any", ema(get("em_clim_any", c), c, 0.05f))
                    }
                }

                // Breakdown
                var out = 0f; var regen = 0f; var clim = 0f; var acc = 0f; var heat = 0f
                val hw = if (heatPossible) get("em_bheat", 0f) else 0f
                batteryHeatW = hw
                if (p >= 0f) {
                    out = p * h
                    val cw = if (!hvacOn) 0f else if (stopped && !heatPossible) (p - accW).coerceAtLeast(0f)
                        else get(climKey, get("em_clim_any", 1000f))
                    climateW = cw
                    val cPart = minOf(cw, p)
                    // When stopped, heating takes what remains; when moving, its learned power
                    val hPart = if (stopped) (p - cPart - accW).coerceIn(0f, if (heatPossible) p - cPart else 0f) else minOf(hw, p - cPart)
                    val aPart = minOf(accW, p - cPart - hPart)
                    clim = cPart * h; acc = aPart * h; heat = hPart * h
                } else if (!stopped) {
                    regen = -p * h
                }
                fun add(t: Totals, k: Int?, k0: Int) = t.copy(
                    outKwh = t.outKwh + out / 1000f, regenKwh = t.regenKwh + regen / 1000f,
                    climKwh = t.climKwh + clim / 1000f, accKwh = t.accKwh + acc / 1000f, heatKwh = t.heatKwh + heat / 1000f,
                    km = if (k != null && k0 >= 0) (k - k0).coerceAtLeast(0) else t.km,
                )
                _sinceCharge.value = add(_sinceCharge.value, km, chargeKm)
                _sinceStart.value = add(_sinceStart.value, km, startKm)
                val mk = monthKey(now)
                val m = months[mk] ?: Totals()
                // System time not yet set (2019 at wake-up): unknown month, km kept for later
                if (mk >= "2024") {
                    val dKm = pendingKm; pendingKm = 0
                    months[mk] = m.copy(
                        outKwh = m.outKwh + out / 1000f, regenKwh = m.regenKwh + regen / 1000f,
                        climKwh = m.climKwh + clim / 1000f, accKwh = m.accKwh + acc / 1000f, heatKwh = m.heatKwh + heat / 1000f, km = m.km + dKm,
                    )
                    publishMonths()
                }

                if (++saveTick >= 30) {
                    saveTick = 0
                    val c = _sinceCharge.value
                    val e = sp.edit()
                    learned.forEach { (k, v) -> e.putFloat(k, v) }
                    e.putFloat("em_c_out", c.outKwh).putFloat("em_c_rgn", c.regenKwh)
                        .putFloat("em_c_clim", c.climKwh).putFloat("em_c_acc", c.accKwh).putFloat("em_c_heat", c.heatKwh)
                    e.apply()
                    saveMonths()
                    BatteryCapacity.flush(context)
                }
            }
        }
    }

    /** Climate setting: fan (ranges), heating / cooling / fan only, defrost. */
    private fun climateKey(s: VehicleState): String {
        val fan = s.fanSpeed ?: 0
        val fanBucket = when { fan >= 15 -> "max"; fan >= 7 -> "hi"; fan >= 4 -> "mid"; else -> "lo" }
        val set = s.driverTemp; val outside = s.outsideTemp
        val mode = when {
            set == null || outside == null -> "x"
            set > outside + 2 -> "heat"
            set < outside - 2 -> "cool"
            else -> "vent"
        }
        val def = (if (s.defrostFront == true) "F" else "") + (if (s.defrostRear == true) "R" else "")
        return "$mode-$fanBucket-$def"
    }

    /** Visible electrical equipment: lights, wipers. "off" = nothing on. */
    private fun accessoryKey(s: VehicleState): String {
        val parts = buildList {
            // Lights: VEH_SIDE_LGHT (0 = off), otherwise the system's day / night mode (3 = lights on),
            // found on the car on 2026-10-14; HEADLIGHTS_STATE does not exist on this model.
            val side = CarProps.readAny(557880099)
            val night = CarProps.readAny(289442317)
            if (side.isNotEmpty() && side != "0" && side != "false" || side.isEmpty() && night == "3") add("lights")
            if (CarProps.readAny(557897511).let { it.isNotEmpty() && it != "0" }) add("wiper")
            if (s.defrostRear == true && s.hvacOn != true) add("rear")
        }
        return if (parts.isEmpty()) "off" else parts.joinToString("+")
    }

    private fun ema(old: Float, new: Float, k: Float) = old + (new - old) * k
}
