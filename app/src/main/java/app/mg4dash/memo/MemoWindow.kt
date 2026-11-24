package app.mg4dash.memo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.chrono.ChronoDraw
import app.mg4dash.ui.Rows
import app.mg4dash.ui.SourceMenu
import app.mg4dash.ui.SwitchView
import app.mg4dash.ui.Themes
import app.mg4dash.ui.Windows
import java.util.Locale

/**
 * The memos' long-press window: every memo on the left (play, name, waveform, length, rename, delete), the USB stick
 * and the recording's settings on the right. Building it starts nothing; the playback's progress is followed only
 * while the window is on screen, and the playback stops when it closes.
 */
class MemoWindow(private val a: Activity) {
    private val k = ChronoDraw(a)
    private val d = k.d
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val root = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
    private val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private class Line(val wave: WaveView, val pos: TextView, val rest: String, val total: Long, val play: View)
    private val rowsByFile = HashMap<java.io.File, Line>()
    private var usbStatus: TextView? = null
    private var usbProgress: ProgressView? = null
    private var usbResult: TextView? = null
    private var busy = false

    fun view(): View {
        root.addView(left, LinearLayout.LayoutParams(0, -2, 1.45f).apply { rightMargin = dp(14) })
        root.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        list(); settings()
        // The playback followed while the window is on screen; stopped when it goes
        val tick = object : Runnable { override fun run() { progress(); root.postDelayed(this, 120) } }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { root.post(tick); refreshUsb() }
            override fun onViewDetachedFromWindow(v: View) { root.removeCallbacks(tick); MemoPlayer.stop() }
        })
        return root
    }

    private fun tv(text: CharSequence, sp: Float, color: Int = col(R.color.text_value), face: Typeface? = null) = TextView(a).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END; face?.let { typeface = it }
    }

    // ── The memos ──

    private fun list() {
        left.removeAllViews(); rowsByFile.clear()
        val memos = MemoFiles.list(a)
        val minutes = Math.round(memos.sumOf { it.durationMs } / 60000.0)
        val mb = memos.sumOf { it.size } / 1_048_576.0
        left.addView(Rows.header(a, a.getString(R.string.mm_stats, "${memos.size} " + a.getString(if (memos.size == 1) R.string.mm_word_one else R.string.mm_word_many), minutes,
            String.format(Locale.getDefault(), "%.1f", mb))))
        val group = Rows.group(a)
        if (memos.isEmpty()) group.addView(tv(a.getString(R.string.mm_none), 18f, col(R.color.text_sub)).apply { setPadding(0, dp(24), 0, dp(24)) })
        for (m in memos) Rows.add(group, row(m))
        left.addView(group, LinearLayout.LayoutParams(-1, -2))
    }

    private fun row(m: MemoFiles.Memo): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = Rows.TAG_ROW
            minimumHeight = dp(92); setPadding(0, dp(10), 0, dp(10))
        }
        val play = PlayView(a, m.file)
        play.setOnClickListener { MemoPlayer.toggle(m.file); play.invalidate(); progress() }
        row.addView(play, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(16) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(tv(m.name ?: MemoFiles.whenText(a, m.at), 19f))
        val wave = WaveView(a, m.levels)
        val size = String.format(Locale.getDefault(), "%.1f Mo", m.size / 1_048_576.0).replace("Mo", a.getString(R.string.mm_mb))
        val pos = tv(if (m.name != null) "${MemoFiles.whenText(a, m.at)} · $size" else size, 14f, col(R.color.text_sub))
        texts.addView(wave, LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(6) })
        texts.addView(pos, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        rowsByFile[m.file] = Line(wave, pos, pos.text.toString(), m.durationMs, play)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(MemoFiles.clock(m.durationMs), 19f).apply { gravity = Gravity.END; fontFeatureSettings = "tnum" },
            LinearLayout.LayoutParams(dp(84), -2).apply { leftMargin = dp(12) })
        // Rename (the MG4Dash keyboard), delete with its confirmation in the row
        val actions = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val rename = iconButton(Icon.EDIT) {
            app.mg4dash.ui.Ask.show(a, a.getString(R.string.mm_rename), m.name ?: "", secret = false) { name ->
                MemoFiles.rename(a, m.file, name); MemoRecorder.touched(); list()
            }
        }
        val trash = iconButton(Icon.TRASH) {}
        trash.setOnClickListener {
            actions.removeAllViews()
            actions.addView(Rows.ghostButton(a, a.getString(R.string.mm_keep)) { actions.removeAllViews(); actions.addView(rename); actions.addView(trash) })
            actions.addView(Rows.actionButton(a, a.getString(R.string.mm_delete), danger = true) {
                if (MemoPlayer.file.value == m.file) MemoPlayer.stop()
                MemoFiles.delete(a, m.file); MemoRecorder.touched(); list()
            }, LinearLayout.LayoutParams(-2, dp(52)).apply { leftMargin = dp(8) })
        }
        actions.addView(rename); actions.addView(trash)
        row.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
        return row
    }

    private fun time(at: Long) = android.text.format.DateFormat.getTimeFormat(a).format(java.util.Date(at))

    /** The memo playing: its waveform filled so far, "0:32 / 1:24" under it. */
    private fun progress() {
        val f = MemoPlayer.file.value
        for ((file, l) in rowsByFile) {
            if (file == f && l.total > 0) {
                val m = MemoPlayer.position()
                l.wave.played = m.toFloat() / l.total; l.pos.text = "${MemoFiles.clock(m.toLong())} / ${MemoFiles.clock(l.total)}"
                l.wave.invalidate()
            } else if (l.wave.played >= 0f) { l.wave.played = -1f; l.pos.text = l.rest; l.wave.invalidate() }
            l.play.invalidate()
        }
    }

    private enum class Icon { EDIT, TRASH }

    private fun iconButton(icon: Icon, onClick: () -> Unit) = object : View(a) {
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h) * 0.22f
            k.p.style = Paint.Style.STROKE; k.p.strokeWidth = 2 * d; k.p.strokeCap = Paint.Cap.ROUND; k.p.strokeJoin = Paint.Join.ROUND
            k.p.color = col(R.color.text_sub)
            val cx = w / 2; val cy = h / 2
            if (icon == Icon.EDIT) {
                cv.drawLine(cx - s, cy + s, cx + s, cy - s, k.p); cv.drawLine(cx - s, cy + s, cx - s * 0.4f, cy + s, k.p)
                cv.drawLine(cx + s * 0.55f, cy - s * 1.45f, cx + s * 1.45f, cy - s * 0.55f, k.p)
            } else {
                cv.drawLine(cx - s * 1.2f, cy - s, cx + s * 1.2f, cy - s, k.p)
                cv.drawRect(cx - s * 0.85f, cy - s, cx + s * 0.85f, cy + s * 1.3f, k.p)
                cv.drawLine(cx - s * 0.35f, cy - s * 1.4f, cx + s * 0.35f, cy - s * 1.4f, k.p)
            }
            k.p.strokeCap = Paint.Cap.BUTT
        }
    }.apply {
        Rows.touchable(this); setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        contentDescription = a.getString(if (icon == Icon.EDIT) R.string.mm_rename else R.string.mm_delete)
    }

    // ── USB stick and settings ──

    private fun settings() {
        right.removeAllViews()
        right.addView(Rows.header(a, a.getString(R.string.mm_usb)))
        val usb = Rows.group(a)
        usbStatus = tv("", 15f, col(R.color.text_sub))
        Rows.add(usb, Rows.row(a, a.getString(R.string.mm_usb_stick), null, usbStatus, null))
        Rows.add(usb, Rows.row(a, a.getString(R.string.mm_copy), a.getString(R.string.mm_copy_sub), Rows.ghostButton(a, a.getString(R.string.mm_copy_button)) { export(false) }, null))
        Rows.add(usb, Rows.row(a, a.getString(R.string.mm_move), a.getString(R.string.mm_move_sub), Rows.actionButton(a, a.getString(R.string.mm_move_button)) { export(true) }, null))
        right.addView(usb, LinearLayout.LayoutParams(-1, -2))
        usbProgress = ProgressView(a).apply { visibility = View.GONE }
        right.addView(usbProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12); leftMargin = dp(6); rightMargin = dp(6) })
        usbResult = tv("", 16f, col(R.color.text_sub)).apply { visibility = View.GONE; setPadding(dp(6), dp(8), 0, 0) }
        right.addView(usbResult)

        right.addView(Rows.header(a, a.getString(R.string.mm_recording_group)))
        val rec = Rows.group(a)
        val musicLabels = listOf(a.getString(R.string.mm_music_cut), a.getString(R.string.mm_music_duck), a.getString(R.string.mm_music_none))
        lateinit var musicValue: TextView
        musicValue = Rows.value(a, musicLabels[MemoRecorder.music(a).ordinal])
        Rows.add(rec, Rows.row(a, a.getString(R.string.mm_music), null, musicValue) {
            SourceMenu.choose(a, musicValue, musicLabels, MemoRecorder.music(a).ordinal) { i -> MemoRecorder.prefs(a).setInt("music", i); musicValue.text = musicLabels[i] + "  ▾" }
        })
        val calls = SwitchView(a).apply { on = MemoRecorder.calls(a) }
        Rows.add(rec, Rows.row(a, a.getString(R.string.mm_calls), a.getString(R.string.mm_calls_sub), calls) {
            MemoRecorder.prefs(a).setBool("calls", !MemoRecorder.calls(a)); calls.on = MemoRecorder.calls(a)
        })
        val qLabels = listOf(a.getString(R.string.mm_quality_voice), a.getString(R.string.mm_quality_high))
        lateinit var qValue: TextView
        qValue = Rows.value(a, qLabels[if (MemoRecorder.high(a)) 1 else 0])
        Rows.add(rec, Rows.row(a, a.getString(R.string.mm_quality), a.getString(R.string.mm_quality_sub), qValue) {
            SourceMenu.choose(a, qValue, qLabels, if (MemoRecorder.high(a)) 1 else 0) { i -> MemoRecorder.prefs(a).setBool("high", i == 1); qValue.text = qLabels[i] + "  ▾" }
        })
        right.addView(rec, LinearLayout.LayoutParams(-1, -2))
        val erase = Rows.group(a)
        Rows.add(erase, Rows.row(a, a.getString(R.string.mm_delete_all), null, Rows.actionButton(a, a.getString(R.string.mm_delete), danger = true) {
            Windows.confirm(a, a.getString(R.string.mm_delete_all), a.getString(R.string.mm_delete_all_q), a.getString(R.string.mm_delete)) {
                MemoPlayer.stop(); MemoFiles.deleteAll(a); MemoRecorder.touched(); list()
            }
        }, null))
        right.addView(erase, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
    }

    /** The stick's state: its name and free space, or none. */
    private fun refreshUsb() {
        Thread {
            val free = MemoUsb.free(a); val label = MemoUsb.label(a)
            root.post {
                usbStatus?.text = if (free == null) a.getString(R.string.mm_usb_none)
                    else a.getString(R.string.mm_usb_free, label ?: "USB", String.format(Locale.getDefault(), "%.1f", free / 1_073_741_824.0))
            }
        }.start()
    }

    private fun export(move: Boolean) {
        if (busy) return
        val memos = MemoFiles.list(a)
        if (memos.isEmpty()) return
        busy = true; MemoPlayer.stop()
        usbProgress?.apply { visibility = View.VISIBLE; value = 0f }
        usbResult?.visibility = View.GONE
        Thread {
            val n = MemoUsb.export(a, memos, move) { done, total -> root.post { usbProgress?.value = done.toFloat() / total } }
            root.post {
                busy = false
                usbProgress?.visibility = View.GONE
                usbResult?.apply {
                    visibility = View.VISIBLE
                    text = when (n) {
                        null -> a.getString(R.string.mm_usb_none)
                        else -> a.getString(if (move) R.string.mm_moved else R.string.mm_copied, n)
                    }
                }
                list(); refreshUsb()
            }
        }.start()
    }

    /** A memo's round play / pause button. */
    @SuppressLint("ViewConstructor")
    private class PlayView(a: Activity, private val file: java.io.File) : View(a) {
        private val k = ChronoDraw(a)
        init { Rows.touchable(this) }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val r = RectF(0f, 0f, w, height.toFloat())
            val playing = MemoPlayer.file.value == file && MemoPlayer.playing.value
            k.p.style = Paint.Style.FILL; k.p.color = if (playing) k.alpha(k.accent, 0.18f) else k.idle; cv.drawOval(r, k.p)
            k.p.color = k.accent
            val cx = r.centerX(); val cy = r.centerY(); val q = w * 0.17f
            if (playing) { cv.drawRect(cx - q, cy - q, cx - q * 0.3f, cy + q, k.p); cv.drawRect(cx + q * 0.3f, cy - q, cx + q, cy + q, k.p) }
            else { val p = android.graphics.Path(); p.moveTo(cx - q * 0.6f, cy - q * 1.1f); p.lineTo(cx + q * 1.1f, cy); p.lineTo(cx - q * 0.6f, cy + q * 1.1f); p.close(); cv.drawPath(p, k.p) }
        }
    }

    /** A memo's waveform, filled in the accent as far as it played ([played] 0..1, −1 not playing). */
    @SuppressLint("ViewConstructor")
    private class WaveView(a: Activity, private val levels: List<Int>) : View(a) {
        private val k = ChronoDraw(a)
        var played = -1f
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val lv = levels.ifEmpty { List(48) { 18 } }
            val n = lv.size; val gap = w / n * 0.35f; val bw = (w - gap * (n - 1)) / n
            k.p.style = Paint.Style.FILL
            for (i in 0 until n) {
                val hh = h * (0.12f + 0.88f * lv[i] / 100f)
                k.p.color = if (played >= 0 && i.toFloat() / n <= played) k.accent else k.alpha(k.sub, 0.35f)
                val x = i * (bw + gap)
                cv.drawRoundRect(RectF(x, (h - hh) / 2, x + bw, (h + hh) / 2), bw / 2, bw / 2, k.p)
            }
        }
    }

    @SuppressLint("ViewConstructor")
    private class ProgressView(a: Activity) : View(a) {
        private val k = ChronoDraw(a)
        var value = 0f; set(v) { field = v; invalidate() }
        override fun onDraw(cv: Canvas) {
            val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
            k.p.style = Paint.Style.FILL; k.p.color = k.alpha(k.sub, 0.2f); cv.drawRoundRect(r, r.height() / 2, r.height() / 2, k.p)
            k.p.color = k.accent; cv.drawRoundRect(RectF(0f, 0f, maxOf(r.height(), r.width() * value), r.height()), r.height() / 2, r.height() / 2, k.p)
        }
    }
}
