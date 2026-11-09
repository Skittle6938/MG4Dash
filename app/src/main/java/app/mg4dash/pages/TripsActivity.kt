package app.mg4dash.pages

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.VehicleState
import app.mg4dash.energy.TripLog
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.TripText
import kotlinx.coroutines.launch

/**
 * Trips page (#102), from the Trips card or the Vehicle page's line: today's totals, the manual reading for a
 * service (ride-hailing, deliveries) and the list of the last 50 trips.
 */
class TripsActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_trips)
    override val page = app.mg4dash.ui.Dock.Page.VEHICLE

    private val sp get() = app.mg4dash.system.Prefs(this).raw
    private lateinit var todayKm: TextView
    private lateinit var todayDetail: TextView
    private lateinit var firstOdo: TextView
    private lateinit var nowOdo: TextView
    private lateinit var readingState: TextView
    private lateinit var readingKm: TextView
    private lateinit var readingButton: TextView
    private lateinit var list: LinearLayout
    private var odometer: Int? = null

    override fun buildBody() {
        header.addView(kit.text(getString(R.string.tr_clear), 19f, R.color.text_sub).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { confirmClear() }
        })

        // ── Today and the manual reading ──
        val left = kit.panel(getString(R.string.tr_today).uppercase())
        todayKm = kit.text("—", 56f, light = true)
        left.addView(todayKm, kit.lp(-1, -2, t = 10))
        todayDetail = kit.text("", 18f, R.color.text_sub)
        left.addView(todayDetail, kit.lp(-1, -2, t = 6))
        firstOdo = info(left, R.string.tr_first_odo, 14)
        nowOdo = info(left, R.string.tr_odo_now, 0)
        left.addView(kit.divider(), kit.lp(-1, 1, t = 14, b = 14))
        left.addView(kit.title(getString(R.string.tr_reading).uppercase()))
        left.addView(kit.text(getString(R.string.tr_reading_hint), 17f, R.color.text_sub).apply { maxLines = 3 }, kit.lp(-1, -2, t = 8))
        readingState = kit.text("", 18f, R.color.text_sub)
        left.addView(readingState, kit.lp(-1, -2, t = 14))
        readingKm = kit.text("", 40f, light = true)
        left.addView(readingKm, kit.lp(-1, -2, t = 6))
        readingButton = kit.text("", 20f, R.color.text_value).apply {
            gravity = Gravity.CENTER; setPadding(kit.dp(26), kit.dp(12), kit.dp(26), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { toggleReading() }
        }
        left.addView(LinearLayout(this).apply { addView(readingButton) }, kit.lp(-1, -2, t = 14))
        body.addView(left, kit.lp(0, -1, 30f, r = 6))

        // ── The list ──
        val right = kit.panel(getString(R.string.wc_trips).uppercase())
        right.addView(row(listOf(R.string.tr_col_day, R.string.tr_col_time, R.string.tr_col_odo, R.string.tr_col_dist,
            R.string.tr_col_duration, R.string.tr_col_cons, R.string.tr_col_batt).map { getString(it).uppercase() }, head = true), kit.lp(-1, -2, t = 10))
        right.addView(kit.divider(), kit.lp(-1, 1, t = 4))
        list = kit.column()
        right.addView(android.widget.ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, kit.lp(-1, 0, 1f))
        right.addView(kit.text(getString(R.string.tr_note), 16f, R.color.text_sub), kit.lp(-1, -2, t = 8))
        body.addView(right, kit.lp(0, -1, 70f, l = 6))

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) { refresh(); kotlinx.coroutines.delay(10_000) }
            }
        }
    }

    private fun info(panel: LinearLayout, label: Int, top: Int): TextView {
        val (r, v) = kit.infoRow(getString(label))
        panel.addView(r, kit.lp(-1, -2, t = top))
        return v
    }

    /** Column widths of the list, in shares of its width. */
    private val weights = floatArrayOf(1.3f, 1.6f, 1.9f, 1.1f, 0.9f, 1.5f, 1.1f)

    private fun row(cells: List<String>, head: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, kit.dp(if (head) 4 else 10), 0, kit.dp(if (head) 8 else 10))
        cells.forEachIndexed { i, s ->
            addView(kit.text(s, if (head) 15f else 19f, if (head) R.color.text_sub else R.color.text_value).apply {
                gravity = if (i == 0) Gravity.START else Gravity.END
                if (head) letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(0, -2, weights[i]))
        }
    }

    private fun refresh(now: Long = System.currentTimeMillis()) {
        val trips = TripLog.load(sp)
        val cur = TripLog.current.value
        val day = TripLog.today(trips, cur, now)
        todayKm.text = TripText.dist(this, day.km)
        val km = trips.filter { it.start >= TripLog.dayStart(now) } + listOfNotNull(cur?.takeIf { it.start >= TripLog.dayStart(now) })
        val kwh = km.sumOf { it.kwh.toDouble() }.toFloat()
        val per100 = if (day.km >= 0.5f && kwh > 0.02f) kwh / day.km * 100f else null
        todayDetail.text = "${TripText.count(this, day.trips)} · ${getString(R.string.tr_driving, TripText.duration(this, day.minutes))} · ${TripText.cons(this, per100)}"
        firstOdo.text = day.firstKm?.let { TripText.odo(this, it) } ?: "—"
        nowOdo.text = odometer?.let { TripText.odo(this, it) } ?: "—"
        showReading()
        list.removeAllViews()
        val rows = listOfNotNull(cur) + trips
        var lastDay: String? = null
        rows.forEach { t ->
            val dayText = TripText.day(this, t.start, now)
            if (lastDay != null && dayText != lastDay) list.addView(kit.divider())
            lastDay = dayText
            val span = if (t === cur) "${TripText.time(this, t.start)} – ${getString(R.string.tr_ongoing)}" else TripText.span(this, t)
            val batt = if (t.startSoc != null && t.endSoc != null) "${t.startSoc} → ${t.endSoc} %" else "—"
            list.addView(row(listOf(dayText, span, TripText.odoRange(this, t.startKm, t.endKm), TripText.dist(this, t.km),
                TripText.duration(this, t.minutes), TripText.cons(this, t.per100), batt)))
        }
        if (rows.isEmpty()) list.addView(kit.text(getString(R.string.tr_none), 19f, R.color.text_sub), kit.lp(-1, -2, t = 14))
    }

    private fun showReading() {
        val r = TripLog.reading(sp)
        readingButton.text = getString(if (r?.running == true) R.string.tr_reading_stop else R.string.tr_reading_start)
        if (r == null) { readingState.text = ""; readingKm.text = ""; return }
        readingState.text = (if (r.running) getString(R.string.tr_reading_running, TripText.time(this, r.start))
            else "${TripText.day(this, r.start, System.currentTimeMillis())} · ${TripText.time(this, r.start)} – ${TripText.time(this, r.end)}") +
            "   " + TripText.odoRange(this, r.startKm, if (r.running) odometer ?: r.startKm else r.endKm)
        readingKm.text = r.km(odometer)?.let { app.mg4dash.system.Units.distText(this, it.toFloat()) } ?: ""
    }

    private fun toggleReading() {
        val km = odometer ?: return
        val now = System.currentTimeMillis()
        if (TripLog.reading(sp)?.running == true) TripLog.stopReading(sp, now, km) else TripLog.startReading(sp, now, km)
        app.mg4dash.system.Log.i("Trips", if (TripLog.reading(sp)?.running == true) "reading started at $km km" else "reading stopped at $km km")
        showReading()
    }

    private fun confirmClear() {
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.tr_clear_confirm)
            .setPositiveButton(R.string.tr_clear) { _, _ -> TripLog.clear(sp); refresh() }
            .setNegativeButton(android.R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.hideBars(it) }
    }

    override fun render(s: VehicleState) {
        if (s.odometerKm != odometer) { odometer = s.odometerKm; nowOdo.text = odometer?.let { TripText.odo(this, it) } ?: "—"; showReading() }
    }
}
