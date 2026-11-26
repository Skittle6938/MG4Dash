package app.mg4dash.media

/**
 * The song's position for the lyrics (card, karaoke, pill; 25/11: the lyrics did not move). The position a player
 * reports is taken as the truth only when it changes; between two reports, and for a player that never reports one
 * (0 all along), the clock runs on its own from the track's start, stops on pause and starts again on play. A player
 * reporting whole seconds ([MediaHub.NowPlaying.positionStepMs]) only corrects the clock when it is outside that second.
 */
internal class LyricsClock {
    private var key: String? = null
    private var reported = Long.MIN_VALUE
    private var reportedRaw = Long.MIN_VALUE
    private var anchor = 0L
    private var at = 0L
    private var playing = false
    private var duration = 0L

    /** A new state of the media ([now]: elapsed realtime). */
    fun update(m: MediaHub.NowPlaying, now: Long) {
        val k = "${m.artist}|${m.title}"
        val pos = m.positionMs.coerceAtLeast(0L)
        val here = position(now)
        // A new report: the player's own position changed. The same position sent again (CarPlay, #135: 0 again and
        // again with a fresh time) is not one, the clock keeps running
        val raw = if (m.positionReportedMs >= 0) m.positionReportedMs else pos
        val fresh = if (m.positionReportedMs >= 0) raw != reportedRaw else pos != reported
        anchor = when {
            k != key -> { key = k; pos }
            // Within the reported second the clock is finer than the report: kept (no step back each second)
            fresh && (m.positionStepMs <= 0 || here < pos || here >= pos + m.positionStepMs) -> pos
            else -> here
        }
        at = now
        reported = pos
        reportedRaw = raw
        playing = m.playing
        duration = m.durationMs
    }

    fun position(now: Long): Long {
        val p = anchor + if (playing) (now - at).coerceAtLeast(0L) else 0L
        return if (duration > 0) p.coerceAtMost(duration) else p
    }

    /** Where the lyrics are: [leadMs] ahead of the song (the setting, 0.4 s by default), so a line is read in time to be sung. */
    fun lyricsAt(now: Long, leadMs: Long = LEAD_MS): Long = (position(now) + leadMs).coerceAtLeast(0L)

    companion object {
        /** The lyrics' default lead on the song (25/11: hard to sing on time). */
        const val LEAD_MS = 400L

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
