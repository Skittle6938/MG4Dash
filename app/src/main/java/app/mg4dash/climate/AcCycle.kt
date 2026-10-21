package app.mg4dash.climate

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.widget.Toast
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A/C intermittent (issue #34) : appui long sur A/C, le compresseur alterne marche et arrêt
 * (durées réglées dans la calibration de la température). Un toucher sur A/C arrête le cycle et
 * laisse le compresseur en marche ; il s'arrête aussi si la clim ou la voiture s'éteint.
 */
object AcCycle {
    private const val TAG = "AcCycle"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun start(context: Context) {
        if (_active.value) return
        if (CarBridge.state.value.hvacOn != true) {
            Toast.makeText(context, R.string.ac_cycle_need_on, Toast.LENGTH_SHORT).show(); return
        }
        val prefs = Prefs(context.applicationContext)
        _active.value = true
        Toast.makeText(context, R.string.ac_cycle_started, Toast.LENGTH_SHORT).show()
        Log.i(TAG, "démarré : ${prefs.acCycleOnSec} s marche / ${prefs.acCycleOffSec} s arrêt")
        job = scope.launch {
            var on = true
            while (true) {
                setAc(on)
                val until = System.currentTimeMillis() + 1000L * (if (on) prefs.acCycleOnSec else prefs.acCycleOffSec)
                while (System.currentTimeMillis() < until) {
                    delay(1000)
                    if (CarBridge.state.value.hvacOn != true) { finish("clim arrêtée"); return@launch }
                }
                on = !on
            }
        }
    }

    /** Arrêt par le conducteur : le compresseur reste en marche. */
    fun stop() {
        if (!_active.value) return
        finish("toucher sur A/C")
        setAc(true)
    }

    private fun finish(reason: String) {
        Log.i(TAG, "arrêt : $reason")
        job?.cancel(); job = null
        _active.value = false
    }

    private fun setAc(on: Boolean) { if (CarBridge.state.value.acOn != on) CarBridge.toggleAc() }

    /** Boutons A/C : toucher = bascule (ou fin du cycle), appui long = cycle. */
    fun attach(view: View) {
        // Le clignotement s'arrête tout de suite (l'état A/C ne change pas forcément, #34)
        view.setOnClickListener { if (_active.value) { stop(); paint(view) } else CarBridge.toggleAc() }
        view.setOnLongClickListener { start(view.context); paint(view); true }
    }

    /** Clignotement lent et doux du bouton tant que le cycle tourne. */
    fun paint(view: View) {
        val anim = view.getTag(R.id.tag_ac_cycle) as? ObjectAnimator
        if (_active.value && anim == null) {
            view.setTag(R.id.tag_ac_cycle, ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.4f).apply {
                duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE; start()
            })
        } else if (!_active.value && anim != null) {
            anim.cancel(); view.setTag(R.id.tag_ac_cycle, null); view.alpha = 1f
        }
    }
}
