package app.mg4dash.cards

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * The move from one lyrics line to the next (26/11: the lines jumped). The rows scroll up by one line in
 * [TRANSITION_MS], centred on the line's time (which already holds the timing setting): it starts half of it before.
 * A line shorter than that gets a shorter move. Used by the card, the karaoke view and the floating pill.
 */
internal object LyricsScroll {
    const val TRANSITION_MS = 450L
    private const val MIN_MS = 150L

    /** How long the move to line [i] of [synced] takes. */
    fun duration(synced: List<Pair<Long, String>>, i: Int): Long =
        if (i <= 0 || i > synced.lastIndex) TRANSITION_MS
        else (synced[i].first - synced[i - 1].first).coerceIn(MIN_MS, TRANSITION_MS)

    /** When the move to line [i] starts. */
    fun startOf(synced: List<Pair<Long, String>>, i: Int): Long = synced[i].first - duration(synced, i) / 2

    /** The line shown at [position]: the one whose move has started. */
    fun index(synced: List<Pair<Long, String>>, position: Long): Int =
        synced.indices.lastOrNull { startOf(synced, it) <= position } ?: 0

    /** Time until the next move starts (for the ticker to start it on time), at most [cap]. */
    fun untilNext(synced: List<Pair<Long, String>>, position: Long, cap: Long = 250L): Long {
        val n = index(synced, position) + 1
        if (n > synced.lastIndex) return cap
        return (startOf(synced, n) - position).coerceIn(16L, cap)
    }

    /**
     * Scrolls [rows] up by [shift] px (they were just given the next line's texts). [current] fades in from
     * [currentFrom]; [previous], the line that was current, shrinks from [previousScale] and fades to its alpha.
     */
    fun slide(rows: List<View>, shift: Float, durationMs: Long, current: View?, currentFrom: Float, previous: View?, previousScale: Float) {
        if (shift <= 0f) return
        val ease = AccelerateDecelerateInterpolator()
        rows.forEach { v ->
            if (v.visibility != View.VISIBLE) return@forEach
            val alpha = v.alpha
            v.animate().cancel()
            v.translationY = shift
            when (v) {
                current -> v.alpha = currentFrom
                previous -> {
                    v.pivotX = v.width / 2f; v.pivotY = 0f
                    v.scaleX = previousScale; v.scaleY = previousScale; v.alpha = 1f
                }
            }
            v.animate().translationY(0f).alpha(alpha).scaleX(1f).scaleY(1f).setDuration(durationMs).setInterpolator(ease).start()
        }
    }

    /** Back in place at once (a seek, a new song, a jump of more than one line). */
    fun settle(rows: List<View>) = rows.forEach { v ->
        v.animate().cancel(); v.translationY = 0f; v.scaleX = 1f; v.scaleY = 1f
    }
}
