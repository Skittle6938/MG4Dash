package app.mg4dash.pages

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Apps
import app.mg4dash.ui.PageActivity
import app.mg4dash.ui.SegmentedGauge

/** Climate page: temperature, fan, air direction and modes. */
class ClimateActivity : PageActivity() {
    private companion object {
        /** Height of the temperature and fan bars (same as the climate card's), and the touch margin above and below. */
        const val BAR_DP = 18
        const val BAR_TOUCH = 12
    }

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
    private lateinit var defLink: TextView
    private lateinit var ecoLink: TextView
    private lateinit var ac: TextView
    private lateinit var eco: TextView
    private lateinit var recirc: TextView
    private lateinit var defFront: TextView
    private lateinit var defRear: TextView
    private val controls = mutableListOf<View>()

    override fun buildBody() {
        // Header: outside temperature + on/off
        outside = kit.text("", 18f, R.color.text_sub)
        header.addView(outside, kit.lp(-2, -2, r = 20))
        power = kit.roundButton(R.drawable.ic_power, 56) { CarBridge.togglePower() }
            .apply { setOnLongClickListener { if (app.mg4dash.system.Prefs(context).climateHoldEnabled) { app.mg4dash.climate.ClimateHoldPanel.show(context as android.app.Activity); true } else false } }
        header.addView(power)

        // ── Redesign "Showroom" (26/11): the real dashboard, the air streams from its outlets, glass panels on it ──
        val cab = object : android.widget.FrameLayout(this) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { super.onLayout(changed, l, t, r, b); if (changed) post { placeCabin() } }
        }.apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF05070B.toInt()); cornerRadius = kit.dp(22).toFloat() }
            clipToOutline = true
        }
        cabin = cab
        cabImage = android.widget.ImageView(this).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY }
        cab.addView(cabImage, android.widget.FrameLayout.LayoutParams(0, 0))
        cab.addView(android.view.View(this).apply {
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xB805070B.toInt(), 0, 0, 0, 0xB805070B.toInt())),
                android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x9405070B.toInt(), 0x5C05070B, 0, 0, 0x6B05070B))))
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        tint = android.view.View(this)
        cab.addView(tint, android.widget.FrameLayout.LayoutParams(-1, -1))
        air = app.mg4dash.showroom.AirFlow(this)
        cab.addView(air, android.widget.FrameLayout.LayoutParams(0, 0))
        // Touch zones on the picture: windscreen, face, feet
        zones = listOf(2, 0, 1).map { k -> android.view.View(this).apply { setOnClickListener { toggleFlow(k) } }.also { cab.addView(it, android.widget.FrameLayout.LayoutParams(0, 0)) } }
        screen = tag(cab, getString(R.string.cl_windshield), R.drawable.ic_flow_screen) { toggleFlow(2) }
        face = tag(cab, getString(R.string.cl_face), R.drawable.ic_flow_face) { toggleFlow(0) }
        feet = tag(cab, getString(R.string.cl_feet), R.drawable.ic_flow_feet) { toggleFlow(1) }
        rearTag = tag(cab, getString(R.string.cl_rear_window), R.drawable.ic_sc_defrost) {}.apply { isClickable = false; textSize = 15f }

        // ── Driver's glass: temperature and fan ──
        val tPanel = glass()
        tPanel.addView(label(getString(R.string.cl_driver_temp)))
        temp = kit.text("—", 76f, light = true).apply {
            gravity = Gravity.CENTER
            setOnLongClickListener { app.mg4dash.ui.TempCalibration.show(this@ClimateActivity) { render(app.mg4dash.car.CarBridge.state.value) }; true }
        }
        val tRow = kit.row().apply { gravity = Gravity.CENTER }
        val down = kit.roundButton(R.drawable.ic_minus, 72, R.color.accent_cold) {}
        val up = kit.roundButton(R.drawable.ic_plus, 72, R.color.accent_heat) {}
        repeatOnHold(down) { CarBridge.stepTemp(-1) }
        repeatOnHold(up) { CarBridge.stepTemp(1) }
        tRow.addView(down); tRow.addView(temp, kit.lp(0, -2, 1f)); tRow.addView(up)
        tPanel.addView(tRow, kit.lp(-1, -2, t = 10))
        // Touched or slid along: that set point (LO, the numbers, HI); the same bar height as the fan's, a taller touch zone
        tempGauge = SegmentedGauge(this).apply {
            segments = 17; setPadding(0, kit.dp(BAR_TOUCH), 0, kit.dp(BAR_TOUCH))
            onSegmentPick = { CarBridge.setTempSlot(it) }
        }
        tPanel.addView(tempGauge, kit.lp(-1, kit.dp(BAR_DP + 2 * BAR_TOUCH), t = 12 - BAR_TOUCH))
        tPanel.addView(kit.row().apply {
            // From the car's range (#107): 20 / 24 / 28 with 18 to 32, 18 / 22 / 26 with 16 to 30
            val f = VehicleState.tempFirst.toInt()
            listOf("LO", "${f + 2}°", "${f + 6}°", "${f + 10}°", "HI").forEachIndexed { i, s ->
                addView(kit.text(s, 14f, R.color.text_title).apply {
                    gravity = when (i) { 0 -> Gravity.START; 4 -> Gravity.END; else -> Gravity.CENTER }
                }, kit.lp(0, -2, 1f))
            }
        }, kit.lp(-1, -2, t = 4))
        tPanel.addView(android.view.View(this).apply { setBackgroundColor(0x1FFFFFFF) }, kit.lp(-1, 1, t = 14, b = 12))
        tPanel.addView(label(getString(R.string.cl_fan)))
        fanValue = kit.text("—", 56f, light = true).apply { gravity = Gravity.CENTER }
        fanGauge = SegmentedGauge(this).apply {
            segments = VehicleState.FAN_MAX; setPadding(0, kit.dp(BAR_TOUCH), 0, kit.dp(BAR_TOUCH))
            onSegmentPick = { CarBridge.setFanLevel(it + 1) }
        }
        val fRow = kit.row().apply { gravity = Gravity.CENTER }
        // As on home: − at the first notch turns the climate off, and does nothing with the climate off (#34)
        val fDown = kit.roundButton(R.drawable.ic_minus, 62) {
            val s = CarBridge.state.value
            if (s.hvacOn == true) {
                if (s.autoOn != true && (s.fanSpeed ?: 0) <= VehicleState.FAN_MIN) CarBridge.togglePower()
                else CarBridge.stepFan(-1)
            }
        }
        val fUp = kit.roundButton(R.drawable.ic_plus, 62) { CarBridge.stepFan(1) }
        fRow.addView(fDown); fRow.addView(fanValue, kit.lp(0, -2, 1f)); fRow.addView(fUp)
        tPanel.addView(fRow, kit.lp(-1, -2, t = 8))
        tPanel.addView(fanGauge, kit.lp(-1, kit.dp(BAR_DP + 2 * BAR_TOUCH), t = 10 - BAR_TOUCH))
        autoNote = kit.text("", 14f, R.color.text_title)
        tPanel.addView(autoNote, kit.lp(-1, -2, t = 6))
        controls += listOf(down, up, fDown, fUp, face, feet, screen)
        driverGlass = tPanel
        cab.addView(tPanel, android.widget.FrameLayout.LayoutParams(kit.dp(324), -2))

        // ── Passenger's glass: modes ──
        val mPanel = glass()
        mPanel.addView(label(getString(R.string.cl_modes)))
        auto = kit.toggleTile("AUTO") { CarBridge.toggleAuto() }
        app.mg4dash.climate.AutoAirVolume.attach(this, auto)
        ac = kit.toggleTile("A/C") { CarBridge.toggleAc() }
        app.mg4dash.climate.AcCycle.attach(ac)
        eco = kit.toggleTile("ECO") { CarBridge.toggleEco() }
        recirc = kit.toggleTile(getString(R.string.cl_recirc)) { CarBridge.toggleRecirc() }
        defFront = kit.toggleTile(getString(R.string.cl_def_front), heat = true) { CarBridge.toggleDefrostFront() }
        defRear = kit.toggleTile(getString(R.string.cl_def_rear), heat = true) { CarBridge.toggleDefrostRear() }
        // Stock climate settings (#65), hidden if the car does not give them
        defLink = kit.toggleTile(getString(R.string.cl_defrost_link)) { CarBridge.setDefrostLinked(CarBridge.state.value.defrostLinked != true) }
        ecoLink = kit.toggleTile(getString(R.string.cl_eco_link)) { CarBridge.setEcoLinked(CarBridge.state.value.ecoLinked != true) }
        listOf(auto, ac, eco, recirc, defFront, defRear).forEach { it.textSize = 16f }
        listOf(defLink, ecoLink).forEach { it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f) }
        val grid = listOf(listOf(auto, ac), listOf(eco, recirc), listOf(defFront, defRear), listOf(defLink, ecoLink))
        grid.forEach { pair ->
            val r = kit.row()
            pair.forEachIndexed { i, v -> r.addView(v, kit.lp(0, -1, 1f, l = if (i > 0) 9 else 0)) }
            mPanel.addView(r, kit.lp(-1, 0, 1f, t = 9))
        }
        val origin = kit.text(getString(R.string.cl_stock), 17f, R.color.accent_action).apply {
            gravity = Gravity.CENTER; setPadding(0, kit.dp(11), 0, kit.dp(2))
            setOnClickListener { Apps.open(this@ClimateActivity, Apps.stock(this@ClimateActivity, Apps.HVAC, Apps.HVAC_HMI), "SAIC") }
        }
        mPanel.addView(origin, kit.lp(-1, -2))
        controls += listOf(auto, ac, eco, recirc, defFront, defRear, defLink, ecoLink, power)
        modesGlass = mPanel
        cab.addView(mPanel, android.widget.FrameLayout.LayoutParams(kit.dp(344), kit.dp(414)))

        val hint = kit.text(getString(R.string.cl_side_hint), 15f, R.color.text_title).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xB810151F.toInt()); cornerRadius = kit.dp(20).toFloat(); setStroke(1, 0x12FFFFFF) }
            setPadding(kit.dp(16), kit.dp(9), kit.dp(16), kit.dp(9))
            setOnClickListener { chooseSide() }
        }
        cab.addView(hint, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(12) })
        // Long press on the picture, out of the panels and tags: left- or right-hand drive
        cab.setOnLongClickListener { chooseSide(); true }
        zones.forEach { it.setOnLongClickListener { chooseSide(); true } }
        body.addView(cab, kit.lp(-1, -1))
        rhd = look.rhd ?: (app.mg4dash.car.CarProps.rightHandDrive() ?: app.mg4dash.system.Prefs(this).raw.getBoolean("rhd_cached", false))
    }

    private val look by lazy { app.mg4dash.showroom.CarLook(this) }
    private var rhd = false
    private lateinit var cabin: android.widget.FrameLayout
    private lateinit var cabImage: android.widget.ImageView
    private lateinit var tint: View
    private lateinit var air: app.mg4dash.showroom.AirFlow
    private lateinit var zones: List<View>
    private lateinit var rearTag: TextView
    private lateinit var autoNote: TextView
    private lateinit var driverGlass: LinearLayout
    private lateinit var modesGlass: LinearLayout
    private var cabBitmap: Pair<Boolean, android.graphics.Bitmap>? = null

    private fun label(t: String) = kit.text(t.uppercase(), 14f, R.color.text_title).apply { letterSpacing = .1f }

    /** A glass panel over the picture. */
    private fun glass() = kit.column().apply {
        background = android.graphics.drawable.GradientDrawable().apply { setColor(0xA8090C13.toInt()); cornerRadius = kit.dp(26).toFloat(); setStroke(1, 0x1FFFFFFF) }
        setPadding(kit.dp(18), kit.dp(18), kit.dp(18), kit.dp(14))
        elevation = kit.dp(10).toFloat()
    }

    /** A tag on the picture where an outlet blows: windscreen, face, feet. */
    private fun tag(parent: android.widget.FrameLayout, text: String, icon: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text; textSize = 18f; gravity = Gravity.CENTER_VERTICAL; maxLines = 1
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val d = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()
        d?.setBounds(0, 0, kit.dp(30), kit.dp(30))
        setCompoundDrawables(d, null, null, null); compoundDrawablePadding = kit.dp(10)
        setPadding(kit.dp(12), 0, kit.dp(20), 0)
        isClickable = true; setOnClickListener { onClick() }
        elevation = kit.dp(4).toFloat()
        parent.addView(this, android.widget.FrameLayout.LayoutParams(-2, kit.dp(54)))
    }

    private fun paintTag(t: TextView, on: Boolean, color: Int) {
        val c = if (on) color else 0xFFD5DCE8.toInt()
        t.setTextColor(c)
        t.compoundDrawables[0]?.setTint(c)
        t.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xB8090C12.toInt()); cornerRadius = kit.dp(27).toFloat(); setStroke(kit.dp(2), if (on) color else 0x33FFFFFF) }
    }

    /** As in the mock-up (1808 × 644): the picture raised by 20 px, the tags at x = 1196 (mirrored in right-hand drive). */
    private fun placeCabin() {
        val w = cabin.width.toFloat(); val h = cabin.height.toFloat(); if (w <= 0f) return
        val s = w / 1808f
        fun size(v: View, x: Float, y: Float, vw: Float, vh: Float) { v.layoutParams = (v.layoutParams as android.widget.FrameLayout.LayoutParams).apply { width = vw.toInt(); height = vh.toInt() }; v.translationX = x; v.translationY = y }
        size(cabImage, 0f, -20f * s, w, 678f * s)
        size(air, 0f, 0f, w, 644f * s)
        // Windscreen, face, feet zones
        size(zones[0], 340f * s, 0f, w - 680f * s, 300f * s)
        size(zones[1], 340f * s, 392f * s, w - 680f * s, 92f * s)
        size(zones[2], 340f * s, 500f * s, w - 680f * s, 144f * s)
        fun x(v: Float) = (if (rhd) 1808f - v else v) * s
        listOf(screen to 300f, face to 446f, feet to 566f).forEach { (t, y) -> t.post { t.translationX = x(1196f) - t.width / 2f; t.translationY = y * s - t.height / 2f } }
        rearTag.post { rearTag.translationX = x(894f) - rearTag.width / 2f; rearTag.translationY = 84f * s - rearTag.height / 2f }
        driverGlass.post { driverGlass.translationX = if (rhd) w - driverGlass.width - kit.dp(16) else kit.dp(16).toFloat(); driverGlass.translationY = (h - driverGlass.height) / 2 }
        modesGlass.post { modesGlass.translationX = if (rhd) kit.dp(16).toFloat() else w - modesGlass.width - kit.dp(16); modesGlass.translationY = (h - modesGlass.height) / 2 }
        air.rhd = rhd
        loadCabin()
    }

    private fun loadCabin() {
        if (cabBitmap?.first == rhd) return
        val side = rhd
        lifecycleScope.launch {
            val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { assets.open("showroom/cabin_${if (side) "r" else "l"}.webp").use { android.graphics.BitmapFactory.decodeStream(it) } }
            if (side == rhd) { cabBitmap = side to b; cabImage.setImageBitmap(b) }
        }
    }

    private fun chooseSide() {
        lateinit var d: android.app.AlertDialog
        val row = kit.row()
        listOf(false to R.string.cl_lhd, true to R.string.cl_rhd).forEachIndexed { i, (r, label) ->
            row.addView(kit.toggleTile(getString(label)) { look.rhd = r; rhd = r; d.dismiss(); placeCabin() }.apply { isSelected = r == rhd }, kit.lp(0, kit.dp(60), 1f, l = if (i > 0) 10 else 0))
        }
        d = app.mg4dash.ui.Windows.show(this, getString(R.string.cl_side_title), row, widthDp = 560)
    }

    // Air direction: table decoded by MG4Control (this is not a bit mask).
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
        directionFor(parts)?.let { CarBridge.setFanDirection(it) }   // at least one outlet stays active
    }

    private var lastKnownAt = 0L

    override fun render(s: VehicleState) {
        val known = s.connected && s.hvacOn != null
        // Old SDK: an occasional empty read greyed the whole page for a moment (#47);
        // keep the previous display while the last valid read is under 10 s old
        val now = android.os.SystemClock.elapsedRealtime()
        if (known) lastKnownAt = now
        else if (lastKnownAt != 0L && now - lastKnownAt < 10_000) { app.mg4dash.system.Log.i("Clim", "empty climate reading ignored"); return }
        val on = s.hvacOn == true
        controls.forEach { it.isEnabled = known }
        power.isSelected = on
        power.setColorFilter(kit.color(if (on) R.color.accent_action else R.color.text_sub))
        // The mode as the shortcut tile shows it: heating or cooling (#122)
        val outsideText = s.outsideTemp?.let { getString(R.string.cl_outside, Math.round(it)) } ?: ""
        outside.text = app.mg4dash.ui.ShortcutTiles.climateMode(this, s)?.let { m ->
            android.text.TextUtils.concat(outsideText, if (outsideText.isEmpty()) "" else "  ·  ", app.mg4dash.ui.ShortcutTiles.modeText(this, m, outside.currentTextColor, outside.textSize.toInt()))
        } ?: outsideText
        temp.text = VehicleState.tempLabel(s.driverTemp)
        temp.setTextColor(kit.color(if (on) R.color.text_value else R.color.text_disabled))
        val t = s.driverTemp
        tempGauge.onColor = kit.color(if (t != null && t >= 23f) R.color.accent_heat else R.color.accent_cold)
        tempGauge.value = when {
            t == null -> 0
            t < VehicleState.tempFirst - 0.25f -> 1
            t > VehicleState.tempLast + 0.25f -> 17
            else -> (Math.round(t) - Math.round(VehicleState.tempFirst) + 2).coerceIn(1, 17)
        }
        val autoOn = on && s.autoOn == true
        fanValue.text = when { !on -> "0"; autoOn -> "AUTO"; else -> s.fanSpeed?.toString() ?: "—" }
        fanValue.setTextColor(kit.color(when { !on -> R.color.text_disabled; autoOn -> R.color.accent_cold; else -> R.color.text_value }))
        fanGauge.onColor = kit.color(if (autoOn) R.color.text_disabled else R.color.accent_cold)
        fanGauge.value = if (on && !autoOn) s.fanSpeed ?: 0 else 0
        val parts = partsOf(s.fanDirection)
        // Orange heats (set point above the outside), blue cools
        val heatMode = (t ?: 0f) > (s.outsideTemp ?: 99f)
        val k = kit.color(if (heatMode) R.color.accent_heat else R.color.accent_cold)
        val scr = on && (parts?.get(2) == true || s.defrostFront == true)
        paintTag(face, on && parts?.get(0) == true, k); paintTag(feet, on && parts?.get(1) == true, k); paintTag(screen, scr, k)
        rearTag.visibility = if (on && s.defrostRear == true) View.VISIBLE else View.GONE
        paintTag(rearTag, true, kit.color(R.color.accent_heat))
        air.heat = heatMode
        air.power = if (!on) 0f else if (autoOn) .55f else .2f + .8f * (s.fanSpeed ?: 0) / VehicleState.FAN_MAX
        air.face = parts?.get(0) == true; air.feet = parts?.get(1) == true; air.screen = scr; air.defrost = s.defrostFront == true
        air.invalidate()
        tint.setBackgroundColor(if (on) (k and 0x00FFFFFF) or 0x14000000 else 0)
        cabImage.colorFilter = if (on) null else android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
            setSaturation(.55f); postConcat(android.graphics.ColorMatrix().apply { setScale(.72f, .72f, .72f, 1f) })
        })
        autoNote.text = if (autoOn && s.autoAirVolume in 0..2) getString(R.string.cl_auto_flow, getString(listOf(R.string.cl_level_high, R.string.cl_level_medium, R.string.cl_level_low)[s.autoAirVolume!!])) else ""
        auto.isSelected = s.autoOn == true
        app.mg4dash.climate.AutoAirVolume.paint(auto, s.autoAirVolume)
        defLink.isSelected = s.defrostLinked == true
        ecoLink.isSelected = s.ecoLinked == true
        (defLink.parent as View).visibility = if (s.defrostLinked == null && s.ecoLinked == null) View.GONE else View.VISIBLE
        defLink.visibility = if (s.defrostLinked == null) View.INVISIBLE else View.VISIBLE
        ecoLink.visibility = if (s.ecoLinked == null) View.INVISIBLE else View.VISIBLE
        ac.isSelected = s.acOn == true
        app.mg4dash.climate.AcCycle.paint(ac)
        eco.isSelected = s.ecoOn == true
        recirc.isSelected = s.recirc == 2   // highlighted in auto mode, as on home
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
        if (closedForMaintenance) return
        handler.removeCallbacksAndMessages(null)
    }
}
