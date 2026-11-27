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

/**
 * The Vehicle page's centre (redesign "Showroom", 26/11): the driver's car, in three-quarter view or in profile with
 * its mirror image, the tyre pressures hung under the wheels. A touch on the car changes the view, a long press opens
 * "Colours and style".
 */
@SuppressLint("ViewConstructor")
class CarStage(context: Context, private val kit: Kit, private val look: CarLook, private val onStyle: () -> Unit) : FrameLayout(context) {
    val car = CarImage(context)
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

    inner class TyreLabel(t: String) {
        val view: LinearLayout = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val left = side(); val right = side()
        init {
            view.addView(kit.text(t.uppercase(), 12f, R.color.text_title).apply { letterSpacing = .1f })
            val r = kit.row()
            r.addView(left.first, kit.lp(-2, -2))
            r.addView(View(context).apply { setBackgroundColor(Themes.color(context, R.color.panel_border)) }, kit.lp(1, kit.dp(38), l = 16, r = 16))
            r.addView(right.first, kit.lp(-2, -2))
            view.addView(r, kit.lp(-2, -2, t = 5))
        }
        private fun side(): Triple<LinearLayout, TextView, TextView> {
            val col = kit.column().apply { gravity = Gravity.CENTER_HORIZONTAL }
            val p = kit.text("—", 28f, light = true)
            val s = kit.text("", 20f, R.color.text_sub)   // side and temperature, readable at a glance (26/11)
            col.addView(p); col.addView(s)
            return Triple(col, p, s)
        }
        fun set(leftSide: Boolean, pressure: String, sub: String, alert: Boolean) {
            val (_, p, s) = if (leftSide) left else right
            p.text = pressure; s.text = sub
            val c = Themes.color(context, if (alert) R.color.accent_danger else R.color.text_value)
            p.setTextColor(c); s.setTextColor(if (alert) c else Themes.color(context, R.color.text_sub))
        }
    }

    /** A pulsing red ring around the wheels of an axle whose tyre needs checking (the sensor reports a warning). */
    inner class Ring : View(context) {
        var on = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
        private val red = Themes.color(context, R.color.accent_danger)
        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(c: android.graphics.Canvas) {
            if (!on) return
            val t = (android.os.SystemClock.uptimeMillis() % 1600L) / 1600f
            val a = .55f + .45f * kotlin.math.cos(t * 2 * Math.PI).toFloat()
            val inset = kit.dp(12).toFloat()
            p.color = red; p.alpha = (a * 110).toInt(); p.strokeWidth = kit.dp(12).toFloat(); p.maskFilter = android.graphics.BlurMaskFilter(kit.dp(10).toFloat(), android.graphics.BlurMaskFilter.Blur.NORMAL)
            c.drawOval(inset, inset, width - inset, height - inset, p)
            p.maskFilter = null; p.alpha = (a * 255).toInt(); p.strokeWidth = kit.dp(3).toFloat()
            c.drawOval(inset, inset, width - inset, height - inset, p)
            postInvalidateDelayed(40)
        }
    }

    val frontRing = Ring()
    val rearRing = Ring()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = car.box.contains(e.x, e.y)
        override fun onSingleTapUp(e: MotionEvent): Boolean { look.threeQuarter = !look.threeQuarter; refresh(); return true }
        override fun onLongPress(e: MotionEvent) { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); onStyle() }
    })

    init {
        clipChildren = true
        addView(car, LayoutParams(-1, -1))
        addView(frontRing, LayoutParams(0, 0)); addView(rearRing, LayoutParams(0, 0))
        head.addView(title); head.addView(name, kit.lp(-2, -2, t = 5))
        addView(head, LayoutParams(-1, -2))
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
        val s = if (height > 0 && sh > 0f) minOf(sw, sh) else sw
        val dy = if (height > 0) ((height - below - s * (groundAt1 + 40f)) / 2f).coerceAtLeast(0f) else 0f
        // Placed as in the mock-up (a 956 px wide stage): the three-quarter car is bigger and goes beyond the sides
        val cw = if (q) 1196f * s else 952f * s
        val k = cw / m.w
        val cx = if (q) (w - 1606f * k) / 2 else (w - cw) / 2
        val top = (if (q) 42f * s - 78f * k else 58f * s) + dy
        car.box.set(cx, top, cx + cw, top + cw * m.h / m.w)
        val fx = cx + pt("front", "c", 0) * k
        val rx = minOf(w - 104f * s, cx + pt("rear", "c", 0) * k)
        val gF = top + pt("front", "ground", 1) * k
        val gR = top + pt("rear", "ground", 1) * k
        car.groundY = gF
        // The rings around the wheels (front and rear centres, radii of car.json)
        listOf(frontRing to "front", rearRing to "rear").forEach { (ring, key) ->
            val cxw = cx + pt(key, "c", 0) * k; val cyw = top + pt(key, "c", 1) * k
            val rx = an.getJSONObject(key).getDouble("rx").toFloat() * k + kit.dp(21); val ry = an.getJSONObject(key).getDouble("ry").toFloat() * k + kit.dp(21)
            ring.layoutParams = (ring.layoutParams as LayoutParams).apply { width = (2 * rx).toInt(); height = (2 * ry).toInt() }
            ring.translationX = cxw - rx; ring.translationY = cyw - ry
        }
        car.reflection = !q
        car.floorY = if (q) gR + 65f * s else gF - 2f * s
        car.floorH = if (q) 170f * s else 120f * s
        car.show(look.config(view), cw.toInt())
        // Title: left in three-quarter view (the car's roof is on the right), centred in profile
        head.gravity = if (q) Gravity.START else Gravity.CENTER_HORIZONTAL
        title.gravity = head.gravity   // the title fills the column: its text is placed like the name
        (head.layoutParams as LayoutParams).apply { gravity = if (q) Gravity.START or Gravity.TOP else Gravity.CENTER_HORIZONTAL or Gravity.TOP; topMargin = kit.dp(4); leftMargin = if (q) kit.dp(8) else 0 }
        listOf(front.view to (fx to gF), rear.view to (rx to gR)).forEach { (v, p) ->
            v.post { v.translationX = (p.first - v.width / 2f).coerceIn(0f, (w - v.width).coerceAtLeast(0f)); v.translationY = p.second + 40f * s - kit.dp(16) }
        }
        car.invalidate()
    }
}
