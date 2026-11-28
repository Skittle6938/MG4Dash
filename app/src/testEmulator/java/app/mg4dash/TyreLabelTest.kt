package app.mg4dash

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.showroom.CarLook
import app.mg4dash.showroom.CarStage
import app.mg4dash.showroom.TyreStatus
import app.mg4dash.ui.Kit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class TyreLabelTest {
    @Test fun pressuresAndTitleFitWithAndWithoutAStatus() {
        val context = RuntimeEnvironment.getApplication()
        val stage = CarStage(context, Kit(context), CarLook(context)) {}
        val axle = stage.front
        for (status in listOf(TyreStatus.NORMAL, TyreStatus.WATCH, TyreStatus.LOW_PRESSURE, TyreStatus.SENSOR_WARNING, TyreStatus.NORMAL)) {
            axle.set(true, "2,38 bar", "gauche · 8 °C", status)
            axle.set(false, "2,51 bar", "droite · 9 °C", TyreStatus.NORMAL)
            val width = View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST)
            val height = View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST)
            axle.view.measure(width, height)
            axle.view.layout(0, 0, axle.view.measuredWidth, axle.view.measuredHeight)

            val title = axle.view.getChildAt(0) as TextView
            val row = axle.view.getChildAt(1) as LinearLayout
            val left = row.getChildAt(0) as LinearLayout
            val right = row.getChildAt(2) as LinearLayout
            val texts = listOf(title, left.getChildAt(0), left.getChildAt(1), left.getChildAt(2),
                right.getChildAt(0), right.getChildAt(1))
            for (text in texts.filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE }) {
                assertTrue("$status: ${text.text} needs ${text.paint.measureText(text.text.toString())} px, has ${text.width} px",
                    text.width >= text.paint.measureText(text.text.toString()).toInt())
            }
        }
    }
}
