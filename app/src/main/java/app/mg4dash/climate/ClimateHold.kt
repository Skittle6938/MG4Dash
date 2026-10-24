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
 * Clim maintenue (suite de l'issue #26) : la voiture reste allumée pendant l'absence du conducteur,
 * les portes se verrouillent après un compte à rebours, et la clim tourne avec ses réglages actuels :
 * - tout de suite, jusqu'à une durée maximale (illimitée par défaut) ;
 * - ou à une heure choisie, en un ou plusieurs cycles (durée, pause), la voiture attendant allumée et
 *   clim coupée (environ 0,2 kW d'après l'auteur de l'issue).
 * Une voiture éteinte ne peut pas être rallumée par l'autoradio (essais du 2026-10-19) : elle n'est
 * éteinte qu'à la fin (durée atteinte, dernier cycle fini ou batterie sous 20 %). Le conducteur qui
 * revient et déverrouille, ou la voiture qui roule, arrête le mode en laissant tout en l'état.
 */
object ClimateHold {
    private const val TAG = "ClimHold"
    const val MIN_SOC = 20
    /** Durées maximales proposées en mode « tout de suite », en minutes ; 0 = illimitée. */
    val DURATIONS = intArrayOf(0, 15, 30, 60, 120, 240, 480)

    enum class Phase { IDLE, ARMED, WAITING, ON, DONE, BATTERY }

    /**
     * Échéances en temps écoulé depuis le démarrage de l'autoradio ([runs] : [début, fin] de chaque cycle,
     * vide en mode « tout de suite ») ; [clock] : les mêmes cycles en heure murale, pour l'affichage.
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

    /** Tout de suite : clim en marche, verrouillage après [lockSec] s, fin après [maxMin] min (0 = jamais). */
    fun start(context: Context, maxMin: Int, lockSec: Int) {
        val now = SystemClock.elapsedRealtime()
        begin(context, Status(Phase.ARMED, now, if (maxMin > 0) now + maxMin * 60_000L else 0L, now + lockSec * 1000L))
        CarBridge.setHvac(true)
        Log.i(TAG, "now: ${if (maxMin > 0) "$maxMin min" else "no limit"}, locking in $lockSec s")
    }

    /**
     * À une heure : premier cycle à [firstWall] (heure murale), [runs] cycles de [durMin] min séparés
     * de [gapMin] min ; clim coupée pendant l'attente.
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

    /** Arrêt par le conducteur : la voiture et la clim restent dans l'état où elles sont. */
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
                    // À une heure précise : clim coupée jusqu'au premier cycle (#26)
                    if (st.scheduled) CarBridge.setHvac(false)
                    _status.value = st.copy(phase = if (st.scheduled) Phase.WAITING else Phase.ON)
                }
                continue
            }
            // Retour du conducteur : portes déverrouillées après le verrouillage, on rend la main
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
                    // Compresseur aussi à chaque cycle (#26), une fois la clim allumée
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
                    // La clim doit tourner : relancée si elle s'arrête (voiture qui coupe, appui sur le bouton…)
                    offReads = if (car.hvacOn == false) offReads + 1 else 0
                    if (offReads >= 30) { Log.w(TAG, "climate stopped: restarting"); CarBridge.setHvac(true); offReads = 0 }
                }
                else -> return
            }
        }
    }

    /** Fin normale : clim arrêtée, voiture éteinte, résultat affiché 5 minutes. */
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
