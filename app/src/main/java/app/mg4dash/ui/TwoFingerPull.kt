package app.mg4dash.ui

import android.app.Activity
import android.view.MotionEvent
import app.mg4dash.media.MediaHub

/**
 * Two fingers sliding down from the top of the screen open Android Auto when a phone is connected;
 * otherwise the gesture does nothing. Read in the activity's own touch handling, with no strip over
 * the screen: the touches keep reaching the widgets until the gesture is recognised. Pinching or
 * moving the map with two fingers does not trigger it, as both fingers must start at the top.
 */
class TwoFingerPull(private val activity: Activity) {
    private val d = activity.resources.displayMetrics.density
    private val startZone = 130 * d
    private val pull = 110 * d
    private val maxSideways = 150 * d
    private val startX = FloatArray(2)
    private val startY = FloatArray(2)
    private var tracking = false
    private var done = false

    /** True: the event belongs to the gesture. [cancel] then takes the touch back from the widgets. */
    fun onTouch(ev: MotionEvent, cancel: (MotionEvent) -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = false; done = false }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = ev.pointerCount == 2 && !done && (0..1).all { ev.getY(it) < startZone }
                if (tracking) for (i in 0..1) { startX[i] = ev.getX(i); startY[i] = ev.getY(i) }
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (ev.pointerCount != 2) tracking = false
                else if ((0..1).all { ev.getY(it) - startY[it] > pull && Math.abs(ev.getX(it) - startX[it]) < maxSideways }) {
                    tracking = false; done = true
                    MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; cancel(it); it.recycle() }
                    if (MediaHub.phoneProjecting) Dock.openProjection(activity)
                    return true
                }
            }
        }
        return done
    }
}
