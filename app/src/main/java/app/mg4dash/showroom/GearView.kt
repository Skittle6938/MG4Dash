package app.mg4dash.showroom

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import app.mg4dash.R
import app.mg4dash.ui.Kit
import app.mg4dash.ui.Themes

/**
 * The gear lever's position on the Vehicle page (27/11, mock-up approved by Marc): the letters P R N D, the one engaged
 * big and underlined; shown, not set (no chip to press). The car gives no parking brake state: the red (P) telltale on
 * the left is lit in P, where the MG4 applies its electric brake by itself.
 */
class GearView(context: Context, private val kit: Kit) : View(context) {
    /** 1 = P, 2 = R, 3 = N, 4 = D; anything else: nothing to show (the stage hides the block). */
    var gear: Int? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val cell = kit.dp(38).toFloat()
    private val epbW = kit.dp(42).toFloat(); private val epbH = kit.dp(30).toFloat(); private val epbGap = kit.dp(12).toFloat()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val on = Themes.color(context, R.color.text_value)
    private val off = Themes.color(context, R.color.text_sub)
    private val action = Themes.color(context, R.color.accent_action)
    private val danger = Themes.color(context, R.color.accent_danger)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // the telltale's and the underline's glow

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension((epbW + epbGap + 4 * cell).toInt(), kit.dp(46))

    override fun onDraw(c: Canvas) {
        val g = gear ?: return
        val x0 = width - 4 * cell
        val mid = kit.dp(21).toFloat()
        for (i in 0 until 4) {
            val active = i == g - 1
            text.color = if (active) on else off
            text.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, if (active) 34f else 26f, resources.displayMetrics)
            val cx = x0 + cell * (i + .5f)
            c.drawText(GEARS[i], cx, mid - (text.descent() + text.ascent()) / 2, text)
            if (active) {
                val y = mid + kit.dp(21)
                line.color = action; line.maskFilter = BlurMaskFilter(kit.dp(5).toFloat(), BlurMaskFilter.Blur.NORMAL); line.alpha = 160
                c.drawRoundRect(cx - cell / 2 + kit.dp(8), y, cx + cell / 2 - kit.dp(8), y + kit.dp(3), kit.dp(2).toFloat(), kit.dp(2).toFloat(), line)
                line.maskFilter = null; line.alpha = 255
                c.drawRoundRect(cx - cell / 2 + kit.dp(8), y, cx + cell / 2 - kit.dp(8), y + kit.dp(3), kit.dp(2).toFloat(), kit.dp(2).toFloat(), line)
            }
        }
        if (g == 1) telltale(c, x0 - epbGap - epbW, mid - epbH / 2)
    }

    /** The parking brake's (P) telltale, drawn from the mock-up's 36 × 26 outline. */
    private fun telltale(c: Canvas, left: Float, top: Float) {
        c.save(); c.translate(left, top); c.scale(epbW / 36f, epbH / 26f)
        icon.color = danger
        val p = Path().apply {
            addCircle(18f, 13f, 8.6f, Path.Direction.CW)
            // The two brackets: radius 14 arcs through (5.9, 4.2)-(5.9, 21.8) and (30.1, 4.2)-(30.1, 21.8)
            addArc(RectF(2.8f, -1f, 30.8f, 27f), 141f, 78f)
            addArc(RectF(5.2f, -1f, 33.2f, 27f), -39f, 78f)
        }
        val pLetter = Path().apply { moveTo(15.7f, 17.6f); lineTo(15.7f, 8.6f); lineTo(18.7f, 8.6f); arcTo(RectF(16f, 8.6f, 21.4f, 14f), -90f, 180f); lineTo(15.7f, 14f) }
        for (glow in listOf(true, false)) {
            icon.maskFilter = if (glow) BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL) else null
            icon.alpha = if (glow) 140 else 255
            icon.strokeWidth = 2.2f; c.drawPath(p, icon)
            icon.strokeWidth = 2f; c.drawPath(pLetter, icon)
        }
        c.restore()
    }

    private companion object { val GEARS = arrayOf("P", "R", "N", "D") }
}
