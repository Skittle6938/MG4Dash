package app.mg4dash

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import app.mg4dash.ui.NavCard
import app.mg4dash.ui.SwipeBox
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A place's swipe goes through any card (19/11: the guidance card could not be swiped): one that takes touches
 * (a tile, a button) and one with nothing to touch (the guidance card), the same way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SwipeThroughCardTest {

    private fun swipe(content: View, dx: Float, dy: Float = 0f): List<Int> {
        val got = ArrayList<Int>()
        val box = SwipeBox(RuntimeEnvironment.getApplication()) { got += it }
        box.addView(content, FrameLayout.LayoutParams(-1, -1))
        box.measure(View.MeasureSpec.makeMeasureSpec(594, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(410, View.MeasureSpec.EXACTLY))
        box.layout(0, 0, 594, 410)
        val t = SystemClock.uptimeMillis()
        fun ev(a: Int, x: Float, y: Float, dt: Long) = MotionEvent.obtain(t, t + dt, a, x, y, 0)
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, 300f, 200f, 0))
        for (k in 1..5) box.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, 300f + dx * k / 5, 200f + dy * k / 5, k * 16L))
        box.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, 300f + dx, 200f + dy, 100))
        return got
    }

    @Test fun guidanceCardSwipes() {
        val nav = NavCard(RuntimeEnvironment.getApplication()).apply { show(null, null) }
        assertEquals("left = next", listOf(1), swipe(nav, -200f))
        assertEquals("right = previous", listOf(-1), swipe(NavCard(RuntimeEnvironment.getApplication()), 200f))
    }

    @Test fun cardTakingTouchesSwipesTheSame() {
        val tile = View(RuntimeEnvironment.getApplication()).apply { setOnClickListener { } }
        assertEquals(listOf(1), swipe(tile, -200f))
    }

    @Test fun aTapIsNoSwipe() {
        assertEquals(emptyList<Int>(), swipe(NavCard(RuntimeEnvironment.getApplication()), 5f))
    }
}
