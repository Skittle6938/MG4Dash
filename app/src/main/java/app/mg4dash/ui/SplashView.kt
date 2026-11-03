package app.mg4dash.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Startup animation: "MG4Dash" appearing, then a thin bar filling like the
 * battery bar, a glowing dot at the end. Fixed colours (no theme): the screen can
 * show before unlocking, when the settings are not readable yet.
 */
class SplashView(context: Context) : View(context) {
    /** Animation time in seconds (driven by [start], or fixed for screenshots). */
    var time = 0f; set(v) { field = v; invalidate() }
    var version = ""

    private val d = resources.displayMetrics.density
    private val sd = resources.displayMetrics.scaledDensity
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private var anim: ValueAnimator? = null

    fun start() {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, CYCLE).apply {
            duration = (CYCLE * 1000).toLong(); repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { time = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim?.cancel() }

    private fun ease(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        // Background: same dark gradient as MG4Dash
        paint.shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint); paint.shader = null
        val t = time % CYCLE
        // Fade out at the end of the cycle, gentle restart
        val out = 1f - ease((t - (CYCLE - 0.35f)) / 0.35f)
        val cx = w / 2; val cy = h / 2 - 20 * d
        // Wordmark: bold "MG4", light "Dash", slides slightly as it appears
        val a = ease(t / 0.6f) * out
        paint.textSize = 64 * sd
        paint.typeface = bold; val w1 = paint.measureText("MG4")
        paint.typeface = light; val w2 = paint.measureText("Dash")
        val x0 = cx - (w1 + w2) / 2; val y0 = cy + (1 - a) * 10 * d
        paint.color = withAlpha(TEXT, a); paint.typeface = bold; c.drawText("MG4", x0, y0, paint)
        paint.color = withAlpha(ACCENT, a); paint.typeface = light; c.drawText("Dash", x0 + w1, y0, paint)
        // "Charge" bar
        val bw = 420 * d; val bh = 6 * d
        val bx = cx - bw / 2; val by = cy + 46 * d
        val track = RectF(bx, by, bx + bw, by + bh)
        paint.color = withAlpha(TRACK, ease((t - 0.2f) / 0.4f) * out)
        c.drawRoundRect(track, bh / 2, bh / 2, paint)
        val p = ease((t - 0.5f) / 1.7f)
        if (p > 0f) {
            val end = bx + bw * p
            paint.shader = LinearGradient(bx, 0f, end, 0f, withAlpha(ENERGY, 0.55f * out), withAlpha(ENERGY, out), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(bx, by, end, by + bh), bh / 2, bh / 2, paint); paint.shader = null
            // Dot with a halo at the end of the bar
            val my = by + bh / 2
            paint.shader = RadialGradient(end, my, 22 * d, withAlpha(ENERGY, 0.5f * out), withAlpha(ENERGY, 0f), Shader.TileMode.CLAMP)
            c.drawCircle(end, my, 22 * d, paint); paint.shader = null
            paint.color = withAlpha(0xFFFFFFFF.toInt(), out); c.drawCircle(end, my, 6 * d, paint)
        }
        // Version, small, fading in
        if (version.isNotEmpty()) {
            paint.textSize = 15 * sd; paint.typeface = light; paint.textAlign = Paint.Align.CENTER
            paint.color = withAlpha(SUB, ease((t - 0.8f) / 0.6f) * out)
            c.drawText(version, cx, by + 46 * d, paint)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    private fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * (color ushr 24)).toInt() shl 24)

    companion object {
        const val CYCLE = 3.0f
        private const val BG_TOP = 0xFF0B0F17.toInt()
        private const val BG_BOTTOM = 0xFF141B29.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val SUB = 0xFFA0AEC0.toInt()
        private const val ACCENT = 0xFF00D2FF.toInt()
        private const val ENERGY = 0xFF00E676.toInt()
        private const val TRACK = 0xFF262D3D.toInt()
    }
}
