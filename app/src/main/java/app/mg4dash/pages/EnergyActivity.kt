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
import app.mg4dash.ui.smooth3
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge
import app.mg4dash.ui.StackedBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Page Énergie : recharge (limite, courant, démarrage/arrêt), programmation, V2L et
 * consommation. Réglages écrits sur les mêmes propriétés que l'appli Énergie SAIC (EH32 EU).
 */
class EnergyActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_energy)
    override val page = app.mg4dash.ui.Dock.Page.ENERGY

    private lateinit var prefs: Prefs
    // Recharge
    private lateinit var soc: TextView
    private lateinit var range: TextView
    private lateinit var status: TextView
    private lateinit var socGauge: SegmentedGauge
    private lateinit var power: TextView
    private lateinit var action: TextView
    private lateinit var limitNote: TextView
    private lateinit var setLimit: (Int?) -> Unit
    private lateinit var setCurrent: (Int?) -> Unit
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
    // Conso
    private lateinit var setPeriod: (Int?) -> Unit
    private lateinit var consTotal: TextView
    private lateinit var bar: StackedBar
    private lateinit var legend: LinearLayout
    private lateinit var regen: TextView
    private lateinit var regenKm: TextView
    private lateinit var chart: LineChart
    private lateinit var chartNote: TextView

    private var period = 0          // 0 = depuis la recharge, 1 = trajet en cours, 2 = 1 000 derniers km
    private var reser: Int? = null
    private var stH = 22; private var stM = 0; private var spH = 6; private var spM = 0

    override fun buildBody() {
        prefs = Prefs(this)
        header.addView(kit.text(getString(R.string.en_stock), 19f, R.color.accent_action).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { Apps.open(this@EnergyActivity, Apps.ENERGY, "SAIC") }
        })
        // Réchauffage de la batterie en roulant (même réglage que l'appli Énergie SAIC)
        heating = kit.toggleTile(getString(R.string.en_heating), heat = true) {
            // Écriture 1 = activer, 2 = désactiver (comme l'appli SAIC) ; la lecture rend 0 quand c'est activé
            val on = heating.isSelected
            CarProps.setInt(CarProps.DRVNG_PTC_HEAT, if (on) 2 else 1, "réchauffage batterie")
            heating.postDelayed({ refreshProps() }, 1500)
        }.apply { textSize = 18f; setPadding(kit.dp(20), 0, kit.dp(20), 0) }
        header.addView(heating, header.childCount - 1, kit.lp(-2, kit.dp(52), r = 12))   // avant le lien SAIC

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
        socGauge = SegmentedGauge(this).apply { segments = 20 }
        c1.addView(socGauge, kit.lp(-1, kit.dp(14), t = 12))
        power = kit.text("", 18f, R.color.text_sub)
        c1.addView(power, kit.lp(-1, -2, t = 8))
        action = kit.toggleTile("—") { onMainAction() }.apply { textSize = 20f }
        // Bouton principal et, câble branché, verrou du pistolet (bouton du launcher d'origine)
        val actRow = kit.row()
        actRow.addView(action, kit.lp(0, kit.dp(64), 1f))
        gunLock = kit.toggleTile(getString(R.string.en_gun_unlock)) {
            CarBridge.toggleChargeLock(); gunLock.postDelayed({ refreshProps() }, 1500)
        }.apply { textSize = 18f; setPadding(kit.dp(16), 0, kit.dp(16), 0); visibility = android.view.View.GONE }
        actRow.addView(gunLock, kit.lp(-2, kit.dp(64), l = 10))
        c1.addView(actRow, kit.lp(-1, -2, t = 12))
        // Limite de charge : seulement là où la voiture l'applique elle-même (ancien SDK). Ailleurs
        // (MG4 Standard sans la limite d'origine), la voiture l'ignore, et MG4Dash ne peut pas arrêter
        // la charge à sa place : il est coupé quand la voiture est fermée (constaté le 2026-10-18).
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
        c1.addView(kit.title(getString(R.string.en_ac_current)), kit.lp(-1, -2, t = 14))
        val (cRow, cSet) = kit.segmented(listOf("6 A", "8 A", "16 A", getString(R.string.en_max))) { i ->
            CarProps.setInt(CarProps.ALTNG_CHRG_CRNT, i + 1, "courant AC"); refreshProps()
        }
        setCurrent = cSet
        c1.addView(cRow, kit.lp(-1, -2, t = 8))

        // ── Colonne 2 : programmation + V2L ──
        val c2 = kit.panel(getString(R.string.en_scheduled))
        val tRow = kit.row()
        schedule = kit.toggleTile(getString(R.string.en_schedule)) {
            val on = reser == 1 || reser == 3
            CarProps.setInt(CarProps.RESER_CTRL, if (on) 2 else 1, "programmation"); refreshProps()
        }
        finish = kit.toggleTile(getString(R.string.en_finish)) {
            CarProps.setInt(CarProps.RESER_CTRL, if (reser == 3) 1 else 3, "continuer après l'heure de fin"); refreshProps()
        }.apply { textSize = 17f }
        tRow.addView(schedule, kit.lp(0, kit.dp(60), 1f))
        tRow.addView(finish, kit.lp(0, kit.dp(60), 1f, l = 10))
        c2.addView(tRow, kit.lp(-1, -2, t = 10))
        val (sRow, sText) = kit.timeStepper(getString(R.string.en_start)) { d -> shiftTime(true, d) }
        startTime = sText
        c2.addView(sRow, kit.lp(-1, -2, t = 14))
        val (eRow2, eText) = kit.timeStepper(getString(R.string.en_end)) { d -> shiftTime(false, d) }
        endTime = eText
        c2.addView(eRow2, kit.lp(-1, -2, t = 10))
        c2.addView(kit.divider(), kit.lp(-1, 1, t = 18))
        c2.addView(kit.title(getString(R.string.en_v2l)), kit.lp(-1, -2, t = 16))
        v2lStatus = kit.text("—", 19f, R.color.text_sub)
        c2.addView(v2lStatus, kit.lp(-1, -2, t = 8))
        c2.addView(kit.text(getString(R.string.en_v2l_stop_at), 17f, R.color.text_title), kit.lp(-1, -2, t = 12))
        val v2ls = listOf(20, 30, 40, 50, 60, 70, 80)
        val (vRow, vSet) = kit.segmented(v2ls.map { "$it" }) { i ->
            CarProps.setInt(CarProps.V2X_SP_SOC, CarProps.v2lCode(v2ls[i]), "seuil V2L ${v2ls[i]} %"); refreshProps()
        }
        setV2l = vSet
        c2.addView(vRow, kit.lp(-1, -2, t = 8))
        v2lAction = kit.toggleTile(getString(R.string.en_v2l_start), heat = true) {
            val s = CarBridge.state.value
            if (VehicleState.isDischarging(s)) CarBridge.stopV2L() else CarBridge.startV2L()
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
        consTotal = kit.text("—", 44f, light = true)
        c3.addView(consTotal, kit.lp(-1, -2, t = 14))
        bar = StackedBar(this)
        c3.addView(bar, kit.lp(-1, kit.dp(22), t = 10))
        legend = kit.column()
        c3.addView(legend, kit.lp(-1, -2, t = 8))
        regen = kit.text("", 18f, R.color.accent_energy)
        c3.addView(regen, kit.lp(-1, -2, t = 6))
        regenKm = kit.text("", 18f, R.color.accent_energy)
        c3.addView(regenKm, kit.lp(-1, -2, t = 2))
        c3.addView(kit.title(getString(R.string.en_recent, app.mg4dash.system.Units.consUnit(this))), kit.lp(-1, -2, t = 18))
        chart = LineChart(this).apply {
            // Appui long sur la courbe : l'effacer (#44)
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

    // ── Données BMS (lues hors thread principal) ──

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

    private fun renderProps(p: Props) {
        reser = p.reser
        heating.isSelected = p.heat == 0
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
        p.st.first?.let { stH = it }; p.st.second?.let { stM = it }
        p.sp.first?.let { spH = it }; p.sp.second?.let { spM = it }
        startTime.text = if (p.st.first != null) hhmm(stH, stM) else "--:--"
        endTime.text = when {
            p.reser == 3 -> getString(R.string.en_limit_word)
            p.sp.first != null -> hhmm(spH, spM)
            else -> "--:--"
        }
        val v2lPct = CarProps.v2lPercent(p.v2l)
        setV2l(v2lPct?.let { listOf(20, 30, 40, 50, 60, 70, 80).indexOf(it) })
        val kw = if (p.volt != null && p.amp != null) p.volt * p.amp / 1000f else null
        val s = CarBridge.state.value
        power.text = listOfNotNull(
            kw?.takeIf { kotlin.math.abs(it) >= 0.1f }?.let { getString(R.string.en_power, String.format(Locale.getDefault(), "%.1f", kotlin.math.abs(it))) },
            s.chargeRemainMin?.takeIf { VehicleState.isCharging(s.chargeStatus) && it in 1..2000 }
                ?.let { getString(R.string.en_ends_in, it / 60, it % 60) },
            // Entrée du chargeur embarqué (charge AC) : tension et courant pris sur le secteur
            p.acVolt?.takeIf { VehicleState.isCharging(s.chargeStatus) && it in 80f..450f }?.let { v ->
                getString(R.string.en_ac_input, Math.round(v), String.format(Locale.getDefault(), "%.1f", p.acAmp ?: 0f))
            },
            // Charge interrompue : motif donné par la batterie (BMS)
            stopReasonLabel(p.stopReason)?.takeIf { VehicleState.isPlugged(s.chargeStatus) && !VehicleState.isCharging(s.chargeStatus) }
                ?.let { getString(R.string.en_stop_reason, it) },
        ).joinToString(" · ")
        regenKm.text = p.regenKm?.takeIf { it > 0.05f && it < 2000f }
            ?.let { getString(R.string.en_regen_km, String.format(Locale.getDefault(), "%.1f", app.mg4dash.system.Units.dist(this, it)), app.mg4dash.system.Units.distUnit(this)) } ?: ""
        // Compteurs SAIC à zéro sur la voiture : consommation mesurée par MG4Dash (EnergyMonitor)
        val t = EnergyMonitor.totals(period)
        renderConsumption(if (t.outKwh > 0f) floatArrayOf(t.outKwh, t.climKwh, t.accKwh, t.regenKwh) else p.cons)
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
        val wanted = prefs.chargeLimit
        setLimit(listOf(50, 60, 70, 80, 90, 100).indexOf(wanted).takeIf { it >= 0 })
        limitNote.text = if (wanted >= 100) getString(R.string.en_no_limit)
            else getString(R.string.en_car_only) + (carLimit?.let { " ($it %)." } ?: ".")
    }

    private fun renderConsumption(c: FloatArray) {
        val (total, clim, acc, rgn) = c.toList()
        val ok = total > 0f
        val drive = if (ok) (total - clim.coerceAtLeast(0f) - acc.coerceAtLeast(0f)).coerceAtLeast(0f) else 0f
        consTotal.text = if (ok) String.format(Locale.getDefault(), "%.1f kWh", total) else "—"
        val green = kit.color(R.color.accent_energy); val cyan = kit.color(R.color.accent_cold); val orange = kit.color(R.color.accent_heat)
        bar.parts = if (ok) listOf(
            StackedBar.Part(drive, green), StackedBar.Part(clim.coerceAtLeast(0f), cyan), StackedBar.Part(acc.coerceAtLeast(0f), orange),
        ) else emptyList()
        legend.removeAllViews()
        listOf(Triple(getString(R.string.en_drive), drive, green), Triple(getString(R.string.en_climate), clim, cyan), Triple(getString(R.string.en_accessories), acc, orange)).forEach { (l, v, col) ->
            val r = kit.row()
            r.addView(android.view.View(this).apply { setBackgroundColor(col) }, kit.lp(kit.dp(12), kit.dp(12)))
            r.addView(kit.text(l, 18f, R.color.text_sub), kit.lp(0, -2, 1f, l = 10))
            r.addView(kit.text(if (ok && v >= 0f) String.format(Locale.getDefault(), "%.1f kWh · %d %%", v, Math.round(v / total * 100)) else "—", 18f))
            legend.addView(r, kit.lp(-1, -2, t = 6))
        }
        regen.text = if (rgn > 0f) getString(R.string.en_regen, String.format(Locale.getDefault(), "%.1f", rgn)) else ""
    }

    private fun renderHistory() {
        val (values, note) = app.mg4dash.ui.ConsumptionCard.curve(this, period)
        chart.values = values
        chartNote.text = note
    }

    override fun render(s: VehicleState) {
        soc.text = VehicleState.socLabel(s) ?: "—"
        range.text = s.rangeKm?.let { app.mg4dash.system.Units.distText(this, it.toFloat()) } ?: "—"
        status.text = VehicleState.chargeStatusLabel(s.chargeStatus)
        val pct = s.batteryPercent
        socGauge.onColor = kit.color(when { pct == null -> R.color.text_disabled; pct <= 15 -> R.color.accent_danger; pct <= 30 -> R.color.accent_heat; else -> R.color.accent_energy })
        socGauge.value = pct?.let { (it + 4) / 5 } ?: 0
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        status.setTextColor(kit.color(when {
            discharge -> R.color.accent_heat
            VehicleState.isCharging(s.chargeStatus) -> R.color.accent_energy
            else -> R.color.text_sub
        }))
        action.text = mainActionLabel(s)
        action.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        // Démarrer / arrêter la charge et le V2L : seulement là où le service EV répond (voie A9)
        action.visibility = if (CarBridge.chargeControl) android.view.View.VISIBLE else android.view.View.INVISIBLE
        v2lAction.visibility = if (CarBridge.chargeControl) android.view.View.VISIBLE else android.view.View.INVISIBLE
        action.isSelected = s.chargeCtrl == 1 || VehicleState.isDischarging(s)
        v2lStatus.text = if (discharge) VehicleState.chargeStatusLabel(s.chargeStatus) +
            (s.dischargeRemainMin?.takeIf { it in 1..6000 }?.let { getString(R.string.en_remaining, it / 60, it % 60) } ?: "")
        else getString(R.string.en_plug_v2l)
        v2lAction.isEnabled = discharge
        v2lAction.isSelected = VehicleState.isDischarging(s)
        v2lAction.text = getString(if (VehicleState.isDischarging(s)) R.string.en_v2l_stopbtn else R.string.en_v2l_start)
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

    private fun shiftTime(start: Boolean, deltaMin: Int) {
        val (h, m) = if (start) stH to stM else spH to spM
        val total = ((h * 60 + m + deltaMin) % 1440 + 1440) % 1440
        val nh = total / 60; val nm = total % 60
        val other = if (start) spH * 60 + spM else stH * 60 + stM
        if (total == other) return    // l'appli SAIC refuse début = fin
        if (start) {
            stH = nh; stM = nm
            CarProps.setInt(CarProps.RESER_ST_HOUR, nh, "début programmation (h)")
            CarProps.setInt(CarProps.RESER_ST_MIN, nm, "début programmation (min)")
            startTime.text = hhmm(nh, nm)
        } else {
            spH = nh; spM = nm
            CarProps.setInt(CarProps.RESER_SP_HOUR, nh, "fin programmation (h)")
            CarProps.setInt(CarProps.RESER_SP_MIN, nm, "fin programmation (min)")
            endTime.text = hhmm(nh, nm)
        }
    }

    private fun hhmm(h: Int, m: Int) = String.format(Locale.getDefault(), "%02d:%02d", h, m)
}
