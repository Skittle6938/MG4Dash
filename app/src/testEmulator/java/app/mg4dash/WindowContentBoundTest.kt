package app.mg4dash

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.ui.Rows
import app.mg4dash.ui.Windows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A window's content keeps within its room, so its buttons stay in sight (25/11: long release notes hid Install). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class WindowContentBoundTest {
    private val notes = (1..300).joinToString("\n") { "Line $it of the release notes" }

    private fun measuredHeight(v: View): Int {
        v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return v.measuredHeight
    }

    @Test fun longContentIsBounded() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val room = 400
        val ownScroll = ScrollView(a).apply { addView(TextView(a).apply { text = notes }) }
        assertTrue("a content that scrolls by itself", measuredHeight(Rows.scrolling(a, ownScroll, room)) <= room)
        assertTrue("a plain content", measuredHeight(Rows.scrolling(a, TextView(a).apply { text = notes }, room)) <= room)
    }

    /** MG4Dash's notes (their own ScrollView), Radio Dash's and AirLink's (a message): Install whole, with its margin. */
    @Test fun installButtonWholeInTheWindow() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setTheme(R.style.Theme_MG4Launcher)
        for (own in listOf(true, false)) for (limit in listOf(720, 640, 560)) {
            val content: View = if (own) ScrollView(a).apply { addView(TextView(a).apply { text = notes }) } else Windows.message(a, notes)
            val install = Rows.actionButton(a, "Install") {}
            val d = Windows.show(a, "MG4Dash 1.7.4-beta21", content, widthDp = 1100, buttons = listOf(install))
            shadowOf(Looper.getMainLooper()).idle()
            val decor = d.window!!.decorView
            // The window manager's passes at this height
            repeat(2) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
                decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
                shadowOf(Looper.getMainLooper()).idle()
            }
            val at = IntArray(2); install.getLocationInWindow(at)
            val bottom = at[1] + install.height
            assertTrue("own=$own limit=$limit: window ${decor.measuredHeight}", decor.measuredHeight <= limit)
            assertTrue("own=$own limit=$limit: Install full height (${install.height})", install.height >= 56)
            assertTrue("own=$own limit=$limit: Install bottom $bottom keeps its margin in ${decor.measuredHeight}", bottom <= decor.measuredHeight - 20)
            d.dismiss()
        }
    }
}
