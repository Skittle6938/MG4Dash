package app.mg4dash.showroom

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The car's lamps on the Vehicle page (27/11, mock-up approved by Marc), drawn over the car in its picture's pixels.
 * The daytime running strips (the row of thin LEDs at the top of each headlight): always, lightly, in both themes.
 * Lights on (VEH_SIDE_LGHT other than 0, a single state) and the dark theme: stronger strips, the projectors and the
 * rear lamp glowing, and a pool of light on the floor far ahead in the car's axis (never at the bumper's foot), drawn by
 * the page since it falls on the left column.
 */
class HeadLights(context: Context) : View(context) {
    val box = RectF()
    var threeQuarter = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var lightsOn = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var dark = true

    private class Lamps(val w: Float, val drl: FloatArray, val proj: List<Pair<Float, Float>>, val tail: Pair<Float, Float>,
                        val beam: FloatArray)

    // Measured on s0 / q0 (1840 × 680 and 1600 × 720); beam: centre x, y, radii x, y, rotation
    private val side = Lamps(1840f, floatArrayOf(150f, 323f, 305f, 286f), listOf(178f to 324f, 204f to 318f), 1715f to 239f,
        floatArrayOf(-532f, 652f, 375f, 44f, 0f))
    private val q34 = Lamps(1600f, floatArrayOf(372f, 370f, 545f, 340f), listOf(390f to 386f, 418f to 384f, 448f to 381f), 1421f to 233f,
        floatArrayOf(-465f, 752f, 420f, 90f, -9f))

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val blurDrlOff = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    private val blurDrlOn = BlurMaskFilter(13f, BlurMaskFilter.Blur.NORMAL)
    private val blurBig = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL)
    private val blurSmall = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // the blurs

    override fun onDraw(c: Canvas) {
        if (box.width() <= 0f) return
        val l = if (threeQuarter) q34 else side
        val k = box.width() / l.w
        val on = lightsOn && dark
        c.save(); c.translate(box.left, box.top); c.scale(k, k)
        p.style = Paint.Style.STROKE; p.color = LIGHT
        p.strokeWidth = if (on) 22f else 14f; p.alpha = if (on) 204 else 97; p.maskFilter = if (on) blurDrlOn else blurDrlOff
        c.drawLine(l.drl[0], l.drl[1], l.drl[2], l.drl[3], p)
        if (on) {
            p.style = Paint.Style.FILL
            l.proj.forEach { (x, y) ->
                p.color = LIGHT; p.alpha = 204; p.maskFilter = blurBig; c.drawCircle(x, y, 30f, p)
                p.color = 0xFFFFFFFF.toInt(); p.maskFilter = blurSmall; c.drawCircle(x, y, 8f, p)
            }
            p.color = TAIL; p.alpha = 179; p.maskFilter = blurBig; c.drawCircle(l.tail.first, l.tail.second, 34f, p)
        }
        p.maskFilter = null
        c.restore()
    }

    /**
     * The pool of light on the floor, well ahead in the car's axis (Marc, 27/11): its centre, radii and rotation in this
     * view's pixels, or null when the lights are not shown on. Drawn by the page over the left column ([BeamOverlay]):
     * this stage clips its children.
     */
    fun beam(): FloatArray? {
        if (!(lightsOn && dark) || box.width() <= 0f) return null
        val l = if (threeQuarter) q34 else side
        val k = box.width() / l.w
        return floatArrayOf(box.left + l.beam[0] * k, box.top + l.beam[1] * k, l.beam[2] * k, l.beam[3] * k, l.beam[4])
    }

    private companion object {
        const val LIGHT = 0xFFDCEBFF.toInt()
        const val TAIL = 0xFFFF2A1E.toInt()
    }
}
