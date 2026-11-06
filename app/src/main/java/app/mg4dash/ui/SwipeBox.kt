package app.mg4dash.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Container changing page on swipe, without disturbing touches on its content:
 * swipe up or left = next page (+1), down or right = previous (−1).
 * [vertical] / [horizontal] choose the accepted directions.
 */
class SwipeBox(
    context: Context,
    private val vertical: Boolean = true,
    private val horizontal: Boolean = true,
    val onSwipe: (Int) -> Unit,
) : FrameLayout(context) {
    /** Horizontal swipe handled separately (e.g. previous / next track), otherwise [onSwipe]. */
    var onHorizontal: ((Int) -> Unit)? = null
    /** False: touches all go to the content (map in full screen, app embedded in the map). */
    var active: () -> Boolean = { true }
    /**
     * Above 0: only a horizontal swipe starting this close to the left or right side is one (a web page, #98);
     * everything else, one finger included, goes to the content.
     */
    var edge: () -> Int = { 0 }
    /** The touch started in the [edge] band (or there is none). */
    private var fromEdge = true
    /** A second finger is down: it is a gesture for the content, such as moving the map. */
    private var multi = false
    private var downX = 0f; private var downY = 0f; private var dir = 0; private var axisH = false
    private val slop = 60 * resources.displayMetrics.density / 2

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!active()) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; dir = 0; multi = false
                val e = edge(); fromEdge = e <= 0 || ev.x < e || ev.x >= width - e
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; dir = 0 }
            MotionEvent.ACTION_MOVE -> if (!multi && fromEdge) {
                val dx = ev.x - downX; val dy = ev.y - downY
                val edged = edge() > 0
                if ((horizontal || onHorizontal != null) && Math.abs(dx) > slop && Math.abs(dx) > 2 * Math.abs(dy)) { dir = if (dx < 0) 1 else -1; axisH = true }
                else if (vertical && !edged && Math.abs(dy) > slop && Math.abs(dy) > 2 * Math.abs(dx)) { dir = if (dy < 0) 1 else -1; axisH = false }
            }
        }
        return dir != 0
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP && dir != 0) {
            val h = onHorizontal
            if (axisH && h != null) h(dir) else onSwipe(dir)
            dir = 0
        }
        return true
    }

    companion object {
        /** Puts [view] in a SwipeBox, at its place and with its dimensions in its parent. */
        fun wrap(view: View, vertical: Boolean = true, horizontal: Boolean = true, onSwipe: (Int) -> Unit): SwipeBox {
            val parent = view.parent as ViewGroup
            val index = parent.indexOfChild(view)
            val lp = view.layoutParams
            parent.removeView(view)
            val box = SwipeBox(view.context, vertical, horizontal, onSwipe)
            box.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            parent.addView(box, index, lp)
            return box
        }
    }
}
