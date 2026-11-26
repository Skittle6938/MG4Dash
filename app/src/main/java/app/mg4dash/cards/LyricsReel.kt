package app.mg4dash.cards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import kotlin.math.abs
import kotlin.math.floor

/**
 * Synced lyrics drawn as one reel that scrolls (26/11: separate rows changing their text jumped). The lines move up
 * together, and each one's size, colour and alpha follow its distance to the current place: the next line is as big
 * as the current one, grows into its place, then shrinks and fades as it goes up. Between two lines nothing moves;
 * the move ([LyricsScroll]) is centred on the line's time and redrawn at the display's frame rate.
 */
internal class LyricsReel(context: Context) : View(context) {
    /** Where the song is for the lyrics (the card's clock, the timing setting included). */
    var position: () -> Long = { 0L }
    private var lines: List<Pair<Long, String>> = emptyList()
    private var bigPx = 40f
    private var smallRatio = .6f
    private var wrapLines = 2
    private var valueColor = 0xFFFFFFFF.toInt()
    private var subColor = 0xFFAAAAAA.toInt()
    private var typeface: Typeface = Typeface.DEFAULT_BOLD
    /** Where the current line's middle sits, as a share of the height. */
    var anchor = .42f
    private var running = false
    private var layouts: Array<StaticLayout?> = emptyArray()
    private var layoutWidth = 0
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)

    fun setLines(synced: List<Pair<Long, String>>) {
        if (synced === lines) return
        lines = synced; layouts = arrayOfNulls(synced.size); invalidate()
    }

    fun style(bigPx: Float, smallRatio: Float, wrapLines: Int, value: Int, sub: Int, typeface: Typeface) {
        if (this.bigPx != bigPx || this.wrapLines != wrapLines || this.typeface != typeface) layouts = arrayOfNulls(lines.size)
        this.bigPx = bigPx; this.smallRatio = smallRatio; this.wrapLines = wrapLines
        valueColor = value; subColor = sub; this.typeface = typeface
        invalidate()
    }

    /** The lines around the current one (the line before, the current one, the next two), as shown. */
    fun shownLines(): List<String> {
        if (lines.isEmpty()) return emptyList()
        val i = LyricsScroll.index(lines, position())
        return ((i - 1)..(i + 2)).mapNotNull { lines.getOrNull(it)?.second }
    }

    /** Redrawn while shown and the song plays; still otherwise. */
    fun run(on: Boolean) {
        if (running == on) return
        running = on
        removeCallbacks(wake)
        invalidate()
    }

    private val wake = Runnable { invalidate() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); removeCallbacks(wake) }

    private fun layout(i: Int, width: Int): StaticLayout? {
        if (width != layoutWidth) { layoutWidth = width; layouts = arrayOfNulls(lines.size) }
        if (i !in lines.indices) return null
        layouts[i]?.let { return it }
        val p = TextPaint(paint).apply { textSize = bigPx; typeface = this@LyricsReel.typeface }
        val text = lines[i].second
        val l = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0f, 1.08f)
            .setMaxLines(wrapLines).setEllipsize(TextUtils.TruncateAt.END).build()
        layouts[i] = l
        return l
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lines.isEmpty() || width <= 0 || height <= 0) return
        val w = width - paddingLeft - paddingRight
        if (w <= 0) return
        val pos = position()
        val f = LyricsScroll.progress(lines, pos)
        val k = floor(f).toInt().coerceIn(0, lines.lastIndex)
        val gap = bigPx * .32f
        fun height(j: Int) = (layout(j, w)?.height ?: 0) * scaleAt(j - f)
        // Middles of the lines around k, from k's middle at 0, continuous in f: nothing jumps
        val from = (k - 6).coerceAtLeast(0); val to = (k + 7).coerceAtMost(lines.lastIndex)
        val mids = FloatArray(to - from + 1)
        for (j in k + 1..to) mids[j - from] = mids[j - 1 - from] + height(j - 1) / 2 + gap * spaceAt(j - .5f - f) + height(j) / 2
        for (j in k - 1 downTo from) mids[j - from] = mids[j + 1 - from] - height(j + 1) / 2 - gap * spaceAt(j + .5f - f) - height(j) / 2
        val focus = if (k + 1 <= to) mids[k - from] + (f - k) * (mids[k + 1 - from] - mids[k - from]) else mids[k - from]
        val shift = paddingTop + (height - paddingTop - paddingBottom) * anchor - focus
        val fade = bigPx * 1.2f
        for (j in from..to) {
            val l = layout(j, w) ?: continue
            val s = scaleAt(j - f)
            val h = l.height * s
            val top = mids[j - from] + shift - h / 2
            if (top > height || top + h < 0) continue
            // Faded near the edges, where a line comes in or leaves
            val edge = ((minOf(top + h / 2, height - top - h / 2)) / fade).coerceIn(0f, 1f)
            val a = alphaAt(j - f) * edge
            if (a <= .01f) continue
            l.paint.color = blend(subColor, valueColor, bigAt(j - f))
            l.paint.alpha = (a * 255).toInt()
            canvas.save()
            canvas.translate(paddingLeft + w / 2f, top)
            canvas.scale(s, s)
            canvas.translate(-w / 2f, 0f)
            l.draw(canvas)
            canvas.restore()
        }
        if (!running) return
        if (f != floor(f)) postInvalidateOnAnimation()
        else { removeCallbacks(wake); postDelayed(wake, LyricsScroll.untilNext(lines, pos, 500L)) }
    }

    /** 1 for the current and next lines' places, 0 beyond, linear in between. */
    private fun bigAt(d: Float) = when {
        d <= -1f -> 0f
        d < 0f -> 1f + d
        d <= 1f -> 1f
        d < 2f -> 2f - d
        else -> 0f
    }

    private fun scaleAt(d: Float) = smallRatio + (1f - smallRatio) * bigAt(d)

    /** The space between two lines: a big one around the current line, a smaller one elsewhere. */
    private fun spaceAt(d: Float) = .5f + .5f * (1f - (abs(d) - .5f).coerceIn(0f, 1f))

    private fun alphaAt(d: Float): Float {
        val xs = floatArrayOf(-3f, -2f, -1f, 0f, 1f, 2f, 3f, 4f)
        val ys = floatArrayOf(0f, .28f, .45f, 1f, .72f, .36f, .25f, 0f)
        if (d <= xs.first()) return 0f
        if (d >= xs.last()) return 0f
        val i = xs.indexOfLast { it <= d }
        val t = (d - xs[i]) / (xs[i + 1] - xs[i])
        return ys[i] + (ys[i + 1] - ys[i]) * t
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
