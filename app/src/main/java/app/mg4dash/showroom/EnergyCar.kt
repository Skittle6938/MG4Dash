package app.mg4dash.showroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Energy page's car (redesign, 26/11): a see-through render with the battery at its real place under the floor,
 * filling with the charge. Battery heating lights it orange; the cable plugged in lights the charge port at the rear
 * left and the current flows to the battery while it charges (or back out with V2L). Drawn in the render's own
 * pixels (1840 × 680), placed at [box].
 */
class EnergyCar(context: Context) : View(context) {
    val box = RectF()
    var body = 0
        set(v) { if (field != v) { field = v; ghost = null; sized = null; load() } }
    var soc = 0f
    /** Charge limit applied by the car (0: none shown). */
    var limit = 0
    var plugged = false
    var charging = false
    var v2l = false
    var heat = false
    var limitText: (Int) -> String = { "$it %" }

    private var ghost: Bitmap? = null
    private var sized: Bitmap? = null
    /** The near side's wheels, opaque, over the see-through car: the far wheels behind them no longer show (26/11). */
    private var wheels: Bitmap? = null
    private var wheelBox: RectF? = null
    var wheel: PaintShop.Wheel = PaintShop.Wheel.AERO17
    private var scope: CoroutineScope? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val img = Paint(Paint.FILTER_BITMAP_FLAG)
    private var battery = RectF(575f, 520f, 1365f, 574f)
    private var port = floatArrayOf(1580f, 285f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)   // blurred glows
        val an = PaintShop.meta(context).getValue(PaintShop.View.SIDE).anchors
        an.optJSONArray("battery")?.let { b -> battery = RectF(b.getJSONArray(0).getDouble(0).toFloat(), b.getJSONArray(0).getDouble(1).toFloat(), b.getJSONArray(1).getDouble(0).toFloat(), b.getJSONArray(2).getDouble(1).toFloat()) }
        an.optJSONArray("port")?.let { q -> port = floatArrayOf(((q.getJSONArray(0).getDouble(0) + q.getJSONArray(1).getDouble(0)) / 2).toFloat(), ((q.getJSONArray(0).getDouble(1) + q.getJSONArray(1).getDouble(1)) / 2).toFloat()) }
    }

    /** The battery's centre and top in view pixels. */
    fun batteryTop(): Pair<Float, Float> { val k = box.width() / 1840f; return (box.left + battery.centerX() * k) to (box.top + battery.top * k) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main); load() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); scope?.cancel(); scope = null }

    private fun load() {
        if (ghost != null) return
        val b = body
        scope?.launch {
            val w = wheel
            val (g, wb) = withContext(Dispatchers.IO) {
                context.assets.open("showroom/s${b}_ghost.webp").use { BitmapFactory.decodeStream(it) } to
                    context.assets.open("showroom/s_wh_${w.id}.webp").use { BitmapFactory.decodeStream(it) }
            }
            if (b == body) { ghost = g; sized = null; wheels = wb; wheelBox = PaintShop.meta(context).getValue(PaintShop.View.SIDE).wheels[w.id]; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        ghost?.let { g ->
            // Brought once to the size it is drawn at: the animated frames (heating, charging) no longer scale it (26/11)
            val w = box.width().toInt(); val h = box.height().toInt()
            if (w > 0 && h > 0 && (sized?.width != w || sized?.height != h)) sized = android.graphics.Bitmap.createScaledBitmap(g, w, h, true)
            canvas.drawBitmap(sized ?: g, box.left, box.top, img)
        }
        val k = box.width() / 1840f
        if (k <= 0f) return
        canvas.save(); canvas.translate(box.left, box.top); canvas.scale(k, k)
        val x0 = battery.left; val x1 = battery.right; val y0 = battery.top; val y1 = battery.bottom; val w = x1 - x0; val h = y1 - y0
        val fill = (soc / 100f).coerceIn(0f, 1f)
        val lim = if (limit in 1..99) limit / 100f else 0f
        val col = if (v2l) HEAT else ENERGY
        val t = (android.os.SystemClock.uptimeMillis() % 1100L) / 1100f
        val pulse = .65f + .35f * kotlin.math.cos(t * 2 * Math.PI).toFloat()
        if (heat) {
            glow.color = HEAT; glow.alpha = (140 * pulse).toInt(); glow.maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(x0 - 10, y0 - 10, x1 + 10, y1 + 10, 16f, 16f, glow)
        }
        p.style = Paint.Style.FILL; p.color = 0xDB05080D.toInt()
        canvas.drawRoundRect(x0 - 7, y0 - 7, x1 + 7, y1 + 7, 12f, 12f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = if (heat) 4f else 2.5f; p.color = if (heat) HEAT else 0x9996C3FF.toInt()
        canvas.drawRoundRect(x0 - 7, y0 - 7, x1 + 7, y1 + 7, 12f, 12f, p)
        p.style = Paint.Style.FILL
        val n = 20
        for (i in 0 until n) {
            val cx = x0 + i * w / n + 2.5f; val cw = w / n - 5f
            val f = ((fill - i.toFloat() / n) * n).coerceIn(0f, 1f)
            val over = lim > 0f && i.toFloat() / n >= lim - 1e-6f
            p.color = if (over) 0xFF141A24.toInt() else 0xFF1D2636.toInt()
            canvas.drawRoundRect(cx, y0, cx + cw, y1, 4f, 4f, p)
            if (f > 0f) { p.color = col; canvas.drawRoundRect(cx, y0, cx + cw * f, y1, 4f, 4f, p) }
            else if (charging && (lim == 0f || !over) && i == (fill * n + 1e-6f).toInt()) { p.color = col; p.alpha = (255 * pulse).toInt(); canvas.drawRoundRect(cx, y0, cx + cw, y1, 4f, 4f, p); p.alpha = 255 }
        }
        if (lim > 0f) {
            val lx = x0 + w * lim
            p.style = Paint.Style.STROKE; p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(lx, y0 - 22, lx, y1 + 12, p)
            p.style = Paint.Style.FILL; p.textSize = 29f; p.textAlign = Paint.Align.CENTER
            canvas.drawText(limitText(limit), lx, y1 + 52, p)
        }
        if (heat) {
            p.style = Paint.Style.STROKE; p.color = HEAT; p.strokeWidth = 4.5f; p.strokeCap = Paint.Cap.ROUND
            for (i in 0 until 7) {
                val hx = x0 + w * (i + .5f) / 7
                p.alpha = (255 * (.65f + .35f * kotlin.math.cos((t + i * .21f) * 2 * Math.PI).toFloat())).toInt()
                val path = Path().apply { moveTo(hx, y0 - 16); rCubicTo(-13f, -13f, 13f, -22f, 0f, -36f); rCubicTo(-13f, -14f, 13f, -22f, 0f, -36f) }
                canvas.drawPath(path, p)
            }
            p.alpha = 255
        }
        // Charge port at the rear left, the cable, and the way down to the battery
        val live = charging || v2l
        val pc = if (v2l) HEAT else if (charging) ENERGY else 0xFF8FB8FF.toInt()
        val px = port[0]; val py = port[1]
        p.style = Paint.Style.FILL; p.color = 0xCC05080D.toInt(); canvas.drawCircle(px, py, 17f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3.5f; p.color = if (plugged) pc else 0x9996C3FF.toInt(); canvas.drawCircle(px, py, 17f, p)
        p.style = Paint.Style.FILL; p.color = if (plugged) pc else 0xBF96C3FF.toInt()
        canvas.drawPath(Path().apply { moveTo(px - 3, py - 10); rLineTo(-6f, 11f); rLineTo(6f, 0f); rLineTo(-3f, 9f); rLineTo(10f, -13f); rLineTo(-6f, 0f); rLineTo(4f, -7f); close() }, p)
        if (plugged) {
            val cab = Path().apply { moveTo(px + 17, py); rCubicTo(70f, 4f, 96f, 60f, 110f, 150f); rCubicTo(14f, 90f, 40f, 150f, 130f, 196f) }
            val ym = (y0 + y1) / 2
            val inn = Path().apply { moveTo(px, py + 17); lineTo(px, y0 - 60); quadTo(px, ym, px - 90, ym); lineTo(x1 + 9, ym) }
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            p.color = 0xFF0A0E15.toInt(); p.strokeWidth = 15f; canvas.drawPath(cab, p)
            p.color = pc; p.alpha = if (live) 140 else 204; p.strokeWidth = 5f; canvas.drawPath(cab, p)
            p.alpha = 102; p.strokeWidth = 4f; canvas.drawPath(inn, p); p.alpha = 255
            if (live) {
                val off = t * 68f
                p.pathEffect = DashPathEffect(floatArrayOf(20f, 48f), if (v2l) -off else off)
                p.strokeWidth = 7f; canvas.drawPath(cab, p)
                p.pathEffect = DashPathEffect(floatArrayOf(20f, 48f), if (v2l) off else -off)
                p.strokeWidth = 6f; canvas.drawPath(inn, p)
                p.pathEffect = null
            }
        }
        canvas.restore()
        if (heat || charging || v2l) postInvalidateDelayed(33)
    }

    private companion object {
        const val ENERGY = 0xFF00E676.toInt()
        const val HEAT = 0xFFFF9100.toInt()
    }
}
