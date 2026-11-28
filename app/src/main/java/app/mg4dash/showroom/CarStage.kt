package app.mg4dash.showroom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes
import app.mg4dash.ui.put

/**
 * The Vehicle page's centre (redesign "Showroom", 26/11): the driver's car, in three-quarter view or in profile with
 * its mirror image, the tyre pressures hung under the wheels. A touch on the car changes the view, a long press opens
 * "Colours and style".
 */
@SuppressLint("ViewConstructor")
class CarStage(context: Context, private val kit: Kit, private val look: CarLook, private val onStyle: () -> Unit) : FrameLayout(context) {
    val car = CarImage(context)
    /** The lamps over the car (27/11): daytime strips always, lit with the lights on in the dark theme. */
    val lights = HeadLights(context).apply { dark = !app.mg4dash.ui.Themes.isLight(context) }
    /** The gear engaged, top right (27/11), with the parking brake telltale in P; hidden while unreadable. */
    private val gearView = GearView(context, kit)
    private val gearBox = kit.column().apply {
        gravity = Gravity.END; visibility = View.GONE
        addView(kit.text(context.getString(R.string.sr_gear).uppercase(), 15f, R.color.text_title).apply { letterSpacing = .1f; isSingleLine = true },
            kit.lp(-2, -2).apply { gravity = Gravity.END })
        addView(gearView, kit.lp(-2, -2, t = 2))
    }

    /** The gear read (1 = P, 2 = R, 3 = N, 4 = D); anything else or null hides the block. */
    fun showGear(raw: Int?) {
        val g = raw?.takeIf { it in 1..4 }
        gearView.gear = g
        gearBox.visibility = if (g != null) View.VISIBLE else View.GONE
    }
    private val title = kit.text("", 15f, R.color.text_title).apply { letterSpacing = .1f; isSingleLine = true }
    private val name = kit.text("", 20f, R.color.text_sub).apply { isSingleLine = true }
    private val head = kit.column()
    val front = TyreLabel(context.getString(R.string.sr_front_tyres))
    val rear = TyreLabel(context.getString(R.string.sr_rear_tyres))
    private val hint = kit.text(context.getString(R.string.sr_touch_hint) + "   |   " + context.getString(R.string.sr_long_hint), 15f, R.color.text_sub).apply {
        // Theme colours: the light theme's text was on a background of its own colour (26/11)
        background = GradientDrawable().apply { setColor(Themes.color(context, R.color.panel_raised)); cornerRadius = kit.dp(20).toFloat(); setStroke(1, Themes.color(context, R.color.panel_border)) }
        setPadding(kit.dp(16), kit.dp(8), kit.dp(16), kit.dp(8))
        setOnClickListener { onStyle() }
    }

    private class TyreSide(val view: LinearLayout, val pressure: TextView, val detail: TextView, val status: TextView)

    inner class TyreLabel(t: String) {
        val view: LinearLayout = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        private val left = side()
        private val right = side()
        private var wheelX = 0f

        init {
            view.addView(kit.text(t.uppercase(), 12f, R.color.text_title).apply { letterSpacing = .1f }, kit.lp(-2, -2))
            val r = kit.row()
            r.addView(left.view, kit.lp(-2, -2))
            r.addView(View(context).apply { setBackgroundColor(Themes.color(context, R.color.panel_border)) }, kit.lp(1, kit.dp(38), l = 16, r = 16))
            r.addView(right.view, kit.lp(-2, -2))
            view.addView(r, kit.lp(-2, -2, t = 5))
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> alignUnderWheel() }
        }

        private fun side(): TyreSide {
            val col = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val pressure = kit.text("—", 28f, light = true)
            val detail = kit.text("", 20f, R.color.text_sub)
            val status = kit.text("", 15f).apply { minLines = 1; visibility = View.INVISIBLE }
            // Each reading contributes its own width even when the status is empty.
            col.addView(pressure, kit.lp(-2, -2))
            col.addView(detail, kit.lp(-2, -2))
            col.addView(status, kit.lp(-2, -2, t = 2))
            return TyreSide(col, pressure, detail, status)
        }

        internal fun set(leftSide: Boolean, pressure: String, detail: String, status: TyreStatus) {
            val tyre = if (leftSide) left else right
            tyre.pressure.put(pressure)
            tyre.detail.put(detail)
            tyre.status.put(status.label?.let(context::getString))
            tyre.status.visibility = if (status.label == null) View.INVISIBLE else View.VISIBLE
            val light = Themes.isLight(context)
            val color = when (status) {
                TyreStatus.NORMAL -> Themes.color(context, R.color.text_value)
                TyreStatus.WATCH -> if (light) 0xFF855300.toInt() else 0xFFFFC070.toInt()
                TyreStatus.LOW_PRESSURE, TyreStatus.SENSOR_WARNING ->
                    if (light) 0xFFAB2438.toInt() else Themes.color(context, R.color.accent_danger)
            }
            tyre.pressure.setTextColor(color)
            tyre.status.setTextColor(color)
        }

