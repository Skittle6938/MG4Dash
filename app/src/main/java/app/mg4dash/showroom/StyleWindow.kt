package app.mg4dash.showroom

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes
import app.mg4dash.ui.Windows

/**
 * "Colours and style" (redesign, 26/11): opened by a long press on the car. The car big on the left, in
 * three-quarter view or in profile; on the right the version, wheels and roof, or the colour. Each choice is shown at
 * once and kept.
 */
object StyleWindow {
    fun show(a: Activity, look: CarLook, changed: () -> Unit) {
        val kit = Kit(a)
        val sk = ShowroomKit(a, kit)
        val root = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Themes.color(a, R.color.bg_bottom)) }
        var dialog: android.app.Dialog? = null

        // ── Left: the car ──
        val stage = FrameLayout(a)
        val car = CarImage(a)
        stage.addView(car, FrameLayout.LayoutParams(-1, -1))
        val head = kit.column().apply { setPadding(kit.dp(38), kit.dp(26), 0, 0) }
        val model = kit.text("", 48f, light = true)
        val name = kit.text("", 21f, R.color.text_sub)
        val wheelLine = kit.text("", 17f, R.color.text_title)
        head.addView(sk.label(a.getString(R.string.sr_my_car)))
        head.addView(model, kit.lp(-2, -2, t = 10)); head.addView(name, kit.lp(-2, -2, t = 10)); head.addView(wheelLine, kit.lp(-2, -2, t = 6))
        stage.addView(head, FrameLayout.LayoutParams(-2, -2))
        var profile = false
        val (viewSeg, showView) = sk.segments(listOf(a.getString(R.string.sr_three_quarter), a.getString(R.string.sr_profile)), 16f) { i -> profile = i == 1; place(car, stage, look, profile) }
        stage.addView(viewSeg, FrameLayout.LayoutParams(kit.dp(300), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = kit.dp(20) })
        root.addView(stage, LinearLayout.LayoutParams(0, -1, 1f))

        // ── Right: the choices ──
        val panel = kit.column().apply {
            setPadding(kit.dp(24), kit.dp(18), kit.dp(22), kit.dp(16))
            setBackgroundColor(0xB80B0E15.toInt())
        }
        val top = kit.row()
        top.addView(kit.text(a.getString(R.string.sr_style_title), 25f), kit.lp(0, -2, 1f))
        top.addView(kit.text(a.getString(R.string.sr_done), 18f, R.color.on_action).apply {
            setPadding(kit.dp(24), kit.dp(10), kit.dp(24), kit.dp(10)); setBackgroundResource(R.drawable.bg_aa_tile)
            setOnClickListener { dialog?.dismiss() }
        })
        panel.addView(top, kit.lp(-1, -2))
        val content = kit.column()
        var tab = 0
        lateinit var render: () -> Unit
        val (tabs, showTab) = sk.segments(listOf(a.getString(R.string.sr_tab_model), a.getString(R.string.sr_tab_colour))) { i -> tab = i; render() }
        panel.addView(tabs, kit.lp(-1, -2, t = 14))
        panel.addView(content, kit.lp(-1, 0, 1f, t = 16))
        val foot = kit.row()
        foot.addView(option(a, kit, a.getString(R.string.sr_stock), null, false) { look.stock(); render() }, kit.lp(kit.dp(190), kit.dp(50)))
        val finish = kit.text("", 14f, R.color.text_title).apply { gravity = Gravity.END; maxLines = 2 }
        foot.addView(finish, kit.lp(0, -2, 1f, l = 12))
        panel.addView(foot, kit.lp(-1, -2))
        root.addView(panel, LinearLayout.LayoutParams(kit.dp(760), -1))

        render = {
            showTab(tab); showView(if (profile) 1 else 0)
            model.text = "MG4 ${look.version.label}"
            name.text = look.name(a.getString(R.string.sr_satin), a.getString(R.string.sr_black_roof))
            wheelLine.text = "${a.getString(R.string.sr_wheels)} ${wheelName(a, look.wheel)} · ${wheelSub(a, look.wheel)}"
            finish.text = a.getString(when (look.paint.finish) { PaintShop.Finish.SATIN -> R.string.sr_fin_satin; PaintShop.Finish.SOLID -> R.string.sr_fin_solid; else -> R.string.sr_fin_metal })
            content.removeAllViews()
            if (tab == 0) modelTab(a, kit, sk, content, look) { render() } else colourTab(a, kit, sk, content, look) { render() }
            place(car, stage, look, profile)
            changed()
        }
        stage.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - t != ob - ot) place(car, stage, look, profile) }
        render()
        dialog = Windows.fullscreen(a, root)
    }

    /** As in the mock-up: the three-quarter car big, beyond the left edge; the profile lower, on its floor. */
    private fun place(car: CarImage, stage: View, look: CarLook, profile: Boolean) {
        val w = stage.width.toFloat(); if (w <= 0f) return
        val s = w / 1010f
        val view = if (profile) PaintShop.View.SIDE else PaintShop.View.Q34
        val m = PaintShop.meta(stage.context).getValue(view)
        val (x, y, cw) = if (profile) Triple(18f * s, 206f * s, 972f * s) else Triple(-84f * s, 104f * s, 1176f * s)
        car.box.set(x, y, x + cw, y + cw * m.h / m.w)
        val k = cw / m.w
        val ground = y + m.anchors.getJSONObject("front").getJSONArray("ground").getDouble(1).toFloat() * k
        car.groundY = ground; car.reflection = profile
        car.floorY = if (profile) 575f * s else 573f * s; car.floorH = if (profile) 150f * s else 190f * s
        car.show(look.config(view), cw.toInt())
        car.invalidate()
    }

    private fun section(kit: Kit, sk: ShowroomKit, parent: LinearLayout, title: String, top: Int): LinearLayout {
        parent.addView(sk.label(title), kit.lp(-1, -2, t = top))
        val grid = kit.row().apply { isBaselineAligned = false }
        parent.addView(grid, kit.lp(-1, -2, t = 9))
        return grid
    }

    private fun option(a: Activity, kit: Kit, title: String, sub: String?, on: Boolean, icon: View? = null, onClick: () -> Unit): LinearLayout = kit.column().apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(Themes.color(a, if (on) R.color.panel_raised else R.color.panel)); cornerRadius = kit.dp(14).toFloat()
            setStroke(kit.dp(if (on) 2 else 1), Themes.color(a, if (on) R.color.accent_action else R.color.panel_border))
        }
        setPadding(kit.dp(10), kit.dp(10), kit.dp(10), kit.dp(10))
        if (icon != null) addView(icon, kit.lp(kit.dp(84), kit.dp(84), b = 6))
        addView(kit.text(title, 18f, if (on) R.color.text_value else R.color.text_sub).apply { gravity = Gravity.CENTER })
        if (sub != null) addView(kit.text(sub, 13f, R.color.text_title).apply { gravity = Gravity.CENTER; maxLines = 2 }, kit.lp(-2, -2, t = 4))
        isClickable = true; setOnClickListener { onClick() }
    }

    private fun modelTab(a: Activity, kit: Kit, sk: ShowroomKit, parent: LinearLayout, look: CarLook, redo: () -> Unit) {
        val versions = section(kit, sk, parent, a.getString(R.string.sr_version), 0)
        CarLook.Version.values().forEachIndexed { i, v ->
            val sub = a.getString(if (v.body == 0) R.string.sr_ver_nospoiler else R.string.sr_ver_spoiler)
            versions.addView(option(a, kit, v.label, sub, look.version == v) { look.choose(v); redo() }, kit.lp(0, kit.dp(84), 1f, l = if (i > 0) 8 else 0))
        }
        val wheels = section(kit, sk, parent, a.getString(R.string.sr_wheels), 18)
        PaintShop.Wheel.values().forEachIndexed { i, w ->
            val icon = ImageView(a).apply { setImageBitmap(wheelThumb(a, w)) }
            wheels.addView(option(a, kit, wheelName(a, w), wheelSub(a, w), look.wheel == w, icon) { look.wheel = w; redo() }, kit.lp(0, -2, 1f, l = if (i > 0) 8 else 0))
        }
        val roof = section(kit, sk, parent, a.getString(R.string.sr_roof), 18)
        roof.addView(option(a, kit, a.getString(R.string.sr_roof_body), null, !look.roof) { look.roof = false; redo() }, kit.lp(kit.dp(220), kit.dp(56)))
        roof.addView(option(a, kit, a.getString(R.string.sr_roof_black), null, look.roof) { look.roof = true; redo() }, kit.lp(kit.dp(170), kit.dp(56), l = 10))
        roof.addView(kit.text(a.getString(R.string.sr_roof_note), 14f, R.color.text_title).apply { maxLines = 2 }, kit.lp(0, -2, 1f, l = 12))
    }

    private fun colourTab(a: Activity, kit: Kit, sk: ShowroomKit, parent: LinearLayout, look: CarLook, redo: () -> Unit) {
        parent.addView(sk.label(a.getString(R.string.sr_mg4_colours)))
        CarPaint.OFFICIAL.chunked(4).forEachIndexed { r, row ->
            val line = kit.row()
            row.forEachIndexed { i, p ->
                val on = look.paint.id == p.id
                val tile = kit.row().apply {
                    background = GradientDrawable().apply {
                        setColor(Themes.color(a, if (on) R.color.panel_raised else R.color.panel)); cornerRadius = kit.dp(14).toFloat()
                        setStroke(kit.dp(if (on) 2 else 1), Themes.color(a, if (on) R.color.accent_action else R.color.panel_border))
                    }
                    setPadding(kit.dp(10), kit.dp(8), kit.dp(8), kit.dp(8))
                    addView(Swatch(a, p), kit.lp(kit.dp(44), kit.dp(44), r = 10))
                    val label = kit.column()
                    label.addView(kit.text(p.name, 15f, if (on) R.color.text_value else R.color.text_sub).apply { maxLines = 2 })
                    if (p.finish == PaintShop.Finish.SATIN) label.addView(kit.text("${a.getString(R.string.sr_satin)} · XPower", 12f, R.color.text_title))
                    addView(label, kit.lp(0, -2, 1f))
                    isClickable = true; setOnClickListener { look.paint = p; redo() }
                }
                line.addView(tile, kit.lp(0, kit.dp(66), 1f, l = if (i > 0) 8 else 0))
            }
            parent.addView(line, kit.lp(-1, -2, t = if (r == 0) 9 else 8))
        }
    }

    /** A round paint sample, lit from the top left. */
    private class Swatch(a: Activity, private val p: CarPaint) : View(a) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = width / 2f
            val col = 0xFF000000.toInt() or p.hex
            val dark = blend(p.hex, 0x000000, .55f)
            paint.shader = RadialGradient(r, r * .9f, r, col, dark, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r, paint)
            val gloss = if (p.finish == PaintShop.Finish.MATTE || p.finish == PaintShop.Finish.SATIN) 0x29FFFFFF else 0x99FFFFFF.toInt()
            paint.shader = RadialGradient(r * .64f, r * .52f, r * .6f, gloss, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(r, r, r, paint)
        }
        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * t).toInt()
            return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    private fun wheelName(a: Activity, w: PaintShop.Wheel) = a.getString(when (w) {
        PaintShop.Wheel.STEEL16 -> R.string.sr_wh_steel16; PaintShop.Wheel.AERO17 -> R.string.sr_wh_aero17
        PaintShop.Wheel.LUX18 -> R.string.sr_wh_lux18; PaintShop.Wheel.XP18 -> R.string.sr_wh_xp18
    })

    private fun wheelSub(a: Activity, w: PaintShop.Wheel) = a.getString(when (w) {
        PaintShop.Wheel.STEEL16 -> R.string.sr_wh_steel16_sub; PaintShop.Wheel.AERO17 -> R.string.sr_wh_aero17_sub
        PaintShop.Wheel.LUX18 -> R.string.sr_wh_lux18_sub; PaintShop.Wheel.XP18 -> R.string.sr_wh_xp18_sub
    })

    private val thumbs = HashMap<PaintShop.Wheel, Bitmap>()

    /** The front wheel of the profile layer, cut round. */
    private fun wheelThumb(a: Activity, w: PaintShop.Wheel): Bitmap = thumbs.getOrPut(w) {
        val m = PaintShop.meta(a).getValue(PaintShop.View.SIDE)
        val box = m.wheels.getValue(w.id)
        val f = m.anchors.getJSONObject("front")
        val cx = f.getJSONArray("c").getDouble(0).toFloat(); val cy = f.getJSONArray("c").getDouble(1).toFloat()
        val r = f.getDouble("ry").toFloat() * .84f
        val src = a.assets.open("showroom/s_wh_${w.id}.webp").use { BitmapFactory.decodeStream(it) }
        val sx = src.width / box.width(); val sy = src.height / box.height()
        val size = 168
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) })
        c.drawColor(0xFF050608.toInt())
        c.drawBitmap(src, Rect(((cx - r - box.left) * sx).toInt(), ((cy - r - box.top) * sy).toInt(), ((cx + r - box.left) * sx).toInt(), ((cy + r - box.top) * sy).toInt()),
            RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        out
    }
}
