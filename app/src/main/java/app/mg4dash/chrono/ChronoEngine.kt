package app.mg4dash.chrono

import android.content.Context
import android.os.SystemClock
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.DriveSettings
import app.mg4dash.car.ReadyState
import app.mg4dash.cards.CardPrefs
import app.mg4dash.map.LocationRepo
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.ui.HomeSlots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The chrono's measurement (24/11): armed from the card, it puts the car in Sport and switches the climate off (both
 * optional, both put back after), waits for the car to move (standing start) or to cross 80 km/h (reprise), then
 * reads the speed and the battery's power as fast as the car gives them until the target, and keeps the run.
 *
 * What was changed on the car is written down before it is changed ([RESTORE_KEY]), so it is put back even if MG4Dash
 * is killed in the middle: [restorePending] at the next start.
 *
 * A run is cancelled, with nothing kept, when the speed drops (braking, lifting off), the gear changes, READY goes
 * off, after [RUN_TIMEOUT_S] s, or with the Cancel button. Armed and nothing happens for [ARM_TIMEOUT_MS]: disarmed.
 */
object ChronoEngine {
    private const val TAG = "Chrono"
    private const val RESTORE_KEY = "chrono_restore"
    const val ARM_TIMEOUT_MS = 120_000L
    const val RUN_TIMEOUT_S = 20.0
    /** A drop this far under the highest speed of the run cancels it (km/h): over the readings' own jitter. */
    private const val DROP_KMH = 4.0
    private const val SPORT = 4

    /** The live values of a run, for the card and the full screen. */
    data class Live(
        val elapsed: Double = 0.0,
        val speed: Double = 0.0,
        val kw: Double? = null,
        val maxKw: Double? = null,
        val split: Double? = null,
        /** Ahead of the record (negative) or behind (positive), in s, at the same speed. */
        val delta: Double? = null,
        /** Where the record run was at the same time, 0..1 of the track. */
        val ghost: Double? = null,
    )

    sealed class Phase {
        data object Idle : Phase()
        data class Armed(val kind: ChronoMath.Kind, val sport: Boolean, val climateCut: Boolean, val since: Long) : Phase()
        data class Running(val kind: ChronoMath.Kind, val sport: Boolean, val climateCut: Boolean, val live: Live) : Phase()
        data class Done(val run: ChronoRunData, val beaten: Set<ChronoHistory.Rec>, val previous: ChronoRunData?, val restored: String?, val at: Long,
                        /** Downhill, it would have beaten the record: the slope (m), shown instead of the record. */
                        val downhill: Float? = null) : Phase()
        data class Cancelled(val kind: ChronoMath.Kind, val reason: String, val at: Long) : Phase()
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    /** The runs kept, changed by every measure and by the window's erase buttons. */
    private val _history = MutableStateFlow(ChronoHistory())
    val history: StateFlow<ChronoHistory> = _history
    private var loaded = false

    /** A one-off message for the card ("not available", "settings restored"), with when it came. */
    data class Note(val text: String, val restored: Boolean, val at: Long)
    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note

    /** Armed or measuring: the home screen's usual animations stay quiet. */
    val busy: Boolean get() = _phase.value is Phase.Armed || _phase.value is Phase.Running

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var cancelAsked = false

    private fun prefs(c: Context) = CardPrefs(c, HomeSlots.Card.CHRONO)

    // ── Options (card_chrono_*) ──
    fun optSport(c: Context) = prefs(c).bool("sport", true)
    fun optClimate(c: Context) = prefs(c).bool("climate", true)
    fun optFullscreen(c: Context) = prefs(c).bool("fullscreen", true)
    fun optBeep(c: Context) = prefs(c).bool("beep", true)
    fun optGps(c: Context) = prefs(c).bool("gps", true)
    fun optRolling(c: Context) = prefs(c).bool("rolling", true)

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        _history.value = ChronoHistory.fromJson(prefs(c).string("runs", null))
    }

    private fun save(c: Context, h: ChronoHistory) {
        prefs(c).setString("runs", h.toJson())
        _history.value = ChronoHistory(h.runs, h.records)
    }

    fun removeRun(c: Context, at: Long) { val h = _history.value.copyOf(); h.remove(at); save(c, h) }
    fun clear(c: Context, records: Boolean) { val h = _history.value.copyOf(); h.clear(records); save(c, h) }
    private fun ChronoHistory.copyOf() = ChronoHistory(runs, records)

