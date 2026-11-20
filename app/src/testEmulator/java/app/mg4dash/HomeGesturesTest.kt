package app.mg4dash

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import app.mg4dash.ui.PinchToEdit
import app.mg4dash.ui.EdgeSwipe
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

    // ── One finger in from a side edge (20/11): the phone's projection ──

    private var opened = 0
    private fun edge() = EdgeSwipe(activity) { opened++ }

    /** One finger from [x0], [y0] to [x1], [y1] in [steps] moves; true if the gesture took the touch. */
    private fun swipe(g: EdgeSwipe, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 6): Boolean {
        var took = g.onTouch(one(MotionEvent.ACTION_DOWN, x0, y0), 1920, cancel)
        for (k in 1..steps) took = g.onTouch(one(MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * k / steps, y0 + (y1 - y0) * k / steps, k * 16L), 1920, cancel) || took
        g.onTouch(one(MotionEvent.ACTION_UP, x1, y1, (steps + 1) * 16L), 1920, cancel)
        return took
    }

    @Test fun fromTheLeftEdgeIsRecognised() {
        assertTrue(swipe(edge(), 10f, 360f, 220f, 380f))
        assertEquals(listOf(MotionEvent.ACTION_CANCEL), cancels)
        assertEquals(1, opened)
    }

    @Test fun fromTheRightEdgeIsRecognised() {
        assertTrue(swipe(edge(), 1910f, 300f, 1700f, 280f))
        assertEquals(1, opened)
    }

    @Test fun startingAwayFromTheEdgeDoesNothing() {
        // 60 px in: a swipe on a card or the map (the band is 32 dp)
        assertFalse(swipe(edge(), 60f, 360f, 400f, 360f))
        assertFalse(swipe(edge(), 1860f, 360f, 1500f, 360f))
        assertTrue(cancels.isEmpty()); assertEquals(0, opened)
    }

    @Test fun shortOrOutwardsDoesNothing() {
        assertFalse(swipe(edge(), 10f, 360f, 110f, 360f))      // 100 px, under 120 dp
        assertFalse(swipe(edge(), 1910f, 360f, 1919f, 360f))   // outwards
        assertEquals(0, opened)
    }

    @Test fun slantedDoesNothing() {
        // In far enough, but more up than half of the way in
        assertFalse(swipe(edge(), 10f, 200f, 200f, 500f))
        assertEquals(0, opened)
    }

    @Test fun aSecondFingerEndsIt() {
        val g = edge()
        g.onTouch(one(MotionEvent.ACTION_DOWN, 10f, 360f), 1920, cancel)
        g.onTouch(many(second(MotionEvent.ACTION_POINTER_DOWN), 10, 10f to 360f, 400f to 360f), 1920, cancel)
        assertFalse(g.onTouch(many(MotionEvent.ACTION_MOVE, 30, 300f to 360f, 700f to 360f), 1920, cancel))
        assertEquals(0, opened)
    }
}