        fun place(x: Float, y: Float) {
            wheelX = x
            view.translationY = y
            alignUnderWheel()
        }

        private fun alignUnderWheel() {
            val maxX = (this@CarStage.width - view.width).coerceAtLeast(0).toFloat()
            view.translationX = (wheelX - view.width / 2f).coerceIn(0f, maxX)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = car.box.contains(e.x, e.y)
        override fun onSingleTapUp(e: MotionEvent): Boolean { look.threeQuarter = !look.threeQuarter; refresh(); return true }
        override fun onLongPress(e: MotionEvent) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onStyle() }
    })

    init {
        clipChildren = true
        addView(car, LayoutParams(-1, -1))
        addView(lights, LayoutParams(-1, -1))
        head.addView(title); head.addView(name, kit.lp(-2, -2, t = 5))
        addView(head, LayoutParams(-1, -2))
        addView(gearBox, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = kit.dp(4); rightMargin = kit.dp(8) })
        addView(front.view, LayoutParams(-2, -2))
        addView(rear.view, LayoutParams(-2, -2))
        addView(hint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(12) })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    /** The car changed (view, paint, version): placed and painted again. */
    fun refresh() {
        title.text = "MG4 ${look.version.label}".uppercase()
        name.text = look.name(context)
        requestLayout(); place()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed) place()
    }

    private fun place() {
        val w = width.toFloat(); if (w <= 0f) return
        val q = look.threeQuarter
        val view = if (q) PaintShop.View.Q34 else PaintShop.View.SIDE
        val m = PaintShop.meta(context).getValue(view)
        val an = m.anchors
        fun pt(key: String, part: String, i: Int) = an.getJSONObject(key).getJSONArray(part).getDouble(i).toFloat()
        // Sized by the width, and by the height: with the bottom dock the stage is shorter and the tyre
        // pressures ran under the hint (#141). Everything down to the tyres' top is proportional to s.
        val sw = w / 956f
        val unit = if (q) 1196f / m.w else 952f / m.w
        val groundAt1 = (if (q) 42f - 78f * unit else 58f) + maxOf(pt("front", "ground", 1), pt("rear", "ground", 1)) * unit
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        front.view.measure(unspec, unspec); hint.measure(unspec, unspec)
        val below = front.view.measuredHeight - kit.dp(16) + kit.dp(8) + hint.measuredHeight + kit.dp(12)
        // As with the bottom dock whatever the bar (Marc 27/11); with the side bar the car comes down by half the rest
        val sh = (height - below - app.mg4dash.ui.Dock.bottomRoomPx(context)) / (groundAt1 + 40f)
        val s = app.mg4dash.ui.Dock.CAR_SCALE * if (height > 0 && sh > 0f) minOf(sw, sh) else sw
        val dy = if (height > 0) ((height - below - s * (groundAt1 + 40f)) / 2f).coerceAtLeast(0f) else 0f
        // Placed as in the mock-up (a 956 px wide stage): the three-quarter car is bigger and goes beyond the sides
        val cw = if (q) 1196f * s else 952f * s
        val k = cw / m.w
        val cx = if (q) (w - 1606f * k) / 2 else (w - cw) / 2
        val top = (if (q) 42f * s - 78f * k else 58f * s) + dy
        car.box.set(cx, top, cx + cw, top + cw * m.h / m.w)
        lights.box.set(car.box); lights.threeQuarter = q; lights.invalidate()
        val fx = cx + pt("front", "c", 0) * k
        val rx = minOf(w - 104f * s, cx + pt("rear", "c", 0) * k)
        val gF = top + pt("front", "ground", 1) * k
        val gR = top + pt("rear", "ground", 1) * k
        car.groundY = gF
        car.reflection = !q
        car.floorY = if (q) gR + 65f * s else gF - 2f * s
        car.floorH = if (q) 170f * s else 120f * s
        car.show(look.config(view), cw.toInt())
        // Title: left in three-quarter view (the car's roof is on the right), centred in profile
        head.gravity = if (q) Gravity.START else Gravity.CENTER_HORIZONTAL
        title.gravity = head.gravity   // the title fills the column: its text is placed like the name
        (head.layoutParams as LayoutParams).apply { gravity = if (q) Gravity.START or Gravity.TOP else Gravity.CENTER_HORIZONTAL or Gravity.TOP; topMargin = kit.dp(4); leftMargin = if (q) kit.dp(8) else 0 }
        val labelOffset = 40f * s - kit.dp(16)
        front.place(fx, gF + labelOffset)
        rear.place(rx, gR + labelOffset)
        car.invalidate()
    }
}