    /** The speed factor learnt by the speed camera alerter from the GPS (1 when off or not learnt yet). */
    private fun factor(c: Context): Double =
        if (!optGps(c)) 1.0 else (app.mg4dash.radar.RadarRepo.speedCorrection(c)?.let { 1.0 + it / 100.0 } ?: 1.0)

    private fun readSpeed(k: Double): Double? = CarBridge.readSpeed()?.let { it * k }

    private fun readKw(): Double? {
        val v = CarProps.getFloat(CarProps.BMS_PACK_VOL)?.takeIf { it in 100f..900f } ?: return null
        val i = CarProps.getFloat(CarProps.BMS_PACK_CRNT) ?: return null
        return v.toDouble() * i / 1000.0
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    /** The dial touched: arms, or disarms / cancels what is going on. */
    fun tap(c: Context) {
        val ctx = c.applicationContext
        load(ctx)
        when (_phase.value) {
            is Phase.Armed -> disarm(ctx)
            is Phase.Running -> cancel()
            else -> scope.launch { arm(ctx) }
        }
    }

    /** The Cancel button of the full screen or the card. */
    fun cancel() { cancelAsked = true; if (_phase.value is Phase.Armed) job?.cancel() }

    /** Back to idle (the result or the cancellation closed). */
    fun dismiss() { if (_phase.value is Phase.Done || _phase.value is Phase.Cancelled) _phase.value = Phase.Idle }

    private fun arm(c: Context) {
        if (busy) return
        val ready = if (BuildConfig.MOCK) true else ReadyState.ready()
        val k = factor(c)
        val v = readSpeed(k) ?: 0.0
        val mph = Units.miles(c)
        val kind = when {
            ready == false -> null
            v < 0.5 -> ChronoMath.Kind.STANDING
            optRolling(c) && v < ChronoMath.targets(ChronoMath.Kind.ROLLING, mph).from - 2 -> ChronoMath.Kind.ROLLING
            else -> null
        }
        if (kind == null) {
            _note.value = Note(c.getString(R.string.ch_not_available), false, SystemClock.elapsedRealtime())
            return
        }
        // What will be changed, written down first: put back even after a crash
        val sport = optSport(c) && (CarBridge.state.value.driveMode ?: DriveSettings.driveMode()) != SPORT
        val mode = if (sport) (CarBridge.state.value.driveMode ?: DriveSettings.driveMode()) else null
        val climate = optClimate(c) && CarBridge.state.value.hvacOn == true
        if (sport || climate) Prefs(c).raw.edit().putString(RESTORE_KEY, JSONObject().apply {
            mode?.let { put("mode", it) }; if (climate) put("hvac", true)
        }.toString()).commit()
        if (sport) CarBridge.setDriveMode(SPORT)
        if (climate) CarBridge.setHvac(false)
        Log.i(TAG, "armed $kind, sport ${sport}, climate cut $climate, factor ${"%.3f".format(java.util.Locale.US, k)}")
        cancelAsked = false
        _note.value = null
        _phase.value = Phase.Armed(kind, sport || (CarBridge.state.value.driveMode == SPORT && optSport(c)), climate, SystemClock.elapsedRealtime())
        job = scope.launch { measure(c, kind, k, mph, sport, climate) }
    }

    private fun disarm(c: Context) {
        job?.cancel(); job = null
        _phase.value = Phase.Idle
        scope.launch { restore(c) }
    }

    /** The whole run, from armed to kept or cancelled. */
    private suspend fun measure(c: Context, kind: ChronoMath.Kind, k: Double, mph: Boolean, sport: Boolean, climate: Boolean) {
        val t = ChronoMath.targets(kind, mph)
        val samples = ArrayList<ChronoMath.Sample>()
        val power = ArrayList<Pair<Double, Double>>()
        var lastZero: Double? = null
        val armedAt = SystemClock.elapsedRealtime()
        var cancelled: String? = null
        val gear0 = if (BuildConfig.MOCK) null else ReadyState.gearRaw()
        // ── Armed: waiting for the car to move (or to reach the reprise's start) ──
        var startAt: Double? = null
        try {
            while (scope.isActive) {
                if (cancelAsked) { cancelled = ""; break }
                if (SystemClock.elapsedRealtime() - armedAt > ARM_TIMEOUT_MS) { cancelled = ""; break }
                val now = now()
                val v = readSpeed(k)
                if (v != null) {
                    if (kind == ChronoMath.Kind.STANDING) {
                        if (v < 0.5) { lastZero = now; samples.clear(); samples += ChronoMath.Sample(now, 0.0) }
                        else { samples += ChronoMath.Sample(now, v); startAt = now; break }
                    } else {
                        if (samples.lastOrNull()?.v != v) samples += ChronoMath.Sample(now, v)
                        while (samples.size > 6) samples.removeAt(0)
                        if (v >= t.from) { startAt = ChronoMath.crossing(samples, t.from) ?: now; break }
                    }
                }
                delay(if (kind == ChronoMath.Kind.STANDING) 15 else 30)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { cancelled = "" }
        if (cancelled != null || startAt == null) { finishCancelled(c, kind, null); return }

        // ── Running: every reading kept when it changes, the power every other one ──
        var start = if (kind == ChronoMath.Kind.STANDING) (ChronoMath.start(samples, lastZero) ?: startAt) else startAt
        var maxV = samples.last().v
        var split: Double? = null
        var end: Double? = null
        var maxKw: Double? = null; var maxKwAt: Double? = null
        var kw: Double? = null
        val history = _history.value
        val rec = history.record(if (kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, mph)
        val alt0 = LocationRepo.location.value?.takeIf { it.hasAltitude() }?.altitude
        _phase.value = Phase.Running(kind, sport || (optSport(c) && CarBridge.state.value.driveMode == SPORT), climate, Live())
        var tick = 0
        var lastUi = 0.0
        while (true) {
            val now = now()
            if (cancelAsked) { cancelled = c.getString(R.string.ch_cancel_button_reason); break }
            val v = readSpeed(k)
            if (v != null) {
                if (samples.last().v != v) samples += ChronoMath.Sample(now, v)
                // Standing start: the start is refined while the first samples come
                if (kind == ChronoMath.Kind.STANDING && samples.count { it.v > 0 } <= 3) start = ChronoMath.start(samples, lastZero) ?: start
                if (v > maxV) maxV = v
                if (v < maxV - DROP_KMH) { cancelled = c.getString(R.string.ch_cancel_brake, speedText(c, maxV, mph)); break }
                if (split == null && t.split != null && v >= t.split) {
                    split = ChronoMath.crossing(samples, t.split)?.minus(start)
                    if (optBeep(c)) beep(c)
                }
                if (v >= t.to) { end = ChronoMath.crossing(samples, t.to) ?: now; if (optBeep(c)) beep(c); break }
            }
            if (tick % 2 == 0) readKw()?.let { p ->
                kw = p
                samples.lastOrNull()?.let { s -> power += s.v to p }
                if (maxKw == null || p > maxKw!!) { maxKw = p; maxKwAt = samples.last().v }
            }
            if (tick % 12 == 0 && !BuildConfig.MOCK) {
                val gear = ReadyState.gearRaw()
                if (gear0 != null && gear != null && gear != gear0) { cancelled = c.getString(R.string.ch_cancel_gear); break }
                if (ReadyState.ready() == false) { cancelled = c.getString(R.string.ch_cancel_ready); break }
            }
            val elapsed = now - start
            if (elapsed > RUN_TIMEOUT_S) { cancelled = c.getString(R.string.ch_cancel_timeout); break }
            if (now - lastUi > 0.033) {
                lastUi = now
                val cur = samples.last().v
                val delta = rec?.let { r -> ChronoMath.traceTimeAt(r.trace, cur.toFloat())?.let { rt -> if (cur > t.from + 1) elapsed - rt else null } }
                val ghost = rec?.let { r -> ((ChronoMath.traceSpeedAt(r.trace, elapsed) - t.from) / (t.to - t.from)).coerceIn(0.0, 1.0) }
                _phase.value = Phase.Running(kind, (_phase.value as? Phase.Running)?.sport ?: sport, climate,
                    Live(elapsed, cur, kw, maxKw, split, delta, ghost))
            }
            tick++
            delay(20)
        }
        if (end == null || cancelled != null) { finishCancelled(c, kind, cancelled); return }

        // ── Kept ──
        val full = end - start
        val alt1 = LocationRepo.location.value?.takeIf { it.hasAltitude() }?.altitude
        val s = CarBridge.state.value
        val run = ChronoRunData(
            at = System.currentTimeMillis(), kind = kind, mph = mph, split = split?.toFloat(), full = full.toFloat(),
            soc = s.batteryPercent, outside = s.outsideTemp?.takeIf { it > -60f && it < 70f },
            climb = if (alt0 != null && alt1 != null) (alt1 - alt0).toFloat() else null,
            maxKw = maxKw?.toFloat(), maxKwAt = maxKwAt?.toFloat(),
            distance = ChronoMath.distance(samples, start, end).toFloat(),
            sport = (_phase.value as? Phase.Running)?.sport ?: sport, climateCut = climate, gps = k != 1.0,
            curve = ChronoMath.powerCurve(power, Math.ceil(t.to).toInt()),
            trace = ChronoMath.trace(samples, start, end),
        )
        val h = _history.value.copyOf()
        val previous = h.record(if (kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, mph)
        val beaten = h.add(run)
        val downhill = run.climb.takeIf { !ChronoHistory.counts(run) && (previous == null || run.full < previous.full) }
        save(c, h)
        Log.i(TAG, "run $kind ${"%.2f".format(java.util.Locale.US, full)} s, split $split, max $maxKw kW, records $beaten")
        val restored = restore(c)
        _phase.value = Phase.Done(run, beaten, previous, restored, SystemClock.elapsedRealtime(), downhill)
    }

    private suspend fun finishCancelled(c: Context, kind: ChronoMath.Kind, reason: String?) {
        job = null
        if (reason == null) _phase.value = Phase.Idle
        else {
            Log.i(TAG, "run cancelled: $reason")
            _phase.value = Phase.Cancelled(kind, reason, SystemClock.elapsedRealtime())
        }
        restore(c)
    }

    private fun speedText(c: Context, kmh: Double, mph: Boolean) =
        if (mph) "${Math.round(kmh / ChronoMath.MPH)} mph" else "${Math.round(kmh)} km/h"

    private fun beep(c: Context) = runCatching {
        app.mg4dash.ui.FxSound.alertRaw(c, R.raw.fx_radar_blip, (Prefs(c).fxVolume * 0.6f).toInt().coerceAtLeast(10))
    }

    /**
     * Puts back what the chrono changed (the drive mode, the climate), as written down when armed. Returns the line
     * that says so, or null when nothing was changed.
     */
    suspend fun restore(c: Context): String? {
        val sp = Prefs(c).raw
        val raw = sp.getString(RESTORE_KEY, null) ?: return null
        val o = try { JSONObject(raw) } catch (_: Exception) { null }
        sp.edit().remove(RESTORE_KEY).apply()
        o ?: return null
        val parts = ArrayList<String>()
        if (o.has("mode")) {
            val mode = o.getInt("mode")
            CarBridge.setDriveMode(mode)
            parts += c.getString(R.string.ch_restored_mode, modeName(c, mode))
        }
        if (o.optBoolean("hvac")) {
            // The climate's state must be known before toggling it (MG4Dash just started)
            var waited = 0
            while (CarBridge.state.value.hvacOn == null && waited < 20_000) { delay(500); waited += 500 }
            CarBridge.setHvac(true)
            parts += c.getString(R.string.ch_restored_climate)
        }
        if (parts.isEmpty()) return null
        val text = parts.joinToString(" · ")
        Log.i(TAG, "restored: $text")
        _note.value = Note(text, true, SystemClock.elapsedRealtime())
        return text
    }

    /** At MG4Dash's start: what a run left changed (MG4Dash killed while armed or measuring) is put back. */
    fun restorePending(c: Context) {
        val ctx = c.applicationContext
        if (Prefs(ctx).raw.getString(RESTORE_KEY, null) == null || busy) return
        scope.launch { delay(3_000); if (!busy) restore(ctx) }
    }

    fun modeName(c: Context, mode: Int): String = c.getString(when (mode) {
        6 -> R.string.dm_snow; 2 -> R.string.dm_eco; 4 -> R.string.dm_sport; 7 -> R.string.dm_custom; else -> R.string.dm_normal
    })
}
