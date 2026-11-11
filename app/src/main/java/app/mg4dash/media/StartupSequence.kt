package app.mg4dash.media

/**
 * The car's startup audio sequence (#36), as a pure state machine: no Android call, an injected clock (the
 * [Input.now] of each step, in ms since MG4Dash started), so that every case is tested.
 *
 * 1. Power-on: nothing is touched, except a source playing while a pause is wanted: paused at once.
 * 2. MG4Dash's startup sound: no source switch and no play while it plays (30 s at most).
 * 3. 1 s after its end (at once with no sound, as soon as a source is there): the source is chosen.
 *    Automatic: the first of the history, waited for 15 s, then the best one there, otherwise the radio.
 *    Fixed: that source, waited for 15 s, otherwise the radio. As the car: the source the car kept.
 * 4. Switch to it ([Action.Switch], the car's source-change command, without playing).
 * 5. 1.5 s later, the wanted state with an explicit command ([Action.Play] / [Action.Pause], never a toggle),
 *    except for a phone projection (DiAuto, wireless Android Auto), where the phone decides.
 * 6. Watched for 10 s: a source that changes state by itself (the radio resuming after the sound) gets the
 *    wanted state again. Anything the driver does stops the sequence at once, the driver's choice is kept.
 * Then [done]: normal operation.
 */
