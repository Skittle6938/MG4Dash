package app.mg4dash.energy

/**
 * The energy card's kWh/100 held steady (#29): a new value replaces the one shown only when it differs from it by
 * [BAND] or more. A value appearing (nothing shown yet, or a period just reset) shows at once, and an unknown value
 * clears it.
 */
object ConsHold {
    const val BAND = 0.5f

    fun held(shown: Float?, fresh: Float?): Float? =
        if (shown != null && fresh != null && Math.abs(fresh - shown) < BAND) shown else fresh
}
