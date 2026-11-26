package app.mg4dash.showroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The painted car (redesign, 26/11): drawn at [box] (view pixels, the render's whole frame), with its mirror image on
 * the floor in profile, and the floor's soft light under it. A new [config] keeps the last image until the new one
 * is painted, then fades to it.
 */
class CarImage(context: Context) : View(context) {
    /** Where the render's frame goes, in this view's pixels (the car may go beyond the view: it is cut). */
    val box = RectF()
    var reflection = false
    /** The floor's light: centre y and height, in view pixels (none if 0). */
    var floorY = 0f
    var floorH = 0f

    private var config: PaintShop.Config? = null
    private var scale = .5f
    private var image: Bitmap? = null
    private var previous: Bitmap? = null
    private var fadeStart = 0L
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val reflPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val floorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val light = app.mg4dash.ui.Themes.isLight(context)
    var onPainted: (() -> Unit)? = null

    fun show(cfg: PaintShop.Config, widthPx: Int) {
        val sc = PaintShop.scaleFor(context, cfg.view, widthPx)
        if (cfg == config && sc == scale && image != null) return
        // Another view (profile / three-quarter): the old picture goes at once, never drawn at the new size (26/11)
        if (config != null && cfg.view != config?.view) { image = null; previous = null; fadeIn = true }
        config = cfg; scale = sc
        val ready = PaintShop.cached(cfg, sc)
        if (ready != null) { set(ready, fade = image != null); return }
        job?.cancel()
        job = scope?.launch {
            val b = try { PaintShop.render(context, cfg, sc) } catch (e: Exception) { android.util.Log.w("MG4L.Showroom", "paint: ${e.message}"); return@launch }
            if (config == cfg) set(b, fade = image != null)
        }
    }

    private var fadeIn = false

    private fun set(b: Bitmap, fade: Boolean) {
        previous = if (fade) image else null
        if (image != null) fadeIn = false
        image = b
        fadeStart = android.os.SystemClock.uptimeMillis()
        invalidate()
        onPainted?.invoke()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val cfg = config
        if (cfg != null && image == null) { config = null; show(cfg, box.width().toInt().coerceAtLeast(1)) }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); scope?.cancel(); scope = null; job = null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (floorH > 0f) {
            val cx = width / 2f; val rx = width * .46f
            floorPaint.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x4D406EBE, 0x00406EBE), null, Shader.TileMode.CLAMP)
            canvas.save(); canvas.translate(cx, floorY); canvas.scale(rx, floorH / 2)
            canvas.drawCircle(0f, 0f, 1f, floorPaint); canvas.restore()
        }
        val img = image ?: return
        val t = ((android.os.SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) fadeIn = false
        // Light theme: a soft shadow under the car, so its edge does not look cut out on the light page (26/11)
        if (light) {
            val g = groundY ?: box.bottom
            shadowPaint.shader = RadialGradient(0f, 0f, 1f, intArrayOf(0x40000000, 0x00000000), null, Shader.TileMode.CLAMP)
            canvas.save(); canvas.translate(box.centerX(), g - box.height() * .02f); canvas.scale(box.width() * .5f, box.height() * .09f)
            canvas.drawCircle(0f, 0f, 1f, shadowPaint); canvas.restore()
        }
        previous?.let { if (t < 1f) drawCar(canvas, it, 1f - t) else previous = null }
        drawCar(canvas, img, if (previous != null || fadeIn) t else 1f)
        if (previous != null || fadeIn) postInvalidateOnAnimation()
    }

    private fun drawCar(canvas: Canvas, b: Bitmap, alpha: Float) {
        if (reflection) {
            // Mirror below the ground line, fading out, like a showroom floor
            val ground = groundY ?: box.bottom
            val h = box.height()
            val layer = canvas.saveLayer(box.left, ground, box.right, ground + h * .4f, null)
            reflPaint.alpha = (alpha * 255 * .27f).toInt()
            val m = Matrix().apply { setScale(box.width() / b.width, -box.height() / b.height); postTranslate(box.left, 2 * ground - box.top) }
            canvas.drawBitmap(b, m, reflPaint)
            maskPaint.shader = LinearGradient(0f, ground, 0f, ground + h * .4f, intArrayOf(0xFF000000.toInt(), 0x00000000), floatArrayOf(.0f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(box.left, ground, box.right, ground + h * .4f, maskPaint)
            canvas.restoreToCount(layer)
        }
        paint.alpha = (alpha * 255).toInt()
        if (light) {
            // Feathered edge: the car drawn once slightly larger and faint under itself
            halo.set(box.left - 1.5f, box.top - 1.5f, box.right + 1.5f, box.bottom + 1.5f)
            paint.alpha = (alpha * 70).toInt(); canvas.drawBitmap(b, null, halo, paint)
            paint.alpha = (alpha * 255).toInt()
        }
        canvas.drawBitmap(b, null, box, paint)
    }

    /** The ground line under the wheels, in view pixels. */
    var groundY: Float? = null

    private val halo = RectF()

    private companion object { const val FADE_MS = 280L }
}
