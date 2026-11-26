package app.mg4dash.pages

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.VehicleState
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.showroom.CarLook
import app.mg4dash.showroom.CarStage
import app.mg4dash.showroom.MiniSwitch
import app.mg4dash.showroom.ShowroomKit
import app.mg4dash.showroom.StyleWindow
import app.mg4dash.system.Apps
import app.mg4dash.system.Units
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge
import app.mg4dash.ui.TripText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Vehicle page, redesign "Showroom" (26/11, v4 mock-up): the driver's car in the centre (touch: other view, long
 * press: colours and style), the driving settings on the left, set from here, and the car's state on the right.
 */
class VehicleActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_vehicle)
    override val page = app.mg4dash.ui.Dock.Page.VEHICLE

    private lateinit var sk: ShowroomKit
    private lateinit var look: CarLook
    private lateinit var stage: CarStage
    private lateinit var showMode: (Int?) -> Unit
    private lateinit var steering: ShowroomKit.Tile
    private lateinit var brake: ShowroomKit.Tile
    private lateinit var customRow: LinearLayout
    private lateinit var regenValue: TextView
    private lateinit var regenGauge: SegmentedGauge
    private lateinit var onePedal: MiniSwitch
    private var savingSwitch: MiniSwitch? = null
    private var savingText: TextView? = null
    private lateinit var savingRow: View
    private lateinit var lane: ShowroomKit.Tile
    private lateinit var aeb: ShowroomKit.Tile
    private lateinit var range: TextView
    private lateinit var soc: TextView
    private lateinit var socGauge: SegmentedGauge
    private lateinit var cons: TextView
    private lateinit var todayKm: TextView
    private lateinit var todayCount: TextView
    private lateinit var todayLast: TextView
    private lateinit var odo: ShowroomKit.Tile
    private lateinit var speed: ShowroomKit.Tile
    private lateinit var outside: ShowroomKit.Tile
    private lateinit var charge: ShowroomKit.Tile
    private lateinit var cpu: Pair<TextView, android.widget.ProgressBar>
    private lateinit var ram: Pair<TextView, android.widget.ProgressBar>
    private var last = VehicleState()

    private val modes = listOf(2 to R.string.dm_eco, 3 to R.string.dm_normal, 4 to R.string.dm_sport, 6 to R.string.dm_snow, 7 to R.string.dm_custom)

    override fun buildBody() {
        sk = ShowroomKit(this, kit)
        look = CarLook(this)
        header.addView(headButton(R.string.vh_sound) { startActivity(android.content.Intent(this, SoundActivity::class.java)) }, kit.lp(-2, -2, r = 12))
        header.addView(headButton(R.string.vh_saic) { Apps.open(this, Apps.stock(this, Apps.VEHICLE, Apps.VEHICLE_HMI), "SAIC") }, kit.lp(-2, -2, r = 12))
        header.addView(kit.text(getString(R.string.vh_mg4control), 18f, R.color.on_action).apply {
            setPadding(kit.dp(20), kit.dp(12), kit.dp(20), kit.dp(12))
            setBackgroundResource(R.drawable.bg_aa_tile)
            setOnClickListener { Apps.open(this@VehicleActivity, Apps.MG4_CONTROL, "MG4 Control") }
        })

        body.addView(left(), kit.lp(kit.dp(412), -1))
        stage = CarStage(this, kit, look) { StyleWindow.show(this, look) { stage.refresh() } }
        body.addView(stage, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(right(), kit.lp(kit.dp(412), -1))
        stage.refresh()

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch { while (true) { refreshTires(); renderTrips(); kotlinx.coroutines.delay(5_000) } }
                while (true) {
                    val (c, m) = withContext(Dispatchers.IO) { app.mg4dash.system.HeadUnitLoad.cpuPercent() to app.mg4dash.system.HeadUnitLoad.memoryMb() }
                    cpu.first.text = c?.let { "$it %" } ?: "—"; cpu.second.progress = c ?: 0
                    ram.first.text = m?.let { (u, t) -> String.format(Locale.getDefault(), "%,d / %,d %s", u, t, getString(R.string.unit_mb)) } ?: "—"
                    ram.second.progress = m?.let { (u, t) -> if (t > 0) u * 100 / t else 0 } ?: 0
                    kotlinx.coroutines.delay(2_000)
                }
            }
        }
    }

    private fun headButton(label: Int, onClick: () -> Unit) = kit.text(getString(label), 19f, R.color.text_sub).apply {
        setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        setBackgroundResource(R.drawable.bg_button)
        setOnClickListener { onClick() }
    }

    // ── Left: driving, set from here ──
    private fun left(): LinearLayout {
        val col = kit.column()
        val drive = sk.card()
        drive.addView(sk.label(getString(R.string.vh_drive_mode)))
        val (seg, show) = sk.segments(modes.map { getString(it.second) }) { i -> CarBridge.setDriveMode(modes[i].first) }
        showMode = show
        drive.addView(seg, kit.lp(-1, -2, t = 12))
        customRow = kit.row()
        steering = sk.Tile().apply { title.text = getString(R.string.vh_steering); view.setOnClickListener { cycleCustom(true) } }
        brake = sk.Tile().apply { title.text = getString(R.string.vh_brake); view.setOnClickListener { cycleCustom(false) } }
        customRow.addView(steering.view, kit.lp(0, -2, 1f, r = 5))
        customRow.addView(brake.view, kit.lp(0, -2, 1f, l = 5))
        drive.addView(customRow, kit.lp(-1, -2, t = 10))
        col.addView(drive, kit.lp(-1, -2))

        val regen = sk.card()
        val head = kit.row()
        head.addView(sk.label(getString(R.string.vh_regen).substringBefore(" (")), kit.lp(0, -2, 1f))
        regenValue = kit.text("—", 19f, R.color.accent_energy)
        head.addView(regenValue)
        regen.addView(head, kit.lp(-1, -2))
        regenGauge = SegmentedGauge(this).apply {
            segments = 4; onColor = kit.color(R.color.accent_energy)
            setPadding(0, kit.dp(6), 0, kit.dp(6))
            onSegmentTap = { CarBridge.setRegenLevel(it) }
        }
        regen.addView(regenGauge, kit.lp(-1, kit.dp(36), t = 10))
        val (pRow, p) = sk.switchRow(getString(R.string.vh_one_pedal)) { CarBridge.toggleOnePedal() }
        onePedal = p
        regen.addView(pRow, kit.lp(-1, -2, t = 6))
        // Energy saving (Range+): a switch where the car takes the command (MG4Control's), a plain value elsewhere
        val sRow: LinearLayout
        if (app.mg4dash.car.DriveSettings.enduranceSettable) {
            val (r, sw) = sk.switchRow(getString(R.string.vh_endurance)) { CarBridge.setEndurance(last.enduranceMode != 1) }
            sRow = r; savingSwitch = sw
        } else {
            sRow = kit.row().apply { minimumHeight = kit.dp(40) }
            sRow.addView(kit.text(getString(R.string.vh_endurance), 18f, R.color.text_sub), kit.lp(0, -2, 1f))
            savingText = kit.text("—", 18f).also { sRow.addView(it) }
        }
        savingRow = sRow
        regen.addView(sRow, kit.lp(-1, -2))
        col.addView(regen, kit.lp(-1, -2, t = 10))

        col.addView(sk.label(getString(R.string.vh_adas)), kit.lp(-1, -2, l = 4, t = 12))
        val grid = kit.row().apply { isBaselineAligned = false }
        lane = sk.Tile().apply { title.text = getString(R.string.vh_lane); view.setOnClickListener { nextLane() } }
        aeb = sk.Tile().apply { title.text = getString(R.string.vh_aeb); view.setOnClickListener { nextAeb() } }
        grid.addView(lane.view, kit.lp(0, -1, 1f, r = 5))
        grid.addView(aeb.view, kit.lp(0, -1, 1f, l = 5))
        col.addView(grid, kit.lp(-1, 0, 1f, t = 8))
        return col
    }

    private fun cycleCustom(steer: Boolean) {
        if (last.driveMode != 7) return
        if (steer) {
            // 1 comfort, 2 normal, 3 sport → index 0..2
            val i = when (last.steeringMode) { 1 -> 0; 2 -> 1; 3 -> 2; else -> 1 }
            CarBridge.setSteering((i + 1) % 3)
        } else {
            // 1 comfort, 0 normal, 2 sport
            val i = when (last.brakePedalMode) { 1 -> 0; 0 -> 1; 2 -> 2; else -> 1 }
            CarBridge.setBrakePedal((i + 1) % 3)
        }
    }

    /** Off → warning → assist → emergency → off. */
    private fun nextLane() {
        val order = listOf(1, 2, 3, 5)
        CarBridge.setLaneMode(order[(order.indexOf(last.lasMode ?: 1) + 1).mod(order.size)])
    }

    /** Off → warning → warning + braking → off. */
    private fun nextAeb() {
        val cur = when { last.fcwState == 1 -> 0; last.fcwBrakeMode == 2 -> 2; last.fcwState == 2 -> 1; else -> 0 }
        CarBridge.setFcw((cur + 1) % 3)
    }

    // ── Right: the car's state ──
    private fun right(): LinearLayout {
        val col = kit.column()
        val r = sk.card()
        r.addView(sk.label(getString(R.string.vh_range)))
        val big = kit.row().apply { gravity = android.view.Gravity.BOTTOM }
        range = kit.text("—", 52f, light = true)
        soc = kit.text("", 28f, R.color.accent_energy, light = true)
        big.addView(range, kit.lp(0, -2, 1f)); big.addView(soc)
        r.addView(big, kit.lp(-1, -2, t = 6))
        socGauge = SegmentedGauge(this).apply { segments = 20; onColor = kit.color(R.color.accent_energy); isClickable = false }
        r.addView(socGauge, kit.lp(-1, kit.dp(16), t = 10))
        val cr = kit.row()
        cr.addView(kit.text(getString(R.string.vh_avg_cons), 16f, R.color.text_sub), kit.lp(0, -2, 1f))
        cons = kit.text("—", 16f)
        cr.addView(cons)
        r.addView(cr, kit.lp(-1, -2, t = 10))
        col.addView(r, kit.lp(-1, -2))

        val today = sk.card().apply { setOnClickListener { startActivity(android.content.Intent(this@VehicleActivity, TripsActivity::class.java)) } }
        val th = kit.row()
        th.addView(sk.label(getString(R.string.tr_today)), kit.lp(0, -2, 1f))
        th.addView(kit.text(getString(R.string.sr_trips), 16f, R.color.accent_action))
        today.addView(th, kit.lp(-1, -2))
        val tr = kit.row()
        todayKm = kit.text("—", 36f, light = true)
        val tRight = kit.column().apply { gravity = android.view.Gravity.END }
        todayCount = kit.text("", 17f); todayLast = kit.text("", 14f, R.color.text_sub)
        tRight.addView(todayCount); tRight.addView(todayLast, kit.lp(-2, -2, t = 3))
        tr.addView(todayKm, kit.lp(0, -2, 1f)); tr.addView(tRight)
        today.addView(tr, kit.lp(-1, -2, t = 6))
        col.addView(today, kit.lp(-1, -2, t = 10))

        odo = sk.Tile(true).apply { title.text = getString(R.string.vh_odometer) }
        speed = sk.Tile(true).apply { title.text = getString(R.string.vh_speed) }
        outside = sk.Tile(true).apply { title.text = getString(R.string.vh_outside) }
        charge = sk.Tile(true).apply { title.text = getString(R.string.vh_charge) }
        listOf(odo to speed, outside to charge).forEachIndexed { i, (a, b) ->
            val row = kit.row().apply { isBaselineAligned = false }
            row.addView(a.view, kit.lp(0, -2, 1f, r = 5)); row.addView(b.view, kit.lp(0, -2, 1f, l = 5))
            col.addView(row, kit.lp(-1, -2, t = if (i == 0) 10 else 8))
        }

        val unit = sk.card(padV = 12).apply { setOnClickListener { app.mg4dash.ui.ProcessDialog.show(this@VehicleActivity) } }
        val uh = kit.row()
        uh.addView(sk.label(getString(R.string.vh_head_unit)), kit.lp(0, -2, 1f))
        uh.addView(kit.text(getString(R.string.ps_hint), 15f, R.color.accent_action))
        unit.addView(uh, kit.lp(-1, -2))
        cpu = bar(unit, getString(R.string.vh_cpu))
        ram = bar(unit, getString(R.string.vh_ram))
        col.addView(unit, kit.lp(-1, 0, 1f, t = 10))
        return col
    }

    private fun bar(parent: LinearLayout, label: String): Pair<TextView, android.widget.ProgressBar> {
        val row = kit.row()
        row.addView(kit.text(label, 15f, R.color.text_sub), kit.lp(kit.dp(96), -2))
        val pb = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(kit.color(R.color.accent_action))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(kit.color(R.color.gauge_off))
        }
        row.addView(pb, kit.lp(0, kit.dp(10), 1f, l = 8, r = 12))
        val v = kit.text("—", 15f).apply { gravity = android.view.Gravity.END }
        row.addView(v, kit.lp(kit.dp(100), -2))
        parent.addView(row, kit.lp(-1, -2, t = 8))
        return v to pb
    }

    private fun renderTrips(now: Long = System.currentTimeMillis()) {
        if (!::todayKm.isInitialized) return
        val trips = app.mg4dash.energy.TripLog.trips.value
        val day = app.mg4dash.energy.TripLog.today(trips, app.mg4dash.energy.TripLog.current.value, now)
        todayKm.text = TripText.dist(this, day.km)
        todayCount.text = TripText.count(this, day.trips)
        todayLast.text = trips.firstOrNull()?.let { getString(R.string.tr_last, TripText.span(this, it)) } ?: getString(R.string.tr_none)
    }

    /** Pressure (kPa, shown in bar), temperature (°C) and sensor state: FL, FR, RL, RR. */
    private suspend fun refreshTires() {
        val data = withContext(Dispatchers.IO) {
            (0..3).map { i -> Triple(CarProps.getInt(0x21401553 + i), CarProps.getInt(0x2140155b + i), CarProps.getInt(0x21401557 + i)) }
        }
        data.forEachIndexed { i, (kpa, temp, state) ->
            val alert = state != null && state != 0
            val p = kpa?.takeIf { it in 50..600 }?.let { Units.pressureText(this, it) } ?: "—"
            val side = getString(if (i % 2 == 0) R.string.sr_left else R.string.sr_right)
            val sub = listOfNotNull(side, if (alert) getString(R.string.vh_tire_check) else temp?.takeIf { it in -40..150 }?.let { "$it °C" }).joinToString(" · ")
            (if (i < 2) stage.front else stage.rear).set(i % 2 == 0, p, sub, alert)
        }
    }

    override fun render(s: VehicleState) {
        last = s
        showMode(modes.indexOfFirst { it.first == s.driveMode }.takeIf { it >= 0 })
        val custom = s.driveMode == 7
        // Old firmwares give neither steering nor brake pedal: the row goes, no hole
        customRow.visibility = if (s.steeringMode == null && s.brakePedalMode == null) View.GONE else View.VISIBLE
        listOf(steering to VehicleState.steeringLabel(s.steeringMode), brake to VehicleState.pedalLabel(s.brakePedalMode)).forEach { (t, v) ->
            t.value.text = v; t.enabled(custom)
            t.subText(getString(if (custom) R.string.sr_tap_change else R.string.sr_custom_only))
        }
        regenGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        regenValue.text = VehicleState.regenLabel(s.regenLevel, s.onePedal)
        onePedal.on = s.onePedal == 1
        savingRow.visibility = if (s.enduranceMode == null) View.GONE else View.VISIBLE
        savingSwitch?.on = s.enduranceMode == 1
        savingText?.text = VehicleState.onOff(s.enduranceMode)
        lane.value.text = VehicleState.lasLabel(s.lasMode); lane.dot(s.lasMode?.let { it != 1 })
        aeb.value.text = VehicleState.aebLabel(s.fcwState, s.fcwBrakeMode); aeb.dot(s.fcwState?.let { it == 2 })
        range.text = s.rangeKm?.let { Units.distText(this, it.toFloat()) } ?: "—"
        soc.text = VehicleState.socLabel(s) ?: ""
        socGauge.value = s.batteryPercent?.let { Math.round(it / 5f) } ?: 0
        cons.text = ConsumptionEstimator.average.value?.let { "${Units.consText(this, it)} ${Units.consUnit(this)}" } ?: "—"
        odo.value.text = s.odometerKm?.let { String.format(Locale.getDefault(), "%,d %s", Math.round(Units.dist(this, it.toFloat())), Units.distUnit(this)) } ?: "—"
        speed.value.text = s.speedKmh?.let { Units.speedText(this, it) } ?: "—"
        outside.value.text = s.outsideTemp?.let { "${Math.round(it)} °C" } ?: "—"
        charge.value.text = VehicleState.chargeStatusLabel(s.chargeStatus)
    }
}
