package app.mg4dash.media

/**
 * When the volume held at 0 during the startup pause (#36) can come back: once the source was seen playing and then
 * paused (the SAIC radio starts about 1 s after the switch, the pause sent with the switch can come too early), or
 * after [maxMs] in any case.
 */
internal class PauseSilence(private val startedAt: Long, private val maxMs: Long) {
    private var seenPlaying = false

    /** At [now], the source [playing]: true when the volume can come back. */
    fun over(now: Long, playing: Boolean): Boolean {
        if (playing) seenPlaying = true
        return (seenPlaying && !playing) || now - startedAt >= maxMs
    }
}
