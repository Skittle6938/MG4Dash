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

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
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
