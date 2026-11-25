package app.mg4dash.cards

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * The lyrics' text size from the room really there (25/11: a tall place showed small text): the largest size where the
 * current and the next line (same size), each wrapping like the song's long lines do, and the [others] smaller lines
 * fit in [w] x [h]. Computed once per place and song, so the size never jumps from one line to the next.
 */
internal object LyricsFit {
    /**
     * [lyrics]: the song's lines (the 90th percentile of their length stands for a long line); [wrap]: the lines a long
     * one may take; [ratio]: the others' size to the big one; [rowGap]: the room taken by each row's margins.
     */
    fun size(w: Int, h: Int, lyrics: List<String>, wrap: Int, others: Int, ratio: Float, rowGap: Int,
             minPx: Float, maxPx: Float, typeface: Typeface? = Typeface.create("sans-serif-medium", Typeface.BOLD)): Float {
        if (w <= 0 || h <= 0) return minPx
        val long = longLine(lyrics)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
        val rows = 2 + others
        val room = h - rows * rowGap
        fun fits(px: Float): Boolean {
            paint.textSize = px
            val layout = StaticLayout.Builder.obtain(long, 0, long.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f, 1.08f).setIncludePad(false).build()
            if (layout.lineCount > wrap) return false
            val big = layout.height
            paint.textSize = px * ratio
            val small = paint.fontSpacing
            return 2 * big + others * small <= room
        }
        if (!fits(minPx)) return minPx
        var lo = minPx; var hi = maxPx
        if (fits(hi)) return hi
        repeat(14) { val mid = (lo + hi) / 2; if (fits(mid)) lo = mid else hi = mid }
        return lo
    }

    /** A long line of the song: the 90th percentile of the lengths, so a single odd line does not shrink the whole song. */
    fun longLine(lyrics: List<String>): String {
        val l = lyrics.filter { it.isNotBlank() }.sortedBy { it.length }
        if (l.isEmpty()) return "Waiting for the lyrics of this song"
        return l[((l.size - 1) * 0.9f).toInt()]
    }
}
