package app.mg4dash.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.mg4dash.map.NavRepo

/**
 * The guidance's manoeuvre drawings: plain thick lines with a head, a ring for the roundabouts (19/11: the
 * drawing as before, thicker, the exit number in the middle of the ring, the roundabouts balanced). Each drawing is laid
 * out in its own units (the entry road comes from below, y down), then scaled and centred to fit the view with a margin:
 * nothing ever goes past its edges, whatever the direction (the tip was cut before).
 */
object NavGlyph {

    sealed class Shape {
        /** Straight on (0), or a turn of [heading] degrees, clockwise (90 = right, -135 = sharp left). */
        data class Turn(val heading: Float) : Shape()
        data class UTurn(val right: Boolean) : Shape()
        data class Fork(val right: Boolean) : Shape()
        data class Merge(val right: Boolean) : Shape()
        /** [heading]: where the exit lies, clockwise from straight on. */
        data class Round(val heading: Float, val number: Int?, val clockwise: Boolean) : Shape()
        object Arrival : Shape()
    }

    /** The shape for a manoeuvre from MG4Link ([type], [side], [exit], [angle] as in [NavRepo.Nav]). */
    fun shape(type: Int, side: Int?, exit: Int?, angle: Int?, leftTraffic: Boolean): Shape {
        val sign = when (side) { 1 -> -1f; 2 -> 1f; else -> 0f }
        return when (type) {
            NavRepo.Turn.DESTINATION -> Shape.Arrival
            NavRepo.Turn.ROUNDABOUT, NavRepo.Turn.ROUNDABOUT_ENTER, NavRepo.Turn.ROUNDABOUT_EXIT ->
                Shape.Round(NavArrowView.roundaboutHeading(side, exit, angle, leftTraffic).toFloat(),
                    exit?.takeIf { it > 0 }, when (side) { 1 -> true; 2 -> false; else -> leftTraffic })
            NavRepo.Turn.U_TURN -> Shape.UTurn(side == 2 || (side != 1 && leftTraffic))
            NavRepo.Turn.FORK, NavRepo.Turn.OFF_RAMP -> Shape.Fork(side != 1)
            NavRepo.Turn.MERGE -> Shape.Merge(side == 1)
            NavRepo.Turn.TURN -> Shape.Turn(90f * sign)
            NavRepo.Turn.SHARP -> Shape.Turn(135f * sign)
            NavRepo.Turn.SLIGHT, NavRepo.Turn.ON_RAMP -> Shape.Turn(45f * sign)
            else -> Shape.Turn(0f)
        }
    }

    fun shape(n: NavRepo.Nav, leftTraffic: Boolean): Shape = shape(n.eventType, n.side, n.exitNumber, n.angle, leftTraffic)

    // ── What is drawn, in the drawing's own units ──

    private sealed class Op {
        class Line(val path: Path, val pts: List<Pair<Float, Float>>) : Op()
        class Ring(val r: Float) : Op()
        class Head(val x: Float, val y: Float, val ux: Float, val uy: Float) : Op()
        class Number(val r: Float, val text: String) : Op()
        class Pin(val r: Float) : Op()
    }

    /** The lines' width, the head's length and half width. */
    private const val BAND = 0.22f
    private const val HEAD_LEN = 0.42f
    private const val HEAD_HALF = 0.32f
    /** The roundabout: its ring, and the length of the entry leg and of the exit leg up to the head's base (equal). */
    internal const val RING = 0.56f
    internal const val LEG = 0.42f

    /** The way along [pts] ending in a head at the last point (the line stops inside the head). */
    private fun route(pts: List<Pair<Float, Float>>, curve: Boolean, out: MutableList<Op>) {
        val (tx, ty) = pts.last(); val (px, py) = pts[pts.size - 2]
        val len = Math.hypot((tx - px).toDouble(), (ty - py).toDouble()).toFloat().coerceAtLeast(1e-4f)
        val ux = (tx - px) / len; val uy = (ty - py) / len
        val bx = tx - ux * HEAD_LEN * 0.85f; val by = ty - uy * HEAD_LEN * 0.85f
        val path = Path().apply {
            moveTo(pts[0].first, pts[0].second)
            if (curve && pts.size == 4) { lineTo(pts[1].first, pts[1].second); quadTo(pts[2].first, pts[2].second, bx, by) }
            else { for (i in 1 until pts.size - 1) lineTo(pts[i].first, pts[i].second); lineTo(bx, by) }
        }
        out += Op.Line(path, pts.dropLast(1) + (bx to by))
        out += Op.Head(tx, ty, ux, uy)
    }

