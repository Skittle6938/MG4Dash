package app.mg4dash.ui

import kotlinx.coroutines.launch
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.map.NavRepo
import java.util.Locale

/**
 * The guidance card (19/11): the Android Auto guidance alone, as MG4Link sends it, big enough to be read while
 * driving. Every place shows everything: the manoeuvre, its distance, the exit, the street, the battery on arrival,
 * the time and km left and the arrival time; only the "then" line (when MG4Link sends one) stays out of the small
 * place. Sizes in sp, none under 22 for a useful text: what does not fit is cut (the street) rather than shrunk.
 */
class NavCard(context: Context) : FrameLayout(context), app.mg4dash.cards.HomeCard {
    override val card = HomeSlots.Card.NAV
    override val view: View get() = this

    override fun place(p: app.mg4dash.cards.CardPlace) { form = formFor(p.size) }

    /** Shown: Android Auto's guidance followed, with the battery estimated on arrival from the distance left. */
    private val follow = app.mg4dash.cards.CardScope()
    override fun shown(on: Boolean) = follow.set(on) {
        launch { NavRepo.nav.collect { n -> show(n, n?.let { app.mg4dash.energy.ArrivalBattery.now(context, it.totalMeters) }) } }
    }

    enum class Form { SMALL, MEDIUM, TALL, WIDE }

    var form: Form = Form.MEDIUM
        set(v) { if (field != v) { field = v; built = null; render() } }

    private var nav: NavRepo.Nav? = null
    private var battery: Int? = null
    /** What is built now: the form, guided or not. */
    private var built: Pair<Form, Boolean>? = null

    private val leftTraffic = Locale.getDefault().country.uppercase() in NavArrowView.LEFT_TRAFFIC

    // The views of the guided layout (filled by [build])
    private var arrow: NavArrowView? = null
    private var distance: TextView? = null
    private var exit: TextView? = null
    private var road: TextView? = null
    private var thenRow: View? = null
    private var thenArrow: NavArrowView? = null
    private var thenText: TextView? = null
    private var batteryText: TextView? = null
    private var batteryLabel: TextView? = null
    private var trip: TextView? = null
    private var time: TextView? = null
    private var km: TextView? = null
    private var eta: TextView? = null

    fun show(n: NavRepo.Nav?, batteryOnArrival: Int?) {
        nav = n; battery = batteryOnArrival
        render()
    }

    private fun render() {
        val n = nav
        val want = form to (n != null)
        if (built != want) build(want.first, want.second)
        if (n == null) return
        arrow?.set(n)
        distance?.text = n.distanceMeters?.takeIf { it >= 0 }?.let { distanceText(it) } ?: ""
        val ex = exitText(n)
        // A street name, else Android Auto's own words
        val street = n.road.ifBlank { n.actionText }
        if (form == Form.SMALL) { exit?.text = listOf(ex, street).filter { it.isNotBlank() }.joinToString(" · "); road?.visibility = GONE }
        else { exit?.text = ex.ifBlank { n.actionText.takeIf { it != street }.orEmpty() }; road?.text = street }
        exit?.visibility = if (exit?.text.isNullOrEmpty()) GONE else VISIBLE
        val t = n.then
        thenRow?.visibility = if (t != null) VISIBLE else GONE
        if (t != null) {
            thenArrow?.setShape(NavGlyph.shape(t.eventType, t.side, t.exitNumber, t.angle, leftTraffic))
            thenText?.text = listOf(context.getString(R.string.nav_then), t.road).filter { it.isNotBlank() }.joinToString(" · ")
        }
        val b = battery
        batteryText?.apply {
            visibility = if (b != null) VISIBLE else GONE
            if (b != null) {
                text = context.getString(if (form == Form.WIDE) R.string.nav_battery_short else R.string.nav_battery, b)
                setTextColor(Themes.color(context, batteryColor(b)))
            }
        }
        batteryLabel?.visibility = if (b != null) VISIBLE else GONE
        val minutes = n.totalSeconds?.takeIf { it > 0 }?.let { minutesText(it) }
        val left = n.totalMeters?.takeIf { it > 0 }?.let { distanceText(it) }
        val at = n.arrival.takeIf { it.isNotBlank() }
        trip?.text = listOfNotNull(minutes, left, at).joinToString(" · ")
        time?.text = minutes.orEmpty(); km?.text = left.orEmpty()
        eta?.text = at?.let { context.getString(R.string.nav_arrival_at, it) }.orEmpty()
    }

    private fun exitText(n: NavRepo.Nav): String {
        val e = n.exitNumber?.takeIf { it > 0 } ?: return ""
        if (n.eventType !in ROUNDABOUTS) return ""
        return if (e == 1) context.getString(R.string.nav_exit_first) else context.getString(R.string.nav_exit_n, e)
    }

    private fun distanceText(m: Int): String = if (app.mg4dash.system.Units.miles(context)) {
        val mi = m / 1609.344f
        when {
            mi >= 10f -> "${Math.round(mi)} mi"
            mi >= 0.1f -> String.format(Locale.getDefault(), "%.1f mi", mi)
            else -> "${(Math.round(m * 1.0936f) / 10) * 10} yd"
        }
    } else when {
        m >= 10_000 -> "${Math.round(m / 1000f)} km"
        m >= 1000 -> String.format(Locale.getDefault(), "%.1f km", m / 1000f)
        m >= 100 -> "${(m / 50) * 50} m"
        else -> "${(m / 10) * 10} m"
    }

