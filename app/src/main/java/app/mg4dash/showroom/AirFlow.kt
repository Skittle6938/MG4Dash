package app.mg4dash.showroom

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * The air over the cabin picture (redesign, 26/11, port of the mock-up's air.js). Each stream is a ribbon on a
 * Bézier curve that starts at a real outlet of the picture (coordinates in the 2400 × 900 source) and widens along
 * its course; waves of light travel along it. Orange heats, blue cools; speed and strength follow the fan. Drawn in
 * a 1808 × 644 frame scaled to the view; mirrored for right-hand drive.
 */
class AirFlow(context: Context) : View(context) {
    var heat = false
    var rhd = false
    /** 0: off (nothing drawn), up to 1 at full fan. */
    var power = 0f
    var defrost = false
    var face = false
    var feet = false
    var screen = false

    private class Line(val path: Path, val core: Path, val ax: Float, val ay: Float, val bx: Float, val by: Float, val v: Float, val ph: Float)

    private val lines = mapOf("face" to ArrayList<Line>(), "screen" to ArrayList(), "feet" to ArrayList())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val start = android.os.SystemClock.uptimeMillis()

    private fun bez(p: FloatArray, n: Int = 20): List<FloatArray> = (0..n).map { i ->
        val t = i / n.toFloat(); val u = 1 - t; val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
        floatArrayOf((a * p[0] + b * p[2] + c * p[4] + d * p[6]) * K, (a * p[1] + b * p[3] + c * p[5] + d * p[7]) * K + OY)
    }

    private fun add(k: String, p: FloatArray, w0: Float, w1: Float, v: Float) {
        val pts = bez(p); val n = pts.size - 1
        val l = ArrayList<FloatArray>(); val r = ArrayList<FloatArray>()
        for (i in 0..n) {
            val a = pts[maxOf(0, i - 1)]; val b = pts[minOf(n, i + 1)]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val d = hypot(dx, dy).takeIf { it > 0f } ?: 1f
            val t = i / n.toFloat(); val w = (w0 + (w1 - w0) * t.pow(1.35f)) / 2
            l.add(floatArrayOf(pts[i][0] - dy / d * w, pts[i][1] + dx / d * w)); r.add(floatArrayOf(pts[i][0] + dy / d * w, pts[i][1] - dx / d * w))
        }
        val path = Path(); l.forEachIndexed { i, q -> if (i == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]) }
        for (i in n downTo 0) path.lineTo(r[i][0], r[i][1]); path.close()
        val core = Path(); pts.forEachIndexed { i, q -> if (i == 0) core.moveTo(q[0], q[1]) else core.lineTo(q[0], q[1]) }
        val list = lines.getValue(k)
        list.add(Line(path, core, pts[0][0], pts[0][1], pts[n][0], pts[n][1], v, (list.size * .37f) % 1f))
    }

    init {
        // Face: the four outlets of the slim vent band, each fanning out towards the occupant it serves
        listOf(floatArrayOf(452f, 612f, 560f, 900f, 170f), floatArrayOf(1135f, 620f, 905f, 905f, 170f), floatArrayOf(1300f, 620f, 1530f, 905f, 170f), floatArrayOf(1930f, 630f, 1790f, 900f, 170f)).forEach { (x, y, tx, ty, sp) ->
            for (i in 0 until 5) {
                val ex = tx + (i - 2) * sp / 2; val ey = ty - abs(i - 2) * 10
                add("face", floatArrayOf(x + (i - 2) * 8, y, x + (i - 2) * 14 + (ex - x) * .10f, y + 60, ex - (ex - x) * .25f, ey - 150, ex, ey), 3f, 46f, .85f + .07f * i)
            }
        }
        // Windscreen: along the top of the dashboard, rising on the glass and opening out
        for (i in 0 until 12) {
            val x = 690f + i * 100; val dx = (x - 1240) * .34f; val y = 392 + abs(x - 1240) * .012f
            add("screen", floatArrayOf(x, y, x + dx * .12f, y - 90, x + dx * .65f, y - 190, x + dx, y - 300 - ((i * 7) % 3) * 18), 5f, 58f, .8f + .05f * (i % 4))
        }
        // Feet: under the dashboard, down into both footwells
        listOf(640f to 726f, 1500f to 706f).forEach { (x0, y0) ->
            for (i in 0 until 5) {
                val x = x0 + i * 86; val dx = (i - 2) * 34f
                add("feet", floatArrayOf(x, y0, x + dx * .2f, y0 + 50, x + dx * .8f, y0 + 120, x + dx, y0 + 196), 5f, 50f, .8f + .06f * i)
            }
        }
    }

    private fun comet(d: Float) = if (d < .42f) (1 - d / .42f) * (1 - d / .42f) else 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (power <= 0f || width == 0) return
        val t = (android.os.SystemClock.uptimeMillis() - start) / 1000f
        canvas.save()
        canvas.scale(width / CW, height / CH)
        if (rhd) { canvas.scale(-1f, 1f, CW / 2, 0f) }
        val (r, g, b) = if (heat) Triple(255, 140, 36) else Triple(36, 200, 255)
        val sp = .14f + .42f * power
        for ((k, on) in listOf("screen" to screen, "face" to face, "feet" to feet)) {
            if (!on) continue
            val boost = if (k == "screen") (if (defrost) 1.5f else 1.15f) else 1f
            val a0 = (.3f + .7f * power) * boost
            for (gl in GLOW.getValue(k)) {
                val cx = gl[0] * K; val cy = gl[1] * K + OY; val rr = gl[2] * K * (1 + .25f * power)
                fill.shader = RadialGradient(cx, cy, rr, argb(min(1f, .42f * a0), r, g, b), argb(0f, r, g, b), Shader.TileMode.CLAMP)
                canvas.drawRect(cx - rr, cy - rr, cx + rr, cy + rr, fill)
            }
            for (l in lines.getValue(k)) {
                val h = (t * sp * l.v + l.ph) % 1f
                val cols = IntArray(N + 1); val whites = IntArray(N + 1); val pos = FloatArray(N + 1)
                for (i in 0..N) {
                    val u = i / N.toFloat(); val e = min(1f, u / .1f) * (1 - u).pow(1.15f)
                    val d1 = (((h - u) % 1f) + 1f) % 1f; val d2 = (((h + .5f - u) % 1f) + 1f) % 1f
                    val c = comet(d1) + comet(d2)
                    cols[i] = argb(min(1f, e * a0 * (.10f + .5f * c)), r, g, b); whites[i] = argb(min(1f, e * a0 * (.05f + .5f * c)), 255, 255, 255); pos[i] = u
                }
                fill.shader = LinearGradient(l.ax, l.ay, l.bx, l.by, cols, pos, Shader.TileMode.CLAMP)
                canvas.drawPath(l.path, fill)
                stroke.shader = LinearGradient(l.ax, l.ay, l.bx, l.by, whites, pos, Shader.TileMode.CLAMP)
                canvas.drawPath(l.core, stroke)
            }
        }
        canvas.restore()
        postInvalidateDelayed(33)   // 30 frames a second: smooth enough, lighter for the head unit
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b

    companion object {
        const val CW = 1808f
        const val CH = 644f
        const val K = 1808f / 2400f
        const val OY = -20f
        private const val N = 16
        private val GLOW = mapOf(
            "face" to listOf(floatArrayOf(452f, 612f, 64f), floatArrayOf(1135f, 622f, 54f), floatArrayOf(1300f, 622f, 54f), floatArrayOf(1930f, 632f, 64f)),
            "screen" to listOf(floatArrayOf(760f, 392f, 100f), floatArrayOf(1000f, 388f, 100f), floatArrayOf(1240f, 386f, 100f), floatArrayOf(1480f, 388f, 100f), floatArrayOf(1720f, 392f, 100f)),
            "feet" to listOf(floatArrayOf(812f, 736f, 120f), floatArrayOf(1672f, 716f, 120f)),
        )
    }
}
