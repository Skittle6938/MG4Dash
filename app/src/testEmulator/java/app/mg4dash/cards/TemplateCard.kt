package app.mg4dash.cards

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.OptionsPopup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * TEMPLATE of a home card, to copy into app/src/main/java/app/mg4dash/cards/ (see CONTRIBUTING.md › "Adding a home
 * card"). It shows the battery's charge and, as an option, the range: the parts every card has, in order.
 * Rendered in every size, dark and light, by CardGalleryShots, so this template always builds and works.
 */
class TemplateCard(context: Context) : FrameLayout(context), HomeCard {
    // 1. Identity: your entry of HomeSlots.Card (id, name, icon, big, manual). The template borrows an existing one.
    override val card = HomeSlots.Card.TRIPS
    override val view: View get() = this

    // 2. Settings: through CardPrefs (keys "card_<id>_…", following the profile, in the settings backup)
    private val prefs = CardPrefs(context, card)
    private var showRange: Boolean
        get() = prefs.bool("show_range", true)
        set(v) = prefs.setBool("show_range", v)

    // 3. Views: CardKit only (theme colours, standard margins and text sizes)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; CardKit.pad(this) }
    private val value = CardKit.text(context, CardKit.BIG_SP, value = true)
    private val detail = CardKit.text(context, CardKit.BODY_SP)
    private val empty = CardKit.empty(context)

    init {
        content.addView(CardKit.title(context, context.getString(card.label).uppercase()))
        content.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addView(content, LayoutParams(-1, -1))
        addView(empty, LayoutParams(-1, -1, Gravity.CENTER))
        isClickable = true   // the whole card takes the touch: a swipe on it still changes card
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // 4. Size: one common shape; Grand affichage's bigger text comes by itself
    private var small = false
    override fun place(p: CardPlace) {
        small = p.small
        render(CarBridge.state.value)
    }

    // 5. Life: the data followed only while shown
    private var scope: CoroutineScope? = null
    override fun shown(on: Boolean) {
        if (on == (scope != null)) return
        scope?.cancel(); scope = null
        if (on) scope = MainScope().also { s -> s.launch { CarBridge.state.collect { render(it) } } }
    }

    // 6. Data, and the "no data yet" state
    fun render(st: VehicleState) {
        val soc = st.socPrecise
        content.visibility = if (soc == null) INVISIBLE else VISIBLE
        empty.visibility = if (soc == null) VISIBLE else GONE
        if (soc == null) return
        value.text = "${soc.toInt()} %"
        detail.text = st.rangeKm?.takeIf { showRange && !small }?.let { "${context.getString(R.string.vh_range)} · $it km" } ?: ""
    }

    // 7. Options: added to the standard popup, opened by a long press
    override fun options(popup: OptionsPopup): OptionsPopup =
        popup.toggle(context.getString(R.string.vh_range), null, { showRange }) { showRange = it; render(CarBridge.state.value) }
}
