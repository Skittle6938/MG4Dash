package app.mg4dash

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import app.mg4dash.ui.PinchToEdit
import app.mg4dash.ui.TwoFingerPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home screen gestures read in the activity's touch handling: the pinch that opens the cards' edit mode (#97),
 * and two fingers pulled down from the top. Density 1 (mdpi): dp = px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class HomeGesturesTest {
    private lateinit var activity: Activity
    private val t0 = SystemClock.uptimeMillis()
    private var pinches = 0
    private val cancels = mutableListOf<Int>()
    private val cancel: (MotionEvent) -> Unit = { cancels += it.actionMasked }

    @Before fun setUp() { activity = Robolectric.buildActivity(Activity::class.java).setup().get() }

    private fun one(action: Int, x: Float, y: Float, at: Long = 0) = MotionEvent.obtain(t0, t0 + at, action, x, y, 0)

    private fun many(action: Int, at: Long, vararg p: Pair<Float, Float>): MotionEvent {
        val props = Array(p.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(p.size) { i -> MotionEvent.PointerCoords().apply { x = p[i].first; y = p[i].second } }
        return MotionEvent.obtain(t0, t0 + at, action, p.size, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    private fun second(action: Int) = action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** Two fingers from [a0], [b0] to [a1], [b1]; true when one of the events was taken. */
    private fun twoFingers(g: (MotionEvent) -> Boolean, a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>, steps: Int = 4): Boolean {
        var taken = g(one(MotionEvent.ACTION_DOWN, a0.first, a0.second))
        taken = g(many(second(MotionEvent.ACTION_POINTER_DOWN), 10, a0, b0)) || taken
        for (s in 1..steps) {
            val f = s.toFloat() / steps
            fun mid(p: Pair<Float, Float>, q: Pair<Float, Float>) = (p.first + (q.first - p.first) * f) to (p.second + (q.second - p.second) * f)
            taken = g(many(MotionEvent.ACTION_MOVE, 10L + s * 16, mid(a0, a1), mid(b0, b1))) || taken
        }
        taken = g(many(second(MotionEvent.ACTION_POINTER_UP), 200, a1, b1)) || taken
        taken = g(one(MotionEvent.ACTION_UP, a1.first, a1.second, 210)) || taken
        return taken
    }

    // ── Pinch to edit ──

    private fun pinch(excluded: (Float, Float) -> Boolean = { _, _ -> false }) = PinchToEdit(activity, excluded) { pinches++ }

    @Test fun pinchInOpensTheEditModeOnce() {
        val p = pinch()
        assertTrue(twoFingers({ p.onTouch(it, cancel) }, 600f to 360f, 1200f to 360f, 860f to 360f, 940f to 360f))
        assertEquals(1, pinches)
        // The widgets are told the touch is over
        assertEquals(listOf(MotionEvent.ACTION_CANCEL), cancels)
    }

    @Test fun eventsAfterThePinchStayTaken() {
        val p = pinch()
        p.onTouch(one(MotionEvent.ACTION_DOWN, 600f, 360f), cancel)
        p.onTouch(many(second(MotionEvent.ACTION_POINTER_DOWN), 10, 600f to 360f, 1200f to 360f), cancel)
        assertTrue(p.onTouch(many(MotionEvent.ACTION_MOVE, 50, 880f to 360f, 920f to 360f), cancel))
        assertTrue("the rest of the gesture is the pinch's", p.onTouch(many(MotionEvent.ACTION_MOVE, 70, 870f to 360f, 930f to 360f), cancel))
        assertTrue(p.onTouch(one(MotionEvent.ACTION_UP, 870f, 360f, 90), cancel))
        assertEquals(1, pinches)
        // A new touch starts again
        assertFalse(p.onTouch(one(MotionEvent.ACTION_DOWN, 600f, 360f, 500), cancel))
    }

    @Test fun pinchCountsAgainOnTheNextGesture() {
        val p = pinch()
        twoFingers({ p.onTouch(it, cancel) }, 600f to 360f, 1200f to 360f, 860f to 360f, 940f to 360f)
        twoFingers({ p.onTouch(it, cancel) }, 600f to 200f, 1200f to 500f, 860f to 340f, 940f to 360f)
        assertEquals(2, pinches)
    }

    @Test fun fingersTooCloseAtTheStartDoNothing() {
        val p = pinch()
        // 150 px apart (less than 160 dp)
        assertFalse(twoFingers({ p.onTouch(it, cancel) }, 825f to 360f, 975f to 360f, 895f to 360f, 905f to 360f))
        assertEquals(0, pinches)
        assertTrue(cancels.isEmpty())
    }

    @Test fun smallPinchDoesNothing() {
        val p = pinch()
        // 600 → 520 px: less than 110 dp closer
        assertFalse(twoFingers({ p.onTouch(it, cancel) }, 600f to 360f, 1200f to 360f, 640f to 360f, 1160f to 360f))
        assertEquals(0, pinches)
    }

    @Test fun closerButNotEnoughInProportionDoesNothing() {
        val p = pinch()
        // 1000 → 700 px: 300 closer, but still 70 % of the start (needs under 65 %)
        assertFalse(twoFingers({ p.onTouch(it, cancel) }, 400f to 360f, 1400f to 360f, 550f to 360f, 1250f to 360f))
        assertEquals(0, pinches)
    }

    @Test fun spreadingDoesNothing() {
        val p = pinch()
        assertFalse(twoFingers({ p.onTouch(it, cancel) }, 860f to 360f, 1060f to 360f, 400f to 360f, 1500f to 360f))
        assertEquals(0, pinches)
    }

    @Test fun pinchOnTheMapIsTheMapsZoom() {
        // A finger in an area that zooms (the map, a web page, an app): the pinch is left to it
        val p = pinch { x, _ -> x < 700f }
        assertFalse(twoFingers({ p.onTouch(it, cancel) }, 600f to 360f, 1200f to 360f, 860f to 360f, 940f to 360f))
        assertEquals(0, pinches)
    }

    @Test fun thirdFingerStopsIt() {
        val p = pinch()
        p.onTouch(one(MotionEvent.ACTION_DOWN, 600f, 360f), cancel)
        p.onTouch(many(second(MotionEvent.ACTION_POINTER_DOWN), 10, 600f to 360f, 1200f to 360f), cancel)
        p.onTouch(many(MotionEvent.ACTION_MOVE, 30, 610f to 360f, 1190f to 360f, 900f to 100f), cancel)
        assertFalse(p.onTouch(many(MotionEvent.ACTION_MOVE, 50, 880f to 360f, 920f to 360f), cancel))
        assertEquals(0, pinches)
    }

    @Test fun oneFingerIsNeverAPinch() {
        val p = pinch()
        assertFalse(p.onTouch(one(MotionEvent.ACTION_DOWN, 600f, 360f), cancel))
        assertFalse(p.onTouch(one(MotionEvent.ACTION_MOVE, 900f, 360f, 30), cancel))
        assertFalse(p.onTouch(one(MotionEvent.ACTION_UP, 900f, 360f, 60), cancel))
        assertEquals(0, pinches)
    }

    // ── Two fingers pulled down ──

    @Test fun pullFromTheTopIsRecognised() {
        val g = TwoFingerPull(activity)
        assertTrue(twoFingers({ g.onTouch(it, cancel) }, 800f to 40f, 1000f to 60f, 820f to 300f, 1010f to 320f))
        assertEquals(listOf(MotionEvent.ACTION_CANCEL), cancels)
    }

    @Test fun pullStartingLowDoesNothing() {
        val g = TwoFingerPull(activity)
        // One finger under the top zone (130 dp): moving the map with two fingers
        assertFalse(twoFingers({ g.onTouch(it, cancel) }, 800f to 40f, 1000f to 200f, 820f to 300f, 1010f to 460f))
        assertTrue(cancels.isEmpty())
    }

    @Test fun shortPullDoesNothing() {
        val g = TwoFingerPull(activity)
        assertFalse(twoFingers({ g.onTouch(it, cancel) }, 800f to 40f, 1000f to 60f, 800f to 120f, 1000f to 140f))
    }

    @Test fun slantedPullDoesNothing() {
        val g = TwoFingerPull(activity)
        // Down far enough but 300 px sideways (more than 150 dp)
        assertFalse(twoFingers({ g.onTouch(it, cancel) }, 800f to 40f, 1000f to 60f, 1100f to 300f, 1300f to 320f))
    }

    @Test fun pullUpDoesNothing() {
        val g = TwoFingerPull(activity)
        assertFalse(twoFingers({ g.onTouch(it, cancel) }, 800f to 120f, 1000f to 120f, 800f to 0f, 1000f to 0f))
    }
}
