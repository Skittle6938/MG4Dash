package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Décharge V2L lancée dès le branchement de l'adaptateur (#82), option désactivée par défaut. La voiture signale
 * l'adaptateur par l'état de charge 11. L'autoradio doit être allumé : au démarrage, un adaptateur déjà branché
 * compte comme un branchement. La décharge s'arrête au seuil réglé sur la page Énergie.
 */
object V2lAuto {
    private const val STATUS_ADAPTER = 11
    private val DISCHARGE_DONE_OR_STOPPED = 13..15

    /** Pas après une décharge arrêtée ou terminée, sinon la voiture repartirait toute seule à chaque arrêt. */
    fun shouldStart(previous: Int?, status: Int?): Boolean =
        status == STATUS_ADAPTER && previous != STATUS_ADAPTER && previous !in DISCHARGE_DONE_OR_STOPPED

    private operator fun IntRange.contains(v: Int?) = v != null && v >= first && v <= last

    fun start(context: Context) {
        val prefs = Prefs(context.applicationContext)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            var previous: Int? = null
            CarBridge.state.collect { s ->
                val status = s.chargeStatus ?: return@collect
                if (shouldStart(previous, status) && prefs.v2lAuto) {
                    Log.i("V2lAuto", "V2L adapter connected: discharge started")
                    CarBridge.startV2L()
                }
                previous = status
            }
        }
    }
}
