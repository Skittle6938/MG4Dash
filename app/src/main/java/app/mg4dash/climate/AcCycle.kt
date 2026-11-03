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
 * Intermittent A/C (issue #34): long press on A/C, the compressor alternates on and off
 * (durations set in the temperature calibration). A touch on A/C stops the cycle and
 * leaves the compressor on. With the climate or car off, the cycle waits and resumes when
 * turned back on, from one trip to the next, as long as the driver has not stopped it (#61).
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
            app.mg4dash.ui.Toasts.show(context, R.string.ac_cycle_need_on); return
        }
        val prefs = Prefs(context.applicationContext)
        prefs.acCycleOn = true
        app.mg4dash.ui.Toasts.show(context, R.string.ac_cycle_started)
        run(prefs, "started")
    }

    /** MG4Dash startup: resumes the cycle left active on the previous trip (#61). */
    fun resume(context: Context) {
        val prefs = Prefs(context.applicationContext)
        if (prefs.acCycleOn && !_active.value) run(prefs, "resumed from the last trip")
    }

    private fun run(prefs: Prefs, why: String) {
        _active.value = true
        Log.i(TAG, "$why: ${prefs.acCycleOnSec} s on / ${prefs.acCycleOffSec} s off")
        job = scope.launch {
            while (true) {
                // Climate off (or car stopped): wait for it to come back on, the cycle stays active
                while (CarBridge.state.value.hvacOn != true) delay(2000)
                var on = true
                cycle@ while (true) {
                    setAc(on)
                    val until = System.currentTimeMillis() + 1000L * (if (on) prefs.acCycleOnSec else prefs.acCycleOffSec)
                    while (System.currentTimeMillis() < until) {
                        delay(1000)
                        if (CarBridge.state.value.hvacOn != true) { Log.i(TAG, "climate off: waiting for it to come back on"); break@cycle }
                    }
                    on = !on
                }
            }
        }
    }

    /** Stopped by the driver: the compressor stays on, and the cycle will not resume. */
    fun stop(context: Context) {
        if (!_active.value) return
        Prefs(context.applicationContext).acCycleOn = false
        finish("toucher sur A/C")
        if (CarBridge.state.value.hvacOn == true) setAc(true)
    }

    private fun finish(reason: String) {
        Log.i(TAG, "stop: $reason")
        job?.cancel(); job = null
        _active.value = false
    }

    private fun setAc(on: Boolean) { if (CarBridge.state.value.acOn != on) CarBridge.toggleAc() }

    /** A/C buttons: touch = toggle (or end of the cycle), long press = cycle. */
    fun attach(view: View) {
        // Blinking stops at once (the A/C state does not necessarily change, #34)
        view.setOnClickListener { if (_active.value) { stop(view.context); paint(view) } else CarBridge.toggleAc() }
        view.setOnLongClickListener { start(view.context); paint(view); true }
    }

    /** Slow, gentle blinking of the button while the cycle runs. */
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