    private fun minutesText(s: Long): String = if (s >= 3600) "${s / 3600} h ${String.format(Locale.US, "%02d", (s % 3600) / 60)}" else "${(s + 59) / 60} min"

    // ── Layouts ──

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun text(size: Float, bold: Boolean = false, color: Int = R.color.text_value, center: Boolean = false) = TextView(context).apply {
        setTextColor(Themes.color(context, color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END; includeFontPadding = false
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        if (center) gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun gap(h: Float) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }
    private fun rule(vertical: Boolean, len: Int = 0) = View(context).apply {
        setBackgroundColor(Themes.color(context, R.color.panel_border))
        layoutParams = if (vertical) LinearLayout.LayoutParams(dp(2f), len) else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(2f)).apply { topMargin = dp(12f); bottomMargin = dp(10f) }
    }
    private fun glyph(px: Float) = NavArrowView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(px), dp(px)) }
    private fun then(size: Float, px: Float): View {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10f), 0, 0) }
        thenArrow = glyph(px).also { row.addView(it) }
        thenText = text(size).apply { setPadding(dp(10f), 0, 0, 0) }.also { row.addView(it) }
        return row.also { thenRow = it }
    }

    private fun clear() {
        removeAllViews()
        arrow = null; distance = null; exit = null; road = null; thenRow = null; thenArrow = null; thenText = null
        batteryText = null; batteryLabel = null; trip = null; time = null; km = null; eta = null
    }

    private fun build(f: Form, guided: Boolean) {
        clear()
        built = f to guided
        val content: View = if (!guided) idle(f) else when (f) {
            Form.SMALL -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f), dp(10f), dp(18f), dp(10f))
                arrow = glyph(176f).also { addView(it) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0)
                    distance = text(58f, true).also { addView(it) }
                    addView(gap(4f)); exit = text(23f).also { addView(it) }
                    road = text(23f).also { it.visibility = GONE; addView(it) }
                    addView(gap(10f)); batteryText = text(24f, true).also { addView(it) }
                    addView(gap(4f)); trip = text(23f).also { addView(it) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            Form.MEDIUM -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14f), dp(14f), dp(18f), dp(14f))
                arrow = glyph(220f).also { addView(it) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16f), 0, 0, 0)
                    distance = text(68f, true).also { addView(it) }
                    addView(gap(4f)); exit = text(30f, true).also { addView(it) }
                    addView(gap(4f)); road = text(24f).also { addView(it) }
                    addView(then(24f, 56f))
                    addView(rule(false))
                    batteryText = text(26f, true).also { addView(it) }
                    addView(gap(4f)); trip = text(24f).also { addView(it) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            Form.TALL -> LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(20f), dp(22f), dp(20f), dp(22f))
                arrow = glyph(280f).also { addView(it) }
                addView(gap(8f)); distance = text(80f, true, center = true).also { addView(it) }
                exit = text(32f, true, center = true).also { addView(it) }
                addView(gap(4f)); road = text(26f, center = true).also { addView(it) }
                addView(then(26f, 60f), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(rule(false))
                batteryText = text(28f, true, center = true).also { addView(it) }
                addView(gap(6f)); trip = text(26f, center = true).also { addView(it) }
            }
            Form.WIDE -> LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24f), dp(20f), dp(32f), dp(20f))
                arrow = glyph(300f).also { addView(it) }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(24f), 0, dp(24f), 0)
                    distance = text(84f, true).also { addView(it) }
                    addView(gap(4f)); exit = text(34f, true).also { addView(it) }
                    addView(gap(4f)); road = text(28f).also { addView(it) }
                    addView(then(28f, 64f))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(rule(true, dp(300f)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(32f), 0, 0, 0)
                    batteryText = text(52f, true).also { addView(it) }
                    batteryLabel = text(24f).apply { setText(R.string.nav_on_arrival) }.also { addView(it) }
                    addView(gap(18f)); time = text(30f, true).also { addView(it) }
                    addView(gap(4f)); km = text(28f).also { addView(it) }
                    addView(gap(4f)); eta = text(28f).also { addView(it) }
                }, LinearLayout.LayoutParams(dp(330f), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun idle(f: Form): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val big = when (f) { Form.SMALL -> 26f; Form.WIDE -> 32f; else -> 30f }
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_map); setColorFilter(Themes.color(context, R.color.text_disabled))
            layoutParams = LinearLayout.LayoutParams(dp(big * 3), dp(big * 3))
        })
        addView(text(big, true, center = true).apply { setText(R.string.nav_idle) })
        addView(text(22f, color = R.color.text_sub, center = true).apply { setText(R.string.nav_idle_sub) })
    }

    companion object {
        private val ROUNDABOUTS = setOf(NavRepo.Turn.ROUNDABOUT, NavRepo.Turn.ROUNDABOUT_ENTER, NavRepo.Turn.ROUNDABOUT_EXIT)

        /** The layout for a place of [size]: the large place as the wide one (its height adds nothing to read). */
        fun formFor(size: HomeSlots.Size?): Form = when (size?.form) {
            HomeSlots.Size.SMALL -> Form.SMALL
            HomeSlots.Size.TALL -> Form.TALL
            HomeSlots.Size.WIDE, HomeSlots.Size.LARGE -> Form.WIDE
            else -> Form.MEDIUM
        }

        /** The battery on arrival in the text colour, orange under 20 %, red under 10 % (the owner's mockups, 19/11). */
        fun batteryColor(p: Int): Int = when { p < 10 -> R.color.accent_danger; p < 20 -> R.color.accent_heat; else -> R.color.text_value }
    }
}
