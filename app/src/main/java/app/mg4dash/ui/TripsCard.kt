package app.mg4dash.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.energy.TripLog

/**
 * "Recent trips" card (#102), placed in a widget's place from the edit mode: the trip in progress, the two before
 * it and today's totals. A touch opens the Trips page.
 */
class TripsCard(context: Context) : LinearLayout(context) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun tv(sp: Float, colorRes: Int, light: Boolean = false) = TextView(context).apply {
        setTextColor(Themes.color(context, colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
        includeFontPadding = false
        if (light) typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }
    private fun line(left: TextView, right: TextView) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(left, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(right.apply { gravity = Gravity.END })
    }
    private fun divider() = View(context).apply { setBackgroundColor(Themes.color(context, R.color.panel_border)) }

    private val nowLabel = tv(18f, R.color.text_sub)
    private val nowOdo = tv(18f, R.color.text_sub)
    private val nowKm = tv(44f, R.color.text_value, light = true)
    private val nowDetail = tv(22f, R.color.text_value)
    private val prev = List(2) { tv(19f, R.color.text_sub) to tv(19f, R.color.text_value) }
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
            prevRows.forEach { it.visibility = if (v) GONE else VISIBLE }
            prevDivider.visibility = if (v) GONE else VISIBLE
            show()
        }

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        isClickable = true
        setOnClickListener { context.startActivity(android.content.Intent(context, app.mg4dash.pages.TripsActivity::class.java)) }
        addView(TextView(context).apply {
            text = context.getString(R.string.title_trips); setTextColor(Themes.color(context, R.color.text_title))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); letterSpacing = 0.12f; maxLines = 1
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
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
        val cur = TripLog.current.value
        val trips = TripLog.trips.value
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
            prevRows[i].visibility = if (compact || t == null) GONE else VISIBLE
        }
        prevDivider.visibility = if (compact || trips.isEmpty()) GONE else VISIBLE
        val day = TripLog.today(trips, cur, now)
        todayLabel.text = "${c.getString(R.string.tr_today)} · ${TripText.count(c, day.trips)}"
        todayValue.text = "${TripText.dist(c, day.km)} · ${TripText.duration(c, day.minutes)}" +
            (if (priced) " · " + TripText.money(TripLog.costOf((listOfNotNull(cur) + trips).filter { it.start >= TripLog.dayStart(now) })) else "")
        todayFrom.text = day.firstKm?.let { c.getString(R.string.tr_since_km, TripText.odo(c, it)) } ?: ""
        val r = TripLog.reading(sp)?.takeIf { it.running }
        readingButton.text = if (r != null) "■  " + app.mg4dash.system.Units.distText(c, (r.km(odo) ?: 0).toFloat()) else "●  " + c.getString(R.string.tr_reading_row)
        readingButton.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(24).toFloat(); setColor(Themes.color(c, R.color.button_idle))
            setStroke((1.5f * d).toInt().coerceAtLeast(1), Themes.color(c, if (r != null) R.color.text_value else R.color.panel_border))
        }
    }
}
