package app.mg4dash.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.cards.CardKit
import app.mg4dash.cards.CardPlace
import app.mg4dash.cards.HomeCard
import app.mg4dash.energy.TripLog

/**
 * "Recent trips" card (#102), placed in a widget's place from the edit mode: the trip in progress, the two before
 * it and today's totals. A touch opens the Trips page. A [HomeCard]: refreshed every 5 s while shown.
 */
class TripsCard(context: Context) : LinearLayout(context), HomeCard {
    override val card = HomeSlots.Card.TRIPS
    override val view: View get() = this

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun tv(sp: Float, colorRes: Int, light: Boolean = false) = CardKit.text(context, sp, colorRes, value = light)
    private fun line(left: TextView, right: TextView) = CardKit.row(left, right)
    private fun divider() = CardKit.divider(context)

    private val nowLabel = tv(18f, R.color.text_sub)
    private val nowOdo = tv(18f, R.color.text_sub)
    private val nowKm = tv(44f, R.color.text_value, light = true)
    private val nowDetail = tv(22f, R.color.text_value)
    private val prev = List(MAX_PREVIOUS) { tv(19f, R.color.text_sub) to tv(19f, R.color.text_value) }
    private val prevRows = prev.map { (l, r) -> line(l, r) }
    private val prevDivider = divider()
    private val todayLabel = tv(21f, R.color.text_sub)
    private val todayValue = tv(21f, R.color.text_value)
    private val todayFrom = tv(17f, R.color.text_sub)
    private val sp get() = app.mg4dash.system.Prefs(context).raw
    /**
     * Manual reading from the card (#102): "● Reading" to start one, "■ 83 km" while it runs (white outline), a touch
     * stops it; elsewhere a touch still opens the Trips page.
     */
    val readingButton = tv(19f, R.color.text_value).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8))
        isClickable = true
        setOnClickListener {
            val km = CarBridge.state.value.odometerKm ?: return@setOnClickListener
            val now = System.currentTimeMillis()
            if (TripLog.reading(sp)?.running == true) TripLog.stopReading(sp, now, km) else TripLog.startReading(sp, now, km)
            app.mg4dash.system.Log.i("Trips", if (TripLog.reading(sp)?.running == true) "reading started at $km km (card)" else "reading stopped at $km km (card)")
            show()
        }
    }

    /** Small place: the previous trips go, the trip in progress and today stay. */
    var compact = false
        set(v) {
            if (field == v) return
            field = v
            show()
        }

    /** Previous trips listed: 2 in a medium place, more in a tall one to fill its height. */
    var previous = 2
        set(v) {
            if (field == v) return
            field = v.coerceIn(0, MAX_PREVIOUS)
            show()
        }

    companion object {
        const val MAX_PREVIOUS = 14
        private const val REFRESH_MS = 5_000L
    }

    /** Small place: the trip in progress and today only; tall place: more previous trips, to fill its height. */
    override fun place(p: CardPlace) {
        compact = p.small
        previous = if (p.form == HomeSlots.Size.TALL) MAX_PREVIOUS else 2
    }

    private var running = false
    private val tick = object : Runnable {
        override fun run() { show(); postDelayed(this, REFRESH_MS) }
    }

    override fun shown(on: Boolean) {
        if (on == running) return
        running = on
        removeCallbacks(tick)
        if (on) tick.run()
    }

    init {
        orientation = VERTICAL
        CardKit.pad(this)
        isClickable = true
        setOnClickListener { context.startActivity(android.content.Intent(context, app.mg4dash.pages.TripsActivity::class.java)) }
        addView(CardKit.title(context, context.getString(R.string.title_trips)))
        addView(line(nowLabel, nowOdo), LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.BOTTOM
            addView(nowKm, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(nowDetail)
        }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(divider(), LayoutParams(-1, 1).apply { topMargin = dp(12); bottomMargin = dp(10) })
        prevRows.forEachIndexed { i, r -> addView(r, LayoutParams(-1, -2).apply { topMargin = if (i > 0) dp(8) else 0 }) }
        addView(prevDivider, LayoutParams(-1, 1).apply { topMargin = dp(12); bottomMargin = dp(10) })
        addView(line(todayLabel, todayValue), LayoutParams(-1, -2))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(todayFrom, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(readingButton)
        }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    fun show(now: Long = System.currentTimeMillis()) {
        val c = context
        // The current profile's trips (study v4, step 12), from two profiles
        val mine = app.mg4dash.system.Profiles.list(c).takeIf { it.size >= 2 }?.let { app.mg4dash.system.Profiles.current(c).id }
        fun ours(t: TripLog.Trip) = mine == null || t.by(mine, TripLog.firstProfile())
        val cur = TripLog.current.value?.takeIf { ours(it) }
        val trips = TripLog.trips.value.filter { ours(it) }
        val odo = CarBridge.state.value.odometerKm
        if (cur != null) {
            nowLabel.text = c.getString(R.string.tr_now, TripText.time(c, cur.start))
            nowOdo.text = TripText.odoRange(c, cur.startKm, odo ?: cur.endKm)
            nowKm.text = TripText.dist(c, cur.km)
            nowDetail.text = "${TripText.duration(c, cur.minutes)} · ${TripText.cons(c, cur.per100)}" + (TripText.price(c)?.let { p -> " · " + TripText.money(TripLog.cost(cur.kwh, p)) } ?: "")
        } else {
            nowLabel.text = c.getString(R.string.tr_none)
            nowOdo.text = odo?.let { TripText.odo(c, it) } ?: ""
            nowKm.text = TripText.dist(c, 0f); nowDetail.text = ""
        }
        val priced = TripText.price(c) != null
        prev.forEachIndexed { i, (l, r) ->
            val t = trips.getOrNull(i)
            l.text = t?.let { (if (i == 0) c.getString(R.string.tr_previous, TripText.span(c, it)) else TripText.span(c, it)) } ?: ""
            // Its cost too, with a kWh price (at the price it was driven at)
            r.text = t?.let { "${TripText.dist(c, it.km)} · ${TripText.consShort(c, it.per100)}" + (if (priced) " · " + TripText.money(it.cost) else "") } ?: ""
            prevRows[i].visibility = if (compact || i >= previous || t == null) GONE else VISIBLE
        }
        prevDivider.visibility = if (compact || trips.isEmpty()) GONE else VISIBLE
        val day = TripLog.today(trips, cur, now)
        todayLabel.text = "${c.getString(R.string.tr_today)} · ${TripText.count(c, day.trips)}"
        todayValue.text = "${TripText.dist(c, day.km)} · ${TripText.duration(c, day.minutes)}" +
            (if (priced) " · " + TripText.money(TripLog.costOf((listOfNotNull(cur) + trips).filter { it.start >= TripLog.dayStart(now) })) else "")
        todayFrom.text = day.firstKm?.let { c.getString(R.string.tr_since_km, TripText.odo(c, it)) } ?: ""
        val r = TripLog.reading(sp)?.takeIf { it.running }
        readingButton.text = if (r != null) "■  " + app.mg4dash.system.Units.distText(c, (r.km(odo) ?: 0).toFloat()) else "●  " + c.getString(R.string.tr_reading_row)
        readingButton.background = CardKit.buttonBackground(c, on = r != null)
    }
}
