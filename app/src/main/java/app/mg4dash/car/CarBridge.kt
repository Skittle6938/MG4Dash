package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.mg4dash.BuildConfig
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Vehicle access through the "carapi" route of the A9 family (SWI69):
 * CarAdapterService (com.saicmotor.caradapter) → queryClient(code) → one binder per domain.
 *
 * States are read by polling rather than callbacks: less AIDL code
 * to maintain, and recovery is automatic if a SAIC service restarts.
 * Climate commands follow choice 2A: one toggle per press, the display follows the reading.
 * Driving settings (drive mode, regeneration, Custom mode, driving aids) go through [DriveSettings].
 */
object CarBridge {
    private const val TAG = "Car"
    private const val ADAPTER_PKG = "com.saicmotor.caradapter"
    private const val ADAPTER_ACTION = "com.saicmotor.caradapter.CarAdapterService"
    private const val CODE_GENERAL = 1
    private const val CODE_EV = 4
    private const val CODE_HVAC = 7
    private const val CODE_VS = 8
    private const val CODE_AUDIO = 10
    private const val CODE_TBOX = 16

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state

    @Volatile private var adapter: AdapterService? = null
    @Volatile private var hvac: HvacService? = null
    @Volatile private var ev: EvService? = null
    @Volatile private var vs: VehicleSettingService? = null
    @Volatile private var vsBinder: IBinder? = null
    @Volatile private var general: GeneralService? = null
    @Volatile private var bound = false

    private var appContext: Context? = null
    @Volatile private var fastPoll = true

    fun start(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        if (BuildConfig.MOCK) {
            MockCar.start(scope, _state)
            return
        }
        // Firmware A9 (SWI69/131/132) : service caradapter. Ancien SDK (SWI68/165/133) : gestionnaires
        // the stock launcher's SAIC. Unknown firmware: try both.
        if (Firmware.family != Firmware.Family.LEGACY) bind()
        if (Firmware.family != Firmware.Family.A9) LegacyCar.init(context)
        DriveSettings.init(context)
        DriveSettings.a9Binder = { vsBinder }
        Log.i(TAG, "firmware ${Firmware.version} (${Firmware.gen}, family ${Firmware.family})")
        scope.launch { pollLoop() }
        watchSaicClimate(context.applicationContext)
        watchClimateWindow()
        trackManualClimate(context.applicationContext)
    }

    /** Charge and V2L start / stop available (EV service of the A9 route). */
    val chargeControl: Boolean get() = BuildConfig.MOCK || ev != null || LegacyCar.chargingReady ||
        CarProps.readIntArea(CHRG_CTRL, 0x1000000) != null   // fallback: CHRG_CTRL property (tested on SWI69)

    private const val CHRG_CTRL = 0x2140f412          // 1 = start, 2 = stop charging (tested on 2026-10-18)
    private const val ELEC_CLCK_CTRL = 0x2140f413     // gun: 1 = locked, 2 = free
    private const val VEHICLE_LOCK = 289421312        // 0x11403800: doors; write 1 = lock, 2 = unlock

    /** Charge gun locked? null if the car does not say. */
    fun chargeLockLocked(): Boolean? = CarProps.readIntArea(ELEC_CLCK_CTRL, 0x1000000)?.let { if (it == 1) true else if (it == 2 || it == 0) false else null }

    /** Locks / unlocks the gun: EV service (A9), otherwise the property directly. */
    fun toggleChargeLock() {
        val target = if (chargeLockLocked() == true) 2 else 1
        if (BuildConfig.MOCK) return
        scope.launch {
            val e = ev
            if (e != null) try { e.elecClckCtrl(target); Log.i(TAG, "charge gun ← $target (EV service)") } catch (ex: Exception) { Log.w(TAG, "charge gun: ${ex.message}") }
            else CarProps.setIntArea(ELEC_CLCK_CTRL, 0x1000000, target, "charge gun")
        }
    }

    private const val POWER_MODE = 557897487   // 0x2140d70f: 2 = switch off (stock SystemUI, CarComfortableController)

    /** Switches the car off, like the "Switch off" button of the stock quick panel. */
    fun powerOffCar(): Boolean {
        // Never while driving: unknown speed or above 1 km/h = refused
        val v = state.value.speedKmh
        if (v == null || v > 1f) { Log.w(TAG, "power off refused, speed $v"); return false }
        if (BuildConfig.MOCK) return true
        CarProps.setIntArea(POWER_MODE, 0, 2, "car power off")
        return true
    }

    /** Locks the doors (does nothing if they already are). */
    fun lockDoors(): Boolean {
        if (doorsLocked() == true) return true
        if (BuildConfig.MOCK) return true
        return CarProps.setIntArea(VEHICLE_LOCK, 0x1000000, 1, "doors: lock")
    }

    /** Turns the climate on or off if it is not already in that state. */
    fun setHvac(on: Boolean) { if (_state.value.hvacOn != on) togglePower() }

    /** Doors locked? (stock SystemUI: 0 = open, 1 to 4 = locked). */
    fun doorsLocked(): Boolean? = CarProps.readIntArea(VEHICLE_LOCK, 0x1000000)?.let { it in 1..4 }

    /** Locks or unlocks the doors, like the stock quick panel tile. Returns the action done. */
    fun toggleDoors(): Boolean? {
        val locked = doorsLocked() ?: return null
        if (BuildConfig.MOCK) return !locked
        CarProps.setIntArea(VEHICLE_LOCK, 0x1000000, if (locked) 2 else 1, if (locked) "portes : déverrouiller" else "doors: lock")
        return !locked
    }

    /** Services that answer, for the Compatibility screen. */
    fun probe(): List<Pair<String, String>> = listOf(
        "caradapter" to if (adapter != null) "ok" else "absent",
        "hvac (7)" to if (hvac != null) "ok" else "absent",
        "ev (4)" to if (ev != null) "ok" else "absent",
        "vehiclesetting (8)" to if (vs != null) "ok" else "absent",
        "general (1)" to if (general != null) "ok" else "absent",
        "AirConditionManager" to LegacyCar.probe(),
        "VehicleChargingManager" to LegacyCar.probeCharging(),
    )

    /** Slows polling when home is not visible. */
    fun setForeground(foreground: Boolean) {
        fastPoll = foreground
    }

    private fun bind() {
        val ctx = appContext ?: return
        if (bound) return
        val intent = Intent(ADAPTER_ACTION).setPackage(ADAPTER_PKG)
        try {
            bound = ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService CarAdapter → $bound")
        } catch (e: Exception) {
            Log.w(TAG, "bindService CarAdapter failed: ${e.message}")
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            adapter = binder?.let { AdapterService(it) }
            Log.i(TAG, "CarAdapter connected")
            scope.launch { resolveClients() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "CarAdapter disconnected")
            adapter = null; hvac = null; ev = null; vs = null; general = null
            _state.value = _state.value.copy(connected = false)
        }
    }

    private var tboxService: TboxService? = null

    /** T-box (connection icons); null on old firmwares. Call off the main thread. */
    fun tbox(): TboxService? {
        if (tboxService?.alive != true) tboxService = query(CODE_TBOX)?.let { TboxService(it) }
        return tboxService
    }

    private var audioService: AudioService? = null

    /** Sound service (Sound page); requested on first use. Call off the main thread. */
    fun audio(): AudioService? {
        if (Firmware.family == Firmware.Family.LEGACY) return appContext?.let { LegacySound.service(it) }
        if (audioService?.alive != true) audioService = query(CODE_AUDIO)?.let { AudioService(it) }
        return audioService
    }

    /** Binder of the car state service (queryClient 0xb: gear, READY), for [ReadyState]. Off the main thread. */
    internal fun carStateBinder(): IBinder? = query(0xb)

    private fun query(code: Int): IBinder? = try {
        adapter?.queryClient(code)
    } catch (e: Exception) {
        Log.w(TAG, "queryClient($code): ${e.message}"); null
    }

    private fun resolveClients() {
        if (hvac?.alive != true) hvac = query(CODE_HVAC)?.let { HvacService(it) }
        if (ev?.alive != true) ev = query(CODE_EV)?.let { EvService(it) }
        if (vs?.alive != true) { vsBinder = query(CODE_VS); vs = vsBinder?.let { VehicleSettingService(it) } }
        if (general?.alive != true) {
            general = query(CODE_GENERAL)?.let { GeneralService(it) }
            // At startup, screen back on: if MG4Dash had turned it off then was relaunched, nothing else turns it on
            if (general != null && !displayChecked) { displayChecked = true; setDisplay(true) }
        }
    }

