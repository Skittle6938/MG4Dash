package app.mg4dash.cards

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/**
 * What a card follows while shown: started by [HomeCard.shown] (true), all cancelled at once when hidden (false).
 * Starting it twice or stopping it twice is harmless.
 *
 *     private val live = CardScope()
 *     override fun shown(on: Boolean) = live.set(on) { launch { CarBridge.state.collect { render(it) } } }
 */
class CardScope {
    private var scope: CoroutineScope? = null
    val running: Boolean get() = scope != null

    /** Shown ([on]): [start] runs in a new scope on the main thread; hidden: everything started is cancelled. */
    fun set(on: Boolean, start: CoroutineScope.() -> Unit) {
        if (on == running) return
        scope?.cancel(); scope = null
        if (on) scope = MainScope().also(start)
    }
}