internal class StartupSequence(
    private val mode: Mode,
    private val wantPlay: Boolean,
    private val radio: String,
) {
    sealed class Mode {
        /** The history of the sources chosen by hand, most recent first (CarPlay left out by the caller). */
        data class Auto(val order: List<String>) : Mode()
        data class Fixed(val pkg: String) : Mode()
        /** "As the car": the source the car kept. */
        data object Car : Mode()
    }

    sealed class Action {
        /** Make [pkg] the car's source, without playing. */
        data class Switch(val pkg: String) : Action()
        data object Play : Action()
        data object Pause : Action()
    }

    /**
     * What is seen at [now]: [soundBusy] MG4Dash's startup sound is to play or playing, [soundEndAt] when it ended
     * (null: no sound, or not over); [available] the sources there; [carSource] the car's; [playing] the source shown
     * plays; [userAt] the last thing the driver did (MG4Dash's buttons, the wheel), [phoneDecides] the source's
     * playback is the phone's (projection).
     */
    data class Input(
        val now: Long,
        val soundBusy: Boolean = false,
        val soundEndAt: Long? = null,
        val available: List<String> = emptyList(),
        val carSource: String? = null,
        val playing: Boolean = false,
        val userAt: Long? = null,
        val phoneDecides: (String) -> Boolean = { false },
    )

    enum class Phase { SOUND, CHOOSE, APPLY, WATCH, DONE }

    var phase = Phase.SOUND
        private set
    /** The source chosen at step 3. */
    var target: String? = null
        private set
    /** Why it ended (log). */
    var endedBy: String? = null
        private set

    val done get() = phase == Phase.DONE

    private var startedAt: Long? = null
    private var chooseAt = 0L
    private var switchedAt = 0L
    private var watchAt = 0L
    private var lastCommandAt = Long.MIN_VALUE / 2
    /** The car announced the target once: a later change of the car's source is the driver's (wheel, radio app). */
    private var targetSeen = false

    fun step(i: Input): List<Action> {
        if (done) return emptyList()
        val start = startedAt ?: i.now.also { startedAt = it }
        // Anything the driver did after the start stops the sequence: the driver's choice is kept
        if (i.userAt != null && i.userAt >= start) return end("driver's action")
        if (i.now - start > MAX_MS) return end("time limit")
        val out = mutableListOf<Action>()
        when (phase) {
            Phase.SOUND -> {
                pauseIfWanted(i, out)
                val soundOver = !i.soundBusy || i.now - start >= SOUND_MAX_MS
                if (soundOver) {
                    val ready = if (i.soundEndAt != null) i.now >= i.soundEndAt + AFTER_SOUND_MS
                        else i.available.isNotEmpty() || i.carSource != null
                    if (ready) { phase = Phase.CHOOSE; chooseAt = i.now; chooseOrWait(i, out) }
                }
            }
            Phase.CHOOSE -> { pauseIfWanted(i, out); chooseOrWait(i, out) }
            Phase.APPLY -> {
                if (sourceTaken(i)) return end("source changed by the driver")
                if (i.now - switchedAt >= SETTLE_MS) {
                    val t = target!!
                    if (i.phoneDecides(t)) return end("left to the phone")
                    apply(i, out, force = true)
                    phase = Phase.WATCH; watchAt = i.now
                }
            }
            Phase.WATCH -> {
                if (sourceTaken(i)) return end("source changed by the driver")
                if (i.now - watchAt >= WATCH_MS) return end("watched")
                apply(i, out, force = false)
            }
            Phase.DONE -> {}
        }
        return out
    }

    private fun end(why: String): List<Action> { phase = Phase.DONE; endedBy = why; return emptyList() }

    /** A pause is wanted and a source plays: paused at once (once per [COMMAND_GAP_MS], the car takes a moment). */
    private fun pauseIfWanted(i: Input, out: MutableList<Action>) {
        if (!wantPlay && i.playing && i.now - lastCommandAt >= COMMAND_GAP_MS) { out += Action.Pause; lastCommandAt = i.now }
    }

    private fun chooseOrWait(i: Input, out: MutableList<Action>) {
        val waited = i.now - chooseAt >= PREFERRED_WAIT_MS
        val t = when (mode) {
            is Mode.Car -> i.carSource ?: if (waited) radio else null
            is Mode.Fixed -> if (mode.pkg in i.available) mode.pkg else if (waited) radio else null
            is Mode.Auto -> {
                val pref = mode.order.firstOrNull()
                if (pref != null && pref in i.available) pref
                else if (waited || pref == null) mode.order.firstOrNull { it in i.available } ?: radio
                else null
            }
        } ?: return
        target = t
        out += Action.Switch(t)
        phase = Phase.APPLY; switchedAt = i.now
    }

    /** The wanted state, explicitly: at once when [force], then only if the source changed state by itself. */
    private fun apply(i: Input, out: MutableList<Action>, force: Boolean) {
        if (i.playing == wantPlay) return
        if (!force && i.now - lastCommandAt < COMMAND_GAP_MS) return
        out += if (wantPlay) Action.Play else Action.Pause
        lastCommandAt = i.now
    }

    private fun sourceTaken(i: Input): Boolean {
        val t = target ?: return false
        if (i.carSource == t) targetSeen = true
        return targetSeen && i.carSource != null && i.carSource != t
    }

    companion object {
        const val SOUND_MAX_MS = 30_000L
        const val AFTER_SOUND_MS = 1_000L
        const val PREFERRED_WAIT_MS = 15_000L
        const val SETTLE_MS = 1_500L
        const val WATCH_MS = 10_000L
        const val COMMAND_GAP_MS = 1_500L
        /** Safety net: the sequence never lasts more than this. */
        const val MAX_MS = 90_000L

        /**
         * The playback state at the last stop ("As at switch-off"). [frozen]: the state frozen when the car left
         * READY in P (null: READY unreadable at that stop). Otherwise the last recorded state; a pause less than
         * 10 s before the last "alive" heartbeat is the head unit's own shutdown pause: the state before it counts.
         */
        fun lastState(frozen: Boolean?, last: Boolean, lastAt: Long, before: Boolean, aliveAt: Long): Boolean = when {
            frozen != null -> frozen
            !last && aliveAt > 0 && lastAt > 0 && aliveAt - lastAt < SHUTDOWN_PAUSE_MS -> before
            else -> last
        }

        const val SHUTDOWN_PAUSE_MS = 10_000L
    }
}
