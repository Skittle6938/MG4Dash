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
 * Home panels in the style of the MG dashboard (issue #38): no solid background, a
 * thin outline, light at the top, fading downwards. The vertical bar keeps its background.
 */
class FadeOutline(private val d: Float, color: Int, private val fade: Boolean = true) : Drawable() {
    private val rgb = color and 0x00FFFFFF
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val rect = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        p.shader = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            // Full outline (#40): same tint all around, not fading at the bottom
            if (fade) intArrayOf(rgb or (0x8C shl 24), rgb or (0x40 shl 24), rgb) else intArrayOf(rgb or (0x70 shl 24), rgb or (0x70 shl 24), rgb or (0x70 shl 24)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        val h = p.strokeWidth
        rect.set(b.left + h, b.top + h, b.right - h, b.bottom - h)
        // Taken in by its inset: its curve follows the card's corner (the clip of a map, a page, a detached window)
        val r = inset(RADIUS * d, h)
        c.drawRoundRect(rect, r, r, p)
    }

    override fun setAlpha(a: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT

    /**
     * Frame of a page or an app awake (#98): the same corners as the card's clip, its outer edge on the card's edge.
     * [radius]: the corner of what it is drawn over, the cards' by default; a detached window's in the move mode is
     * larger, by its frame.
     */
    class Awake(private val d: Float, color: Int, width: Float, private val radius: Float = RADIUS * d) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = width; this.color = color }
        private val rect = RectF()
        override fun draw(c: Canvas) {
            val h = p.strokeWidth / 2
            rect.set(bounds.left + h, bounds.top + h, bounds.right - h, bounds.bottom - h)
            val r = inset(radius, h)
            c.drawRoundRect(rect, r, r, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(cf: ColorFilter?) {}
        @Suppress("OVERRIDE_DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    companion object {
        /**
         * Corner radius of every card, in dp, the one used everywhere (#15): the panels' outline, the clip of the map,
         * a page or an app, the player, the detached windows at rest and awake, their accent outline.
         */
        const val RADIUS = 14f

        /** [RADIUS] in pixels. */
        fun radius(c: android.content.Context) = RADIUS * c.resources.displayMetrics.density

        /** Radius of a line drawn [by] px inside a corner of radius [r]: the same curve, concentric. */
        fun inset(r: Float, by: Float) = (r - by).coerceAtLeast(0f)

        /** Tag of a panel filled to its edges (the map, a web page): rounded corners, the outline drawn over it. */
        const val FRONT = "fade_outline_front"

        /**
         * Applies the style to the panels (bg_panel background with the panels' elevation) once
         * layout is done; the (narrow) vertical bar is left as it is. The map and the web page
         * receive the same rounded corners and the same outline, drawn over them.
         * [more]: home cards left out of the screen for now (#97), styled for when they are placed.
         */
        fun applyTo(root: View, more: Collection<View> = emptyList()) {
            root.post {
                val d = root.resources.displayMetrics.density
                val color = Themes.color(root.context, R.color.text_sub)
                val fade = app.mg4dash.system.Prefs(root.context).panelFade
                fun style(v: View) {
                    v.background = FadeOutline(d, color, fade); v.elevation = 0f
                    if (v.tag == FRONT) {
                        v.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, RADIUS * d) }
                        }
                        v.clipToOutline = true
                        v.foreground = FadeOutline(d, color, fade)
                    }
                }
                fun walk(v: View) {
                    // Not the guidance banner: already shown when home is rebuilt during a trip, it lost its background
                    if (v !is NavBanner && v.elevation >= 5.5f * d && v.background != null && v.width > 120 * d) style(v)
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(root)
                more.filter { !it.isAttachedToWindow && it.elevation >= 5.5f * d && it.background != null }.forEach { style(it) }
            }
        }
    }
}
