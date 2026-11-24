package app.mg4dash.chrono

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import app.mg4dash.R
import app.mg4dash.cards.CardPlace
import app.mg4dash.cards.CardScope
import app.mg4dash.cards.HomeCard
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.OptionsPopup
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * The chrono's card (24/11): the last 0-100 and the record on the left with the last runs as bars, a stopwatch dial
 * on the right to arm it. Armed, the dial pulses; measuring, the time, the gap to the record and the progress; then
 * the result (or why it was cancelled). The details are in its long-press window ([ChronoWindow]).
 */
@SuppressLint("ViewConstructor")
class ChronoCard(context: Context) : View(context), HomeCard {
    override val card = HomeSlots.Card.CHRONO
    override val view: View get() = this

    private val k = ChronoDraw(context)
    private val d = k.d
    private var place = CardPlace(HomeSlots.Size.MEDIUM)
    private var phase: ChronoEngine.Phase = ChronoEngine.Phase.Idle
    private var history = ChronoHistory()
    private var note: ChronoEngine.Note? = null
    private val dial = RectF()
    private var downX = -1f; private var downY = -1f

    init {
        ChronoEngine.load(context)
        history = ChronoEngine.history.value
        ChronoScreen.attach(context)
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }; false }
        setOnClickListener {
            val cx = dial.centerX(); val cy = dial.centerY(); val r = dial.width() / 2 * 1.3f
            if ((downX - cx) * (downX - cx) + (downY - cy) * (downY - cy) <= r * r) ChronoEngine.tap(context)
        }
        contentDescription = context.getString(R.string.wc_chrono)
    }

    override fun place(p: CardPlace) { place = p; invalidate() }

    private val live = CardScope()
    override fun shown(on: Boolean) = live.set(on) {
        launch { ChronoEngine.phase.collect { phase = it; invalidate() } }
        launch { ChronoEngine.history.collect { history = it; invalidate() } }
        launch { ChronoEngine.note.collect { note = it; invalidate() } }
    }

    override fun options(popup: OptionsPopup): OptionsPopup? {
        val a = context as? android.app.Activity ?: return null
        return popup.content(ChronoWindow(a).view(), 1760)
    }

    private val mph get() = k.mph
    private val kind get() = when (val p = phase) {
        is ChronoEngine.Phase.Armed -> p.kind
        is ChronoEngine.Phase.Running -> p.kind
        is ChronoEngine.Phase.Done -> p.run.kind
        is ChronoEngine.Phase.Cancelled -> p.kind
        else -> ChronoMath.Kind.STANDING
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val s = if (place.big) 1.3f else 1f
        val pad = 20 * d
        val now = SystemClock.elapsedRealtime()
        // The result and the cancellation go back to idle after 10 s
        when (val p = phase) {
            is ChronoEngine.Phase.Done -> if (now - p.at > 10_000) ChronoEngine.dismiss() else postInvalidateDelayed(500)
            is ChronoEngine.Phase.Cancelled -> if (now - p.at > 10_000) ChronoEngine.dismiss() else postInvalidateDelayed(500)
            else -> {}
        }
        note?.let { if (now - it.at < 4_500) postInvalidateDelayed(500) }
        val armed = phase is ChronoEngine.Phase.Armed
        if (armed) {
            // The card's outline in the accent while armed
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 2.5f * d; k.p.color = k.accent
            val r = app.mg4dash.ui.FadeOutline.RADIUS * d
            cv.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), r, r, k.p)
            postInvalidateOnAnimation()
        }
        when {
            place.tall && !place.wide -> drawTall(cv, w, h, pad, s)
            place.wide -> {
                val half = w * 0.5f
                drawSide(cv, RectF(pad, pad * 0.8f, half - pad * 0.5f, h - pad), s, details = true)
                drawCurve(cv, RectF(half + pad * 0.5f, pad, w - pad, h - pad))
            }
            else -> drawSide(cv, RectF(pad, pad * 0.8f, w - pad, h - pad), s, details = place.medium)
        }
    }

    /** The dial, from the phase. */
    private fun drawDial(cv: Canvas, cx: Float, cy: Float, r: Float) {
        dial.set(cx - r, cy - r, cx + r, cy + r)
        when (val p = phase) {
            is ChronoEngine.Phase.Armed -> {
                val pulse = ((SystemClock.uptimeMillis() % 1400) / 1400f)
                val v = app.mg4dash.car.CarBridge.state.value.speedKmh?.toDouble() ?: 0.0
                k.dial(cv, cx, cy, r, true, null, pulse, context.getString(R.string.ch_ready), "${k.speed(v)} ${k.unit}", false)
            }
            is ChronoEngine.Phase.Running -> {
                val t = ChronoMath.targets(p.kind, mph)
                k.dial(cv, cx, cy, r, true, ((p.live.speed - t.from) / (t.to - t.from)).toFloat(), null, "${k.speed(p.live.speed)}", k.unit, false, k.value)
            }
            is ChronoEngine.Phase.Done, is ChronoEngine.Phase.Cancelled ->
                k.dial(cv, cx, cy, r, false, null, null, context.getString(R.string.ch_replay), null, true)
            else -> k.dial(cv, cx, cy, r, false, null, null, context.getString(R.string.ch_arm), null, true)
        }
    }

    /** Title, the texts of the phase and the dial on the right of [r]. */
    private fun drawSide(cv: Canvas, r: RectF, s: Float, details: Boolean) {
        val dr = min(r.height() * 0.4f, r.width() * 0.2f) * (if (place.big) 1.1f else 1f)
        drawDial(cv, r.right - dr, r.centerY() + dr * 0.05f, dr)
        drawTexts(cv, RectF(r.left, r.top, r.right - dr * 2 - 16 * d, r.bottom), s, details, bars = true)
    }

    private fun titleRow(cv: Canvas, r: RectF, s: Float): Float {
        val y = r.top + 17 * s * d
        k.text(cv, context.getString(R.string.ch_title), r.left, y, 16 * s, k.title, k.medium, spacing = 0.14f)
        var x = r.left + k.width(context.getString(R.string.ch_title), 16 * s, k.medium, 0.14f) + 14 * d
        val (sport, clim) = when (val p = phase) {
            is ChronoEngine.Phase.Armed -> p.sport to p.climateCut
            is ChronoEngine.Phase.Running -> p.sport to p.climateCut
            else -> false to false
        }
        if (sport) x += k.chip(cv, context.getString(R.string.dm_sport), x, y - 6 * s * d, 12 * s, k.orange) + 8 * d
        if (clim && x < r.right - 60 * d) k.chip(cv, context.getString(R.string.ch_climate_off), x, y - 6 * s * d, 12 * s, k.accent)
        return y
    }

    private fun drawTexts(cv: Canvas, r: RectF, s: Float, details: Boolean, bars: Boolean) {
        var y = titleRow(cv, r, s)
        val bigSp = (if (place.big) 86f else if (place.small) 46f else 52f)
        val rec = history.record(if (kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, mph)
        val barsH = if (bars) max(24 * d, r.height() * 0.13f) else 0f
        val now = SystemClock.elapsedRealtime()
        when (val p = phase) {
            is ChronoEngine.Phase.Armed -> {
                y += 44 * s * d
                val t = ChronoMath.targets(p.kind, mph)
                val line = if (p.kind == ChronoMath.Kind.STANDING) context.getString(R.string.ch_starts_move)
                    else context.getString(R.string.ch_starts_at, "${k.speed(t.from)} ${k.unit}")
                k.text(cv, ellipsize(line, r.width(), 21 * s), r.left, y, 21 * s, k.value)
                y += 28 * s * d
                k.text(cv, ellipsize(context.getString(R.string.ch_tap_cancel), r.width(), 17 * s), r.left, y, 17 * s, k.sub)
                y += 30 * s * d
                recordLine(cv, r, y, s, rec)
            }
            is ChronoEngine.Phase.Running -> {
                y += (bigSp + 8) * d
                val l = p.live
                k.text(cv, k.time(l.elapsed), r.left, y, bigSp, k.value, k.light)
                k.text(cv, "s", r.left + k.width(k.time(l.elapsed), bigSp, k.light) + 6 * d, y, 22 * s, k.sub)
                y += 26 * s * d
                l.delta?.let { dl -> k.chip(cv, context.getString(R.string.ch_vs_record, k.signed(dl)), r.left, y - 6 * d, 14 * s, if (dl <= 0) k.green else k.orange) }
                val t = ChronoMath.targets(p.kind, mph)
                val track = RectF(r.left, r.bottom - 12 * d, r.right, r.bottom - 4 * d)
                k.track(cv, track, ((l.speed - t.from) / (t.to - t.from)).toFloat(),
                    t.split?.let { ((it - t.from) / (t.to - t.from)).toFloat() }, l.ghost?.toFloat(),
                    l.split?.let { "${k.time(it)} s" })
                return
            }
            is ChronoEngine.Phase.Done -> {
                y += 20 * s * d
                val r0 = p.run
                if (p.downhill != null) k.chip(cv, context.getString(R.string.ch_downhill, k.climb(p.downhill)), r.left, y, 13 * s, k.sub, 0.08f)
                else if (p.beaten.isNotEmpty()) shimmer(cv, k.chip(cv, context.getString(R.string.ch_new_record), r.left, y, 13 * s, k.orange, 0.22f), r.left, y, 13 * s)
                y += (bigSp + 2) * d
                k.text(cv, k.time(r0.full), r.left, y, bigSp, k.value, k.light)
                val tw = k.width(k.time(r0.full), bigSp, k.light)
                k.text(cv, "s", r.left + tw + 6 * d, y, 22 * s, k.sub)
                p.previous?.takeIf { p.downhill == null }?.let { pr ->
                    val dl = r0.full - pr.full
                    k.text(cv, k.signed(dl.toDouble()), r.left + tw + 30 * d, y, 19 * s, if (dl <= 0) k.green else k.orange, k.medium)
                }
                y += 30 * s * d
                val bits = listOfNotNull(r0.split?.let { "${k.label(r0.kind, true).substringBefore(' ')} ${k.time(it)} s" }, r0.maxKw?.let { k.kw(it) },
                    r0.soc?.let { "$it %" }, r0.outside?.let { k.temp(it) }, r0.climb?.let { k.climb(it) })
                k.text(cv, ellipsize(bits.joinToString(" · "), r.width(), 17 * s), r.left, y, 17 * s, k.value)
                p.restored?.let { y += 26 * s * d; k.text(cv, ellipsize(it.replaceFirstChar { c -> c.uppercase() }, r.width(), 15 * s), r.left, y, 15 * s, k.sub) }
                return
            }
            is ChronoEngine.Phase.Cancelled -> {
                y += 44 * s * d
                k.text(cv, context.getString(R.string.ch_cancelled), r.left, y, 26 * s, k.value)
                y += 30 * s * d
                k.text(cv, ellipsize(p.reason, r.width(), 19 * s), r.left, y, 19 * s, k.sub)
                y += 28 * s * d
                k.text(cv, context.getString(R.string.ch_nothing_kept), r.left, y, 17 * s, k.sub)
                return
            }
            else -> {
                val last = history.runs.firstOrNull { it.kind == ChronoMath.Kind.STANDING && it.mph == mph } ?: history.runs.firstOrNull { it.mph == mph }
                y += (bigSp + 6) * d
                if (last != null) {
                    k.text(cv, k.time(last.full), r.left, y, bigSp, k.value, k.light)
                    k.text(cv, "s", r.left + k.width(k.time(last.full), bigSp, k.light) + 6 * d, y, 22 * s, k.sub)
                    y += 24 * s * d
                    k.text(cv, ellipsize("${k.label(last.kind)} · ${k.date(last.at)}", r.width(), 16 * s), r.left, y, 16 * s, k.sub)
                } else {
                    k.text(cv, "—", r.left, y, bigSp, k.value, k.light)
                    y += 24 * s * d
                    k.text(cv, context.getString(R.string.ch_no_run), r.left, y, 16 * s, k.sub)
                }
                y += 30 * s * d
                val n = note?.takeIf { !it.restored && now - it.at < 4_500 }
                if (n != null) k.text(cv, ellipsize(n.text, r.width(), 16 * s), r.left, y, 16 * s, k.value)
                else recordLine(cv, r, y, s, rec)
                if (details && last != null && y + 30 * s * d < r.bottom - barsH - 10 * d) {
                    y += 30 * s * d
                    k.conditions(cv, listOfNotNull(last.soc?.let { ChronoDraw.Icon.BATTERY to "$it %" }, last.outside?.let { ChronoDraw.Icon.TEMP to k.temp(it) },
                        last.climb?.let { ChronoDraw.Icon.CLIMB to k.climb(it) }, last.maxKw?.let { ChronoDraw.Icon.BOLT to k.kw(it) }), r.left, y, 15 * s, r.right)
                }
            }
        }
        if (bars && phase !is ChronoEngine.Phase.Running) {
            val runs = history.lastTimes(ChronoMath.Kind.STANDING, mph)
            val br = RectF(r.left, r.bottom - barsH, r.left + min(r.width(), 260 * s * d), r.bottom)
            if (br.top > y + 8 * d) k.bars(cv, br, runs, rec?.at)
        }
        note?.takeIf { it.restored && now - it.at < 4_500 }?.let {
            val text = it.text.replaceFirstChar { c -> c.uppercase() }
            k.chip(cv, ellipsize(text, r.width() - 20 * d, 14 * s), r.left, r.bottom - 14 * s * d, 14 * s, k.green, 0.9f)
        }
    }

    /** The trophy and the record's times. */
    private fun recordLine(cv: Canvas, r: RectF, y: Float, s: Float, rec: ChronoRunData?) {
        rec ?: return
        k.trophy(cv, r.left + 9 * s * d, y - 6 * s * d, 18 * s * d, k.orange)
        val split = history.record(ChronoHistory.Rec.SPLIT, mph)
        val line = context.getString(R.string.ch_record, "${k.time(rec.full)} s") +
            (if (rec.kind == ChronoMath.Kind.STANDING) split?.split?.let { " · ${k.label(rec.kind, true).substringBefore(' ')} ${k.time(it)} s" } ?: "" else "")
        k.text(cv, ellipsize(line, r.width() - 26 * s * d, 17 * s), r.left + 26 * s * d, y, 17 * s, k.value)
    }

    /** A light sweeping across the new record's pill. */
    private fun shimmer(cv: Canvas, w: Float, x: Float, cy: Float, sp: Float) {
        val h = sp * d * 1.75f
        val t = (SystemClock.uptimeMillis() % 1800) / 1800f
        val sx = x - w * 0.3f + (w * 1.6f) * t
        val g = android.graphics.LinearGradient(sx - w * 0.2f, 0f, sx + w * 0.2f, 0f, intArrayOf(0, 0x66FFFFFF, 0), null, android.graphics.Shader.TileMode.CLAMP)
        cv.save(); cv.clipRect(x, cy - h / 2, x + w, cy + h / 2)
        k.p.shader = g; k.p.style = Paint.Style.FILL; cv.drawRoundRect(RectF(x, cy - h / 2, x + w, cy + h / 2), h / 2, h / 2, k.p); k.p.shader = null
        cv.restore()
        postInvalidateOnAnimation()
    }

    /** Tall: the dial on top, the time and the record, then the last runs. */
    private fun drawTall(cv: Canvas, w: Float, h: Float, pad: Float, s: Float) {
        val dr = min(w * 0.24f, h * 0.17f) * (if (place.big) 1.1f else 1f)
        drawDial(cv, w / 2, pad + dr * 1.15f, dr)
        val top = pad + dr * 2.4f
        if (phase is ChronoEngine.Phase.Idle) {
            drawTexts(cv, RectF(pad, top - 30 * d, w - pad, top + 150 * s * d), s, details = false, bars = false)
            // The last four runs
            var y = top + 170 * s * d
            val rec = history.record(ChronoHistory.Rec.FULL, mph)
            for (run in history.runs.filter { it.mph == mph }.take(4)) {
                if (y > h - pad) break
                k.p.color = k.border; cv.drawRect(pad, y - 30 * s * d, w - pad, y - 30 * s * d + 1, k.p)
                k.text(cv, k.date(run.at), pad, y, 16 * s, k.sub)
                k.text(cv, run.split?.let { k.time(it) } ?: "—", w * 0.42f, y, 17 * s, k.value, align = Paint.Align.RIGHT)
                k.text(cv, k.time(run.full), w * 0.66f, y, 19 * s, k.value, k.medium, Paint.Align.RIGHT)
                val gap = when {
                    !ChronoHistory.counts(run) -> context.getString(R.string.ch_slope)
                    rec == null || rec.kind != run.kind -> ""
                    rec.at == run.at -> context.getString(R.string.ch_record_run)
                    else -> k.signed((run.full - rec.full).toDouble())
                }
                k.text(cv, gap, w - pad, y, 16 * s, if (rec?.at == run.at) k.orange else k.sub, align = Paint.Align.RIGHT)
                y += 40 * s * d
            }
        } else drawTexts(cv, RectF(pad, top - 30 * d, w - pad, h - pad), s, details = false, bars = false)
    }

    /** Wide places: the last run's power against speed, the record dashed. */
    private fun drawCurve(cv: Canvas, r: RectF) {
        val run = (phase as? ChronoEngine.Phase.Done)?.run ?: history.runs.firstOrNull { it.mph == mph }
        if (run == null) { k.text(cv, context.getString(R.string.ch_no_run), r.centerX(), r.centerY(), 18f, k.sub, align = Paint.Align.CENTER); return }
        val rec = history.record(if (run.kind == ChronoMath.Kind.STANDING) ChronoHistory.Rec.FULL else ChronoHistory.Rec.ROLLING, mph)
        k.curve(cv, r, run, rec, axes = true, legend = true, small = true)
    }

    private fun ellipsize(t: String, width: Float, sp: Float): String {
        if (k.width(t, sp) <= width) return t
        var e = t
        while (e.length > 1 && k.width("$e…", sp) > width) e = e.dropLast(1)
        return "$e…"
    }
}
