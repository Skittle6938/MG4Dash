package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.mg4dash.R

/** Segmented horizontal gauge (fan, battery, KERS). */
class SegmentedGauge @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var segments: Int = 10
        set(v) { field = v.coerceAtLeast(1); invalidate() }

    /** Number of lit segments, 0..segments; negative = unknown (all off, greyed). */
    var value: Int = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var onColor: Int = app.mg4dash.ui.Themes.color(context, R.color.accent_cold)
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Touching a slot: its index 0..segments-1 (null = non-touch gauge). */
    var onSegmentTap: ((Int) -> Unit)? = null
    var onLongTap: (() -> Unit)? = null

    private val gestures = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: android.view.MotionEvent) = true
        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
            val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
            val i = ((e.x - paddingLeft) / w * segments).toInt().coerceIn(0, segments - 1)
            onSegmentTap?.invoke(i)
            return true
        }
        override fun onLongPress(e: android.view.MotionEvent) { onLongTap?.invoke() }
    })

    /**
     * Touching or sliding along the gauge picks a level: shown at once while the finger moves, given once when it is
     * lifted (its index 0..segments-1). The fan and the temperature set point (climate card and page).
     */
    var onSegmentPick: ((Int) -> Unit)? = null
    private var picking = -1

    private fun indexAt(x: Float): Int {
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        return ((x - paddingLeft) / w * segments).toInt().coerceIn(0, segments - 1)
    }

    /**
     * Held still on a gauge that picks a level (Marc, 20/11: the climate card's fan): this instead of a level, the level
     * shown before put back. A touch or a slide still picks the level.
     */
    var onPickHold: (() -> Unit)? = null
    private var before = 0
    private var downX = 0f
    private var downY = 0f
    private val hold = Runnable {
        val h = onPickHold ?: return@Runnable
        if (picking < 0) return@Runnable
        picking = -1; value = before
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        h()
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        onSegmentPick?.let { pick ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true); before = value; downX = event.x; downY = event.y
                    picking = indexAt(event.x); value = picking + 1
                    if (onPickHold != null) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                android.view.MotionEvent.ACTION_MOVE -> if (picking >= 0) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) removeCallbacks(hold)
                    picking = indexAt(event.x); value = picking + 1
                }
                android.view.MotionEvent.ACTION_UP -> { removeCallbacks(hold); if (picking >= 0) pick(indexAt(event.x)); picking = -1 }
                android.view.MotionEvent.ACTION_CANCEL -> { removeCallbacks(hold); if (picking >= 0) value = before; picking = -1 }
            }
            return true
        }
        if (onSegmentTap == null) return super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked == android.view.MotionEvent.ACTION_DOWN)
        return gestures.onTouchEvent(event)
    }

    private val offColor = app.mg4dash.ui.Themes.color(context, R.color.gauge_off)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val gap = resources.displayMetrics.density * 4f
    private val radius = resources.displayMetrics.density * 3f

    override fun onDraw(canvas: Canvas) {
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val segW = (w - gap * (segments - 1)) / segments
        for (i in 0 until segments) {
            val left = paddingLeft + i * (segW + gap)
            rect.set(left, paddingTop.toFloat(), left + segW, (paddingTop + h).toFloat())
            paint.color = if (value > i) onColor else offColor
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
