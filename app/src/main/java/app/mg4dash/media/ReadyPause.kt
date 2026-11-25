package app.mg4dash.media

/**
 * "Pause out of READY" (25/11, off by default): the playback paused when the car leaves READY, after [OFF_READS]
 * reads without READY (one a second: a short drop of the signal does nothing), never during a call. READY back
 * without a restart of the head unit: what it paused plays again, unless "Playback at startup" is "Always paused" or
 * the driver did something meanwhile. The state frozen at the stop is taken before this pause
 * ([MediaHub] watchStop), so "As at switch-off" still resumes what was playing at the next car start.
 */
internal class ReadyPause {
    enum class Do { PAUSE, RESUME }

    private var wasReady: Boolean? = null
    private var offReads = 0
    private var pausedByUs = false

    /** One READY read. [startPlayback]: 0 as at switch-off, 1 always paused, 2 always playing. */
    fun step(enabled: Boolean, ready: Boolean, playing: Boolean, inCall: Boolean, startPlayback: Int): Do? {
        if (ready) {
            offReads = 0
            wasReady = true
            if (!pausedByUs) return null
            pausedByUs = false
            return if (enabled && startPlayback != 1 && !playing) Do.RESUME else null
        }
        if (wasReady != true) return null
        if (++offReads < OFF_READS) return null
        wasReady = false
        if (!enabled || !playing || inCall) return null
        pausedByUs = true
        return Do.PAUSE
    }

    /** The driver's own media action: what they chose is kept, nothing resumed for them. */
    fun driverActed() { pausedByUs = false }

    companion object {
        /** Reads without READY (one a second) before the pause. */
        const val OFF_READS = 3
    }
}
