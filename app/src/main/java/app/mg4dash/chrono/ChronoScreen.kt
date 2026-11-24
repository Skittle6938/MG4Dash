package app.mg4dash.chrono

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mg4dash.R
import app.mg4dash.ui.Themes
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.math.max
import kotlin.math.min

/**
 * The chrono over the whole screen (option, on by default): while measuring, the time in very big, the speed, the
 * power and the progress, over thin speed lines; then the result for 10 s. Over MG4Dash's own window only.
 */
object ChronoScreen {
    private var activity: WeakReference<Activity>? = null
    private var view: ScreenView? = null
    private var following = false

    /** The home screen's activity: the full screen goes over it. Starts following the measure once. */
    fun attach(c: Context) {
        val a = c as? Activity ?: return
        activity = WeakReference(a)
        if (following) return
        following = true
        MainScope().launch { ChronoEngine.phase.collect { onPhase(it) } }
    }

    val showing: Boolean get() = view != null

    private fun onPhase(p: ChronoEngine.Phase) {
        val a = activity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
        when (p) {
            is ChronoEngine.Phase.Running -> if (view == null && a != null && ChronoEngine.optFullscreen(a)) show(a)
            is ChronoEngine.Phase.Done -> {}
            else -> hide()
        }
        view?.invalidate()
    }

    private fun show(a: Activity) {
        val root = a.window.decorView as? ViewGroup ?: return
        val v = ScreenView(a)
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view = v
    }

    private fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    @SuppressLint("ViewConstructor")
    private class ScreenView(c: Context) : View(c) {
        private val k = ChronoDraw(c)
        private val d = k.d
        private val bg = if (Themes.isLight(c)) Themes.color(c, R.color.bg_top) or 0xFF000000.toInt() else 0xFF050506.toInt()
        private val started = SystemClock.uptimeMillis()
        private var doneAt = 0L
        private val cancelBox = RectF(); private val rearmBox = RectF(); private val closeBox = RectF()
        private val rnd = java.util.Random(7)
        private val lines = List(30) { floatArrayOf(rnd.nextFloat(), 0.08f + rnd.nextFloat() * 0.84f, 0.05f + rnd.nextFloat() * 0.18f, 0.6f + rnd.nextFloat() * 0.8f) }
        private val dots = List(28) { floatArrayOf((rnd.nextFloat() * Math.PI * 2).toFloat(), 0.5f + rnd.nextFloat()) }

