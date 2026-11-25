package app.mg4dash

import android.app.Activity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.ui.Rows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A window's content keeps within its room, so its buttons stay in sight (25/11: long release notes hid Install). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WindowContentBoundTest {
    private fun measuredHeight(v: View): Int {
        v.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return v.measuredHeight
    }

    @Test fun longContentIsBounded() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val notes = (1..300).joinToString("\n") { "Line $it of the release notes" }
        val room = 400
        val ownScroll = ScrollView(a).apply { addView(TextView(a).apply { text = notes }) }
        assertTrue("a content that scrolls by itself", measuredHeight(Rows.scrolling(a, ownScroll, room)) <= room)
        assertTrue("a plain content", measuredHeight(Rows.scrolling(a, TextView(a).apply { text = notes }, room)) <= room)
    }
}
