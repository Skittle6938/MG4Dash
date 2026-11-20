package app.mg4dash.ui

import android.app.Activity
import android.view.MotionEvent
import app.mg4dash.media.MediaHub

/**
 * One finger sliding in from the left or the right edge of the screen opens the phone's projection (MG4Link, Android
 * Auto, CarPlay) when a phone is connected; otherwise the gesture does nothing (Marc, 20/11: in place of two fingers
 * from the top, awkward at times; either edge, so that it is within reach whichever side the wheel is, or of the
 * passenger). Read in the activity's own touch handling, with no strip over the screen: the touches keep reaching the
 * widgets until the gesture is recognised, then the widgets get a cancel. A second finger ends it, so pinching or
 * moving the map with two fingers never triggers it.
 */
class EdgeSwipe(private val activity: Activity, private val open: () -> Unit = { if (MediaHub.phoneProjecting) Dock.openProjection(activity) }) {
    private val d = activity.resources.displayMetrics.density
    private val edge = EDGE_DP * d
    private val pull = PULL_DP * d
    private var startX = 0f
    private var startY = 0f
    /** +1: from the left edge, rightwards; -1: from the right edge, leftwards; 0: not tracking. */
    private var side = 0
    private var done = false

    /** True: the event belongs to the gesture. [cancel] then takes the touch back from the widgets. */
    fun onTouch(ev: MotionEvent, width: Int, cancel: (MotionEvent) -> Unit): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                done = false; startX = ev.x; startY = ev.y
                side = when {
                    ev.x <= edge -> 1
                    ev.x >= width - edge -> -1
                    else -> 0
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> side = 0
            MotionEvent.ACTION_MOVE -> if (side != 0 && !done) {
                val dx = (ev.x - startX) * side
                val dy = Math.abs(ev.y - startY)
                when {
                    // Mostly across: no more than one step up or down for two inwards
                    dy > MAX_SLANT * Math.max(dx, 0f) + d * 24 -> side = 0
                    dx >= pull -> {
                        side = 0; done = true
                        MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL; cancel(it); it.recycle() }
                        open()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> side = 0
        }
        return done
    }

    companion object {
        /** The edge band the finger starts in, on each side (dp; px on the car, 43 px with Grand affichage). */
        const val EDGE_DP = 32
        /** How far in the finger goes (dp). */
        const val PULL_DP = 120
        /** The slope allowed: up or down at most half of the way in. */
        const val MAX_SLANT = 0.5f
    }
}
