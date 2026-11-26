package app.mg4dash.pages

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.energy.EnergyMonitor
import app.mg4dash.car.VehicleState
import app.mg4dash.energy.ChargeGuard
import app.mg4dash.system.Apps
import app.mg4dash.system.Prefs
import app.mg4dash.ui.LineChart
import app.mg4dash.ui.PageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Energy page: charging (limit, current, start/stop), schedule, V2L and
 * consumption. Settings written to the same properties as the SAIC Energy app (EH32 EU).
 */
class EnergyActivity : PageActivity() {
    private companion object { const val V2L_CONFIRM_MS = 10_000L }
    override val pageTitle get() = getString(R.string.title_energy)
    override val page = app.mg4dash.ui.Dock.Page.ENERGY

    private lateinit var prefs: Prefs
    // Recharge
    private lateinit var soc: TextView
    private lateinit var range: TextView
    private lateinit var status: TextView
    private lateinit var power: TextView
    private lateinit var action: TextView
    private lateinit var limitNote: TextView
    private lateinit var setLimit: (Int?) -> Unit
    private lateinit var setCurrent: (Int?) -> Unit
    private lateinit var currentTitle: TextView
    private lateinit var currentRow: android.view.View
    private lateinit var heating: TextView
    private lateinit var gunLock: TextView
    // Programmation
    private lateinit var schedule: TextView
    private lateinit var finish: TextView
    private lateinit var startTime: TextView
    private lateinit var endTime: TextView
    // V2L
    private lateinit var v2lStatus: TextView
    private lateinit var setV2l: (Int?) -> Unit
    private lateinit var v2lAction: TextView
    private var v2lWanted: Boolean? = null
    private var v2lWantedUntil = 0L
    // Conso
    private lateinit var setPeriod: (Int?) -> Unit
    private lateinit var consTotal: TextView
    private lateinit var legend: LinearLayout
    private lateinit var ring: app.mg4dash.ui.RingChart
    private lateinit var regenLabel: TextView
    private lateinit var regen: TextView
    /** Range recovered since the last charge (km, from the car); the other periods are estimated. */
    private lateinit var chart: LineChart
    private lateinit var chartNote: TextView

    private lateinit var energyCar: app.mg4dash.showroom.EnergyCar
    private lateinit var heatLine: android.view.View
    private lateinit var v2lBanner: TextView
    private lateinit var v2lBox: LinearLayout
    private lateinit var v2lInfo: List<app.mg4dash.showroom.ShowroomKit.Tile>
    private var v2lStopPct: Int? = null
    private var packKw: Float? = null

    /** V2L running: the banner and the three figures in place of the charge button and settings. */
    private fun paintV2lBottom(s: VehicleState) {
        val on = VehicleState.isDischarging(s)
        v2lBanner.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        v2lBox.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        (action.parent as android.view.View).visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        (currentRow.parent.parent as android.view.View).visibility = if (on) android.view.View.GONE else android.view.View.VISIBLE
        if (!on) return
        val stop = v2lStopPct
        v2lBanner.text = stop?.let { getString(R.string.sr_v2l_banner, it) } ?: VehicleState.chargeStatusLabel(s.chargeStatus)
        val soc = s.batteryPercent
        val left = if (soc != null && stop != null) (soc - stop).coerceAtLeast(0) else null
        val kwh = left?.let { it * app.mg4dash.system.Prefs(this).batteryKwh / 100f }
        val kw = packKw?.let { kotlin.math.abs(it) }?.takeIf { it >= .1f }
        v2lInfo[0].value.text = left?.let { "$it %" } ?: "—"
        v2lInfo[1].value.text = kwh?.let { String.format(Locale.getDefault(), "≈ %.0f kWh", it) } ?: "—"
        v2lInfo[2].value.text = if (kwh != null && kw != null) (kwh / kw * 60).toInt().let { m -> String.format(Locale.getDefault(), "≈ %d h %02d", m / 60, m % 60) } else "—"
    }

    private fun LinearLayout.children() = (0 until childCount).map { getChildAt(it) }

    /** As in the mock-up (a 910 px wide centre): the car under the charge, battery heating above the battery. */
    private fun placeCar(center: android.widget.FrameLayout, hint: android.view.View) {
        val w = center.width.toFloat(); if (w <= 0f) return
        val s = w / 910f
        val cw = 880f * s; val cx = (w - cw) / 2; val top = 92f * s
        energyCar.box.set(cx, top, cx + cw, top + cw * 680f / 1840f)
        energyCar.invalidate()
        val k = cw / 1840f
        val (bx, by) = energyCar.batteryTop()
        val py = top + 392f * k
        heating.translationX = bx - heating.width / 2f; heating.translationY = py - heating.height / 2f
        heatLine.translationX = bx - 1f; heatLine.translationY = py + kit.dp(26)
        heatLine.layoutParams = heatLine.layoutParams.apply { height = (by - py - kit.dp(34)).toInt().coerceAtLeast(0) }
        val ground = top + 641.96f * k
        hint.translationX = bx - hint.width / 2f; hint.translationY = ground + 14f * s
    }

