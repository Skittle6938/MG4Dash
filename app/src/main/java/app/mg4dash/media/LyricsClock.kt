package app.mg4dash.media

/**
 * The song's position for the lyrics (card, karaoke, pill; 25/11: the lyrics did not move). The position a player
 * reports is taken as the truth only when it changes; between two reports, and for a player that never reports one
 * (0 all along), the clock runs on its own from the track's start, stops on pause and starts again on play.
 */
internal class LyricsClock {
    private var key: String? = null
    private var reported = Long.MIN_VALUE
    private var anchor = 0L
    private var at = 0L
    private var playing = false
    private var duration = 0L

    /** A new state of the media ([now]: elapsed realtime). */
    fun update(m: MediaHub.NowPlaying, now: Long) {
        val k = "${m.artist}|${m.title}"
        val pos = m.positionMs.coerceAtLeast(0L)
        when {
            k != key -> { key = k; anchor = pos; at = now }
            pos != reported -> { anchor = pos; at = now }
            m.playing != playing -> { anchor = position(now); at = now }
        }
        reported = pos
        playing = m.playing
        duration = m.durationMs
    }

    fun position(now: Long): Long {
        val p = anchor + if (playing) (now - at).coerceAtLeast(0L) else 0L
        return if (duration > 0) p.coerceAtMost(duration) else p
    }

    companion object {
        /**
         * Where a [android.media.session.PlaybackState] is now: its position moved on by the time since it was reported,
         * at its speed (1 when missing), while it plays. Unknown (-1) is 0.
         */
        fun projected(position: Long, updatedAt: Long, speed: Float, playing: Boolean, now: Long): Long {
            if (position < 0) return 0
            if (!playing || updatedAt <= 0) return position
            val s = if (speed > 0f) speed else 1f
            return position + ((now - updatedAt).coerceAtLeast(0L) * s).toLong()
        }
    }
}
