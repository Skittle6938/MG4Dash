package app.mg4dash.climate

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
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
 * Pré-climatisation programmée (issue #26) : le conducteur part avec la clé sans verrouiller ;
 * au bout du délai, MG4Dash verrouille les portes et éteint la voiture (sinon les veilleuses restent
 * allumées), puis à chaque cycle rallume la voiture (sans « Ready »), met la clim en marche avec ses
 * réglages actuels, l'arrête à la fin du cycle et éteint de nouveau la voiture.
 * Tout s'arrête si une porte est déverrouillée (retour du conducteur), si la voiture roule, ou sous
 * 20 % de batterie. L'écran reste allumé sur PreCoolActivity pendant tout le programme.
 */
object PreCool {
    private const val TAG = "MG4L.PreCool"
    const val MIN_SOC = 20

    data class Plan(val lockSec: Int, val startMin: Int, val durMin: Int, val runs: Int, val gapMin: Int)

    enum class Phase { IDLE, ARMED, WAITING, RUNNING, DONE, BATTERY, FAILED }

    /**
     * État du programme. Les échéances sont en temps écoulé depuis le démarrage (l'horloge de l'autoradio
     * repart en 2019 au réveil) ; les heures affichées sont calculées une fois, à l'activation.
     */
    data class Status(
        val phase: Phase = Phase.IDLE,
        val plan: Plan? = null,
        val lockAt: Long = 0L,                      // elapsedRealtime
        val runs: List<LongArray> = emptyList(),    // [début, fin] en elapsedRealtime
        val clock: List<Pair<Long, Long>> = emptyList(), // mêmes cycles en heure murale (affichage)
        val lockClock: Long = 0L,
        val run: Int = -1,                          // cycle en cours ou prochain
        val failedRuns: Int = 0,
    ) {
        val active get() = phase == Phase.ARMED || phase == Phase.WAITING || phase == Phase.RUNNING
        val finished get() = phase == Phase.DONE || phase == Phase.BATTERY || phase == Phase.FAILED
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    fun start(context: Context, plan: Plan) {
        stop("nouveau programme")
        val now = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
        val lockAt = now + plan.lockSec * 1000L
        val runs = (0 until plan.runs).map { i ->
            val s = lockAt + (plan.startMin + i * (plan.durMin + plan.gapMin)) * 60_000L
            longArrayOf(s, s + plan.durMin * 60_000L)
        }
        _status.value = Status(Phase.ARMED, plan, lockAt, runs, runs.map { (it[0] - now + wall) to (it[1] - now + wall) },
            lockAt - now + wall, 0)
        Log.i(TAG, "programme : $plan")
        val app = context.applicationContext
        wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4dash:precool").apply { setReferenceCounted(false); acquire(24 * 3600_000L) }
        job = scope.launch { loop(app) }
    }

    /** Arrêt par le conducteur (bouton) ou retour à la voiture : la voiture reste dans l'état où elle est. */
    fun stop(reason: String) {
        if (job == null && _status.value.phase == Phase.IDLE) return
        Log.i(TAG, "arrêt : $reason")
        job?.cancel(); job = null
        _status.value = Status()
        release()
    }

    private fun release() { try { wake?.release() } catch (_: Throwable) {}; wake = null }

    private fun set(phase: Phase, run: Int = _status.value.run) { _status.value = _status.value.copy(phase = phase, run = run) }

    private suspend fun loop(app: Context) {
        var unlockedReads = 0
        while (true) {
            delay(1000)
            val st = _status.value
            val now = SystemClock.elapsedRealtime()
            val car = CarBridge.state.value
            // Garde-fous : la voiture roule, ou le conducteur est revenu (portes déverrouillées)
            if ((car.speedKmh ?: 0f) > 3f) { stop("la voiture roule"); return }
            if (st.phase != Phase.ARMED && now > st.lockAt + 15_000) {
                unlockedReads = if (CarBridge.doorsLocked() == false) unlockedReads + 1 else 0
                if (unlockedReads >= 2) { stop("portes déverrouillées : retour du conducteur"); return }
            }
            val soc = car.batteryPercent
            if (soc != null && soc < MIN_SOC && st.phase != Phase.ARMED) {
                Log.w(TAG, "batterie à $soc % : arrêt")
                CarBridge.setHvac(false); delay(3000); CarBridge.powerOffCar()
                finish(Phase.BATTERY); return
            }
            when (st.phase) {
                Phase.ARMED -> if (now >= st.lockAt) {
                    CarBridge.lockDoors()
                    delay(2000)
                    CarBridge.powerOffCar()
                    Log.i(TAG, "portes verrouillées, voiture éteinte")
                    set(Phase.WAITING, 0)
                    show(app)
                }
                Phase.WAITING -> if (now >= st.runs[st.run][0]) startRun(st.run)
                Phase.RUNNING -> if (now >= st.runs[st.run][1]) {
                    Log.i(TAG, "cycle ${st.run + 1} terminé")
                    CarBridge.setHvac(false); delay(3000); CarBridge.powerOffCar()
                    if (st.run + 1 < st.runs.size) set(Phase.WAITING, st.run + 1)
                    else { finish(if (_status.value.failedRuns > 0 && _status.value.failedRuns == st.runs.size) Phase.FAILED else Phase.DONE); return }
                }
                else -> return
            }
        }
    }

    private suspend fun startRun(i: Int) {
        Log.i(TAG, "cycle ${i + 1} : allumage")
        CarBridge.powerOnCar()
        delay(6000)
        var ok = false
        for (attempt in 1..4) {
            CarBridge.setHvac(true)
            delay(5000)
            if (CarBridge.state.value.hvacOn == true) { ok = true; break }
            Log.w(TAG, "clim pas encore en marche (essai $attempt)")
        }
        if (!ok) _status.value = _status.value.copy(failedRuns = _status.value.failedRuns + 1)
        Log.i(TAG, "cycle ${i + 1} : clim ${if (ok) "en marche" else "refusée"}")
        set(Phase.RUNNING, i)
    }

    /** Fin du programme : le résultat reste affiché 5 minutes, puis l'écran peut se mettre en veille. */
    private suspend fun finish(phase: Phase) {
        set(phase)
        Log.i(TAG, "programme fini : $phase")
        delay(5 * 60_000L)
        if (_status.value.phase == phase) { _status.value = Status(); release() }
        job = null
    }

    private fun show(app: Context) {
        try {
            app.startActivity(Intent(app, PreCoolActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (t: Throwable) { Log.w(TAG, "écran de veille : $t") }
    }
}
