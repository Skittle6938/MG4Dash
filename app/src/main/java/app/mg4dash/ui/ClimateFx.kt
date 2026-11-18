package app.mg4dash.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The climate card's warm or cold effect (#122, Marc's variant C): heat haze rising from the bottom while heating,
 * frost crystals in the corners while cooling. Its strength follows the gap between the set point and the outside
 * temperature, as the shortcut tile's mode does.
 */
object ClimateFx {
    const val WARM = 0xFFFF8A3D.toInt()
    const val COLD = 0xFF5CC8FF.toInt()
    /** Length of one loop of the animation (ms). */
    const val PERIOD_MS = 5_000L

    /** Strength 0..1 from the gap (°C) between the set point and outside: 0 within half a degree, full from about 14 °C. */
    fun intensity(gap: Float): Float = if (abs(gap) <= 0.5f) 0f else (0.25f + abs(gap) / 14f).coerceAtMost(1f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Scale of the effect on the Keep climate screen (seen from outside): larger waves and crystals than on the card. */
    const val HOLD_SCALE = 1.5f

    /**
     * The Keep climate screen's effect (Marc, 18/11, variant B): heating ([first] true) or cooling and its strength, as
     * the climate card's; null with nothing to show: the climate not running (waiting for its time, finished), the
     * screen off, the option off, a temperature unknown, or within half a degree.
     */
    fun holdEffect(running: Boolean, hvacOn: Boolean?, screenOn: Boolean, option: Boolean, acOn: Boolean?, set: Float?, out: Float?): Pair<Boolean, Float>? {
        if (!running || hvacOn != true || !screenOn || !option || set == null || out == null) return null
        val k = intensity(set - out).takeIf { it > 0f } ?: return null
        return !app.mg4dash.ui.ShortcutTiles.coolingFor(acOn == true, set, out, null) to k
    }

    /**
     * Draws the effect in [r] at time [t] (0..1 over [PERIOD_MS]); [d]: screen density, [warm]: heating, else cooling,
     * [k]: strength from [intensity].
     */
    fun draw(c: Canvas, r: RectF, warm: Boolean, k: Float, t: Float, d: Float) {
        if (k <= 0f) return
        c.save(); c.clipRect(r)
        if (warm) haze(c, r, k, t, d) else frost(c, r, k, t, d)
        c.restore()
    }

    private fun alpha(color: Int, a: Int) = (color and 0xFFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Wavy lines rising from the bottom, their waves running upwards. */
    private fun haze(c: Canvas, r: RectF, k: Float, t: Float, d: Float) {
        val lines = (4 + 6 * k).toInt()
        val top = r.bottom - r.height() * (0.22f + 0.25f * k)
        val amp = (3f + 4f * k) * d
        val step = 3f * d
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f * d; paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until lines) {
            val x0 = r.left + r.width() * (i + 0.5f) / lines
            val path = Path()
            var y = r.bottom - 4f * d; var first = true
            while (y > top) {
                val x = x0 + amp * sin(((y - r.bottom) / (16f * d) + 2 * PI * t * 2 + i * 1.7).toFloat())
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                y -= step
            }
            // Each line breathes on its own beat, so they never pulse together
            val beat = 0.6f + 0.4f * sin((2 * PI * (t + i * 0.37)).toFloat())
            paint.shader = LinearGradient(0f, r.bottom, 0f, top, alpha(WARM, ((60 + 70 * k) * beat).toInt()), alpha(WARM, 0), Shader.TileMode.CLAMP)
            c.drawPath(path, paint)
        }
        paint.shader = null
        // A faint warm floor under them
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, r.bottom, 0f, top, alpha(WARM, (22 + 30 * k).toInt()), alpha(WARM, 0), Shader.TileMode.CLAMP)
        c.drawRect(r.left, top, r.right, r.bottom, paint)
        paint.shader = null
    }

