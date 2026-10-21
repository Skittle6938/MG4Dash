package app.mg4dash.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import app.mg4dash.R

/**
 * Panneaux de l'accueil dans le style du tableau de bord MG (issue #38) : pas de fond plein, un
 * contour fin, clair en haut, qui s'estompe vers le bas. La barre verticale garde son fond.
 */
class FadeOutline(private val d: Float, color: Int) : Drawable() {
    private val rgb = color and 0x00FFFFFF
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(rgb or (0x8C shl 24), rgb or (0x40 shl 24), rgb), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val h = p.strokeWidth
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h)
        c.drawRoundRect(rect, RADIUS * d, RADIUS * d, p)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val RADIUS = 14f

        /**
         * Applique le style aux panneaux (fond bg_panel avec l'élévation des panneaux) une fois la
         * mise en page faite ; la barre verticale (étroite) est laissée telle quelle. La carte
         * reçoit les mêmes coins arrondis et le même contour.
         */
        fun applyTo(root: View) {
            root.post {
                val d = root.resources.displayMetrics.density
                val color = Themes.color(root.context, R.color.text_sub)
                fun walk(v: View) {
                    if (v.elevation >= 5.5f * d && v.background != null && v.width > 120 * d) {
                        v.background = FadeOutline(d, color); v.elevation = 0f
                    }
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(root)
                root.findViewById<View>(R.id.mapCard)?.let { m ->
                    m.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, RADIUS * d) }
                    }
                    m.clipToOutline = true
                    m.foreground = FadeOutline(d, color)
                }
            }
        }
    }
}
