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
 * V2L discharge started as soon as the adapter is plugged in (#82), option off by default. The car signals
 * the adapter with charge state 11. The head unit must be on: at startup, an adapter already plugged in
 * counts as plugging in. Discharge stops at the threshold set on the Energy page.
 */
object V2lAuto {
    private const val STATUS_ADAPTER = 11
    private val DISCHARGE_DONE_OR_STOPPED = 13..15

    /** Not after a stopped or finished discharge, otherwise the car would restart by itself at each stop. */
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
