package app.mg4dash.ui

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

/** Container with rounded corners (20 dp by default, like the panels) that crops its content: used for the map. */
class RoundedFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, radiusDp: Float = 20f,
) : FrameLayout(context, attrs) {
    private val radius = resources.displayMetrics.density * radiusDp

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }
}
