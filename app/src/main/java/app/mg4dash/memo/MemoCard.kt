package app.mg4dash.memo

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
import app.mg4dash.chrono.ChronoDraw
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.OptionsPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * The voice memos' card (24/11): the memos kept and the last one on the left, a big record button on the right.
 * Recording, the time, the microphone's level and a stop button, in red (red only means "recording"). The list,
 * playback, names and the USB stick are in its long-press window ([MemoWindow]).
 */
@SuppressLint("ViewConstructor")
class MemoCard(context: Context) : View(context), HomeCard {
    override val card = HomeSlots.Card.MEMO
    override val view: View get() = this

    private val k = ChronoDraw(context)
    private val d = k.d
    private val red = 0xFFFF5252.toInt()
    private var place = CardPlace(HomeSlots.Size.MEDIUM)
    private var memos: List<MemoFiles.Memo> = emptyList()
    private var usb = false
    private var note: MemoRecorder.Note? = null
    private val button = RectF()
    private val plays = ArrayList<Pair<RectF, MemoFiles.Memo>>()
    private var downX = -1f; private var downY = -1f

    init {
        MemoPill.init(context)
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) { downX = e.x; downY = e.y }; false }
        setOnClickListener {
            val r = button.width() / 2 * 1.25f
            if ((downX - button.centerX()) * (downX - button.centerX()) + (downY - button.centerY()) * (downY - button.centerY()) <= r * r) {
                if (!MemoRecorder.recording && MemoRecorder.inCall(context) && !MemoRecorder.calls(context)) return@setOnClickListener
                MemoPlayer.stop(); MemoRecorder.toggle(context); return@setOnClickListener
            }
            plays.firstOrNull { it.first.contains(downX, downY) }?.let { MemoPlayer.toggle(it.second.file); invalidate() }
        }
        contentDescription = context.getString(R.string.wc_memo)
    }

    override fun place(p: CardPlace) { place = p; invalidate() }

    private val live = CardScope()
    override fun shown(on: Boolean) {
        MemoRecorder.cardVisible = on
        live.set(on) {
            launch { MemoRecorder.changed.collect { reload() } }
            launch { MemoRecorder.rec.collect { invalidate() } }
            launch { MemoRecorder.levels.collect { invalidate() } }
            launch { MemoPlayer.playing.collect { invalidate() } }
            launch { MemoRecorder.note.collect { n -> note = n; invalidate() } }
            // The stick, and a call taking the microphone, looked at now and then
            launch { while (true) { usb = withContext(Dispatchers.IO) { app.mg4dash.system.UsbExport.stick(context) != null }; invalidate(); delay(4_000) } }
        }
    }

    private suspend fun reload() { memos = withContext(Dispatchers.IO) { MemoFiles.list(context) }; invalidate() }

    override fun options(popup: OptionsPopup): OptionsPopup? {
        val a = context as? android.app.Activity ?: return null
        return popup.content(MemoWindow(a).view(), 1720)
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val s = if (place.big) 1.3f else if (place.wide) 1.1f else 1f
        val pad = 20 * d
        val rec = MemoRecorder.rec.value
        val now = SystemClock.elapsedRealtime()
        plays.clear()
        if (rec != null) {
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 2.5f * d; k.p.color = red
            val r = app.mg4dash.ui.FadeOutline.RADIUS * d
            cv.drawRoundRect(RectF(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d), r, r, k.p)
            postInvalidateOnAnimation()
        }
        val tall = place.tall && !place.wide
        val br = if (tall) min(w * 0.17f, h * 0.12f) else min(h * 0.24f, w * 0.15f) * s
        val bx = if (tall) w / 2 else w - pad - br * 1.25f
        val by = if (tall) pad + 30 * d + br * 1.3f else h / 2 - 8 * d
        drawButton(cv, bx, by, br, rec, s)
        val left = RectF(pad, pad * 0.8f, if (tall) w - pad else bx - br * 1.5f - 10 * d, h - pad)
        if (tall) left.top = by + br + 56 * d
        // Title
        var y = (if (tall) pad * 0.8f else left.top) + 17 * s * d
        k.text(cv, context.getString(R.string.mm_title), pad, y, 16 * s, k.title, k.medium, spacing = 0.14f)
        if (rec != null) k.chip(cv, "REC", pad + k.width(context.getString(R.string.mm_title), 16 * s, k.medium, 0.14f) + 14 * d, y - 6 * s * d, 12 * s, red)
        if (tall) y = left.top
        if (rec != null) {
            y += (if (tall) 56 else 62) * s * d
            k.text(cv, MemoFiles.clock(rec.elapsed(now)), left.left, y, 56 * s, k.value, k.light)
            y += 28 * s * d
            k.text(cv, ellipsize(context.getString(if (rec.pausedSince != null) R.string.mm_paused_call else R.string.mm_goes_on), left.width(), 16 * s), left.left, y, 16 * s, k.sub)
            // The microphone's level, 24 bars
            val bars = RectF(left.left, min(left.bottom - 44 * s * d, y + 20 * d), left.left + min(left.width(), 300 * s * d), min(left.bottom, y + 20 * d + 44 * s * d))
            val lv = MemoRecorder.levels.value
            val n = 24; val gap = bars.width() / n * 0.3f; val bw = (bars.width() - gap * (n - 1)) / n
            for (i in 0 until n) {
                val v = lv.getOrNull(i - (n - lv.size)) ?: 0
                val hh = bars.height() * (0.08f + 0.92f * v / 100f)
                k.p.style = Paint.Style.FILL; k.p.color = if (rec.pausedSince != null) k.alpha(k.sub, 0.3f) else k.accent
                val x = bars.left + i * (bw + gap)
                cv.drawRoundRect(RectF(x, bars.centerY() - hh / 2, x + bw, bars.centerY() + hh / 2), bw / 2, bw / 2, k.p)
            }
            return
        }
        // Idle
        val total = memos.sumOf { it.durationMs }
        y += (if (tall) 46 else 58) * s * d
        val count = "${memos.size}"
        k.text(cv, count, left.left, y, 50 * s, k.value, k.light)
        k.text(cv, context.getString(if (memos.size == 1) R.string.mm_word_one else R.string.mm_word_many), left.left + k.width(count, 50 * s, k.light) + 10 * d, y, 22 * s, k.sub)
        y += 26 * s * d
        if (memos.isNotEmpty()) {
            k.text(cv, context.getString(R.string.mm_total, Math.round(total / 60000.0).toInt().coerceAtLeast(if (total > 0) 1 else 0)), left.left, y, 16 * s, k.sub)
            y += 26 * s * d
            val last = memos.first()
            k.text(cv, ellipsize(context.getString(R.string.mm_last, MemoFiles.whenText(context, last.at).replaceFirstChar { it.lowercase() }, MemoFiles.clock(last.durationMs)), left.width(), 17 * s), left.left, y, 17 * s, k.value)
        } else { k.text(cv, ellipsize(context.getString(R.string.mm_none), left.width(), 16 * s), left.left, y, 16 * s, k.sub) }
        val n = note
        val fresh = n != null && now - n.at < (if (n.kind == MemoRecorder.Note.Kind.CAR_OFF) 30_000 else 4_500)
        if (fresh) postInvalidateDelayed(500)
        val inCall = MemoRecorder.inCall(context) && !MemoRecorder.calls(context)
        when {
            inCall -> { y += 28 * s * d; k.text(cv, ellipsize(context.getString(R.string.mm_mic_busy), left.width(), 16 * s), left.left, y, 16 * s, k.sub); postInvalidateDelayed(2_000) }
            fresh && n!!.kind == MemoRecorder.Note.Kind.CAR_OFF -> {
                y += 28 * s * d
                for (line in wrap(context.getString(R.string.mm_car_off, MemoFiles.clock(n.ms)), left.width(), 15 * s).take(3)) {
                    k.text(cv, line, left.left, y, 15 * s, k.sub); y += 21 * s * d
                }
            }
            usb && memos.isNotEmpty() -> { y += 28 * s * d; k.text(cv, ellipsize(context.getString(R.string.mm_usb_hint), left.width(), 15 * s), left.left, y, 15 * s, k.sub) }
        }
        if (fresh && n!!.kind == MemoRecorder.Note.Kind.SAVED)
            k.chip(cv, ellipsize(context.getString(R.string.mm_saved, MemoFiles.clock(n.ms)), left.width() - 20 * d, 14 * s), left.left, left.bottom - 14 * s * d, 14 * s, k.green, 0.9f)
        // Tall: the last three, each with ▶
        if (tall && !fresh) {
            var ry = y + 44 * s * d
            for (m in memos.take(3)) {
                if (ry > h - pad) break
                k.p.style = Paint.Style.FILL; k.p.color = k.border; cv.drawRect(pad, ry - 30 * s * d, w - pad, ry - 30 * s * d + 1, k.p)
                val pr = RectF(pad, ry - 22 * s * d, pad + 36 * s * d, ry + 14 * s * d)
                playButton(cv, pr, MemoPlayer.file.value == m.file && MemoPlayer.playing.value)
                plays += RectF(pr.left - 6 * d, pr.top - 6 * d, w - pad, pr.bottom + 6 * d) to m
                k.text(cv, ellipsize(m.name ?: MemoFiles.whenText(context, m.at), w - pad * 2 - 120 * d, 17 * s), pr.right + 14 * d, ry + 2 * d, 17 * s, k.value)
                k.text(cv, MemoFiles.clock(m.durationMs), w - pad, ry + 2 * d, 17 * s, k.sub, align = Paint.Align.RIGHT)
                ry += 52 * s * d
            }
        }
    }

    /** The record button: a dark disc with a red dot; recording, a red rounded square with a pulsing halo. */
    private fun drawButton(cv: Canvas, cx: Float, cy: Float, r: Float, rec: MemoRecorder.Rec?, s: Float) {
        button.set(cx - r, cy - r, cx + r, cy + r)
        val busy = rec == null && MemoRecorder.inCall(context) && !MemoRecorder.calls(context)
        k.p.style = Paint.Style.FILL
        if (rec != null) {
            val t = (SystemClock.uptimeMillis() % 1600) / 1600f
            k.p.color = k.alpha(red, 0.28f * (1 - t)); cv.drawCircle(cx, cy, r * (1.02f + 0.22f * t), k.p)
            k.p.color = k.alpha(red, 0.14f); cv.drawCircle(cx, cy, r, k.p)
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 2 * d; k.p.color = red; cv.drawCircle(cx, cy, r, k.p)
            k.p.style = Paint.Style.FILL; val q = r * 0.3f
            cv.drawRoundRect(RectF(cx - q, cy - q, cx + q, cy + q), q * 0.3f, q * 0.3f, k.p)
        } else {
            k.p.color = k.idle; cv.drawCircle(cx, cy, r, k.p)
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 1.5f * d; k.p.color = k.border; cv.drawCircle(cx, cy, r, k.p)
            k.p.style = Paint.Style.FILL; k.p.color = if (busy) k.alpha(k.sub, 0.4f) else red; cv.drawCircle(cx, cy, r * 0.36f, k.p)
        }
        val label = context.getString(if (rec != null) R.string.mm_stop else R.string.mm_record)
        k.text(cv, label, cx, cy + r + 28 * s * d, 15 * s, if (rec != null) red else if (busy) k.alpha(k.sub, 0.5f) else k.value, k.medium, Paint.Align.CENTER, 0.12f)
    }

    private fun playButton(cv: Canvas, r: RectF, playing: Boolean) {
        k.p.style = Paint.Style.FILL; k.p.color = k.idle; cv.drawOval(r, k.p)
        k.p.color = k.accent
        val cx = r.centerX(); val cy = r.centerY(); val q = r.width() * 0.2f
        if (playing) { cv.drawRect(cx - q, cy - q, cx - q * 0.3f, cy + q, k.p); cv.drawRect(cx + q * 0.3f, cy - q, cx + q, cy + q, k.p) }
        else { val p = android.graphics.Path(); p.moveTo(cx - q * 0.7f, cy - q); p.lineTo(cx + q, cy); p.lineTo(cx - q * 0.7f, cy + q); p.close(); cv.drawPath(p, k.p) }
    }

    private fun ellipsize(t: String, width: Float, sp: Float): String {
        if (k.width(t, sp) <= width) return t
        var e = t
        while (e.length > 1 && k.width("$e…", sp) > width) e = e.dropLast(1)
        return "$e…"
    }

    private fun wrap(t: String, width: Float, sp: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (word in t.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (k.width(next, sp) > width && line.isNotEmpty()) { out += line; line = word } else line = next
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