    private fun ops(shape: Shape): List<Op> {
        val out = ArrayList<Op>()
        when (shape) {
            is Shape.Turn -> {
                val h = Math.toRadians(shape.heading.toDouble())
                val ux = Math.sin(h).toFloat(); val uy = -Math.cos(h).toFloat()
                when {
                    Math.abs(shape.heading) < 1f -> route(listOf(0f to 1.1f, 0f to -1f), false, out)
                    Math.abs(shape.heading) > 100f -> route(listOf(0f to 1.1f, 0f to -0.55f, Math.signum(ux) * 0.95f to 0.2f), false, out)
                    else -> route(listOf(0f to 1.1f, 0f to 0.05f, ux to uy + 0.05f), false, out)
                }
            }
            is Shape.UTurn -> {
                val s = if (shape.right) 1f else -1f; val ru = 0.42f
                val path = Path().apply {
                    moveTo(0f, 1.1f); lineTo(0f, 0f)
                    arcTo(RectF(minOf(0f, 2 * ru * s), -ru, maxOf(0f, 2 * ru * s), ru), if (s > 0) 180f else 0f, if (s > 0) 180f else -180f)
                    lineTo(2 * ru * s, 0.45f)
                }
                out += Op.Line(path, listOf(0f to 1.1f, 0f to 0f, ru * s to -ru, 2 * ru * s to 0f, 2 * ru * s to 0.45f))
                out += Op.Head(2 * ru * s, 0.45f + HEAD_LEN * 0.85f, 0f, 1f)
            }
            is Shape.Fork -> {
                val s = if (shape.right) 1f else -1f
                val a = Math.toRadians(32.0)
                route(listOf(0f to 1.1f, 0f to 0.3f, 0f to 0f, s * Math.sin(a).toFloat() to -Math.cos(a).toFloat()), true, out)
            }
            is Shape.Merge -> {
                val s = if (shape.right) 1f else -1f
                route(listOf(s * 0.75f to 1.1f, s * 0.75f to 0.55f, 0f to 0.05f, 0f to -1f), false, out)
            }
            is Shape.Round -> {
                // Back the way it came (a U-turn round it): the exit beside the entry, on the side the traffic returns
                val back = Math.abs(((shape.heading - 180f + 540f) % 360f) - 180f) < 30f
                val heading = if (back) (if (shape.clockwise) 150f else 210f) else shape.heading
                val te = Math.toRadians(heading - 90.0)
                val ex = Math.cos(te).toFloat(); val ey = Math.sin(te).toFloat()
                out += Op.Ring(RING)
                val entry = Path().apply { moveTo(0f, RING + LEG); lineTo(0f, RING) }
                out += Op.Line(entry, listOf(0f to RING + LEG, 0f to RING))
                val stem = LEG + HEAD_LEN * 0.85f
                route(listOf(ex * RING to ey * RING, ex * (RING + stem) to ey * (RING + stem)), false, out)
                shape.number?.let { out += Op.Number(RING * 0.66f, "$it") }
            }
            Shape.Arrival -> out += Op.Pin(0.6f)
        }
        return out
    }

    /** The extent of everything drawn, half the line widths included. */
    private fun bounds(ops: List<Op>): RectF {
        val b = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        fun add(x: Float, y: Float, r: Float) { b.left = minOf(b.left, x - r); b.top = minOf(b.top, y - r); b.right = maxOf(b.right, x + r); b.bottom = maxOf(b.bottom, y + r) }
        for (o in ops) when (o) {
            is Op.Line -> o.pts.forEach { add(it.first, it.second, BAND / 2 + 0.03f) }
            is Op.Ring -> add(0f, 0f, o.r + BAND / 2 + 0.03f)
            is Op.Head -> { add(o.x, o.y, 0.03f); add(o.x - o.ux * HEAD_LEN - o.uy * HEAD_HALF, o.y - o.uy * HEAD_LEN + o.ux * HEAD_HALF, 0.03f); add(o.x - o.ux * HEAD_LEN + o.uy * HEAD_HALF, o.y - o.uy * HEAD_LEN - o.ux * HEAD_HALF, 0.03f) }
            is Op.Number -> add(0f, 0f, o.r)
            is Op.Pin -> { add(0f, -o.r * 0.4f, o.r); add(0f, o.r * 1.5f, 0.03f) }
        }
        return b
    }

