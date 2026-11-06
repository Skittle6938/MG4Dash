package app.mg4dash.ui

import android.app.Activity
import android.view.MotionEvent

/**
 * Pinch on the home screen, two fingers moving together, as on Samsung or Pixel home screens:
 * opens the cards' edit mode (#97). Read in the activity's own touch handling, like [TwoFingerPull];
 * [excluded] keeps the pinch for areas that zoom with it (the map, a web page or an app in the map).
 */
class PinchToEdit(activity: Activity, private val excluded: (Float, Float) -> Boolean, private val onPinch: () -> Unit) {
    private val d = activity.resources.displayMetrics.density
    /** Fingers at least this far apart at the start, and at least this much closer at the end. */
    private val minStart = 160 * d
    private val minClose = 110 * d
    private var startSpan = 0f
    private var tracking = false
    private var done = false

    private fun span(ev: MotionEvent): Float = Math.hypot((ev.getX(0) - ev.getX(1)).toDouble(), (ev.getY(0) - ev.getY(1)).toDouble()).toFloat()

    /** True: the event belongs to the gesture. [cancel] then takes the touch back from the widgets. */
    fun onTouch(ev: MotionEvent, cancel: (MotionEvent) -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; done = false }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = ev.pointerCount == 2 && !done && (0..1).none { excluded(ev.getX(it), ev.getY(it)) }
                if (tracking) startSpan = span(ev).also { if (it < minStart) tracking = false }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (ev.pointerCount != 2) tracking = false
                else {
                    val s = span(ev)
                    if (startSpan - s > minClose && s < startSpan * 0.65f) {
                        tracking = false; done = true
                        MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; cancel(it); it.recycle() }
                        onPinch()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> tracking = false
        }
        return done
    }
}