    private suspend fun pollLoop() {
        var tick = 0L
        while (scope.isActive) {
            try {
                if (adapter == null) {
                    if (tick % 5 == 0L && Firmware.family != Firmware.Family.LEGACY) bind()
                } else if (tick % 5 == 0L) {
                    resolveClients()
                }
                refreshHvac()
                refreshHeating()
                if (tick % 3 == 0L) refreshEnergy()
            } catch (e: Exception) {
                Log.w(TAG, "poll: ${e.message}")
            }
            tick++
            delay(if (fastPoll) 1000L else 5000L)
        }
    }

    private inline fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }

    // Value just set: the car takes a moment to apply it, and the reread (every
    // second) showed the old value again in the meantime ("the value hesitates", issue #6). Keep
    // the display on the new value until the car confirms it, 3 s at most.
    @Volatile private var holdTemp: Pair<Float, Long>? = null
    @Volatile private var holdFan: Pair<Int, Long>? = null
    private const val HOLD_MS = 3000L

    private fun held(s: VehicleState): VehicleState {
        val now = System.currentTimeMillis()
        var r = s
        holdTemp?.let { (v, until) ->
            if (now > until || s.driverTemp == v) holdTemp = null else r = r.copy(driverTemp = v)
        }
        // Limits test running (#107): the driver's set point stays on screen
        probeShown?.let { r = r.copy(driverTemp = it) }
        holdFan?.let { (v, until) ->
            if (now > until || s.fanSpeed == v) holdFan = null else r = r.copy(fanSpeed = v)
        }
        holdRecirc?.let { h -> heldRecirc(s.recirc, h, now).let { (shown, keep) -> if (!keep) holdRecirc = null; r = r.copy(recirc = shown) } }
        holdDir?.let { h -> heldRecirc(s.fanDirection, h, now).let { (shown, keep) -> if (!keep) holdDir = null; r = r.copy(fanDirection = shown) } }
        for ((f, h) in holds) {
            val read = f.get(s)?.let { if (it) 1 else 0 }
            val (shown, keep) = heldRecirc(read, h, now)
            if (!keep) holds.remove(f)
            r = f.set(r, shown?.let { it == 1 })
        }
        return r
    }

    /**
     * Climate on / off buttons shown at once when touched (#34), held until the car reads the same or for
     * [RECIRC_HOLD_MS], then the car's state again (back if it refused). A second quick touch starts from the display.
     */
    internal enum class Toggle(val get: (VehicleState) -> Boolean?, val set: (VehicleState, Boolean?) -> VehicleState) {
        POWER({ it.hvacOn }, { s, v -> s.copy(hvacOn = v) }),
        AC({ it.acOn }, { s, v -> s.copy(acOn = v) }),
        AUTO({ it.autoOn }, { s, v -> s.copy(autoOn = v) }),
        ECO({ it.ecoOn }, { s, v -> s.copy(ecoOn = v) }),
        DEFROST_FRONT({ it.defrostFront }, { s, v -> s.copy(defrostFront = v) }),
        DEFROST_REAR({ it.defrostRear }, { s, v -> s.copy(defrostRear = v) }),
    }
    private val holds = java.util.concurrent.ConcurrentHashMap<Toggle, Pair<Int, Long>>()
    @Volatile private var holdDir: Pair<Int, Long>? = null

    /** [t] shown as [on] at once and held (BuildConfig.MOCK applies it for real in MockCar). */
    private fun showAtOnce(t: Toggle, on: Boolean) {
        if (BuildConfig.MOCK) return
        holds[t] = (if (on) 1 else 0) to System.currentTimeMillis() + RECIRC_HOLD_MS
        _state.value = t.set(_state.value, on)
    }

    /** The recirculation mode chosen (#34): shown at once, until the car reads it or for [RECIRC_HOLD_MS]. */
    @Volatile private var holdRecirc: Pair<Int, Long>? = null
    private const val RECIRC_HOLD_MS = 4000L

    /** What to show from the car's [read] and the mode [held] (mode, until) at [now]; true if still held. */
    internal fun heldRecirc(read: Int?, held: Pair<Int, Long>, now: Long): Pair<Int?, Boolean> = when {
        read == held.first -> read to false
        now > held.second -> read to false
        else -> held.first to true
    }

    /** Recirculation: the next mode after [shown] (outside 1 → recirculated 0 → auto 2 → outside). */
    internal fun nextRecirc(shown: Int?): Int = when (shown) { 1 -> 0; 0 -> 2; else -> 1 }

    // ── Heated seats and steering wheel (climate properties, area 0x75, found by MG4Control) ──
    private const val SEAT_HEAT_L = 0x15402513
    private const val SEAT_HEAT_R = 0x15402514
    private const val WHEEL_HEAT = 0x1540253a
    private const val HEAT_AREA = 0x75
    @Volatile private var heatHoldUntil = 0L

    private fun refreshHeating() {
        if (BuildConfig.MOCK || System.currentTimeMillis() < heatHoldUntil) return
        val s = _state.value
        val l = CarProps.readIntArea(SEAT_HEAT_L, HEAT_AREA)?.takeIf { it in 0..3 }
        val r = CarProps.readIntArea(SEAT_HEAT_R, HEAT_AREA)?.takeIf { it in 0..3 }
        val w = CarProps.readIntArea(WHEEL_HEAT, HEAT_AREA)?.takeIf { it >= 0 }?.let { if (it > 0) 1 else 0 }
        if (l != s.seatHeatL || r != s.seatHeatR || w != s.wheelHeat) _state.value = s.copy(seatHeatL = l, seatHeatR = r, wheelHeat = w)
        // A failed read (startup, car asleep) must no longer make the buttons disappear (#19)
        if (l != null) appContext?.let { c ->
            val sp = app.mg4dash.system.Prefs(c).raw
            if (!sp.getBoolean("heat_seen", false)) sp.edit().putBoolean("heat_seen", true).apply()
        }
    }

    /**
     * Heated seat: the car only knows a "step" (value 1) that goes DOWN
     * (off → 3 → 2 → 1 → off, established on a vehicle by MG4Control). Display ahead of time, then reread.
     */
    fun stepSeatHeat(left: Boolean) {
        val cur = (if (left) _state.value.seatHeatL else _state.value.seatHeatR) ?: 0
        val next = (cur + 3) % 4
        _state.value = if (left) _state.value.copy(seatHeatL = next) else _state.value.copy(seatHeatR = next)
        heatHoldUntil = System.currentTimeMillis() + 2_500
        if (BuildConfig.MOCK) return
        scope.launch {
            CarProps.setIntArea(if (left) SEAT_HEAT_L else SEAT_HEAT_R, HEAT_AREA, 1, "heated seat ${if (left) "L" else "R"}")
            delay(2_600); refreshHeating()
        }
    }

    /** Heated steering wheel: a toggle (value 1), like the stock button. */
    fun toggleWheelHeat() {
        val on = _state.value.wheelHeat == 1
        _state.value = _state.value.copy(wheelHeat = if (on) 0 else 1)
        heatHoldUntil = System.currentTimeMillis() + 2_500
        if (BuildConfig.MOCK) return
        scope.launch {
            CarProps.setIntArea(WHEEL_HEAT, HEAT_AREA, 1, "heated wheel")
            delay(2_600); refreshHeating()
        }
    }

    /** Heated seats / wheel to show: setting, otherwise 64 / 77 kWh versions whose car exposes them. */
    fun heatingShown(context: Context): Boolean {
        val p = app.mg4dash.system.Prefs(context)
        return when (p.heatedSeats) {
            1 -> true
            2 -> false
            else -> BuildConfig.MOCK && p.batteryModel != 51 || p.batteryModel in listOf(64, 77) && (_state.value.seatHeatL != null || p.raw.getBoolean("heat_seen", false))
        }
    }

    @Volatile private var tempRangeChecked = false

    /**
     * SWI69 (A9) set point range, as the stock climate app (HvacService / AppCache.isTempRange16to30CarModel): 16 to 30
     * (LO 15.5, HI 30.5) on the car models 132 (EU/UK MY24), 198 (Israel MY24), 200 (Turkey MY24) and on the model 69
     * sold in the EU / UK (nation 5, 8), Australia (24) or Israel (7); 18 to 32 (LO 17.5, HI 32.5) otherwise.
     */
    private fun detectTempRange() {
        if (tempRangeChecked || BuildConfig.MOCK) return
        tempRangeChecked = true
        val model = try { adapter?.queryClient(2)?.let { ConfigService(it).carModelConfig } } catch (_: Exception) { null }
        val nation = try {
            appContext?.getSystemService("CarConfigService")?.let { m -> m.javaClass.getMethod("getNationalCode").invoke(m) as? Int }
        } catch (_: Throwable) { null }
        // Learned from the car before (it always wins); otherwise the model and nation as the stock app, as a start;
        // otherwise 16 to 32 shown until the car's answers at a limit tell (owner's design)
        val learned = rangePrefs()?.getInt(PREF_TEMP_RANGE, 0) ?: 0
        rangeLearned = learned != 0
        val low = when (learned) { 1 -> true; 2 -> false; else -> if (START_FROM_MODEL) tempRange16to30(model, nation) else null }
        applyTempRange(low)
        Log.i(TAG, "set point range: model $model, nation $nation, learned $learned → ${rangeName(low)}")
    }

    /** Use the model and nation as a starting point (false: 16 to 32 for everyone until learned). */
    private const val START_FROM_MODEL = true
    private const val PREF_TEMP_RANGE = "a9_temp_range"   // 0 unknown, 1 16 to 30, 2 18 to 32
    @Volatile private var rangeLearned = false
    /** Last set point sent at a limit and when (to read the car's answer to it). */
    @Volatile private var edgeSent: Pair<Float, Long>? = null

    private fun rangePrefs() = appContext?.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
    private fun rangeName(low: Boolean?) = when (low) { true -> "16 to 30"; false -> "18 to 32"; null -> "16 to 32 until learned" }

    /** LO / HI sent for a range: 15.5 / 30.5 (16 to 30), 17.5 / 32.5 (18 to 32), 15.5 / 32.5 (16 to 32, not known yet). */
    private fun applyTempRange(low: Boolean?) {
        VehicleState.tempLo = if (low == false) 17.5f else 15.5f
        VehicleState.tempHi = if (low == true) 30.5f else 32.5f
    }

    /**
     * The car's range from what it reports (raw, before any hold) and the last value sent at a limit: true 16 to 30,
     * false 18 to 32, null nothing new. Only values that exist in one range alone count: 16 or lower (15, 15.5, 16) is
     * 16 to 30, 32 or higher (32, 32.5, 33) is 18 to 32. A value sent below 16 the car keeps at 17 or more: 18 to 32;
     * a value sent above 31 it keeps at 31 or less: 16 to 30.
     */
    internal fun learnRange(reported: Float?, sent: Float?, sentAgoMs: Long): Boolean? {
        if (reported == null || reported.isNaN() || reported <= 0f) return null
        if (reported <= 16.25f) return true
        if (reported >= 31.75f) return false
        if (sent != null && sentAgoMs in 1_500..6_000) {
            if (sent < 16.25f && reported >= 16.75f) return false
            if (sent > 31.25f && reported <= 31.25f) return true
        }
        return null
    }

    private fun learnFrom(reported: Float?) {
        if (rangeLearned || BuildConfig.MOCK) return
        val e = edgeSent
        val low = learnRange(reported, e?.first, e?.let { System.currentTimeMillis() - it.second } ?: -1) ?: return
        rangeLearned = true; edgeSent = null
        applyTempRange(low)
        rangePrefs()?.edit()?.putInt(PREF_TEMP_RANGE, if (low) 1 else 2)?.apply()
        Log.i(TAG, "set point range learned: ${rangeName(low)} (car reported $reported)")
    }

    /** A set point sent: kept to read the car's answer if it is at a limit of the range not learned yet. */
    private fun sentTemp(target: Float) {
        if (!rangeLearned && (target < 16.25f || target > 31.25f)) edgeSent = target to System.currentTimeMillis()
    }

    /** The A9 car model and nation code: true for the 16-to-30 range, false for 18 to 32, null if unknown. */
    internal fun tempRange16to30(model: Int?, nation: Int?): Boolean? = when {
        model == null -> null
        model in listOf(132, 198, 200) -> true
        model == 69 -> nation?.let { it in listOf(5, 8, 24, 7) }
        else -> false
    }

    private fun refreshHvac() {
        if (hvac != null) detectTempRange()
        val h = hvac ?: run {
            if (Firmware.family != Firmware.Family.A9) { _state.value = held(LegacyCar.readHvac(_state.value)); afterHvacRead(legacy = true) }
            return
        }
        refreshA9Hvac(h)
        afterHvacRead(legacy = false)
    }

    // ── Set point limits tested once on the car (#107, owner's design) ──────────────────────────────
    // Not deduced from the firmware: the lowest and highest set points the car really takes, each found by sending
    // values from outside inwards and reading back what the car keeps (out of its range it refuses the value, or
    // SWI69 turns it into another one). Done once, climate off and standing still; the result is kept for good and wins over everything else.

    private const val PREF_PROBE_LO = "temp_probe_lo"
    private const val PREF_PROBE_HI = "temp_probe_hi"
    private const val PREF_PROBE_PATH = "temp_probe_path"   // "legacy" or "a9": the values are in that path's units
    private const val PROBE_WAIT_MS = 1_500L
    private const val PREF_PROBE_TRIES = "temp_probe_tries"
    /** A car that never gives a result is not tested at every start: three times at most. */
    private const val PROBE_MAX_TRIES = 3
    @Volatile private var probeTried = false
    @Volatile private var probing = false
    @Volatile private var probeShown: Float? = null

    /** The limits tested before on this path, or null. */
    private fun probedLimits(legacy: Boolean): Pair<Float, Float>? {
        val p = rangePrefs() ?: return null
        if (p.getString(PREF_PROBE_PATH, null) != if (legacy) "legacy" else "a9") return null
        val lo = p.getFloat(PREF_PROBE_LO, 0f); val hi = p.getFloat(PREF_PROBE_HI, 0f)
        return if (lo > 0f && hi > lo) lo to hi else null
    }

    private fun afterHvacRead(legacy: Boolean) {
        if (BuildConfig.MOCK || probing) return
        probedLimits(legacy)?.let { (lo, hi) ->
            VehicleState.tempLo = lo; VehicleState.tempHi = hi; rangeLearned = true
            return
        }
        maybeProbe(legacy)
    }

    /** Candidates from outside inwards: the first one the car keeps exactly as sent is the limit. */
    internal fun probeCandidates(legacy: Boolean, low: Boolean): List<Float> = when {
        legacy && low -> listOf(13f, 14f, 15f, 16f, 17f)
        legacy -> listOf(35f, 34f, 33f, 32f, 31f)
        low -> listOf(14.5f, 15.5f, 16.5f, 17.5f)
        else -> listOf(33.5f, 32.5f, 31.5f, 30.5f)
    }

    /** Plausible limits: a dozen to twenty degrees apart, LO under 20 and HI over 28. */
    internal fun plausibleLimits(lo: Float, hi: Float) = lo in 12f..20f && hi in 28f..36f && hi - lo in 12f..20f

    private fun maybeProbe(legacy: Boolean) {
        if (probeTried) return
        val s = _state.value
        val orig = s.driverTemp ?: return
        // Only with the climate off and the car standing: the set point really changes for a few seconds
        if (s.hvacOn != false || (s.speedKmh ?: 0f) > 1f) return
        probeTried = true
        val prefs = rangePrefs() ?: return
        val tries = prefs.getInt(PREF_PROBE_TRIES, 0)
        if (tries >= PROBE_MAX_TRIES) return
        prefs.edit().putInt(PREF_PROBE_TRIES, tries + 1).apply()
        probing = true; probeShown = orig
        scope.launch {
            try { probe(legacy, orig) } catch (e: Exception) { Log.w(TAG, "set point limits test: ${e.message}") }
            finally { probing = false; probeShown = null }
        }
    }

    private suspend fun probe(legacy: Boolean, orig: Float) {
        fun send(v: Float) {
            try { if (legacy) LegacyCar.temp(Math.round(v)) else hvac?.setDriverTemperature(v) }
            catch (e: Exception) { Log.i(TAG, "set point limits test: $v refused (${e.message})") }
        }
        fun read(): Float? = if (legacy) LegacyCar.drvTempRaw()?.takeIf { it > 0 }?.toFloat()
            else safe { hvac?.driverTemperature }?.takeIf { !it.isNaN() && it > 0f }
        Log.i(TAG, "set point limits test (${if (legacy) "old SDK" else "A9"}), set point $orig")
        suspend fun limit(low: Boolean): Float? {
            // From a set point in the middle, so that any change read is the car's answer
            send(22f); delay(PROBE_WAIT_MS)
            if (read() != 22f) { Log.i(TAG, "set point limits test: 22 not taken (read ${read()})"); return null }
            for (c in probeCandidates(legacy, low)) {
                send(c); delay(PROBE_WAIT_MS)
                val r = read()
                Log.i(TAG, "set point limits test: sent $c, car keeps $r")
                // Only a value the car keeps as sent: out of its range, SWI69 turns it into another one
                // (14.5 read back as 31, 33.5 as 17), which is no limit
                if (r != null && Math.abs(r - c) < 0.01f) return r
            }
            return null
        }
        val lo = limit(true)
        val hi = limit(false)
        send(orig); delay(PROBE_WAIT_MS)
        Log.i(TAG, "set point limits test: back to $orig (read ${read()})")
        if (lo == null || hi == null || !plausibleLimits(lo, hi)) {
            Log.i(TAG, "set point limits test: no result (LO $lo, HI $hi), range kept as it was")
            return
        }
        rangePrefs()?.edit()?.putFloat(PREF_PROBE_LO, lo)?.putFloat(PREF_PROBE_HI, hi)
            ?.putString(PREF_PROBE_PATH, if (legacy) "legacy" else "a9")?.apply()
        VehicleState.tempLo = lo; VehicleState.tempHi = hi; rangeLearned = true
        Log.i(TAG, "set point limits tested: LO $lo, HI $hi → ${VehicleState.tempFirst.toInt()} to ${VehicleState.tempLast.toInt()}, kept")
        refreshHvac()
    }

    private fun refreshA9Hvac(h: HvacService) {
        // Fan level 15 is AUTO on SWI69 too (#108's report: fan=15 while AUTO read false): shown as AUTO
        val fanRaw = safe { h.fanSpeed }
        _state.value = _state.value.copy(
            connected = true,
            hvacOn = safe { h.hvacPowerStatus },
            acOn = safe { h.acStatus },
            autoOn = if (fanRaw == LegacyCar.FAN_AUTO) true else safe { h.autoStatus },
            recirc = safe { h.airCirculationStatus }?.takeIf { it >= 0 },
            fanSpeed = fanRaw?.let { LegacyCar.validFan(it, _state.value.fanSpeed) },
            fanDirection = safe { h.fanDirection }?.takeIf { it in 0..7 },
            driverTemp = safe { h.driverTemperature }?.takeIf { !it.isNaN() && it > 0f }.also { learnFrom(it) },
            outsideTemp = safe { h.outSideTemperature }?.takeIf { !it.isNaN() && it > -60f && it < 70f },
            defrostFront = safe { h.frontDefrostStatus },
            defrostRear = safe { h.rearDefrostStatus },
            ecoOn = safe { h.ecoStatus },
            autoAirVolume = safe { h.autoAirVolume }?.takeIf { it in 0..2 },
            defrostLinked = safe { h.defrostLinkage }?.takeIf { it in 0..1 }?.let { it == 0 },
            ecoLinked = safe { h.ecoLinkage }?.takeIf { it in 1..2 }?.let { it == 1 },
        ).let { held(it) }
    }

    private fun refreshEnergy() {
        val s = _state.value
        val e = ev
        val v = vs
        val g = general
        _state.value = s.copy(
            // Old firmwares (SWI68…): no CarAdapter, climate goes through LegacyCar. Setting
            // "connected" to false here greyed the home climate card for a moment at each reread (#47)
            connected = adapter != null || LegacyCar.ready,
            batteryPercent = e?.let { safe { it.chargePercentChange } }?.takeIf { it in 0..100 } ?: s.batteryPercent,
            rangeKm = e?.let { safe { it.currentRange } }?.takeIf { it in 0..2000 } ?: s.rangeKm,
            chargeStatus = e?.let { safe { it.chargeStatus } } ?: s.chargeStatus,
            chargeRemainMin = e?.let { safe { it.chargingRemainTime } } ?: s.chargeRemainMin,
            chargeCtrl = e?.let { safe { it.chargeCtrlStatus } } ?: s.chargeCtrl,
            dischargeCtrl = e?.let { safe { it.disChargeCtrl } } ?: s.dischargeCtrl,
            dischargeRemainMin = e?.let { safe { it.disChargingRemainTime } } ?: s.dischargeRemainMin,
            enduranceMode = v?.let { safe { it.enduranceMode } } ?: s.enduranceMode,
            overSpeedSound = v?.let { safe { it.overSpeedSoundMode } } ?: s.overSpeedSound,
            speedLimitSound = v?.let { safe { it.speedLimitSoundMode } } ?: s.speedLimitSound,
            odometerKm = odometer(g?.let { safe { it.totalMileage } }, s.odometerKm),
            speedKmh = g?.let { safe { it.latestSpeed } } ?: s.speedKmh,
        )
        // Tenth of % (BMS): read separately, on all access routes
        if (!BuildConfig.MOCK) {
            val precise = (CarProps.getFloat(0x2160f404) ?: CarProps.readFloatArea(0x2160f404, 0x1000000))?.takeIf { it in 0.1f..100f }
            if (precise != _state.value.socPrecise) _state.value = _state.value.copy(socPrecise = precise)
        }
        if (e == null || v == null || g == null) refreshFromProps()
        if (e == null && Firmware.family != Firmware.Family.A9) _state.value = LegacyCar.readCharging(_state.value)
        if (v == null && Firmware.family == Firmware.Family.LEGACY) _state.value = LegacyCar.readDriving(_state.value)
        if (!BuildConfig.MOCK) refreshDrive()
    }

    /**
     * Fallback common to all firmwares: CarPropertyManager measurements, same identifiers on
     * SWI133 (found by MG4Control) and SWI69. Only fills in what the services did not give.
     */
    private fun refreshFromProps() {
        val s = _state.value
        val area = 0x1000000
        fun i(id: Int) = CarProps.readIntArea(id, area)
        fun f(id: Int) = CarProps.readFloatArea(id, area)?.takeIf { it.isFinite() && it != 82.3f && it != 511.5f }
        val soc = f(0x2160f404)?.takeIf { it in 0f..100f }?.let { Math.round(it) }
        val range = i(0x2140f41c)?.takeIf { it in 1..1500 }
        // BMS charge state: same scale as the EV service (10 = fast charge, found on SWI133 while
        // DC charging, issue #13); 0 with the cable plugged = connected without charging.
        val raw = i(0x2140f409)
        val dc = i(0x2140f43f)?.takeIf { it != 0 && it != 1023 } != null
        val ac = i(0x2140f43e)?.takeIf { it != 0 && it != 1023 } != null
        val status = when { raw != null && raw in 1..15 -> raw; dc || ac -> 7; else -> 0 }
        // Without an EV service, the properties are the only source: reread on each pass
        // (before, the first value read stayed shown: % and range frozen, issue #13).
        val noEv = ev == null
        /** Property value if the settings service is missing, otherwise the service's (property as a fallback). */
        fun fresh(prop: Int?, cur: Int?): Int? = if (vs == null) prop ?: cur else cur ?: prop
        _state.value = s.copy(
            connected = s.connected || soc != null,
            batteryPercent = if (noEv) soc ?: s.batteryPercent else s.batteryPercent ?: soc,
            rangeKm = if (noEv) range ?: s.rangeKm else s.rangeKm ?: range,
            chargeStatus = if (ev == null) status else s.chargeStatus,
            // Reread on each pass (#102): before, the first value stayed shown without a general service
            // (SWI165), the km stopped and the average kWh/100 climbed
            odometerKm = odometer(i(0x21401566), s.odometerKm),
            overSpeedSound = fresh(i(0x2140a16c)?.takeIf { it in 0..1 }, s.overSpeedSound),
            // Speed limit beep without a settings service (SWI165, #37). Steering, brake pedal
            // and Range+ do not exist on these cars (meaningless values): lines hidden.
            speedLimitSound = fresh((i(0x2140a1ba) ?: i(0x2140a1b4))?.takeIf { it in 0..3 }, s.speedLimitSound),
            // Speed without a general service (SWI68, #19): PERF_VEHICLE_SPEED, like MG4Control on
            // all firmwares; already in km/h at SAIC despite the AOSP spec, negative in reverse.
            speedKmh = if (general != null) s.speedKmh else
                (CarProps.readFloatArea(0x11600207, 0x1000000) ?: CarProps.readFloatArea(0x11600207, 0))
                    ?.takeIf { !it.isNaN() }?.let { Math.abs(it) }?.takeIf { it < 300f } ?: s.speedKmh,
        )
    }

    /** Odometer [read] now (service or property), never below the [current] one: a late source does not take km back (#102). */
    internal fun odometer(read: Int?, current: Int?): Int? = read?.takeIf { it > 0 && (current == null || it >= current) } ?: current

    // ── Commandes clim ────────────────────────────────────────────────────────
    // Run off the main thread, then a quick reread so the UI follows the real state.

    /** Climate command on old SDK firmwares (blocking, so off the main thread). */
    private fun legacy(label: String, action: () -> Boolean) {
        ownCommandAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            val front = ourFrontTask()
            val ok = try { action() } catch (e: Exception) { Log.w(TAG, "SET $label: ${e.message}"); false }
            Log.i(TAG, "SET $label (old SDK) → $ok")
            delay(300); refreshHvac(); keepFront(front)
            delay(700); refreshHvac(); keepFront(front)
            delay(1000); keepFront(front)
        }
    }

    /** MG4Dash task in the foreground before a command, or null. */
    private fun ourFrontTask(): Int? = try {
        val ctx = appContext
        val am = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        @Suppress("DEPRECATION")
        am?.getRunningTasks(1)?.firstOrNull()?.takeIf { it.topActivity?.packageName == ctx.packageName }?.id
    } catch (_: Throwable) { null }

    /**
     * Old SDK: turning the climate on opens the SAIC launcher's climate screen over MG4Dash
     * (#48). If an MG4Dash page was in front, bring it back in front (setting, on by default).
     */
    private fun keepFront(task: Int?) {
        val ctx = appContext ?: return
        if (task == null || !app.mg4dash.system.Prefs(ctx).hideSaicClimate) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val top = am.getRunningTasks(1).firstOrNull() ?: return
            val pkg = top.topActivity?.packageName ?: return
            if (top.id != task && pkg.startsWith("com.saicmotor")) {
                am.moveTaskToFront(task, 0)
                Log.i(TAG, "SAIC climate screen ${top.topActivity?.flattenToShortString()} came in front: MG4Dash brought back")
            }
        } catch (e: Throwable) { Log.w(TAG, "SAIC climate: ${e.message}") }
    }

    /**
     * Old SDK, physical climate button under the screen (#62): the car also opens its climate screen over
     * MG4Dash. Every second, note whether MG4Dash is in front; if a SAIC climate screen replaces it within
     * 3 s, bring MG4Dash back in front (same setting as #48). Not when MG4Dash opened an app itself.
     */
    private var lastTopSeen: String? = null
    /** Last climate command sent by MG4Dash (elapsedRealtime). */
    @Volatile private var ownCommandAt = 0L

    /**
     * Diagnostic (#62): climate turned on by the car (button under the screen), not by MG4Dash. On some
     * old firmwares the SAIC climate page is a window over MG4Dash, without changing the
     * foreground app: note the focused window and the top windows, read from the system state.
     */
    private fun watchClimateWindow() {
        if (Firmware.family == Firmware.Family.A9) return
        scope.launch {
            var prev: Boolean? = null
            _state.collect { s ->
                val on = s.hvacOn
                if (prev == false && on == true && android.os.SystemClock.elapsedRealtime() - ownCommandAt > 4_000) {
                    delay(900)
                    closeClimateWindow()
                }
                if (on != null) prev = on
            }
        }
    }

    /**
     * #62: who owns the climate page and whether it stays shown: package, uid, window type and
     * visibility, read from the window state. Used to know if it can be prevented from appearing.
     */
    private fun logHvacWindow(moment: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "window", "windows"))
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor()
            var inHvac = false
            val keys = listOf("mSession", "package=", "ty=", "mHasSurface", "mViewVisibility", "isOnScreen", "isVisible")
            val found = mutableListOf<String>()
            for (l in lines) {
                val t = l.trim()
                if (t.startsWith("Window #")) inHvac = t.contains("hvac", ignoreCase = true)
                else if (inHvac && keys.any { t.contains(it) }) found += t.take(160)
            }
            Log.i(TAG, "climate window owner ($moment): ${if (found.isEmpty()) "no hvac window listed" else found.joinToString(" | ")}")
        } catch (e: Throwable) { Log.w(TAG, "climate window owner: ${e.message}") }
    }

    /** Focused window, read from the system state (null if unreadable). */
    private fun focusedWindow(): String? = try {
        val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "window", "windows"))
        val lines = p.inputStream.bufferedReader().readLines()
        p.waitFor()
        lines.firstOrNull { it.contains("mCurrentFocus") }?.trim()
    } catch (e: Throwable) { Log.w(TAG, "climate window: ${e.message}"); null }

    /**
     * #62: on these firmwares, the SAIC climate page is a system "hvac page" window over
     * MG4Dash (lopezjm2001's log, SWI165). A "back" does not close it; it is closed through
     * SystemUI's service ([SaicHvacPage]): the previous screen stays shown (setting #48).
     */
    private fun closeClimateWindow() {
        val focus = focusedWindow()
        Log.i(TAG, "climate window: ${focus ?: "focus unknown"}")
        if (focus?.contains("hvac page") != true) return
        logHvacWindow("shown")
        val ctx = appContext ?: return
        if (!app.mg4dash.system.Prefs(ctx).hideSaicClimate) return
        SaicHvacPage.close(ctx)   // back to the previous screen, as asked by lopezjm2001 (#62)
    }

    private fun watchSaicClimate(ctx: Context) {
        if (Firmware.family == Firmware.Family.A9) return
        scope.launch {
            var lastFront: Int? = null
            var lastFrontAt = 0L
            while (isActive) {
                delay(1000)
                if (!app.mg4dash.system.Prefs(ctx).hideSaicClimate) continue
                val now = android.os.SystemClock.elapsedRealtime()
                val ours = ourFrontTask()
                if (ours != null) { lastFront = ours; lastFrontAt = now; continue }
                val task = lastFront ?: continue
                if (now - lastFrontAt > 3_000 || now - app.mg4dash.system.Apps.openedAt < 5_000) continue
                try {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    @Suppress("DEPRECATION")
                    val top = am.getRunningTasks(1).firstOrNull()?.topActivity ?: continue
                    val name = top.flattenToShortString().lowercase()
                    // Diagnostic (#62): what comes in front of MG4Dash, to recognise each firmware's climate screen
                    if (top.flattenToShortString() != lastTopSeen) { lastTopSeen = top.flattenToShortString(); Log.i(TAG, "front after MG4Dash: $lastTopSeen") }
                    if (top.packageName.startsWith("com.saicmotor") &&
                        listOf("hvac", "aircondition", "climate").any { it in name }) {
                        am.moveTaskToFront(task, 0)
                        lastFrontAt = 0L
                        Log.i(TAG, "SAIC climate screen ${top.flattenToShortString()} opened by the car: MG4Dash brought back")
                    }
                } catch (e: Throwable) { Log.w(TAG, "SAIC climate watch: ${e.message}") }
            }
        }
    }

    private val useLegacy: Boolean get() = !BuildConfig.MOCK && hvac == null && LegacyCar.ready

    private fun command(label: String, action: (HvacService) -> Unit) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        ownCommandAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            val h = hvac ?: run { Log.w(TAG, "$label ignored: climate not connected"); return@launch }
            try {
                action(h)
                Log.i(TAG, "SET $label → ok")
            } catch (e: Exception) {
                Log.w(TAG, "SET $label → failed: ${e.message}")
            }
            delay(300); refreshHvac()
            delay(700); refreshHvac()
        }
    }

    // ── Charging and V2L: same commands as the stock launcher's buttons ──
    // chargeCtrl(1) = start, chargeCtrl(2) = stop; likewise disChargeCtrl for V2L.

    private fun evCommand(label: String, action: (EvService) -> Unit) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        scope.launch {
            val e = ev ?: run {
                if (LegacyCar.chargingReady) {
                    val ok = when (label) {
                        "startCharge" -> LegacyCar.chargeCtrl(1)
                        "stopCharge" -> LegacyCar.chargeCtrl(2)
                        "startV2L" -> LegacyCar.dischargeCtrl(1)
                        else -> LegacyCar.dischargeCtrl(2)
                    }
                    Log.i(TAG, "EV $label (old SDK) → $ok")
                    delay(1500); refreshEnergy(); delay(3000); refreshEnergy()
                } else if (label == "startCharge" || label == "stopCharge") {
                    // Fallback without an EV service or SDK: the CHRG_CTRL property (same effect, tested on SWI69)
                    CarProps.setIntArea(CHRG_CTRL, 0x1000000, if (label == "startCharge") 1 else 2, label)
                    delay(1500); refreshEnergy(); delay(3000); refreshEnergy()
                } else Log.w(TAG, "$label ignored: EV service not connected")
                return@launch
            }
            try { action(e); Log.i(TAG, "EV $label → sent") } catch (ex: Exception) { Log.w(TAG, "EV $label → failed: ${ex.message}") }
            delay(1500); refreshEnergy()
            delay(3000); refreshEnergy()
        }
    }

    // ── Screen off and ESC (vertical bar gestures, issue #12) ──

    @Volatile private var displayChecked = false

    /** Heating equipment declared by the car (A9): seats, wheel; null if unknown. */
    fun heatingConfig(): Pair<Int?, Int?> {
        val b = try { adapter?.queryClient(2) } catch (_: Exception) { null } ?: return null to null
        val c = ConfigService(b)
        return (try { c.seatHeatingConfig } catch (_: Exception) { null }) to (try { c.steeringWheelHeatConfig } catch (_: Exception) { null })
    }

    /** Turns the central screen on or off through the vehicle service; false if the service does not answer. */
    fun setDisplay(on: Boolean): Boolean {
        if (BuildConfig.MOCK) return true
        if (Firmware.family == Firmware.Family.LEGACY) return setDisplayLegacy(on)
        val g = general ?: run { Log.w(TAG, "screen: general service missing"); return false }
        return try { g.setDisplayState(on); Log.i(TAG, "screen ${if (on) "on" else "off"}"); true }
        catch (e: Exception) { Log.w(TAG, "screen: ${e.message}"); false }
    }

    /**
     * SWI68 / SWI133 (#79): no screen command in the general service, but SystemUI receives the same orders
     * as its stock switch. A touch or a key turns the screen back on, whoever the caller is.
     */
    private fun setDisplayLegacy(on: Boolean): Boolean {
        val ctx = appContext ?: return false
        val intent = android.content.Intent(if (on) "com.saicmotor.screen.on" else "com.saicmotor.screen.off")
            .putExtra("pid", android.os.Process.myPid())
        if (on) intent.putExtra("trans", false) else intent.putExtra("showTime", false).putExtra("theme", 0)
        return try { ctx.sendBroadcast(intent); Log.i(TAG, "screen ${if (on) "on" else "off"} (SystemUI)"); true }
        catch (e: Exception) { Log.w(TAG, "screen: ${e.message}"); false }
    }

    /**
     * ESC active? 1 = active, 0 = off (VEHICLE_ESP, found on the car on 2026-10-18).
     * null if the car does not say.
     */
    fun escActive(): Boolean? {
        if (BuildConfig.MOCK) return mockEsc
        // Found on the car: the state follows VEHICLE_ESP itself (1 = active, 0 = off), like TCS_OPNG_MD
        val v = CarProps.readIntArea(VEHICLE_ESP, 0x1000000) ?: CarProps.readIntArea(TCS_OPNG_MD, 0x1000000)
            ?: return if (Firmware.family == Firmware.Family.LEGACY) LegacyCar.escActive() else null
        return v != 0
    }
    private var mockEsc = true

    /** Toggles ESC: the SAIC command is a press (value 1), like the MG menu switch. */
    fun toggleEsc() {
        if (BuildConfig.MOCK) { mockEsc = !mockEsc; return }
        // Old SDK without the property: VehicleControlManager (SWI68/165)
        if (CarProps.readIntArea(VEHICLE_ESP, 0x1000000) == null && Firmware.family == Firmware.Family.LEGACY) {
            Log.i(TAG, "ESC (old SDK): ${LegacyCar.toggleEsc()}"); return
        }
        val ok = CarProps.setIntArea(VEHICLE_ESP, 0x1000000, 1, "ESC (press)")
        Log.i(TAG, "ESC: command sent → $ok, state before: ${escActive()}")
    }
    private const val VEHICLE_ESP = 289421317       // 0x11403805: ESC command (setDrivingEpsMode of the MG menu)
    private const val TCS_OPNG_MD = 289421343       // 0x1140381f: 1 = ESC active, 0 = off (fallback)

    // ── Driving settings (Driving card, KERS gauge): MG4Control's commands, see DriveSettings ──
    //
    // A touch shows the choice at once, as pending: the command leaves in the background and the car's rereads
    // do not take it back meanwhile. Confirmed by a reread, it settles; not confirmed within PENDING_MS, the
    // value read comes back and [driveRefused] says so.

    /** Pending choices: field → (value asked, time it is given up). */
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    private const val PENDING_MS = 4000L
    private val _drivePending = MutableStateFlow<Set<String>>(emptySet())
    /** Fields whose choice the car has not confirmed yet ("driveMode", "regenLevel"…). */
    val drivePending: StateFlow<Set<String>> = _drivePending
    private val _driveRefused = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** A choice the car did not take (its label, for the log). */
    val driveRefused: kotlinx.coroutines.flow.SharedFlow<String> = _driveRefused

    private fun VehicleState.drive(field: String): Int? = when (field) {
        "driveMode" -> driveMode; "regenLevel" -> regenLevel; "onePedal" -> onePedal; "steeringMode" -> steeringMode
        "brakePedalMode" -> brakePedalMode; "lasMode" -> lasMode; "fcwState" -> fcwState; "fcwBrakeMode" -> fcwBrakeMode; else -> null
    }

    private fun VehicleState.drive(field: String, v: Int?): VehicleState = when (field) {
        "driveMode" -> copy(driveMode = v); "regenLevel" -> copy(regenLevel = v); "onePedal" -> copy(onePedal = v)
        "steeringMode" -> copy(steeringMode = v); "brakePedalMode" -> copy(brakePedalMode = v); "lasMode" -> copy(lasMode = v)
        "fcwState" -> copy(fcwState = v); "fcwBrakeMode" -> copy(fcwBrakeMode = v); else -> this
    }

    private val driveFields = listOf("driveMode", "regenLevel", "onePedal", "steeringMode", "brakePedalMode", "lasMode", "fcwState", "fcwBrakeMode")

    /** Rereads the driving settings: a pending choice stays until confirmed or given up; a setting that stops answering keeps its last value. */
    private fun refreshDrive() {
        val (s, refused) = settle(_state.value, DriveSettings.read(_state.value), System.currentTimeMillis())
        _state.value = s
        _drivePending.value = pending.keys.toSet()
        refused?.let { Log.w(TAG, "SET $it not confirmed by the car"); _driveRefused.tryEmit(it) }
    }

    /** [cur] after the car's [read] at [now]: pending choices kept, confirmed or given up; and a field given up, if any. */
    internal fun settle(cur: VehicleState, read: VehicleState, now: Long): Pair<VehicleState, String?> {
        var refused: String? = null
        var s = cur
        driveFields.forEach { f ->
            val got = read.drive(f)
            val p = pending[f]
            s = when {
                p == null -> s.drive(f, got ?: s.drive(f))
                got == p.first -> { pending.remove(f); s.drive(f, got) }
                now < p.second -> s.drive(f, p.first)
                else -> { pending.remove(f); refused = f; s.drive(f, got ?: s.drive(f)) }
            }
        }
        return s to refused
    }

    /** For the tests: a choice waiting for the car until [until]. */
    internal fun setPending(field: String, value: Int, until: Long) { pending[field] = value to until; _drivePending.value = pending.keys.toSet() }
    internal fun clearPending() { pending.clear(); _drivePending.value = emptySet() }

    /**
     * A driving setting command: [targets] shown at once, the command off the main thread, then rereads until the car
     * confirms. Never refused while driving (owner's choice).
     */
    private fun drive(label: String, targets: Map<String, Int>, action: () -> Boolean) {
        var s = _state.value
        targets.forEach { (f, v) -> s = s.drive(f, v) }
        _state.value = s
        if (BuildConfig.MOCK) return
        val until = System.currentTimeMillis() + PENDING_MS
        targets.forEach { (f, v) -> pending[f] = v to until }
        _drivePending.value = pending.keys.toSet()
        scope.launch {
            val ok = try { action() } catch (e: Exception) { Log.w(TAG, "SET $label: ${e.message}"); false }
            Log.i(TAG, "SET $label → $ok")
            for (wait in longArrayOf(600, 900, 1500, 1200)) { delay(wait); refreshDrive(); if (targets.keys.none { pending.containsKey(it) }) break }
        }
    }

    /** Drive mode: 6 snow, 2 eco, 3 normal, 4 sport, 7 custom. */
    fun setDriveMode(mode: Int) = drive("drive mode $mode", mapOf("driveMode" to mode)) { DriveSettings.setDriveMode(mode) }

    /** Regeneration level 0..3; one-pedal driving is turned off first (MG4Control's setRegenLevel). */
    fun setRegenLevel(level: Int) = drive("regen $level", mapOf("regenLevel" to level, "onePedal" to 0)) { DriveSettings.setRegenLevel(level) }

    /** One-pedal driving on, as MG4Control's "One Pedal" regeneration level. */
    fun setOnePedal() = drive("one pedal", mapOf("onePedal" to 1)) { DriveSettings.setRegenLevel(DriveSettings.REGEN_ONE_PEDAL) }

    /** One-pedal driving: toggle (long press on the KERS gauge). */
    fun toggleOnePedal() {
        val on = _state.value.onePedal != 1
        drive("one pedal $on", mapOf("onePedal" to if (on) 1 else 0)) { DriveSettings.setOnePedal(on) }
    }

    /** Custom mode steering, [index] 0 comfort, 1 normal, 2 sport. */
    fun setSteering(index: Int) {
        val v = DriveSettings.customValue(DriveSettings.Custom.STEERING, index) ?: return
        drive("steering $index", mapOf("steeringMode" to v)) { DriveSettings.setCustom(DriveSettings.Custom.STEERING, index) }
    }

    /** Custom mode brake pedal, [index] 0 comfort, 1 normal, 2 sport. */
    fun setBrakePedal(index: Int) {
        val v = DriveSettings.customValue(DriveSettings.Custom.PEDAL, index) ?: return
        drive("brake pedal $index", mapOf("brakePedalMode" to v)) { DriveSettings.setCustom(DriveSettings.Custom.PEDAL, index) }
    }

    /** Lane keeping: 1 off, 2 warning, 3 assist, 5 emergency. */
    fun setLaneMode(mode: Int) = drive("lane keeping $mode", mapOf("lasMode" to mode)) { DriveSettings.setLaneMode(mode) }

    /** Front collision: 0 off, 1 warning, 2 warning + braking. */
    fun setFcw(choice: Int) {
        val cur = _state.value.fcwState
        drive("front collision $choice", if (choice == 0) mapOf("fcwState" to 1) else mapOf("fcwState" to 2, "fcwBrakeMode" to choice)) { DriveSettings.setFcw(choice, cur) }
    }

    fun startCharge() = evCommand("startCharge") { it.chargeCtrl(1) }
    fun stopCharge() = evCommand("stopCharge") { it.chargeCtrl(2) }
    fun startV2L() = evCommand("startV2L") { it.disChargeCtrl(1) }
    fun stopV2L() = evCommand("stopV2L") { it.disChargeCtrl(2) }

    /** Immediate reread of energy (after a charge setting). */
    fun refreshEnergyNow() { scope.launch { refreshEnergy() } }

    // ── Toggles: "read, toggle, reread" loop until the target state (owner's choice) ──
    // The SAIC service's switch…() commands have no argument: they move one step.
    // A press therefore sets a TARGET state (the opposite of the state read), then toggle and reread
    // until reaching it, with a limited number of steps so it never loops forever.

    private val cycleLock = Mutex()

    private fun cycleTo(
        label: String,
        target: Int,
        maxSteps: Int,
        read: (HvacService) -> Int?,
        advance: (HvacService) -> Unit,
    ) {
        if (BuildConfig.MOCK) { MockCar.command(label, _state); return }
        ownCommandAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            cycleLock.withLock {
                val h = hvac ?: run { Log.w(TAG, "$label ignored: climate not connected"); return@launch }
                var steps = 0
                while (true) {
                    val cur = safe { read(h) }
                    if (cur == target) { Log.i(TAG, "SET $label=$target reached ($steps step(s))"); break }
                    if (cur == null) { Log.w(TAG, "SET $label=$target: state unreadable"); break }
                    if (steps >= maxSteps) { Log.w(TAG, "SET $label=$target NOT reached (read $cur)"); break }
                    try { advance(h) } catch (e: Exception) { Log.w(TAG, "SET $label: ${e.message}"); break }
                    steps++
                    delay(400)
                }
                refreshHvac()
            }
        }
    }

    /** Fan restored when leaving AUTO when no manual setting was kept yet. */
    private val DEFAULT_FAN = 4

    private fun Boolean?.asInt(): Int? = this?.let { if (it) 1 else 0 }
    private fun toggleTarget(cur: Boolean?): Int = if (cur == true) 0 else 1

    fun togglePower() { val on = _state.value.hvacOn != true; showAtOnce(Toggle.POWER, on); if (useLegacy) legacy("power") { LegacyCar.power(on) } else cycleTo("power", if (on) 1 else 0, 2,
        { it.hvacPowerStatus.asInt() }, { it.switchHvacPowerStatus() }) }
    fun toggleAc() { val on = _state.value.acOn != true; showAtOnce(Toggle.AC, on); if (useLegacy) legacy("ac") { LegacyCar.ac(on) } else cycleTo("ac", if (on) 1 else 0, 2,
        { it.acStatus.asInt() }, { it.switchACStatus() }) }
    fun toggleAuto() {
        if (_state.value.autoOn == true && _state.value.hvacOn == true) { showAtOnce(Toggle.AUTO, false); leaveAuto(); return }
        val on = _state.value.autoOn != true
        showAtOnce(Toggle.AUTO, on)
        if (useLegacy) legacy("auto") { LegacyCar.auto(on) } else cycleTo("auto", if (on) 1 else 0, 2,
            { it.autoStatus.asInt() }, { it.switchAutoStatus() })
    }

    /**
     * Last settings outside AUTO (fan, direction, recirculation), kept continuously and after
     * a restart: restored when leaving AUTO from MG4Dash (#34), even if AUTO was turned on elsewhere.
     */
    private fun trackManualClimate(ctx: Context) {
        val sp = app.mg4dash.system.Prefs(ctx).raw
        scope.launch {
            _state.collect { s ->
                if (s.hvacOn != true || s.autoOn != false) return@collect
                val fan = s.fanSpeed?.takeIf { it in VehicleState.FAN_MIN..VehicleState.FAN_MAX }
                val dir = s.fanDirection?.takeIf { it in 0..6 }
                val rec = s.recirc?.takeIf { it in 0..2 }
                if (fan != null && fan != sp.getInt("manual_fan", -1) || dir != null && dir != sp.getInt("manual_dir", -1) ||
                    rec != null && rec != sp.getInt("manual_recirc", -1)) {
                    sp.edit().apply {
                        fan?.let { putInt("manual_fan", it) }; dir?.let { putInt("manual_dir", it) }; rec?.let { putInt("manual_recirc", it) }
                    }.apply()
                }
            }
        }
    }

    /** Leaves AUTO then restores previous fan, direction and recirculation, rereading the car at each step. */
    private fun leaveAuto() {
        val sp = appContext?.let { app.mg4dash.system.Prefs(it).raw }
        val fan = sp?.getInt("manual_fan", -1)?.takeIf { it >= VehicleState.FAN_MIN }
        val dir = sp?.getInt("manual_dir", -1)?.takeIf { it in 0..6 }
        val rec = sp?.getInt("manual_recirc", -1)?.takeIf { it in 0..2 }
        Log.i(TAG, "leaving AUTO, back to fan $fan, direction $dir, recirc $rec")
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(autoOn = false, fanSpeed = fan ?: _state.value.fanSpeed, fanDirection = dir ?: _state.value.fanDirection, recirc = rec ?: _state.value.recirc); return }
        ownCommandAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            cycleLock.withLock {
                if (useLegacy) {
                    try {
                        LegacyCar.auto(false); delay(700)
                        fan?.let { LegacyCar.fan(it); delay(500) }
                        dir?.let { LegacyCar.direction(it); delay(500) }
                        rec?.let { LegacyCar.loop(it) }
                    } catch (e: Exception) { Log.w(TAG, "leave AUTO (old SDK): ${e.message}") }
                    delay(500); refreshHvac(); return@withLock
                }
                val h = hvac ?: return@withLock
                // 1) Leave AUTO by setting the fan: on SWI69 the AUTO toggle only turns it on,
                //    while setting the fan goes back to manual at once (found on 31/10)
                try { h.setFanSpeed(fan ?: DEFAULT_FAN) } catch (e: Exception) { Log.w(TAG, "leave AUTO fan: ${e.message}") }
                var waited = 0
                while (waited < 1500 && safe { h.autoStatus } == true) { delay(250); waited += 250 }
                if (safe { h.autoStatus } == true) {
                    Log.i(TAG, "leave AUTO: still AUTO after fan, switching")
                    try { h.switchAutoStatus() } catch (e: Exception) { Log.w(TAG, "leave AUTO: ${e.message}") }
                    delay(800)
                }
                Log.i(TAG, "leave AUTO: AUTO now ${safe { h.autoStatus }} after $waited ms")
                // 2) Direction : commande directe
                dir?.let { try { h.setFanDirection(it) } catch (e: Exception) { Log.w(TAG, "leave AUTO direction: ${e.message}") }; delay(400) }
                // 3) Recirculation: 3-state toggle, until the wanted value (3 steps at most)
                rec?.let { target ->
                    for (i in 0 until 3) {
                        val cur = safe { h.airCirculationStatus }?.takeIf { it >= 0 } ?: break
                        if (cur == target) break
                        try { h.switchAirCirculationStatus() } catch (_: Exception) { break }
                        delay(400)
                    }
                }
                refreshHvac()
                Log.i(TAG, "AUTO left: fan ${_state.value.fanSpeed}, direction ${_state.value.fanDirection}, recirc ${_state.value.recirc}")
            }
        }
    }
    fun toggleDefrostFront() { val on = _state.value.defrostFront != true; showAtOnce(Toggle.DEFROST_FRONT, on); if (useLegacy) legacy("defrostFront") { LegacyCar.defrostFront(on) } else cycleTo("defrostFront", if (on) 1 else 0, 2,
        { it.frontDefrostStatus.asInt() }, { it.switchFrontDefrostStatus() }) }
    fun toggleDefrostRear() { val on = _state.value.defrostRear != true; showAtOnce(Toggle.DEFROST_REAR, on); if (useLegacy) legacy("defrostRear") { LegacyCar.defrostRear(on) } else cycleTo("defrostRear", if (on) 1 else 0, 2,
        { it.rearDefrostStatus.asInt() }, { it.switchRearDefrostStatus() }) }

    /** Air flow in AUTO mode (#65): 0 = high, 1 = medium, 2 = low, like the stock climate setting. */
    fun setAutoAirVolume(level: Int) = hvacSetting("autoAirVolume=$level", { it.copy(autoAirVolume = level) },
        { LegacyCar.setAutoAirVolume(level) }) { it.setAutoAirVolume(level) }
    /** Rear defrost on with the front (#65). */
    fun setDefrostLinked(on: Boolean) = hvacSetting("defrostLinked=$on", { it.copy(defrostLinked = on) },
        { LegacyCar.setDefrostLinked(on) }) { it.setDefrostLinkage(if (on) 0 else 1) }
    /** Climate following ECO mode (#65). */
    fun setEcoLinked(on: Boolean) = hvacSetting("ecoLinked=$on", { it.copy(ecoLinked = on) },
        { LegacyCar.setEcoLinked(on) }) { it.setEcoLinkage(if (on) 1 else 2) }

    private fun hvacSetting(label: String, mock: (VehicleState) -> VehicleState, old: () -> Boolean, action: (HvacService) -> Unit) {
        if (BuildConfig.MOCK) { _state.value = mock(_state.value); return }
        if (useLegacy) legacy(label, old) else command(label, action)
    }

    fun toggleEco() { val on = _state.value.ecoOn != true; showAtOnce(Toggle.ECO, on); cycleTo("eco", if (on) 1 else 0, 2,
        { it.ecoStatus.asInt() }, { it.switchEcoStatus() }) }

    /** Recirculation: recirculated (0) ↔ outside air (1); 3-state cycle, so 3 steps at most. */
    /**
     * 3-state recirculation: outside (1) → recirculated (0) → auto (2) → outside.
     * The SAIC command has no argument and moves one step; if the car does not offer
     * the auto state through this button, two steps go back to the start state (no change).
     */
    fun toggleRecirc() {
        // From the mode shown, not the car's (not reread yet after a quick previous tap, #34): shown at once and held
        val target = nextRecirc(_state.value.recirc)
        holdRecirc = target to System.currentTimeMillis() + RECIRC_HOLD_MS
        recircTarget = target
        if (BuildConfig.MOCK) { _state.value = _state.value.copy(recirc = target); return }
        _state.value = _state.value.copy(recirc = target)
        if (recircRunning.getAndSet(true)) return   // the steps under way go on to this last mode
        ownCommandAt = android.os.SystemClock.elapsedRealtime()
        scope.launch {
            val front = if (useLegacy) ourFrontTask() else null
            try {
                cycleLock.withLock {
                    // One step at a time, rereading the car, towards the last mode chosen; 3 steps go round the cycle
                    var steps = 0
                    while (true) {
                        val want = recircTarget ?: break
                        val h = hvac
                        val cur = if (h != null) safe { h.airCirculationStatus }?.takeIf { it >= 0 } else LegacyCar.loopMode()
                        if (cur == want) { Log.i(TAG, "SET recirc=$want reached ($steps step(s))"); break }
                        if (cur == null) { Log.w(TAG, "SET recirc=$want: state unreadable"); break }
                        if (steps >= 4) { Log.w(TAG, "SET recirc=$want NOT reached (read $cur)"); break }
                        try { if (h != null) h.switchAirCirculationStatus() else LegacyCar.loopStep() } catch (e: Exception) { Log.w(TAG, "SET recirc: ${e.message}"); break }
                        steps++
                        delay(400)
                    }
                }
            } finally { recircRunning.set(false) }
            refreshHvac()
            front?.let { keepFront(it) }
        }
    }

    /** The recirculation mode the steps under way go to: the last one chosen. */
    @Volatile private var recircTarget: Int? = null
    private val recircRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Temperature in 1° steps: LO, 18 to 32, HI (limits measured by MG4Control on A9).
     * From a half-degree value, the first press rounds in the requested direction.
     */
    fun stepTemp(direction: Int) {
        val cur = _state.value.driverTemp ?: return
        val min = VehicleState.TEMP_MIN; val max = VehicleState.TEMP_MAX   // LO / HI
        val target = when {
            direction > 0 && cur < VehicleState.tempFirst -> VehicleState.tempFirst
            direction < 0 && cur > VehicleState.tempLast -> VehicleState.tempLast
            direction > 0 -> (Math.floor(cur.toDouble()) + 1).toFloat()
            else -> (Math.ceil(cur.toDouble()) - 1).toFloat()
        }.let { t -> if (t < VehicleState.tempFirst) min else if (t > VehicleState.tempLast) max else t }
        if (target == cur) return
        _state.value = _state.value.copy(driverTemp = target)   // immediate visual feedback
        holdTemp = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("temp=$target") { LegacyCar.temp(Math.round(target)) }
        else { sentTemp(target); command("temp=$target") { it.setDriverTemperature(target) } }
    }

    fun stepFan(delta: Int) {
        val cur = _state.value.fanSpeed ?: return
        // Climate off: lowering the fan turned it back on (#34), only + starts it
        if (delta < 0 && _state.value.hvacOn != true) return
        // Climate off, "+": turn it back on like the power button, the car resumes its last fan
        // level instead of starting from 1 (#34)
        if (delta == 1 && _state.value.hvacOn != true) { togglePower(); return }
        val target = (cur + delta).coerceIn(VehicleState.FAN_MIN, VehicleState.FAN_MAX)
        if (target == cur) return
        _state.value = _state.value.copy(fanSpeed = target)
        holdFan = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("fan=$target") { LegacyCar.fan(target) }
        else command("fan=$target") { it.setFanSpeed(target) }
    }

    /** The temperature gauge's slot [i] (17 slots: LO, the numbers, HI) as the set point to send. */
    internal fun tempForSlot(i: Int, slots: Int = 17): Float = when {
        i <= 0 -> VehicleState.TEMP_MIN
        i >= slots - 1 -> VehicleState.TEMP_MAX
        else -> (VehicleState.tempFirst + (i - 1)).coerceAtMost(VehicleState.tempLast)
    }

    /** Set point picked on the gauge (climate page): sent once, shown at once and held as with − / +. */
    fun setTempSlot(i: Int) {
        val target = tempForSlot(i)
        if (target == _state.value.driverTemp) return
        _state.value = _state.value.copy(driverTemp = target)
        holdTemp = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("temp=$target") { LegacyCar.temp(Math.round(target)) }
        else { sentTemp(target); command("temp=$target") { it.setDriverTemperature(target) } }
    }

    /** Fan level picked on the gauge (1..11): climate off, it is turned on as with "+"; otherwise sent once and held. */
    fun setFanLevel(level: Int) {
        if (_state.value.hvacOn != true) { togglePower(); return }
        val target = level.coerceIn(VehicleState.FAN_MIN, VehicleState.FAN_MAX)
        if (target == _state.value.fanSpeed && _state.value.autoOn != true) return
        _state.value = _state.value.copy(fanSpeed = target)
        holdFan = target to System.currentTimeMillis() + HOLD_MS
        if (useLegacy) legacy("fan=$target") { LegacyCar.fan(target) }
        else command("fan=$target") { it.setFanSpeed(target) }
    }

    /** Air direction, 0–6 scale (MG4Control's AirFlow table). */
    fun setFanDirection(value: Int) {
        if (value !in 0..6) return
        _state.value = _state.value.copy(fanDirection = value)
        holdDir = value to System.currentTimeMillis() + RECIRC_HOLD_MS
        if (useLegacy) legacy("direction=$value") { LegacyCar.direction(value) }
        else command("direction=$value") { it.setFanDirection(value) }
    }
}