    /**
     * The extent used for the layout: a roundabout is centred on its ring with the same room whatever its exit
     * (19/11: better balanced), so the ring never moves from one manoeuvre to the next.
     */
    private fun layoutBounds(shape: Shape, ops: List<Op>): RectF {
        if (shape !is Shape.Round) return bounds(ops)
        val e = RING + LEG + HEAD_LEN * 0.85f + 0.03f
        return RectF(-e, -e, e, e)
    }

    private class Fit(val ox: Float, val oy: Float, val scale: Float)

    private fun fit(box: RectF, b: RectF, margin: Float): Fit {
        val aw = box.width() - 2 * margin; val ah = box.height() - 2 * margin
        // The same scale for every drawing of a size (about 2.7 units high), smaller only for one that would not fit
        val scale = minOf(ah / 2.7f, aw / b.width(), ah / b.height())
        return Fit(box.centerX() - b.centerX() * scale, box.centerY() - b.centerY() * scale, scale)
    }

    /** Where the head's tip and the drawing's origin (a roundabout's centre) land in [box] (tests). */
    internal fun tipAndOrigin(box: RectF, shape: Shape, margin: Float = box.width() * 0.04f): Pair<android.graphics.PointF, android.graphics.PointF> {
        val ops = ops(shape); val f = fit(box, layoutBounds(shape, ops), margin)
        val h = ops.filterIsInstance<Op.Head>().last()
        return android.graphics.PointF(f.ox + h.x * f.scale, f.oy + h.y * f.scale) to android.graphics.PointF(f.ox, f.oy)
    }

    /** The entry leg's and the exit leg's lengths (up to the head's base) of a roundabout drawing (tests). */
    internal fun legs(shape: Shape.Round): Pair<Float, Float> {
        val lines = ops(shape).filterIsInstance<Op.Line>()
        fun len(l: Op.Line) = Math.hypot((l.pts.last().first - l.pts.first().first).toDouble(), (l.pts.last().second - l.pts.first().second).toDouble()).toFloat()
        return len(lines.first()) to len(lines.last())
    }

    /** Where to draw [text] so that its ink (not its baseline) is centred on the point: measured at 100 px. */
    internal fun inkOffset(t: Paint, text: String): Pair<Float, Float> {
        val big = Paint(t).apply { textSize = 100f }
        val r = android.graphics.Rect(); big.getTextBounds(text, 0, text.length, r)
        val k = t.textSize / 100f
        return -r.exactCenterX() * k to -r.exactCenterY() * k
    }

    /** Draws [shape] in [box] in [color]; everything stays within [box] minus [margin]. */
    fun draw(c: Canvas, box: RectF, shape: Shape, color: Int, margin: Float = box.width() * 0.04f) {
        val ops = ops(shape)
        val f = fit(box, layoutBounds(shape, ops), margin)
        c.save()
        c.translate(f.ox, f.oy)
        c.scale(f.scale, f.scale)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = BAND; this.color = color
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        for (o in ops) when (o) {
            is Op.Line -> c.drawPath(o.path, stroke)
            is Op.Ring -> c.drawCircle(0f, 0f, o.r, stroke)
            is Op.Head -> {
                val bx = o.x - o.ux * HEAD_LEN; val by = o.y - o.uy * HEAD_LEN
                val p = Path().apply {
                    moveTo(o.x, o.y)
                    lineTo(bx - o.uy * HEAD_HALF, by + o.ux * HEAD_HALF)
                    lineTo(bx + o.uy * HEAD_HALF, by - o.ux * HEAD_HALF)
                    close()
                }
                fill.pathEffect = android.graphics.CornerPathEffect(0.05f); c.drawPath(p, fill); fill.pathEffect = null
            }
            is Op.Number -> {
                val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD; textSize = o.r * 1.3f; this.color = color }
                val (dx, dy) = inkOffset(t, o.text)
                c.drawText(o.text, dx, dy, t)
            }
            is Op.Pin -> {
                val cy = -o.r * 0.4f
                val outer = Path().apply {
                    addCircle(0f, cy, o.r, Path.Direction.CW)
                    op(Path().apply { moveTo(-o.r * 0.82f, cy + o.r * 0.58f); lineTo(0f, o.r * 1.5f); lineTo(o.r * 0.82f, cy + o.r * 0.58f); close() }, Path.Op.UNION)
                }
                c.drawPath(Path(outer).apply { op(Path().apply { addCircle(0f, cy, o.r * 0.4f, Path.Direction.CW) }, Path.Op.DIFFERENCE) }, fill)
            }
        }
        c.restore()
    }
}
