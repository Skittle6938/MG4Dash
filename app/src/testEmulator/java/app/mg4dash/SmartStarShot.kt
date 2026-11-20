package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import app.mg4dash.ui.CenteredIconSpan
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The smart mode's star sits with the word AUTO in the middle of its wide button (Marc, 20/11), not at its left edge. */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class SmartStarShot {
    @Test fun starAndWordCentredTogether() {
        val ctx = RuntimeEnvironment.getApplication()
        val star = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_smart)!!.mutate().apply { setTint(Color.WHITE); setBounds(0, 0, 20, 20) }
        val tv = TextView(ctx).apply {
            gravity = Gravity.CENTER; textSize = 18f; setTextColor(Color.WHITE); setBackgroundColor(Color.BLACK)
            text = android.text.SpannableString("*AUTO").apply { setSpan(CenteredIconSpan(star, 6), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        val w = 300; val h = 60
        tv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        tv.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        tv.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "smart_auto_star.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun ink(x: Int) = (0 until h).any { Color.red(bmp.getPixel(x, it)) > 128 }
        val left = (0 until w).first { ink(it) }; val right = (w - 1 downTo 0).first { ink(it) }
        // The ink (star + word) is centred: same margin on each side, and the star far from the left edge
        assertTrue("left $left right $right", Math.abs(left - (w - 1 - right)) <= 8)
        assertTrue("left $left", left > 60)
        // The star is there, vertically within the word's line
        assertTrue((0 until h).any { Color.red(bmp.getPixel(left + 10, it)) > 128 })
    }
}