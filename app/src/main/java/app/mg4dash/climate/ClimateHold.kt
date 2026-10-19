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
 * Clim maintenue (suite de l'issue #26) : la voiture reste allumée, la clim tourne
 * avec ses réglages actuels et un grand écran montre la température intérieure aux passants.
 * La pré-climatisation programmée a été abandonnée : voiture éteinte, l'autoradio ne peut ni la
 * rallumer ni lancer la clim (essais du 2026-10-19).
 * Fin : durée maximale atteinte ou batterie sous 20 % (clim arrêtée et voiture éteinte), voiture qui
 * roule, ou arrêt par le conducteur (la voiture et la clim restent alors comme elles sont).
 */
object ClimateHold {
    private const val TAG = "MG4L.ClimateHold"
    const val MIN_SOC = 20
    /** Durées maximales proposées, en minutes ; 0 = illimitée (seul le seuil de batterie arrête le mode). */
    val DURATIONS = intArrayOf(0, 15, 30, 60, 120, 240, 480)

    enum class Phase { IDLE, ARMED, ON, DONE, BATTERY }

    /** Échéances en temps écoulé depuis le démarrage : l'horloge de l'autoradio peut sauter au réveil. */
    data class Status(val phase: Phase = Phase.IDLE, val startAt: Long = 0L, val endAt: Long = 0L, val lockAt: Long = 0L) {
        val finished get() = phase == Phase.DONE || phase == Phase.BATTERY
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    /**
     * Démarre : clim en marche tout de suite, compte à rebours de [lockSec] secondes à l'écran (le
     * conducteur sort avec la clé), puis verrouillage des portes. La durée maximale part du démarrage.
     */
    fun start(context: Context, maxMin: Int, lockSec: Int) {
        stop("nouveau départ")
        val now = SystemClock.elapsedRealtime()
        _status.value = Status(Phase.ARMED, now, if (maxMin > 0) now + maxMin * 60_000L else 0L, now + lockSec * 1000L)
        Log.i(TAG, "clim maintenue : ${if (maxMin > 0) "$maxMin min" else "sans limite"}, verrouillage dans $lockSec s")
        val app = context.applicationContext
        wake = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4dash:climhold").apply { setReferenceCounted(false); acquire(24 * 3600_000L) }
        CarBridge.setHvac(true)
        job = scope.launch { loop() }
        show(app)
    }

    /** Arrêt par le conducteur : la voiture et la clim restent dans l'état où elles sont. */
    fun stop(reason: String) {
        if (job == null && _status.value.phase == Phase.IDLE) return
        Log.i(TAG, "arrêt : $reason")
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
            if (st.phase == Phase.ARMED && now >= st.lockAt) {
                CarBridge.lockDoors()
                Log.i(TAG, "portes verrouillées")
                _status.value = st.copy(phase = Phase.ON)
                continue
            }
            // Retour du conducteur : portes déverrouillées après le verrouillage, on rend la main (clim laissée)
            if (st.phase == Phase.ON && now > st.lockAt + 15_000) {
                unlockedReads = if (CarBridge.doorsLocked() == false) unlockedReads + 1 else 0
                if (unlockedReads >= 2) { stop("portes déverrouillées : retour du conducteur"); return }
            }
            if ((car.speedKmh ?: 0f) > 3f) { stop("la voiture roule"); return }
            val soc = car.batteryPercent
            when {
                soc != null && soc < MIN_SOC -> { Log.w(TAG, "batterie à $soc %"); end(Phase.BATTERY); return }
                st.endAt > 0 && SystemClock.elapsedRealtime() >= st.endAt -> { end(Phase.DONE); return }
            }
            // La clim doit tourner : relancée si elle s'arrête (voiture qui coupe, appui sur le bouton…)
            offReads = if (car.hvacOn == false) offReads + 1 else 0
            if (offReads >= 30) { Log.w(TAG, "clim arrêtée : relance"); CarBridge.setHvac(true); offReads = 0 }
        }
    }

    /** Fin normale : clim arrêtée, voiture éteinte, résultat affiché 5 minutes. */
    private suspend fun end(phase: Phase) {
        CarBridge.setHvac(false)
        delay(3000)
        CarBridge.powerOffCar()
        _status.value = _status.value.copy(phase = phase)
        Log.i(TAG, "fin : $phase, voiture éteinte")
        delay(5 * 60_000L)
        if (_status.value.phase == phase) { _status.value = Status(); release() }
        job = null
    }

    fun show(context: Context) {
        try {
            context.startActivity(Intent(context, ClimateHoldActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (t: Throwable) { Log.w(TAG, "écran de la clim maintenue : $t") }
    }
}