        init { isClickable = true; isFocusable = true; elevation = 100f }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) when {
                ChronoEngine.phase.value is ChronoEngine.Phase.Running && cancelBox.contains(e.x, e.y) -> ChronoEngine.cancel()
                rearmBox.contains(e.x, e.y) -> { ChronoEngine.dismiss(); ChronoEngine.tap(context) }
                closeBox.contains(e.x, e.y) -> ChronoEngine.dismiss()
            }
            return true
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            cv.drawColor(bg)
            when (val p = ChronoEngine.phase.value) {
                is ChronoEngine.Phase.Running -> running(cv, w, h, p)
                is ChronoEngine.Phase.Done -> {
                    if (doneAt == 0L) doneAt = SystemClock.uptimeMillis()
                    if (SystemClock.elapsedRealtime() - p.at > 10_000) { ChronoEngine.dismiss(); return }
                    result(cv, w, h, p)
                }
                else -> {}
            }
            postInvalidateOnAnimation()
        }

        /** Thin lines streaking right to left, more and faster with the speed. */
        private fun speedLines(cv: Canvas, w: Float, h: Float, speed: Double) {
            val f = (speed / 100.0).coerceIn(0.0, 1.3).toFloat()
            val t = (SystemClock.uptimeMillis() - started) / 1000f
            k.p.style = Paint.Style.STROKE; k.p.strokeCap = Paint.Cap.ROUND
            for (l in lines) {
                val len = l[2] * w * (0.4f + f)
                val x = w - ((l[0] * (w + len) + t * l[3] * w * (0.15f + f * 1.4f)) % (w + len))
                k.p.strokeWidth = 1.2f * d
                k.p.color = k.alpha(k.accent, (0.04f + 0.32f * f) * l[3] / 1.4f)
                cv.drawLine(x, l[1] * h, x + len, l[1] * h, k.p)
            }
            k.p.strokeCap = Paint.Cap.BUTT
        }

        private fun header(cv: Canvas, w: Float, kind: ChronoMath.Kind, sport: Boolean, clim: Boolean, pad: Float): Float {
            val y = pad + 26 * d
            k.dial(cv, pad + 18 * d, y - 8 * d, 18 * d, true, null, null, "", null, false)
            val title = "${context.getString(R.string.ch_title)} · ${k.label(kind).uppercase()}"
            k.text(cv, title, pad + 50 * d, y, 20f, k.value, k.medium, spacing = 0.12f)
            var x = pad + 50 * d + k.width(title, 20f, k.medium, 0.12f) + 18 * d
            if (sport) x += k.chip(cv, context.getString(R.string.dm_sport), x, y - 7 * d, 15f, k.orange) + 10 * d
            if (clim) k.chip(cv, context.getString(R.string.ch_climate_off), x, y - 7 * d, 15f, k.accent)
            return y
        }

        private fun button(cv: Canvas, box: RectF, label: String, accent: Boolean) {
            k.p.style = Paint.Style.FILL; k.p.color = if (accent) k.alpha(k.accent, 0.16f) else k.idle
            cv.drawRoundRect(box, box.height() / 2, box.height() / 2, k.p)
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 2 * d; k.p.color = if (accent) k.accent else k.border
            cv.drawRoundRect(box, box.height() / 2, box.height() / 2, k.p)
            k.text(cv, label, box.centerX(), box.centerY() + 7 * d, 20f, if (accent) k.accent else k.value, k.medium, Paint.Align.CENTER)
        }

        private fun running(cv: Canvas, w: Float, h: Float, p: ChronoEngine.Phase.Running) {
            val l = p.live
            speedLines(cv, w, h, l.speed)
            val pad = 32 * d
            val y = header(cv, w, p.kind, p.sport, p.climateCut, pad)
            cancelBox.set(w - pad - 170 * d, y - 34 * d, w - pad, y + 16 * d)
            button(cv, cancelBox, context.getString(R.string.cancel), false)
            // The time
            val big = min(190f, h / d * 0.36f)
            val ty = h * 0.55f
            val tt = k.time(l.elapsed)
            k.text(cv, tt, w / 2, ty, big, k.value, k.light, Paint.Align.CENTER)
            k.text(cv, "s", w / 2 + k.width(tt, big, k.light) / 2 + 12 * d, ty, big * 0.25f, k.sub)
            l.delta?.let { dl ->
                val s = context.getString(R.string.ch_vs_record, k.signed(dl))
                k.chip(cv, s, w / 2 - (k.width(s, 20f, k.medium) + 26 * d) / 2, ty + 48 * d, 20f, if (dl <= 0) k.green else k.orange)
            }
            // Speed on the left, power on the right
            val sy = h * 0.42f
            k.text(cv, context.getString(R.string.ch_speed), pad * 1.5f, sy - 76 * d, 15f, k.title, k.medium, spacing = 0.14f)
            k.text(cv, "${k.speed(l.speed)}", pad * 1.5f, sy, 76f, k.value, k.light)
            k.text(cv, k.unit, pad * 1.5f, sy + 30 * d, 18f, k.sub)
            val rx = w - pad * 1.5f
            k.text(cv, context.getString(R.string.ch_power), rx, sy - 76 * d, 15f, k.title, k.medium, Paint.Align.RIGHT, 0.14f)
            k.text(cv, l.kw?.let { "${Math.round(it)}" } ?: "—", rx - 46 * d, sy, 76f, k.value, k.light, Paint.Align.RIGHT)
            k.text(cv, "kW", rx, sy, 20f, k.sub, align = Paint.Align.RIGHT)
            val top = max(100.0, (l.maxKw ?: 0.0) * 1.15)
            val bar = RectF(rx - 200 * d, sy + 20 * d, rx, sy + 26 * d)
            k.p.style = Paint.Style.FILL; k.p.color = k.alpha(k.sub, 0.2f); cv.drawRoundRect(bar, 3 * d, 3 * d, k.p)
            k.p.color = k.accent; cv.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * ((l.kw ?: 0.0) / top).toFloat().coerceIn(0f, 1f), bar.bottom), 3 * d, 3 * d, k.p)
            l.maxKw?.let { m ->
                val mx = bar.left + bar.width() * (m / top).toFloat().coerceIn(0f, 1f)
                k.p.color = k.value; cv.drawRect(mx - d, bar.top - 4 * d, mx + d, bar.bottom + 4 * d, k.p)
                k.text(cv, context.getString(R.string.ch_max, "${Math.round(m)} kW"), rx, bar.bottom + 24 * d, 15f, k.sub, align = Paint.Align.RIGHT)
            }
            // Progress
            val t = ChronoMath.targets(p.kind, k.mph)
            val track = RectF(pad * 1.5f, h - pad - 40 * d, w - pad * 1.5f, h - pad - 30 * d)
            val split = t.split?.let { ((it - t.from) / (t.to - t.from)).toFloat() }
            k.track(cv, track, ((l.speed - t.from) / (t.to - t.from)).toFloat(), split, l.ghost?.toFloat(), null)
            k.text(cv, "${k.speed(t.from)}", track.left, track.bottom + 26 * d, 15f, k.sub)
            k.text(cv, "${k.speed(t.to)} ${k.unit}", track.right, track.bottom + 26 * d, 15f, k.sub, align = Paint.Align.RIGHT)
            if (split != null) {
                val sx = track.left + track.width() * split
                val s = l.split?.let { "${k.label(p.kind, true).substringBefore(' ')} · ${k.time(it)} s" } ?: "${k.speed(t.split!!)}"
                if (l.split != null) k.chip(cv, s, sx, track.top - 26 * d, 16f, k.value, 0.12f).let { } else k.text(cv, s, sx, track.top - 18 * d, 15f, k.sub, align = Paint.Align.CENTER)
            }
        }

        private fun result(cv: Canvas, w: Float, h: Float, p: ChronoEngine.Phase.Done) {
            val run = p.run
            val pad = 32 * d
            val y = header(cv, w, run.kind, run.sport, run.climateCut, pad)
            p.restored?.let { k.text(cv, context.getString(R.string.ch_restored, it), w - pad, y, 16f, k.sub, align = Paint.Align.RIGHT) }
            val left = pad * 1.5f
            // Pill and gap
            val py = y + 62 * d
            var x = left
            if (p.downhill != null) x += k.chip(cv, context.getString(R.string.ch_downhill, k.climb(p.downhill)), x, py, 17f, k.sub, 0.08f) + 16 * d
            else if (p.beaten.isNotEmpty()) {
                val pw = k.chip(cv, context.getString(R.string.ch_new_record), x, py, 17f, k.orange, 0.24f)
                shimmer(cv, x, py, pw, 17f)
                burst(cv, x + pw / 2, py)
                x += pw + 16 * d
            }
            p.previous?.takeIf { p.downhill == null }?.let { pr ->
                val dl = (run.full - pr.full).toDouble()
                k.text(cv, k.signed(dl) + " s", x, py + 8 * d, 22f, if (dl <= 0) k.green else k.orange, k.medium)
            }
            // The time
            val big = min(150f, h / d * 0.27f)
            val ty = py + 30 * d + big * d * 0.82f
            val tt = k.time(run.full)
            k.text(cv, tt, left - 6 * d, ty, big, k.value, k.light)
            k.text(cv, "s", left + k.width(tt, big, k.light) + 6 * d, ty, big * 0.25f, k.sub)
            // Six tiles
            val tiles = listOf(
                (if (run.kind == ChronoMath.Kind.STANDING) k.label(run.kind, true) else k.label(run.kind)) to (run.split?.let { "${k.time(it)} s" } ?: "${k.time(run.full)} s"),
                context.getString(R.string.ch_peak_kw) to k.kw(run.maxKw),
                context.getString(R.string.ch_distance) to k.dist(run.distance),
                context.getString(R.string.ch_battery) to (run.soc?.let { "$it %" } ?: "—"),
                context.getString(R.string.ch_outside) to k.temp(run.outside),
                context.getString(R.string.ch_climb) to k.climb(run.climb),
            )
            val gx = left; val gw = w * 0.48f - left; val tw = (gw - 24 * d) / 3; val th = 76 * d
            val gy = ty + 26 * d
            tiles.forEachIndexed { i, (label, v) ->
                val r = RectF(gx + (i % 3) * (tw + 12 * d), gy + (i / 3) * (th + 12 * d), gx + (i % 3) * (tw + 12 * d) + tw, gy + (i / 3) * (th + 12 * d) + th)
                if (r.bottom > h - 10 * d) return@forEachIndexed
                k.p.style = Paint.Style.FILL; k.p.color = k.idle; cv.drawRoundRect(r, 14 * d, 14 * d, k.p)
                k.text(cv, label.uppercase(), r.left + 14 * d, r.top + 26 * d, 12f, k.title, k.medium, spacing = 0.1f)
                k.text(cv, v, r.left + 14 * d, r.bottom - 16 * d, 24f, k.value)
            }
            // The curve and the buttons
            val cr = RectF(w * 0.53f, py - 10 * d, w - pad, h - pad - 90 * d)
            val rec = p.previous ?: ChronoEngine.history.value.record(if (run.kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, k.mph)
            k.curve(cv, cr, run, rec, axes = true, legend = true)
            closeBox.set(w - pad - 180 * d, h - pad - 60 * d, w - pad, h - pad)
            rearmBox.set(closeBox.left - 214 * d, closeBox.top, closeBox.left - 20 * d, closeBox.bottom)
            button(cv, rearmBox, context.getString(R.string.ch_rearm), true)
            button(cv, closeBox, context.getString(R.string.ch_close), false)
        }

        private fun shimmer(cv: Canvas, x: Float, cy: Float, w: Float, sp: Float) {
            val h = sp * d * 1.75f
            val t = (SystemClock.uptimeMillis() % 1800) / 1800f
            val sx = x - w * 0.3f + w * 1.6f * t
            k.p.shader = android.graphics.LinearGradient(sx - w * 0.2f, 0f, sx + w * 0.2f, 0f, intArrayOf(0, 0x77FFFFFF, 0), null, android.graphics.Shader.TileMode.CLAMP)
            k.p.style = Paint.Style.FILL
            cv.drawRoundRect(RectF(x, cy - h / 2, x + w, cy + h / 2), h / 2, h / 2, k.p)
            k.p.shader = null
        }

        /** A small burst of dots once, around the record's pill. */
        private fun burst(cv: Canvas, cx: Float, cy: Float) {
            val t = (SystemClock.uptimeMillis() - doneAt) / 900f
            if (t > 1f) return
            k.p.style = Paint.Style.FILL
            for ((a, s) in dots.map { it[0] to it[1] }) {
                val r = (30 + 110 * t * s) * d
                k.p.color = k.alpha(if (s > 1f) k.orange else k.accent, 1f - t)
                cv.drawCircle(cx + Math.cos(a.toDouble()).toFloat() * r, cy + Math.sin(a.toDouble()).toFloat() * r * 0.6f, 3.2f * d, k.p)
            }
        }
    }
}
