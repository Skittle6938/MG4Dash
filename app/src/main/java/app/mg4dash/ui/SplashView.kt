package app.mg4dash.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.PathParser
import app.mg4dash.R
import kotlin.math.sin

/** Fixed colours and bundled paths also work before the user's storage is unlocked. */
class SplashView(context: Context) : View(context) {
    var time = 0f; set(value) { field = value; invalidate() }
    var version = "" // Kept for callers producing startup screenshots.
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = PathParser.createPathFromPathData(context.getString(R.string.brand_outline))!!
    private val road = PathParser.createPathFromPathData(context.getString(R.string.brand_road))!!
    private val accent = PathParser.createPathFromPathData(context.getString(R.string.brand_accent))!!
    private val measure = PathMeasure(outline, false)
    private val segment = Path()
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private var lastFrame = 0L
    private var running = false

    fun start() {
        running = true
        lastFrame = 0L
        time = 0f
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (running) {
            lastFrame = 0L
            time = 0f
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
        if (running && visibility == VISIBLE) time = 0f
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }
    private fun ease(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    override fun onDraw(canvas: Canvas) {
        val visible = hasWindowFocus() && isShown && display?.state == android.view.Display.STATE_ON
        if (running && visible) {
            val now = android.os.SystemClock.uptimeMillis()
            // A stalled boot frame must not consume the entire reveal before it is displayed.
            if (lastFrame != 0L) time += (now - lastFrame).coerceIn(0L, 32L) / 1000f
            lastFrame = now
            postInvalidateOnAnimation()
        } else lastFrame = 0L
        // No reveal (23/11): the logo, the name and the slogan shown at once, only the dots below them move
        val revealTime = 2f
        canvas.drawColor(Color.BLACK)
        // Design coordinates keep the composition inside both wide and smaller displays.
        val scale = minOf(width / 960f, height / 360f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.save()
        canvas.translate(-52f, -108f)
        canvas.scale(104f / 256f, 104f / 256f)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 23f
        segment.rewind()
        measure.getSegment(0f, measure.length * ease(revealTime / .35f), segment, true)
        canvas.drawPath(segment, paint)
        paint.style = Paint.Style.FILL
        canvas.save()
        canvas.clipRect(0f, 0f, 256f * ease((revealTime - .25f) / .3f), 256f)
        canvas.drawPath(road, paint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(0f, 0f, 256f * ease((revealTime - .35f) / .3f), 256f)
        paint.color = 0xFF00E676.toInt()
        canvas.drawPath(accent, paint)
        canvas.restore()
        canvas.restore()

        paint.textAlign = Paint.Align.CENTER
        paint.typeface = medium
        paint.textSize = 34f
        paint.color = Color.WHITE
        paint.alpha = (255 * ease((revealTime - .45f) / .25f)).toInt()
        canvas.drawText("MG4Dash", 0f, 20f, paint)
        paint.typeface = regular
        paint.textSize = 15f
        paint.color = 0xFF929CA6.toInt()
        paint.alpha = (255 * ease((revealTime - .6f) / .25f)).toInt()
        canvas.drawText("Simply your Dash", 0f, 45f, paint)

        val shown = ease((revealTime - .85f) / .2f)
        for (i in 0..2) {
            val pulse = .35f + .35f * (1f + sin((time * 3.5f - i * .8f).toDouble()).toFloat()) / 2f
            paint.alpha = (255 * shown * pulse).toInt()
            canvas.drawCircle((i - 1) * 14f, 76f, 2.3f, paint)
        }
        paint.alpha = 255
        canvas.restore()
    }

    companion object {
        const val CYCLE = 3f
    }
}
