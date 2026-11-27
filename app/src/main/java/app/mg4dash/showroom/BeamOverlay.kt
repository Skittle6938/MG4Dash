package app.mg4dash.showroom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/**
 * The headlights' pool on the floor (27/11, Marc: far ahead in the car's axis), over the whole Vehicle page: it leaves
 * the car's stage and falls on the left column's cards. Added light (additive), never touched (not clickable).
 */
class BeamOverlay(context: Context, private val lights: HeadLights) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val at = IntArray(2); private val mine = IntArray(2)
    private var last: List<Float>? = null

    init { isClickable = false; isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Redrawn when the lights, the view or the car's place change. */
    fun refresh() {
        val b = lights.beam()?.toList()
        lights.getLocationInWindow(at); getLocationInWindow(mine)
        val key = b?.let { it + listOf((at[0] - mine[0]).toFloat(), (at[1] - mine[1]).toFloat()) }
        if (key != last) { last = key; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val b = lights.beam() ?: return
        lights.getLocationInWindow(at); getLocationInWindow(mine)
        c.save()
        c.translate((at[0] - mine[0]) + b[0], (at[1] - mine[1]) + b[1]); c.rotate(b[4]); c.scale(b[2], b[3])
        // Its brightest part a little towards the car (on the right), fading to nothing at the edge
        p.shader = RadialGradient(.25f, 0f, 1.25f, intArrayOf(0xC7DCEBFF.toInt(), 0x33DCEBFF, 0x00DCEBFF), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(.25f, 0f, 1.25f, p)
        p.shader = null
        c.restore()
    }
}