    /** Six-branch crystals in the corners, slowly growing and twinkling. */
    private fun frost(c: Canvas, r: RectF, k: Float, t: Float, d: Float) {
        // Corner (x, y from the corner, size) for each crystal; more of them as the gap grows
        val spots = listOf(Triple(18f, 18f, 26f), Triple(56f, 26f, 12f), Triple(26f, 60f, 10f), Triple(84f, 14f, 8f), Triple(14f, 92f, 7f))
            .take((2 + 3 * k).toInt().coerceIn(2, 5))
        val corners = listOf(1f to 1f, -1f to -1f, -1f to 1f, 1f to -1f)
        corners.forEachIndexed { ci, (sx, sy) ->
            val ox = if (sx > 0) r.left else r.right; val oy = if (sy > 0) r.top else r.bottom
            val main = ci < 2   // two opposite corners stronger, the two others fainter
            spots.forEachIndexed { si, (x, y, s) ->
                val phase = (2 * PI * (t + ci * 0.25 + si * 0.13)).toFloat()
                val grow = 0.85f + 0.15f * sin(phase)
                val a = ((if (main) 80 else 50) + 70 * k) * (0.55f + 0.45f * sin(phase + 1f))
                flake(c, ox + sx * x * d, oy + sy * y * d, s * d * grow * (0.8f + 0.2f * k), alpha(COLD, a.toInt()), d)
            }
            if (main) {
                paint.style = Paint.Style.FILL
                paint.shader = RadialGradient(ox, oy, r.height() * (0.35f + 0.25f * k), alpha(COLD, (20 + 25 * k).toInt()), alpha(COLD, 0), Shader.TileMode.CLAMP)
                c.drawRect(r, paint)
                paint.shader = null
            }
        }
    }

    /**
     * The effect as a card's foreground: it animates (about 20 frames a second) only while there is something to show
     * and the card is on screen. [dim]: strength scaled down (dark theme, night driving).
     */
    class Effect(private val density: Float, private val radius: Float, private val dim: Float) : android.graphics.drawable.Drawable() {
        private var warm = true
        private var k = 0f
        private val start = android.os.SystemClock.uptimeMillis()
        private val clip = android.graphics.Path()
        private val tick = Runnable { invalidateSelf() }

        /** Heating ([warm]) or cooling at strength [k] (0: nothing, the animation stops). */
        fun set(warm: Boolean, k: Float) {
            val nk = k * dim
            if (warm == this.warm && nk == this.k) return
            this.warm = warm; this.k = nk
            invalidateSelf()
        }

        val running: Boolean get() = k > 0f && isVisible

        override fun draw(canvas: Canvas) {
            unscheduleSelf(tick)
            if (k <= 0f || !isVisible) return
            val r = RectF(bounds)
            clip.reset(); clip.addRoundRect(r, radius, radius, Path.Direction.CW)
            canvas.save(); canvas.clipPath(clip)
            val now = android.os.SystemClock.uptimeMillis()
            draw(canvas, r, warm, k, ((now - start) % PERIOD_MS) / PERIOD_MS.toFloat(), density)
            canvas.restore()
            scheduleSelf(tick, now + FRAME_MS)
        }

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            if (!visible) unscheduleSelf(tick) else if (changed) invalidateSelf()
            return changed
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private const val FRAME_MS = 50L

    private fun flake(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, d: Float) {
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.color = color; paint.strokeWidth = (s / 9f).coerceAtLeast(1f * d)
        for (i in 0 until 6) {
            val ang = PI / 3 * i
            val dx = cos(ang).toFloat(); val dy = sin(ang).toFloat()
            c.drawLine(cx, cy, cx + dx * s, cy + dy * s, paint)
            val bx = cx + dx * s * 0.6f; val by = cy + dy * s * 0.6f
            for (sg in intArrayOf(-1, 1)) {
                val a2 = ang + sg * PI / 4
                c.drawLine(bx, by, bx + cos(a2).toFloat() * s * 0.3f, by + sin(a2).toFloat() * s * 0.3f, paint)
            }
        }
    }
}
