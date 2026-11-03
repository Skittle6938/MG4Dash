package app.mg4dash.climate

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import app.mg4dash.system.Log
import app.mg4dash.car.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Keep climate on (follow-up to issue #26): the car stays on while the driver is away,
 * the doors lock after a countdown, and the climate runs with its current settings:
 * - right away, up to a maximum duration (unlimited by default);
 * - or at a chosen time, in one or more cycles (duration, pause), the car waiting on with the
 *   climate off (about 0.2 kW according to the issue's author).
 * A car that is off cannot be turned back on by the head unit (tests of 2026-10-19): it is only
 * switched off at the end (duration reached, last cycle done or battery below 20 %). The driver
 * coming back and unlocking, or the car moving, stops the mode and leaves everything as it is.
 */
object ClimateHold {
    private const val TAG = "ClimHold"
    const val MIN_SOC = 20
    /** Maximum durations offered in "right away" mode, in minutes; 0 = unlimited. */
    val DURATIONS = intArrayOf(0, 15, 30, 60, 120, 240, 480)

    enum class Phase { IDLE, ARMED, WAITING, ON, DONE, BATTERY }

    /**
     * Deadlines in time elapsed since the head unit started ([runs]: [start, end] of each cycle,
     * empty in "right away" mode); [clock]: the same cycles in wall clock time, for display.
     */
    data class Status(
        val phase: Phase = Phase.IDLE,
        val startAt: Long = 0L,
        val endAt: Long = 0L,
        val lockAt: Long = 0L,
        val runs: List<LongArray> = emptyList(),
        val clock: List<Pair<Long, Long>> = emptyList(),
        val run: Int = 0,
    ) {
        val finished get() = phase == Phase.DONE || phase == Phase.BATTERY
        val scheduled get() = runs.isNotEmpty()
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    /** Right away: climate on, lock after [lockSec] s, end after [maxMin] min (0 = never). */
    fun start(context: Context, maxMin: Int, lockSec: Int) {
        val now = SystemClock.elapsedRealtime()
        begin(context, Status(Phase.ARMED, now, if (maxMin > 0) now + maxMin * 60_000L else 0L, now + lockSec * 1000L))
        CarBridge.setHvac(true)
        Log.i(TAG, "now: ${if (maxMin > 0) "$maxMin min" else "no limit"}, locking in $lockSec s")
    }

    /**
     * At a set time: first cycle at [firstWall] (wall clock), [runs] cycles of [durMin] min separated
     * by [gapMin] min; climate off while waiting.
     */
    fun schedule(context: Context, firstWall: Long, runs: Int, durMin: Int, gapMin: Int, lockSec: Int) {
        val now = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
        val first = now + (firstWall - wall).coerceAtLeast(0L)
        val list = (0 until runs).map { i ->
            val s = first + i * (durMin + gapMin) * 60_000L
            longArrayOf(s, s + durMin * 60_000L)
        }
        begin(context, Status(Phase.ARMED, now, 0L, now + lockSec * 1000L, list, list.map { (it[0] - now + wall) to (it[1] - now + wall) }))
        CarBridge.setHvac(false)
        Log.i(TAG, "scheduled: $runs × $durMin min (pause $gapMin min), locking in $lockSec s")
    }

    private fun begin(context: Context, st: Status) {
        stop("nouveau départ")
        _status.value = st
        val app = context.applicationContext
        wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4dash:climhold").apply { setReferenceCounted(false); acquire(24 * 3600_000L) }
        job = scope.launch { loop() }
        show(app)
    }

    /** Stopped by the driver: the car and climate stay in their current state. */
    fun stop(reason: String) {
        if (job == null && _status.value.phase == Phase.IDLE) return
        Log.i(TAG, "stop: $reason")
        job?.cancel(); job = null
        _status.value = Status()
        release()
    }

    private fun release() { try { wake?.release() } catch (_: Throwable) {}; wake = null }

    private suspend fun loop() {
        var offReads = 0
        var unlockedReads = 0
        while (true) {
            delay(1000)
            val st = _status.value
            val car = CarBridge.state.value
            val now = SystemClock.elapsedRealtime()
            if (st.phase == Phase.ARMED) {
                if (now >= st.lockAt) {
                    CarBridge.lockDoors()
                    Log.i(TAG, "doors locked")
                    // At a set time: climate off until the first cycle (#26)
                    if (st.scheduled) CarBridge.setHvac(false)
                    _status.value = st.copy(phase = if (st.scheduled) Phase.WAITING else Phase.ON)
                }
                continue
            }
            // Driver back: doors unlocked after locking, hand back control
            if (now > st.lockAt + 15_000) {
                unlockedReads = if (CarBridge.doorsLocked() == false) unlockedReads + 1 else 0
                if (unlockedReads >= 2) { stop("portes déverrouillées : retour du conducteur"); return }
            }
            if ((car.speedKmh ?: 0f) > 3f) { stop("la voiture roule"); return }
            val soc = car.batteryPercent
            if (soc != null && soc < MIN_SOC) { Log.w(TAG, "battery at $soc %"); end(Phase.BATTERY); return }
            when (st.phase) {
                Phase.WAITING -> if (now >= st.runs[st.run][0]) {
                    Log.i(TAG, "cycle ${st.run + 1}: climate on")
                    CarBridge.setHvac(true); offReads = 0
                    // Compressor too at each cycle (#26), once the climate is on
                    scope.launch { delay(2500); if (CarBridge.state.value.acOn != true) CarBridge.toggleAc() }
                    _status.value = st.copy(phase = Phase.ON)
                }
                Phase.ON -> {
                    if (st.scheduled && now >= st.runs[st.run][1]) {
                        Log.i(TAG, "cycle ${st.run + 1} done")
                        if (st.run + 1 < st.runs.size) { CarBridge.setHvac(false); _status.value = st.copy(phase = Phase.WAITING, run = st.run + 1) }
                        else { end(Phase.DONE); return }
                        continue
                    }
                    if (!st.scheduled && st.endAt > 0 && now >= st.endAt) { end(Phase.DONE); return }
                    // The climate must run: restarted if it stops (car cutting it, button press…)
                    offReads = if (car.hvacOn == false) offReads + 1 else 0
                    if (offReads >= 30) { Log.w(TAG, "climate stopped: restarting"); CarBridge.setHvac(true); offReads = 0 }
                }
                else -> return
            }
        }
    }

    /** Normal end: climate off, car switched off, result shown for 5 minutes. */
    private suspend fun end(phase: Phase) {
        CarBridge.setHvac(false)
        delay(3000)
        CarBridge.powerOffCar()
        _status.value = _status.value.copy(phase = phase)
        Log.i(TAG, "end: $phase, car off")
        delay(5 * 60_000L)
        if (_status.value.phase == phase) { _status.value = Status(); release() }
        job = null
    }

    fun show(context: Context) {
        try {
            context.startActivity(Intent(context, ClimateHoldActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (t: Throwable) { Log.w(TAG, "keep-climate screen: $t") }
    }
}