    private var period = 0          // 0 = since the charge, 1 = current trip, 2 = last 1,000 km
    private var reser: Int? = null
    private var stH = 22; private var stM = 0; private var spH = 6; private var spM = 0

    override fun buildBody() {
        prefs = Prefs(this)
        header.addView(kit.text(getString(R.string.en_stock), 19f, R.color.accent_action).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            // Stock Energy app, or its charging page on SWI165 (#60); button hidden if neither exists
            val stock = listOf(Apps.ENERGY, Apps.ENERGY_SWI165).firstOrNull { Apps.available(this@EnergyActivity, it) }
            setOnClickListener { stock?.let { Apps.open(this@EnergyActivity, it, "SAIC") } }
            if (stock == null) visibility = android.view.View.GONE
        })
        // Battery heating while driving (same setting as the SAIC Energy app)
        heating = kit.toggleTile(getString(R.string.en_heating), heat = true) {
            // Write 1 = on, 2 = off (like the SAIC app); reading returns 0 when on
            val on = heating.isSelected
            CarProps.setInt(CarProps.DRVNG_PTC_HEAT, if (on) 2 else 1, "battery heating")
            heatWanted = !on; heatWantedAt = android.os.SystemClock.elapsedRealtime()
            // Reread after 3 s, like the stock apps: the battery takes 2 to 3 s to return its new state
            heating.postDelayed({ refreshProps() }, 3000)
        }.apply { textSize = 18f; setPadding(kit.dp(20), 0, kit.dp(20), 0) }

