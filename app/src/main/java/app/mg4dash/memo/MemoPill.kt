package app.mg4dash.memo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.ui.Themes

/**
 * The small REC pill (24/11): while a memo records and its card is not on screen, at the top centre over every screen
 * (MG4Dash's pages and the other apps: a system overlay, allowed by the platform signature), with a pulsing red dot,
 * the time and a round stop button.
 */
object MemoPill {
    private var app: Context? = null
    private var view: PillView? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(c: Context) { app = c.applicationContext }

    /** Shown or taken off as the recording and the card's visibility change. */
    fun update() = main.post {
        val want = MemoRecorder.recording && !MemoRecorder.cardVisible
        if (want && view == null) show() else if (!want && view != null) hide()
    }

    fun refresh() { view?.invalidate() }

    private fun show() {
        val c = app ?: return
        val ctx = Themes.themed(c, big = false)
        val d = c.resources.displayMetrics.density
        val v = PillView(ctx)
        val lp = WindowManager.LayoutParams((236 * d).toInt(), (60 * d).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (10 * d).toInt()
        }
        try { c.getSystemService(WindowManager::class.java).addView(v, lp); view = v }
        catch (e: Exception) { Log.w("Memo", "REC pill: ${e.message}") }
    }

    private fun hide() {
        val v = view ?: return
        view = null
        runCatching { app?.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class PillView(c: Context) : View(c) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val red = 0xFFFF5252.toInt()
        private val stop = RectF()

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && stop.contains(e.x, e.y)) MemoRecorder.stop(context)
            return true
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = RectF(d, d, w - d, h - d)
            p.style = Paint.Style.FILL; p.color = Themes.color(context, R.color.panel) or 0xFF000000.toInt()
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = red
            cv.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
            // Pulsing dot
            val t = (SystemClock.uptimeMillis() % 1200) / 1200f
            p.style = Paint.Style.FILL
            p.color = (red and 0x00FFFFFF) or ((0x70 * (1 - t)).toInt() shl 24); cv.drawCircle(h / 2 + 2 * d, h / 2, 7 * d + 7 * d * t, p)
            p.color = red; cv.drawCircle(h / 2 + 2 * d, h / 2, 7 * d, p)
            p.textSize = 15 * d; p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.letterSpacing = 0.1f
            cv.drawText("REC", h / 2 + 18 * d, h / 2 + 5 * d, p)
            p.letterSpacing = 0f
            val rec = MemoRecorder.rec.value
            p.color = Themes.color(context, R.color.text_value); p.textSize = 22 * d; p.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            p.fontFeatureSettings = "tnum"
            cv.drawText(MemoFiles.clock(rec?.elapsed() ?: 0L), h / 2 + 62 * d, h / 2 + 8 * d, p)
            // Stop: 44 dp round button
            val s = 44 * d
            stop.set(w - s - 8 * d, (h - s) / 2, w - 8 * d, (h + s) / 2)
            p.color = (red and 0x00FFFFFF) or 0x30000000; cv.drawOval(stop, p)
            p.color = red; val q = 8 * d
            cv.drawRoundRect(RectF(stop.centerX() - q, stop.centerY() - q, stop.centerX() + q, stop.centerY() + q), 3 * d, 3 * d, p)
            postInvalidateDelayed(100)
        }
    }
}
