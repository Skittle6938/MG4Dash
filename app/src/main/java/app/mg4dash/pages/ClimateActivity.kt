package app.mg4dash.pages

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Apps
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge

/** Page Climatisation : température, ventilation, direction de l'air et modes. */
class ClimateActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_climate)
    override val page = app.mg4dash.ui.Dock.Page.CLIMATE

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var power: ImageButton
    private lateinit var outside: TextView
    private lateinit var temp: TextView
    private lateinit var tempGauge: SegmentedGauge
    private lateinit var fanValue: TextView
    private lateinit var fanGauge: SegmentedGauge
    private lateinit var face: TextView
    private lateinit var feet: TextView
    private lateinit var screen: TextView
    private lateinit var auto: TextView
    private lateinit var ac: TextView
    private lateinit var eco: TextView
    private lateinit var recirc: TextView
    private lateinit var defFront: TextView
    private lateinit var defRear: TextView
    private val controls = mutableListOf<View>()

    override fun buildBody() {
        // En-tête : température extérieure + marche/arrêt
        outside = kit.text("", 18f, R.color.text_sub)
        header.addView(outside, kit.lp(-2, -2, r = 20))
        power = kit.roundButton(R.drawable.ic_power, 56) { CarBridge.togglePower() }
            .apply { setOnLongClickListener { if (app.mg4dash.system.Prefs(context).climateHoldEnabled) { app.mg4dash.climate.ClimateHoldPanel.show(context as android.app.Activity); true } else false } }
        header.addView(power)

        // ── Température ──
        val tPanel = kit.panel(getString(R.string.cl_driver_temp))
        temp = kit.text("—", 120f, light = true).apply {
            gravity = Gravity.CENTER
            setOnLongClickListener { app.mg4dash.ui.TempCalibration.show(this@ClimateActivity) { render(app.mg4dash.car.CarBridge.state.value) }; true }
        }
        val tRow = kit.row().apply { gravity = Gravity.CENTER }
        val down = kit.roundButton(R.drawable.ic_minus, 96, R.color.accent_cold) {}
        val up = kit.roundButton(R.drawable.ic_plus, 96, R.color.accent_heat) {}
        repeatOnHold(down) { CarBridge.stepTemp(-1) }
        repeatOnHold(up) { CarBridge.stepTemp(1) }
        tRow.addView(down)
        tRow.addView(temp, kit.lp(0, -2, 1f))
        tRow.addView(up)
        tPanel.addView(tRow, kit.lp(-1, 0, 1f))
        tempGauge = SegmentedGauge(this).apply { segments = 17 }
        tPanel.addView(tempGauge, kit.lp(-1, kit.dp(14), t = 8))
        tPanel.addView(kit.row().apply {
            listOf("LO", "20°", "24°", "28°", "HI").forEachIndexed { i, s ->
                addView(kit.text(s, 15f, R.color.text_title).apply {
                    gravity = when (i) { 0 -> Gravity.START; 4 -> Gravity.END; else -> Gravity.CENTER }
                }, kit.lp(0, -2, 1f))
            }
        }, kit.lp(-1, -2, t = 6))
        controls += listOf(down, up)

        // ── Ventilation et direction ──
        val fPanel = kit.panel(getString(R.string.cl_fan))
        fanValue = kit.text("—", 72f, light = true).apply { gravity = Gravity.CENTER }
        fPanel.addView(fanValue, kit.lp(-1, 0, 1f))
        fanGauge = SegmentedGauge(this).apply { segments = VehicleState.FAN_MAX }
        val fRow = kit.row()
        val fDown = kit.roundButton(R.drawable.ic_minus, 72) { CarBridge.stepFan(-1) }
        val fUp = kit.roundButton(R.drawable.ic_plus, 72) { CarBridge.stepFan(1) }
        fRow.addView(fDown)
        fRow.addView(fanGauge, kit.lp(0, kit.dp(26), 1f, l = 16, r = 16))
        fRow.addView(fUp)
        fPanel.addView(fRow, kit.lp(-1, -2))
        fPanel.addView(kit.title(getString(R.string.cl_airflow)), kit.lp(-1, -2, t = 22))
        val dRow = kit.row()
        face = kit.toggleTile(getString(R.string.cl_face)) { toggleFlow(0) }
        feet = kit.toggleTile(getString(R.string.cl_feet)) { toggleFlow(1) }
        screen = kit.toggleTile(getString(R.string.cl_windshield)) { toggleFlow(2) }
        listOf(face, feet, screen).forEachIndexed { i, v -> dRow.addView(v, kit.lp(0, kit.dp(72), 1f, l = if (i > 0) 10 else 0)) }
        fPanel.addView(dRow, kit.lp(-1, -2, t = 10))
        controls += listOf(fDown, fUp, face, feet, screen)

        // ── Modes ──
        val mPanel = kit.panel(getString(R.string.cl_modes))
        auto = kit.toggleTile("AUTO") { CarBridge.toggleAuto() }
        ac = kit.toggleTile("A/C") { CarBridge.toggleAc() }
        eco = kit.toggleTile("ECO") { CarBridge.toggleEco() }
        recirc = kit.toggleTile(getString(R.string.cl_recirc)) { CarBridge.toggleRecirc() }
        defFront = kit.toggleTile(getString(R.string.cl_def_front), heat = true) { CarBridge.toggleDefrostFront() }
        defRear = kit.toggleTile(getString(R.string.cl_def_rear), heat = true) { CarBridge.toggleDefrostRear() }
        val grid = listOf(listOf(auto, ac), listOf(eco, recirc), listOf(defFront, defRear))
        grid.forEach { pair ->
            val r = kit.row()
            pair.forEachIndexed { i, v -> r.addView(v, kit.lp(0, -1, 1f, l = if (i > 0) 10 else 0)) }
            mPanel.addView(r, kit.lp(-1, 0, 1f, t = 10))
        }
        val origin = kit.text(getString(R.string.cl_stock), 18f, R.color.accent_action).apply {
            gravity = Gravity.CENTER; setPadding(0, kit.dp(14), 0, kit.dp(4))
            setOnClickListener { Apps.open(this@ClimateActivity, Apps.HVAC, "SAIC") }
        }
        mPanel.addView(origin, kit.lp(-1, -2))
        controls += listOf(auto, ac, eco, recirc, defFront, defRear, power)

        body.addView(tPanel, kit.lp(0, -1, 1.15f, r = 6))
        body.addView(fPanel, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(mPanel, kit.lp(0, -1, 1f, l = 6))
    }

    // Direction de l'air : table décodée par MG4Control (ce n'est pas un masque de bits).
    private fun partsOf(d: Int?): BooleanArray? = when (d) {
        0 -> booleanArrayOf(true, false, false)
        1 -> booleanArrayOf(true, true, false)
        2 -> booleanArrayOf(false, true, false)
        3 -> booleanArrayOf(false, true, true)
        4 -> booleanArrayOf(false, false, true)
        5 -> booleanArrayOf(true, true, true)
        6 -> booleanArrayOf(true, false, true)
        else -> null
    }

    private fun directionFor(p: BooleanArray): Int? = when {
        p[0] && p[1] && p[2] -> 5
        p[0] && p[2] -> 6
        p[0] && p[1] -> 1
        p[1] && p[2] -> 3
        p[0] -> 0
        p[1] -> 2
        p[2] -> 4
        else -> null
    }

    private fun toggleFlow(index: Int) {
        val parts = partsOf(CarBridge.state.value.fanDirection)?.copyOf() ?: booleanArrayOf(false, false, false)
        parts[index] = !parts[index]
        directionFor(parts)?.let { CarBridge.setFanDirection(it) }   // au moins une sortie reste active
    }

    override fun render(s: VehicleState) {
        val known = s.connected && s.hvacOn != null
        val on = s.hvacOn == true
        controls.forEach { it.isEnabled = known }
        power.isSelected = on
        power.setColorFilter(kit.color(if (on) R.color.accent_action else R.color.text_sub))
        outside.text = s.outsideTemp?.let { getString(R.string.cl_outside, Math.round(it)) } ?: ""
        temp.text = VehicleState.tempLabel(s.driverTemp)
        temp.setTextColor(kit.color(if (on) R.color.text_value else R.color.text_disabled))
        val t = s.driverTemp
        tempGauge.onColor = kit.color(if (t != null && t >= 23f) R.color.accent_heat else R.color.accent_cold)
        tempGauge.value = when {
            t == null -> 0
            t <= VehicleState.TEMP_MIN -> 1
            t >= VehicleState.TEMP_MAX -> 17
            else -> (Math.round(t) - 16).coerceIn(1, 17)
        }
        val autoOn = on && s.autoOn == true
        fanValue.text = when { !on -> "0"; autoOn -> "AUTO"; else -> s.fanSpeed?.toString() ?: "—" }
        fanValue.setTextColor(kit.color(when { !on -> R.color.text_disabled; autoOn -> R.color.accent_cold; else -> R.color.text_value }))
        fanGauge.onColor = kit.color(if (autoOn) R.color.text_disabled else R.color.accent_cold)
        fanGauge.value = if (on && !autoOn) s.fanSpeed ?: 0 else 0
        val parts = partsOf(s.fanDirection)
        face.isSelected = parts?.get(0) == true
        feet.isSelected = parts?.get(1) == true
        screen.isSelected = parts?.get(2) == true
        auto.isSelected = s.autoOn == true
        ac.isSelected = s.acOn == true
        eco.isSelected = s.ecoOn == true
        recirc.isSelected = s.recirc == 2   // surligné en mode auto, comme sur l'accueil
        recirc.text = when (s.recirc) { 0 -> getString(R.string.cl_air_recirc); 1 -> getString(R.string.cl_air_outside); 2 -> getString(R.string.cl_air_auto); else -> getString(R.string.cl_recirc) }
        defFront.isSelected = s.defrostFront == true
        defRear.isSelected = s.defrostRear == true
    }

    private fun repeatOnHold(view: View, action: () -> Unit) {
        val repeat = object : Runnable { override fun run() { action(); handler.postDelayed(this, 350) } }
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> if (v.isEnabled) { v.isPressed = true; action(); handler.postDelayed(repeat, 500) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
    }
}