        // ── Centre (redesign "Showroom", 26/11): the charge, the see-through car with its battery, the actions ──
        val sk = app.mg4dash.showroom.ShowroomKit(this, kit)
        val center = android.widget.FrameLayout(this)
        energyCar = app.mg4dash.showroom.EnergyCar(this).apply { body = app.mg4dash.showroom.CarLook(this@EnergyActivity).version.body; limitText = { getString(R.string.sr_limit_at, it) } }
        center.addView(energyCar, android.widget.FrameLayout.LayoutParams(-1, -1))
        val top = kit.row().apply { gravity = Gravity.CENTER }
        soc = kit.text("—", 84f, light = true)
        top.addView(soc)
        val side = kit.column()
        range = kit.text("—", 28f)
        status = kit.text("—", 19f, R.color.text_sub)
        power = kit.text("", 15f, R.color.text_title)
        side.addView(range); side.addView(status, kit.lp(-2, -2, t = 4)); side.addView(power, kit.lp(-2, -2, t = 2))
        top.addView(side, kit.lp(-2, -2, l = 24))
        center.addView(top, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        // Battery heating, right above the battery
        heatLine = android.view.View(this)
        center.addView(heatLine, android.widget.FrameLayout.LayoutParams(kit.dp(2), 0))
        center.addView(heating, android.widget.FrameLayout.LayoutParams(-2, kit.dp(52)))
        val bottom = kit.column()
        action = kit.toggleTile("—") { onMainAction() }.apply { textSize = 19f; letterSpacing = .08f }
        val actRow = kit.row()
        actRow.addView(action, kit.lp(0, kit.dp(60), 1f))
        gunLock = kit.toggleTile(getString(R.string.en_gun_unlock)) {
            CarBridge.toggleChargeLock(); gunLock.postDelayed({ refreshProps() }, 1500)
        }.apply { textSize = 16f; setPadding(kit.dp(18), 0, kit.dp(18), 0); visibility = android.view.View.GONE }
        actRow.addView(gunLock, kit.lp(-2, kit.dp(60), l = 10))
        bottom.addView(actRow, kit.lp(-1, -2))
        val chips = kit.row().apply { isBaselineAligned = false }
        // Charge limit: only where the car applies it itself (old SDK). Elsewhere
        // (MG4 Standard without the stock limit), the car ignores it, and MG4Dash cannot stop
        // charging in its place: it is cut when the car is closed (found on 2026-10-18).
        if (app.mg4dash.system.Firmware.nativeChargeLimit) {
            val lim = kit.column()
            val lHead = kit.row()
            lHead.addView(sk.label(getString(R.string.en_limit), 13f), kit.lp(0, -2, 1f))
            limitNote = kit.text("", 13f, R.color.text_title)
            lHead.addView(limitNote)
            lim.addView(lHead, kit.lp(-1, -2))
            val limits = listOf(50, 60, 70, 80, 90, 100)
            val (lRow, lSet) = kit.segmented(limits.map { "$it %" }) { i ->
                ChargeGuard.setLimit(this, limits[i]); refreshProps()
            }
            setLimit = lSet
            lRow.children().forEach { (it as TextView).textSize = 15f }
            lim.addView(lRow, kit.lp(-1, kit.dp(48), t = 6))
            chips.addView(lim, kit.lp(0, -2, 1.25f, r = 18))
        }
        val cur = kit.column()
        currentTitle = sk.label(getString(R.string.en_ac_current), 13f)
        cur.addView(currentTitle)
        val (cRow, cSet) = kit.segmented(listOf("6 A", "8 A", "16 A", getString(R.string.en_max))) { i ->
            CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "AC current"); refreshProps()
        }
        setCurrent = cSet
        currentRow = cRow
        cRow.children().forEach { (it as TextView).textSize = 15f }
        cur.addView(cRow, kit.lp(-1, kit.dp(48), t = 6))
        chips.addView(cur, kit.lp(0, -2, 1f))
        bottom.addView(chips, kit.lp(-1, -2, t = 12))
        // During V2L (mock-up): an orange banner instead of the button, and what is left before the automatic stop
        v2lBanner = kit.toggleTile("", heat = true) {}.apply { isSelected = true; isClickable = false; textSize = 17f; letterSpacing = .03f; visibility = android.view.View.GONE }
        bottom.addView(v2lBanner, 0, kit.lp(-1, kit.dp(60)))
        v2lBox = kit.column().apply { visibility = android.view.View.GONE }
        v2lBox.addView(sk.label(getString(R.string.sr_v2l_before), 13f))
        val infos = kit.row()
        v2lInfo = listOf(R.string.sr_v2l_usable, R.string.sr_v2l_energy, R.string.sr_v2l_time).mapIndexed { i, l ->
            val t = sk.Tile(true).apply { title.text = getString(l) }
            infos.addView(t.view, kit.lp(0, -2, 1f, l = if (i > 0) 8 else 0))
            t
        }
        v2lBox.addView(infos, kit.lp(-1, -2, t = 6))
        bottom.addView(v2lBox, kit.lp(-1, -2, t = 12))
        center.addView(bottom, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = kit.dp(60); rightMargin = kit.dp(60); bottomMargin = kit.dp(10) })

        // ── Battery (24/11): health and settings, a line under the car ──
        val batteryHint = kit.text("", 15f, R.color.text_title).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(kit.color(R.color.panel_raised)); cornerRadius = kit.dp(20).toFloat(); setStroke(1, kit.color(R.color.panel_border)) }
            setPadding(kit.dp(16), kit.dp(9), kit.dp(16), kit.dp(9))
        }
        fun showBattery() {
            val bc = app.mg4dash.energy.BatteryCapacity
            bc.apply(this)
            val h = bc.health(this)?.let { String.format(java.util.Locale.getDefault(), "%.0f %%", it.percent) } ?: "—"
            val txt = getString(R.string.en_battery_header, h) + " · " + getString(R.string.en_health) + "   |   "
            val all = android.text.SpannableString(txt + getString(R.string.en_battery_settings) + " ›")
            all.setSpan(android.text.style.ForegroundColorSpan(kit.color(R.color.accent_action)), txt.length, all.length, 0)
            batteryHint.text = all
        }
        showBattery()
        batteryHint.setOnClickListener { batteryOptions { showBattery() } }
        center.addView(batteryHint, android.widget.FrameLayout.LayoutParams(-2, -2))
        center.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - t != ob - ot) center.post { placeCar(center, batteryHint) } }

        // ── Colonne 2 : programmation + V2L ──
        val leftCol = kit.column()
        val c2 = sk.card().apply { addView(sk.label(getString(R.string.en_scheduled))) }
        val tRow = kit.row()
        schedule = kit.toggleTile(getString(R.string.en_schedule)) {
            val on = reser == 1 || reser == 3
            CarProps.setInt(CarProps.RESER_CTRL, if (on) 2 else 1, "schedule"); refreshProps()
        }
        finish = kit.toggleTile(getString(R.string.en_finish)) {
            CarProps.setInt(CarProps.RESER_CTRL, if (reser == 3) 1 else 3, "continue after end time"); refreshProps()
        }.apply { textSize = 17f }
        tRow.addView(schedule, kit.lp(0, kit.dp(60), 1f))
        tRow.addView(finish, kit.lp(0, kit.dp(60), 1f, l = 10))
        c2.addView(tRow, kit.lp(-1, -2, t = 10))
        val (sRow, sText) = kit.timeStepper(getString(R.string.en_start), { pickTime(true) }) { d -> shiftTime(true, d) }
        startTime = sText
        c2.addView(sRow, kit.lp(-1, -2, t = 14))
        val (eRow2, eText) = kit.timeStepper(getString(R.string.en_end), { pickTime(false) }) { d -> shiftTime(false, d) }
        endTime = eText
        c2.addView(eRow2, kit.lp(-1, -2, t = 10))
        leftCol.addView(c2, kit.lp(-1, -2))
        val v2lCard = sk.card().apply { addView(sk.label(getString(R.string.en_v2l))) }
        v2lStatus = kit.text("—", 19f, R.color.text_sub)
        v2lStatus.maxLines = 2; v2lStatus.isSingleLine = false
        v2lCard.addView(v2lStatus, kit.lp(-1, -2, t = 8))
        v2lCard.addView(kit.text(getString(R.string.en_v2l_stop_at), 16f, R.color.text_title), kit.lp(-1, -2, t = 12))
        val v2ls = listOf(20, 30, 40, 50, 60, 70, 80)
        val (vRow, vSet) = kit.segmented(v2ls.map { "$it" }) { i ->
            CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode(v2ls[i]), "V2L threshold ${v2ls[i]} %"); refreshProps()
        }
        setV2l = vSet
        vRow.children().forEach { (it as TextView).textSize = 15f }
        v2lCard.addView(vRow, kit.lp(-1, kit.dp(48), t = 6))
        v2lAction = kit.toggleTile(getString(R.string.en_v2l_start), heat = true) {
            val s = CarBridge.state.value
            val on = !v2lShownOn(s)
            if (on) CarBridge.startV2L() else CarBridge.stopV2L()
            // The car confirms within a few seconds: the button changes at once
            v2lWanted = on; v2lWantedUntil = android.os.SystemClock.uptimeMillis() + V2L_CONFIRM_MS
            paintV2l(s)
        }.apply { textSize = 18f }
        v2lCard.addView(LinearLayout(this), kit.lp(-1, 0, 1f))
        v2lCard.addView(v2lAction, kit.lp(-1, kit.dp(56), t = 10))
        leftCol.addView(v2lCard, kit.lp(-1, 0, 1f, t = 10))

        // ── Colonne 3 : consommation ──
        val c3 = kit.panel(getString(R.string.en_consumption))
        val (pRow, pSet) = kit.segmented(listOf(getString(R.string.en_since_charge), getString(R.string.en_trip), getString(R.string.en_last_year))) { i ->
            period = i; prefs.consumptionPeriod = i; setPeriod(i); refreshProps(); renderHistory()
        }
        setPeriod = pSet
        period = prefs.consumptionPeriod.coerceIn(0, 2)
        pSet(period)
        c3.addView(pRow, kit.lp(-1, -2, t = 10))
        // Total, and to its right what regeneration gave back
        consTotal = kit.text("—", 36f, light = true)
        val totalRow = kit.row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        totalRow.addView(consTotal, kit.lp(-2, -2))
        val recovered = kit.column()
        totalRow.addView(recovered, kit.lp(0, -2, 1f, l = 16))
        regenLabel = kit.text(getString(R.string.en_regen_short).uppercase(), 15f, R.color.text_sub).apply { letterSpacing = 0.1f }
        recovered.addView(regenLabel, kit.lp(-1, -2))
        c3.addView(totalRow, kit.lp(-1, -2, t = 14))
        // Breakdown ring on the left, the detail on the right
        ring = app.mg4dash.ui.RingChart(this)
        legend = kit.column()
        c3.addView(kit.row().apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ring, kit.lp(kit.dp(104), kit.dp(104)))
            addView(legend, kit.lp(0, -2, 1f, l = 16))
        }, kit.lp(-1, -2, t = 8))
        regen = kit.text("", 22f, R.color.accent_energy).apply { setTextColor(app.mg4dash.ui.Themes.energy(context)) }
        recovered.addView(regen, kit.lp(-1, -2))
        c3.addView(kit.title(getString(R.string.en_recent, app.mg4dash.system.Units.stackUnit(this))), kit.lp(-1, -2, t = 18))
        chart = LineChart(this).apply {
            // Long press on the curve: erase it (#44)
            setOnLongClickListener { app.mg4dash.ui.ConsumptionCard.confirmClear(this@EnergyActivity, this, period); true }
        }
        c3.addView(chart, kit.lp(-1, 0, 1f, t = 8))
        chartNote = kit.text("", 15f, R.color.text_title)
        c3.addView(chartNote, kit.lp(-1, -2, t = 4))

        body.addView(leftCol, kit.lp(kit.dp(400), -1))
        body.addView(center, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(c3, kit.lp(kit.dp(470), -1))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { app.mg4dash.energy.ConsumptionCurve.curves.collect { renderHistory() } }
                while (true) { refreshProps(); delay(2_000) }
            }
        }
        renderLimit()
    }

    // ── BMS data (read off the main thread) ──

    private data class Props(
        val target: Int?, val current: Int?, val reser: Int?, val st: Pair<Int?, Int?>, val sp: Pair<Int?, Int?>,
        val v2l: Int?, val volt: Float?, val amp: Float?, val cons: FloatArray, val heat: Int? = null,
        val stopReason: Int? = null, val acVolt: Float? = null, val acAmp: Float? = null,
    )

    private fun refreshProps() {
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) {
                val chrg = period == 0
                fun f(newId: Int, oldId: Int?) = CarProps.getFloat(newId) ?: oldId?.let { CarProps.getFloat(it) }
                Props(
                    CarProps.getInt(CarProps.CHRG_TRGT_SOC), CarProps.getInt(CarProps.ALTNG_CHRG_CRNT),
                    CarProps.getInt(CarProps.RESER_CTRL),
                    CarProps.getInt(CarProps.RESER_ST_HOUR) to CarProps.getInt(CarProps.RESER_ST_MIN),
                    CarProps.getInt(CarProps.RESER_SP_HOUR) to CarProps.getInt(CarProps.RESER_SP_MIN),
                    CarProps.getInt(CarProps.V2X_SP_SOC),
                    CarProps.getFloat(CarProps.BMS_PACK_VOL), CarProps.getFloat(CarProps.BMS_PACK_CRNT),
                    floatArrayOf(
                        f(if (chrg) CarProps.CONS_TOTAL_AFT_CHRG else CarProps.CONS_TOTAL_AFT_ST, if (chrg) CarProps.OLD_TOTAL_AFT_CHRG else CarProps.OLD_TOTAL_AFT_ST) ?: -1f,
                        f(if (chrg) CarProps.CONS_ATC_AFT_CHRG else CarProps.CONS_ATC_AFT_ST, null) ?: -1f,
                        f(if (chrg) CarProps.CONS_ACCRY_AFT_CHRG else CarProps.CONS_ACCRY_AFT_ST, if (chrg) CarProps.OLD_ACC_AFT_CHRG else CarProps.OLD_ACC_AFT_ST) ?: -1f,
                        f(if (chrg) CarProps.CONS_RGEN_AFT_CHRG else CarProps.CONS_RGEN_AFT_ST, if (chrg) CarProps.OLD_RGEN_AFT_CHRG else CarProps.OLD_RGEN_AFT_ST) ?: -1f,
                    ),
                    CarProps.getInt(CarProps.DRVNG_PTC_HEAT),
                    CarProps.getInt(CarProps.BMS_CHRG_SP_RSN),
                    CarProps.getFloat(CarProps.ONBD_CHRG_AC_VOL), CarProps.getFloat(CarProps.ONBD_CHRG_AC_CRNT),
                )
            }
            renderProps(p)
        }
    }

    private var heatWanted: Boolean? = null
    private var heatWantedAt = 0L

    private fun renderProps(p: Props) {
        reser = p.reser
        // Right after a touch, the requested state is shown until the battery confirms it (4 s at most):
        // reread too early, the old state brought the button back for a moment
        val read = p.heat?.let { it == 0 }
        val wanted = heatWanted?.takeIf { android.os.SystemClock.elapsedRealtime() - heatWantedAt < 4_000 && it != read }
        if (read == heatWanted) heatWanted = null
        heating.isSelected = wanted ?: (p.heat == 0)
        val locked = CarBridge.chargeLockLocked()
        gunLock.visibility = if (locked != null && VehicleState.isPlugged(CarBridge.state.value.chargeStatus)) android.view.View.VISIBLE else android.view.View.GONE
        gunLock.text = getString(if (locked == false) R.string.en_gun_lock else R.string.en_gun_unlock)
        heating.isEnabled = p.heat != null
        energyCar.heat = heating.isSelected
        heatLine.setBackgroundColor(kit.color(if (heating.isSelected) R.color.accent_heat else R.color.panel_border))
        val carLimit = CarProps.targetSocPercent(p.target)
        renderLimit(carLimit)
        setCurrent(p.current?.minus(1))
        val on = p.reser == 1 || p.reser == 3
        schedule.isSelected = on
        finish.isSelected = p.reser == 3
        finish.isEnabled = on
        // Time just set: the car's reread is ignored for a moment (as for the climate),
        // otherwise the display returned to the old time until the car took the new one
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= holdStartUntil) {
            p.st.first?.let { stH = it }; p.st.second?.let { stM = it }
            startTime.text = if (p.st.first != null) hhmm(stH, stM) else "--:--"
        }
        if (now >= holdEndUntil) {
            p.sp.first?.let { spH = it }; p.sp.second?.let { spM = it }
            endTime.text = when {
                p.reser == 3 -> getString(R.string.en_limit_word)
                p.sp.first != null -> hhmm(spH, spM)
                else -> "--:--"
            }
        }
        val v2lPct = CarProps.v2lPercent(p.v2l)
        v2lStopPct = v2lPct
        setV2l(v2lPct?.let { listOf(20, 30, 40, 50, 60, 70, 80).indexOf(it) })
        val kw = if (p.volt != null && p.amp != null) p.volt * p.amp / 1000f else null
        packKw = kw
        paintV2lBottom(CarBridge.state.value)
        val s = CarBridge.state.value
        power.text = listOfNotNull(
            kw?.takeIf { kotlin.math.abs(it) >= 0.1f }?.let { getString(R.string.en_power, String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(it))) },
            s.chargeRemainMin?.takeIf { VehicleState.isCharging(s.chargeStatus) && it in 1..2000 }
                ?.let { getString(R.string.en_ends_in, it / 60, it % 60) },
            // On-board charger input (AC charge): voltage and current taken from the mains
            p.acVolt?.takeIf { VehicleState.isCharging(s.chargeStatus) && it in 80f..450f }?.let { v ->
                getString(R.string.en_ac_input, Math.round(v), String.format(Locale.getDefault(), "%.1f", p.acAmp ?: 0f))
            },
            // Charge interrupted: reason given by the battery (BMS)
            stopReasonLabel(p.stopReason)?.takeIf { VehicleState.isPlugged(s.chargeStatus) && !VehicleState.isCharging(s.chargeStatus) }
                ?.let { getString(R.string.en_stop_reason, it) },
        ).joinToString(" · ")
        // SAIC counters at zero on the car: consumption measured by MG4Dash (EnergyMonitor)
        val t = EnergyMonitor.totals(period)
        renderConsumption(if (t.outKwh > 0f) floatArrayOf(t.outKwh, t.climKwh, t.accKwh, t.regenKwh, t.heatKwh) else p.cons)
    }

    private fun stopReasonLabel(code: Int?): String? = when (code) {
        1 -> getString(R.string.sr_fault)
        2 -> getString(R.string.sr_overtemp)
        3 -> getString(R.string.sr_plug)
        4 -> getString(R.string.sr_voltage)
        5 -> getString(R.string.sr_other)
        else -> null
    }

    private fun renderLimit(carLimit: Int? = null) {
        if (!::setLimit.isInitialized) return
        // The limit set in the car (stock app, iSMART) prevails: before, the last value
        // chosen in MG4Dash was shown (100 % by default) even if the car was at 80 % (#50)
        val wanted = carLimit?.takeIf { it in 50..100 }?.also { if (it != prefs.chargeLimit) prefs.chargeLimit = it } ?: prefs.chargeLimit
        energyCar.limit = if (wanted < 100) wanted else 0
        setLimit(listOf(50, 60, 70, 80, 90, 100).indexOf(wanted).takeIf { it >= 0 })
        limitNote.text = if (wanted >= 100) getString(R.string.en_no_limit) else getString(R.string.sr_car_stops_at, wanted)
    }

    private fun renderConsumption(c: FloatArray) {
        val (total, clim, acc, rgn) = c.toList()
        val heat = c.getOrElse(4) { 0f }.coerceAtLeast(0f)
        val showHeat = heat >= app.mg4dash.energy.EnergyMonitor.HEAT_SHOWN_KWH
        val ok = total > 0f
        // Driving with regeneration deducted, like the chart; shares are counted on this total
        val drive = if (ok) (total - clim.coerceAtLeast(0f) - acc.coerceAtLeast(0f) - heat - rgn.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f
        val shared = (drive + clim.coerceAtLeast(0f) + acc.coerceAtLeast(0f) + heat).coerceAtLeast(0.001f)
        consTotal.text = if (ok) String.format(Locale.getDefault(), "%.1f kWh", total) else "—"
        val green = kit.color(R.color.accent_energy); val cyan = kit.color(R.color.accent_cold); val orange = kit.color(R.color.accent_heat)
        val violet = kit.color(R.color.accent_battery)
        ring.show(if (ok) listOfNotNull(drive to green, clim.coerceAtLeast(0f) to cyan, (heat to violet).takeIf { showHeat }, acc.coerceAtLeast(0f) to orange) else emptyList(),
            if (ok) "${Math.round(drive / shared * 100)} %" else "")
        legend.removeAllViews()
        listOfNotNull(Triple(getString(R.string.en_drive), drive, green), Triple(getString(R.string.en_climate), clim, cyan),
            Triple(getString(R.string.en_battery_heat), heat, violet).takeIf { showHeat },
            Triple(getString(R.string.en_accessories), acc, orange)).forEach { (l, v, col) ->
            val r = kit.row()
            r.addView(android.view.View(this).apply { setBackgroundColor(col) }, kit.lp(kit.dp(12), kit.dp(12)))
            r.addView(kit.text(l, 18f, R.color.text_sub), kit.lp(0, -2, 1f, l = 10))
            r.addView(kit.text(if (ok && v >= 0f) String.format(Locale.getDefault(), "%.1f kWh · %d %%", v, Math.round(v / shared * 100)) else "—", 18f))
            legend.addView(r, kit.lp(-1, -2, t = 6))
        }
        regen.text = if (rgn > 0f) app.mg4dash.ui.ConsumptionCard.regenValues(this, rgn,
            app.mg4dash.ui.ConsumptionCard.regenKm(EnergyMonitor.totals(period))) else ""
        regenLabel.visibility = if (rgn > 0f) android.view.View.VISIBLE else android.view.View.INVISIBLE
    }

    private fun renderHistory() {
        val (points, note) = app.mg4dash.ui.ConsumptionCard.fill(this, chart, period, stacked = true)
        app.mg4dash.ui.ConsumptionCard.axis(this, period, points).let { (a, b) -> chart.startLabel = a; chart.endLabel = b }
        chart.emptyText = if (period == 2) R.string.chart_empty_trips else R.string.chart_empty
        chartNote.text = note
    }

    override fun render(s: VehicleState) {
        soc.text = VehicleState.socLabel(s) ?: "—"
        range.text = s.rangeKm?.let { app.mg4dash.system.Units.distText(this, it.toFloat()) } ?: "—"
        status.text = VehicleState.chargeStatusLabel(s.chargeStatus)
        val pct = s.batteryPercent
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        status.setTextColor(kit.color(when {
            discharge -> R.color.accent_heat
            VehicleState.isCharging(s.chargeStatus) -> R.color.accent_energy
            else -> R.color.text_sub
        }))
        action.text = mainActionLabel(s)
        action.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        // Charge current meaningless in V2L: the limit comes from the adapter's resistor (#81)
        val currentShown = if (VehicleState.isDischargeMode(s.chargeStatus)) android.view.View.GONE else android.view.View.VISIBLE
        currentTitle.visibility = currentShown
        currentRow.visibility = currentShown
        // Start / stop charging and V2L: only where the EV service answers (A9 route)
        // In V2L, only the V2L settings button stays: the charge one was redundant
        action.visibility = when {
            VehicleState.isDischargeMode(s.chargeStatus) -> android.view.View.GONE
            CarBridge.chargeControl -> android.view.View.VISIBLE
            else -> android.view.View.INVISIBLE
        }
        v2lAction.visibility = if (CarBridge.chargeControl) android.view.View.VISIBLE else android.view.View.INVISIBLE
        action.isSelected = s.chargeCtrl == 1 || VehicleState.isDischarging(s)
        v2lStatus.text = if (discharge) VehicleState.chargeStatusLabel(s.chargeStatus) +
            (s.dischargeRemainMin?.takeIf { it in 1..6000 }?.let { getString(R.string.en_remaining, it / 60, it % 60) } ?: "")
        else getString(R.string.en_plug_v2l)
        v2lAction.isEnabled = discharge
        paintV2l(s)
        energyCar.soc = s.batteryPercent?.toFloat() ?: 0f
        energyCar.plugged = VehicleState.isPlugged(s.chargeStatus)
        energyCar.charging = VehicleState.isCharging(s.chargeStatus)
        energyCar.v2l = VehicleState.isDischarging(s)
        paintV2lBottom(s)
        energyCar.invalidate()
    }

    /** V2L button state: the requested one until the car has confirmed it, otherwise the car's. */
    private fun v2lShownOn(s: VehicleState): Boolean {
        val real = VehicleState.isDischarging(s)
        val wanted = v2lWanted ?: return real
        if (wanted == real || android.os.SystemClock.uptimeMillis() > v2lWantedUntil) { v2lWanted = null; return real }
        return wanted
    }

    private fun paintV2l(s: VehicleState) {
        val on = v2lShownOn(s)
        v2lAction.isSelected = on
        v2lAction.text = getString(if (on) R.string.en_v2l_stopbtn else R.string.en_v2l_start)
    }

    private fun mainActionLabel(s: VehicleState): String = when {
        !VehicleState.isPlugged(s.chargeStatus) -> getString(R.string.en_unplugged)
        VehicleState.isDischargeMode(s.chargeStatus) -> getString(if (VehicleState.isDischarging(s)) R.string.en_v2l_stopbtn else R.string.en_v2l_start)
        s.chargeCtrl == 1 -> getString(R.string.en_stop_charge)
        else -> getString(R.string.en_charge_now)
    }

    private fun onMainAction() {
        val s = CarBridge.state.value
        if (!VehicleState.isPlugged(s.chargeStatus)) return
        when {
            VehicleState.isDischargeMode(s.chargeStatus) -> if (VehicleState.isDischarging(s)) CarBridge.stopV2L() else CarBridge.startV2L()
            s.chargeCtrl == 1 -> CarBridge.stopCharge()
            else -> CarBridge.startCharge()
        }
    }

    /**
     * The battery's settings (24/11): its model (the reference of the estimated health) and the new capacity used
     * for the health, with what MG4Dash measured. [closed] once it goes.
     */
    private fun batteryOptions(closed: () -> Unit) {
        val prefs = app.mg4dash.system.Prefs(this)
        val bc = app.mg4dash.energy.BatteryCapacity
        val f = { v: Float -> String.format(java.util.Locale.getDefault(), "%.1f", v) }
        val models = bc.MODELS.map { it.first }
        val measured = bc.fromCar()?.let { getString(R.string.st_cap_car, f(it)) }
            ?: bc.estimate(this)?.let { getString(R.string.st_cap_est, f(it.first), it.second) }
            ?: bc.progress(this).provisional?.let { getString(R.string.st_cap_est_current, f(it)) }
            ?: getString(R.string.st_cap_none)
        lateinit var popup: app.mg4dash.ui.OptionsPopup
        popup = app.mg4dash.ui.OptionsPopup(this, getString(R.string.vh_battery), R.drawable.ic_battery)
            .choice(getString(R.string.en_battery_model), getString(R.string.en_battery_model_desc),
                models.map { "$it kWh" }, { models.indexOf(prefs.batteryModel).coerceAtLeast(0) }) { prefs.batteryModel = models[it] }
            .choice(getString(R.string.st_new_cap).trimEnd(' ', ':', '\u00a0', '\u202f'), getString(R.string.en_new_cap_desc),
                listOf(getString(R.string.st_new_cap_default, f(bc.modelNominal(this))), getString(R.string.st_new_cap_item_custom)),
                { if (prefs.batteryNewKwh > 0f) 1 else 0 }) { choice ->
                if (choice == 0) prefs.batteryNewKwh = 0f
                else app.mg4dash.ui.Ask.show(this, getString(R.string.st_new_cap_title), if (prefs.batteryNewKwh > 0f) prefs.batteryNewKwh.toString() else "", secret = false, number = true) { typed ->
                    typed.trim().replace(',', '.').toFloatOrNull()?.takeIf { it in 30f..120f }?.let { prefs.batteryNewKwh = Math.round(it * 10f) / 10f }
                    closed()
                }
            }
            .note(measured)
        popup.show { closed() }
    }

    /** Touching the time: hour / minute wheels, like "climate at a set time". */
    private fun pickTime(start: Boolean) {
        val cur = if (start) stH * 60 + stM else spH * 60 + spM
        val (view, read) = kit.timeWheels(cur)
        lateinit var d: android.app.AlertDialog
        d = app.mg4dash.ui.Windows.show(this, getString(if (start) R.string.en_start else R.string.en_end), view, widthDp = 640,
            buttons = listOf(app.mg4dash.ui.Rows.actionButton(this, getString(android.R.string.ok)) { d.dismiss(); setTime(start, read()) }))
    }

    private fun shiftTime(start: Boolean, deltaMin: Int) {
        val (h, m) = if (start) stH to stM else spH to spM
        setTime(start, h * 60 + m + deltaMin)
    }

    private var holdStartUntil = 0L
    private var holdEndUntil = 0L

    private fun setTime(start: Boolean, minutes: Int) {
        val total = Math.floorMod(minutes, 1440)
        val until = android.os.SystemClock.uptimeMillis() + 4000
        if (start) holdStartUntil = until else holdEndUntil = until
        val nh = total / 60; val nm = total % 60
        val other = if (start) spH * 60 + spM else stH * 60 + stM
        if (total == other) return    // the SAIC app refuses start = end
        if (start) {
            stH = nh; stM = nm
            CarProps.setInt(CarProps.RESER_ST_HOUR, nh, "schedule start (h)")
            CarProps.setInt(CarProps.RESER_ST_MIN, nm, "schedule start (min)")
            startTime.text = hhmm(nh, nm)
        } else {
            spH = nh; spM = nm
            CarProps.setInt(CarProps.RESER_SP_HOUR, nh, "schedule end (h)")
            CarProps.setInt(CarProps.RESER_SP_MIN, nm, "schedule end (min)")
            endTime.text = hhmm(nh, nm)
        }
        // Like the SAIC Energy app: 50 ms after the times, the scheduled charge state is rewritten
        // unchanged; without this confirmation the car did not keep the new time
        startTime.postDelayed({
            val ctrl = CarProps.getInt(CarProps.RESER_CTRL) ?: reser
            if (ctrl != null) CarProps.setInt(CarProps.RESER_CTRL, ctrl, "schedule confirm")
            refreshProps()
        }, 50)
    }

    private fun hhmm(h: Int, m: Int) = String.format(Locale.getDefault(), "%02d:%02d", h, m)
}
