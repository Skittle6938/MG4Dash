package app.mg4dash.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.DriveSettings
import app.mg4dash.car.VehicleState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Driving" card (#38): drive mode, regeneration, Custom mode steering and brake pedal, lane keeping and front
 * collision, read and changed with MG4Control's commands ([DriveSettings]). Never locked while driving.
 * A setting the car does not answer is greyed ("not available"); steering and pedal only in Custom mode, as the
 * stock screen. Its content follows the place: [Form].
 */
class DrivingCard(context: Context) : LinearLayout(context) {
    /**
     * COMPACT: small place, mode and regeneration. MEDIUM: + steering and pedal side by side, the aids summed up.
     * FULL: big square or tall place, everything in one column. WIDE: wide band, two columns (front collision in "All").
     */
    enum class Form { COMPACT, MEDIUM, FULL, WIDE }

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun color(res: Int) = Themes.color(context, res)

    var form = Form.MEDIUM
        set(v) { if (field != v) { field = v; build() } }

    /** A row of choices and its update ([selected] index, [enabled]). */
    private class Choices(val view: LinearLayout, val chips: List<TextView>)
    private val rows = HashMap<String, Choices>()
    private val notes = HashMap<String, TextView>()
    private var summary: TextView? = null

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(16), dp(20), dp(16))
        build()
    }

    private fun tv(text: String, sp: Float, colorRes: Int, medium: Boolean = false) = TextView(context).apply {
        this.text = text; setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
        includeFontPadding = false
        if (medium) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** Section title, with a note on the right ("in Custom mode", "not available"). */
    private fun section(key: String, label: String, top: Int) = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.BOTTOM
        setPadding(0, dp(top), 0, dp(7))
        addView(tv(label.uppercase(), 14f, R.color.text_title).apply { letterSpacing = 0.1f }, LayoutParams(0, -2, 1f))
        addView(tv("", 14f, R.color.text_sub).also { notes[key] = it })
    }

    private fun choices(key: String, labels: List<String>, height: Int, sp: Float, onPick: (Int) -> Unit): LinearLayout {
        val r = LinearLayout(context).apply { orientation = HORIZONTAL }
        val chips = labels.mapIndexed { i, l ->
            tv(l, sp, R.color.text_value).apply {
                gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { if (isEnabled) onPick(i) }
            }.also { r.addView(it, LayoutParams(0, dp(height), 1f).apply { if (i > 0) leftMargin = dp(8) }) }
        }
        rows[key] = Choices(r, chips)
        return r
    }

    private fun s(res: Int) = context.getString(res)

    private fun modeRow(h: Int, sp: Float) = choices("mode", listOf(s(R.string.dm_snow), s(R.string.dm_eco), s(R.string.dm_normal), s(R.string.dm_sport), s(R.string.dm_custom)), h, sp) {
        CarBridge.setDriveMode(DriveSettings.DRIVE_MODES[it]); show()
    }
    private fun regenRow(h: Int, sp: Float) = choices("regen", listOf(s(R.string.rg_low), s(R.string.rg_medium), s(R.string.rg_high), s(R.string.rg_adaptive), s(R.string.rg_one_pedal)), h, sp) {
        if (it == 4) CarBridge.setOnePedal() else CarBridge.setRegenLevel(it); show()
    }
    private val feel get() = listOf(s(R.string.cf_comfort), s(R.string.dm_normal), s(R.string.dm_sport))
    private fun steeringRow(h: Int, sp: Float) = choices("steering", feel, h, sp) { CarBridge.setSteering(it); show() }
    private fun pedalRow(h: Int, sp: Float) = choices("pedal", feel, h, sp) { CarBridge.setBrakePedal(it); show() }
    private fun laneRow(h: Int, sp: Float) = choices("lane", listOf(s(R.string.st_off), s(R.string.alert), s(R.string.assist), s(R.string.emergency)), h, sp) {
        CarBridge.setLaneMode(DriveSettings.LANE_MODES[it]); show()
    }
    private fun fcwRow(h: Int, sp: Float) = choices("fcw", listOf(s(R.string.st_off), s(R.string.alert), s(R.string.aeb_alert_brake)), h, sp) {
        CarBridge.setFcw(it); show()
    }

    /** Title, with "All ›" (the whole card in a window) where the place cannot hold everything. */
    private fun title() = LinearLayout(context).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(tv(s(R.string.vh_driving), 17f, R.color.text_title, medium = true).apply { letterSpacing = 0.12f }, LayoutParams(0, -2, 1f))
        if (form != Form.FULL) addView(tv(s(R.string.dv_all), 18f, R.color.text_value, medium = true).apply {
            setPadding(dp(14), dp(6), 0, dp(6)); isClickable = true
            setOnClickListener { DrivingCard.openAll(context) }
        })
    }

    private fun build() {
        removeAllViews(); rows.clear(); notes.clear(); summary = null
        addView(title())
        when (form) {
            Form.COMPACT -> {
                addView(section("mode", s(R.string.dv_mode), 8)); addView(modeRow(44, 15f))
                addView(section("regen", s(R.string.dv_regen), 10)); addView(regenRow(44, 14f))
            }
            Form.MEDIUM -> {
                addView(section("mode", s(R.string.dv_mode), 10)); addView(modeRow(48, 16f))
                addView(section("regen", s(R.string.dv_regen), 12)); addView(regenRow(48, 15f))
                addView(LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    addView(column(section("steering", s(R.string.vh_steering), 12), steeringRow(44, 14f)), LayoutParams(0, -2, 1f))
                    addView(column(section("pedal", s(R.string.dv_pedal), 12), pedalRow(44, 14f)), LayoutParams(0, -2, 1f).apply { leftMargin = dp(18) })
                })
                addView(tv("", 16f, R.color.text_sub).also { summary = it }, LayoutParams(-1, -2).apply { topMargin = dp(18) })
            }
            Form.FULL -> {
                addView(section("mode", s(R.string.vh_drive_mode), 10)); addView(modeRow(54, 18f))
                addView(section("regen", s(R.string.dv_regen), 16)); addView(regenRow(54, 17f))
                addView(section("steering", s(R.string.vh_steering), 14)); addView(steeringRow(48, 17f))
                addView(section("pedal", s(R.string.vh_brake), 14)); addView(pedalRow(48, 17f))
                addView(section("lane", s(R.string.vh_lane), 14)); addView(laneRow(48, 17f))
                addView(section("fcw", s(R.string.vh_aeb), 14)); addView(fcwRow(48, 17f))
            }
            Form.WIDE -> addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(column(section("mode", s(R.string.vh_drive_mode), 10), modeRow(54, 18f), section("regen", s(R.string.dv_regen), 16), regenRow(54, 17f)),
                    LayoutParams(0, -2, 1f).apply { rightMargin = dp(26) })
                addView(column(section("steering", s(R.string.vh_steering), 10), steeringRow(48, 17f), section("pedal", s(R.string.vh_brake), 12), pedalRow(48, 17f),
                    section("lane", s(R.string.vh_lane), 12), laneRow(48, 17f)), LayoutParams(0, -2, 1f))
            })
        }
        show()
    }

    private fun column(vararg views: View) = LinearLayout(context).apply { orientation = VERTICAL; views.forEach { addView(it) } }

    /** Paints a row: [sel] highlighted, greyed if not [enabled]. */
    private fun paint(key: String, sel: Int?, enabled: Boolean, note: String = "") {
        val r = rows[key] ?: return
        notes[key]?.text = note
        r.chips.forEachIndexed { i, c ->
            val on = enabled && i == sel
            c.isEnabled = enabled
            c.setTextColor(color(if (!enabled) R.color.text_disabled else if (on) R.color.button_idle else R.color.text_value))
            c.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            c.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(color(if (on) R.color.text_value else R.color.button_idle))
                if (!on) setStroke((1.5f * d).toInt().coerceAtLeast(1), color(if (enabled) R.color.text_disabled else R.color.panel_border))
            }
        }
    }

    fun show(st: VehicleState = CarBridge.state.value) {
        val na = s(R.string.dv_unavailable)
        paint("mode", st.driveMode?.let { DriveSettings.DRIVE_MODES.indexOf(it) }?.takeIf { it >= 0 }, st.driveMode != null, if (st.driveMode == null) na else "")
        val regen = when { st.onePedal == 1 || st.regenLevel == DriveSettings.REGEN_ONE_PEDAL -> 4; st.regenLevel in 0..3 -> st.regenLevel; else -> null }
        val regenKnown = st.regenLevel != null || st.onePedal != null
        paint("regen", regen, regenKnown, if (!regenKnown) na else "")
        val custom = st.driveMode == 7
        fun feelRow(key: String, setting: DriveSettings.Custom, v: Int?) =
            paint(key, DriveSettings.customIndex(setting, v), custom && v != null, if (v == null) na else if (!custom) s(R.string.dv_custom_only) else "")
        feelRow("steering", DriveSettings.Custom.STEERING, st.steeringMode)
        feelRow("pedal", DriveSettings.Custom.PEDAL, st.brakePedalMode)
        paint("lane", st.lasMode?.let { DriveSettings.LANE_MODES.indexOf(it) }?.takeIf { it >= 0 }, st.lasMode != null, if (st.lasMode == null) na else "")
        val fcw = when (st.fcwState) { 1 -> 0; 2 -> st.fcwBrakeMode?.takeIf { it in 1..2 } ?: 1; else -> null }
        paint("fcw", fcw, st.fcwState != null, if (st.fcwState == null) na else "")
        summary?.text = listOf(s(R.string.vh_lane) + " " + VehicleState.lasLabel(st.lasMode), s(R.string.vh_aeb) + " " + VehicleState.aebLabel(st.fcwState, st.fcwBrakeMode))
            .joinToString("  ·  ")
    }

    companion object {
        /** The whole card in a window over home ("All ›" of a smaller place), following the car while open. */
        fun openAll(context: Context) {
            val card = DrivingCard(context).apply { form = Form.FULL }
            val d = context.resources.displayMetrics.density
            val dialog = android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
            val panel = android.widget.FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = FadeOutline.radius(context); setColor(Themes.color(context, R.color.panel_raised).let { it or (0xFF shl 24) })
                    setStroke((1.5f * d).toInt(), Themes.color(context, R.color.panel_border))
                }
                addView(card, android.widget.FrameLayout.LayoutParams(-1, -2))
                isClickable = true
            }
            val root = android.widget.FrameLayout(context).apply {
                setBackgroundColor(0x99000000.toInt())
                setOnClickListener { dialog.dismiss() }
                addView(panel, android.widget.FrameLayout.LayoutParams((1100 * d).toInt(), -2, Gravity.CENTER))
            }
            dialog.setContentView(root)
            val scope = kotlinx.coroutines.MainScope()
            scope.launch { CarBridge.state.collect { card.show(it) } }
            dialog.setOnDismissListener { scope.cancel() }
            dialog.show()
        }
    }
}
