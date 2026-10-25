package app.mg4dash.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Conteneur qui change de page au balayage, sans gêner les touchers de son contenu :
 * balayage vers le haut ou vers la gauche = page suivante (+1), vers le bas ou la droite = précédente (−1).
 * [vertical] / [horizontal] choisissent les sens acceptés.
 */
class SwipeBox(
    context: Context,
    private val vertical: Boolean = true,
    private val horizontal: Boolean = true,
    val onSwipe: (Int) -> Unit,
) : FrameLayout(context) {
    /** Balayage horizontal traité à part (ex. morceau précédent / suivant), sinon [onSwipe]. */
    var onHorizontal: ((Int) -> Unit)? = null
    private var downX = 0f; private var downY = 0f; private var dir = 0; private var axisH = false
    private val slop = 60 * resources.displayMetrics.density / 2

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dir = 0 }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX; val dy = ev.y - downY
                if ((horizontal || onHorizontal != null) && Math.abs(dx) > slop && Math.abs(dx) > 2 * Math.abs(dy)) { dir = if (dx < 0) 1 else -1; axisH = true }
                else if (vertical && Math.abs(dy) > slop && Math.abs(dy) > 2 * Math.abs(dx)) { dir = if (dy < 0) 1 else -1; axisH = false }
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
        /** Met [view] dans une SwipeBox, à sa place et avec ses dimensions dans son parent. */
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
