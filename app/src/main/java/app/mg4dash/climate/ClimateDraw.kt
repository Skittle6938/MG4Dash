package app.mg4dash.climate

/**
 * Whether the climate really draws power to heat or cool (#34: "heating" was shown with the blower only, or on a car
 * that only heats at HI): the climate's share of the battery's power, measured by EnergyMonitor. Above [ON_W] for
 * [HOLD_MS] it confirms; below [OFF_W] for [HOLD_MS] it denies; in between, or for [GRACE_MS] after the switch-on,
 * nothing is decided (the rule alone, so the mode still shows at once).
 */
class ClimateDraw {
    private var since = 0L
    private var above: Boolean? = null
    private var aboveSince = 0L
    private var state: Boolean? = null

    /**
     * The confirmation at [now] (ms, monotonic) for the climate [on] drawing [watts] (null: unknown): true heating or
     * cooling for real, false only the blower, null not known (follow the rule).
     */
    fun update(now: Long, on: Boolean, watts: Float?): Boolean? {
        if (!on) { since = 0L; above = null; state = null; return null }
        if (since == 0L) since = now
        if (watts == null) return state
        val side = when { watts >= ON_W -> true; watts <= OFF_W -> false; else -> above }
        if (side != above) { above = side; aboveSince = now }
        if (side != null && now - aboveSince >= HOLD_MS && now - since >= GRACE_MS) state = side
        return state
    }

    companion object {
        const val ON_W = 500f
        const val OFF_W = 300f
        const val HOLD_MS = 5_000L
        /** After the switch-on: the time for the heater or the compressor to start. */
        const val GRACE_MS = 30_000L
        /** The one following the car's climate (the shortcut tile, the card, the Keep climate screen share it). */
        val shared = ClimateDraw()
    }
}
