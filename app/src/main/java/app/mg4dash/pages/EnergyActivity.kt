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
    private lateinit var socGauge: app.mg4dash.ui.SocBar
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
    private var regenKm: Float? = null
    private lateinit var chart: LineChart
    private lateinit var chartNote: TextView

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
        header.addView(heating, header.childCount - 1, kit.lp(-2, kit.dp(52), r = 12))   // before the SAIC link

        // ── Colonne 1 : recharge ──
        val c1 = kit.panel(getString(R.string.en_charging))
        val top = kit.row()
        soc = kit.text("—", 72f, light = true)
        top.addView(soc)
        val side = kit.column()
        range = kit.text("—", 26f)
        status = kit.text("—", 19f, R.color.text_sub)
        side.addView(range); side.addView(status, kit.lp(-2, -2, t = 6))
        top.addView(side, kit.lp(0, -2, 1f, l = 22))
        c1.addView(top, kit.lp(-1, -2, t = 6))
        socGauge = app.mg4dash.ui.SocBar(this).apply { currentBubble = false }   // the large % is just above
        c1.addView(socGauge, kit.lp(-1, kit.dp(52), t = 4))
        power = kit.text("", 18f, R.color.text_sub)
        c1.addView(power, kit.lp(-1, -2, t = 8))
        action = kit.toggleTile("—") { onMainAction() }.apply { textSize = 20f }
        // Main button and, with the cable plugged in, gun lock (stock launcher's button)
        val actRow = kit.row()
        actRow.addView(action, kit.lp(0, kit.dp(64), 1f))
        gunLock = kit.toggleTile(getString(R.string.en_gun_unlock)) {
            CarBridge.toggleChargeLock(); gunLock.postDelayed({ refreshProps() }, 1500)
        }.apply { textSize = 18f; setPadding(kit.dp(16), 0, kit.dp(16), 0); visibility = android.view.View.GONE }
        actRow.addView(gunLock, kit.lp(-2, kit.dp(64), l = 10))
        c1.addView(actRow, kit.lp(-1, -2, t = 12))
        // Charge limit: only where the car applies it itself (old SDK). Elsewhere
        // (MG4 Standard without the stock limit), the car ignores it, and MG4Dash cannot stop
        // charging in its place: it is cut when the car is closed (found on 2026-10-18).
        if (app.mg4dash.system.Firmware.nativeChargeLimit) {
            c1.addView(kit.title(getString(R.string.en_limit)), kit.lp(-1, -2, t = 18))
            val limits = listOf(50, 60, 70, 80, 90, 100)
            val (lRow, lSet) = kit.segmented(limits.map { "$it %" }) { i ->
                ChargeGuard.setLimit(this, limits[i]); refreshProps()
            }
            setLimit = lSet
            c1.addView(lRow, kit.lp(-1, -2, t = 8))
            limitNote = kit.text("", 16f, R.color.text_title).apply { maxLines = 2 }
            c1.addView(limitNote, kit.lp(-1, -2, t = 8))
        }
        currentTitle = kit.title(getString(R.string.en_ac_current))
        c1.addView(currentTitle, kit.lp(-1, -2, t = 14))
        val (cRow, cSet) = kit.segmented(listOf("6 A", "8 A", "16 A", getString(R.string.en_max))) { i ->
            CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "AC current"); refreshProps()
        }
        setCurrent = cSet
        currentRow = cRow
        c1.addView(cRow, kit.lp(-1, -2, t = 8))

        // ── Colonne 2 : programmation + V2L ──
        val c2 = kit.panel(getString(R.string.en_scheduled))
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
        c2.addView(kit.divider(), kit.lp(-1, 1, t = 18))
        c2.addView(kit.title(getString(R.string.en_v2l)), kit.lp(-1, -2, t = 16))
        v2lStatus = kit.text("—", 19f, R.color.text_sub)
        c2.addView(v2lStatus, kit.lp(-1, -2, t = 8))
        c2.addView(kit.text(getString(R.string.en_v2l_stop_at), 17f, R.color.text_title), kit.lp(-1, -2, t = 12))
        val v2ls = listOf(20, 30, 40, 50, 60, 70, 80)
        val (vRow, vSet) = kit.segmented(v2ls.map { "$it" }) { i ->
            CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode(v2ls[i]), "V2L threshold ${v2ls[i]} %"); refreshProps()
        }
        setV2l = vSet
        c2.addView(vRow, kit.lp(-1, -2, t = 8))
        v2lAction = kit.toggleTile(getString(R.string.en_v2l_start), heat = true) {
            val s = CarBridge.state.value
            val on = !v2lShownOn(s)
            if (on) CarBridge.startV2L() else CarBridge.stopV2L()
            // The car confirms within a few seconds: the button changes at once
            v2lWanted = on; v2lWantedUntil = android.os.SystemClock.uptimeMillis() + V2L_CONFIRM_MS
            paintV2l(s)
        }.apply { textSize = 18f }
        c2.addView(LinearLayout(this), kit.lp(-1, 0, 1f))
        c2.addView(v2lAction, kit.lp(-1, kit.dp(60), t = 10))

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

        body.addView(c1, kit.lp(0, -1, 1.05f, r = 6))
        body.addView(c2, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(c3, kit.lp(0, -1, 1f, l = 6))

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
        val stopReason: Int? = null, val acVolt: Float? = null, val acAmp: Float? = null, val regenKm: Float? = null,
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
                    CarProps.getFloat(CarProps.REGEN_RNG_AFT_CHRG),
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
        setV2l(v2lPct?.let { listOf(20, 30, 40, 50, 60, 70, 80).indexOf(it) })
        val kw = if (p.volt != null && p.amp != null) p.volt * p.amp / 1000f else null
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
        regenKm = p.regenKm
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
        setLimit(listOf(50, 60, 70, 80, 90, 100).indexOf(wanted).takeIf { it >= 0 })
        limitNote.text = if (wanted >= 100) getString(R.string.en_no_limit)
            else getString(R.string.en_car_only) + (carLimit?.let { " ($it %)." } ?: ".")
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
            if (period == 0) regenKm else app.mg4dash.ui.ConsumptionCard.regenKmEstimate(EnergyMonitor.totals(period))) else ""
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
        socGauge.soc = pct?.toFloat() ?: 0f
        socGauge.charging = VehicleState.isCharging(s.chargeStatus)
        // Same bar as the home widget; no trip start marker with the cable plugged in
        socGauge.tripStart = if (VehicleState.isPlugged(s.chargeStatus)) null else app.mg4dash.energy.EnergyMonitor.tripStartSoc.value?.toFloat()
        // The room under the bar is only for the trip start bubble
        val barHeight = kit.dp(if (VehicleState.isPlugged(s.chargeStatus)) 28 else 52)
        if (socGauge.layoutParams.height != barHeight) socGauge.layoutParams = socGauge.layoutParams.apply { height = barHeight }
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

    /** Touching the time: hour / minute wheels, like "climate at a set time". */
    private fun pickTime(start: Boolean) {
        val cur = if (start) stH * 60 + stM else spH * 60 + spM
        val (view, read) = kit.timeWheels(cur)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (start) R.string.en_start else R.string.en_end)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ -> setTime(start, read()) }
            .setNegativeButton(R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
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
