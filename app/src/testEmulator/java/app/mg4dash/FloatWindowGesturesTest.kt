package app.mg4dash

import android.app.Activity
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.mg4dash.ui.FloatWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Detached window (web app, map / app card, #98) on its own, without home: first place, kept place, and the touches
 * of the move mode (move, corner, edge, two fingers), the double tap back to the card and the long press menu.
 * Density 1 (mdpi): dp = px.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class FloatWindowGesturesTest {
    private val bounds = Rect(0, 0, 1800, 700)
    private lateinit var activity: Activity
    private var saved: IntArray? = null
    private val kept = mutableListOf<IntArray>()
    private var attached = 0
    private var menus = 0
    private var t0 = 0L
    /** Where the cards' layer is on screen (under a title bar, if any): the touches are given in its coordinates. */
    private var ox = 0f
    private var oy = 0f

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setContentView(FrameLayout(activity))
        // Far from 0: the first touch is never taken for the second of a double tap
        t0 = SystemClock.uptimeMillis() + 100_000L
        idle()
        val at = IntArray(2).also { activity.findViewById<View>(android.R.id.content).getLocationOnScreen(it) }
        ox = at[0].toFloat(); oy = at[1].toFloat()
    }

    private fun window(rect: IntArray? = null): FloatWindow {
        saved = rect
        val dock = View(activity)
        (activity.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout).addView(dock, FrameLayout.LayoutParams(400, 300))
        return FloatWindow(
            activity, panel = FrameLayout(activity), dock = dock, bounds = { Rect(bounds) },
            onAttach = { attached++ }, onMenu = { menus++ }, onMoved = {}, keepAwake = { false },
            savedRect = { saved }, keepRect = { kept += it; saved = it },
        ).also { it.open(); idle() }
    }

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun last(): Rect = kept.last().let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }

    private fun ev(action: Int, x: Float, y: Float, at: Long) = MotionEvent.obtain(t0, t0 + at, action, x + ox, y + oy, 0)

    private fun two(action: Int, at: Long, x0: Float, y0: Float, x1: Float, y1: Float): MotionEvent {
        val props = Array(2) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = x0 + ox; y = y0 + oy }, MotionEvent.PointerCoords().apply { x = x1 + ox; y = y1 + oy })
        return MotionEvent.obtain(t0, t0 + at, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** One finger from ([x0], [y0]) to ([x1], [y1]), let go there. */
    private fun slide(w: FloatWindow, x0: Float, y0: Float, x1: Float, y1: Float) {
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, x0, y0, 0)))
        w.onTouch(ev(MotionEvent.ACTION_MOVE, (x0 + x1) / 2, (y0 + y1) / 2, 30))
        w.onTouch(ev(MotionEvent.ACTION_MOVE, x1, y1, 60))
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_UP, x1, y1, 90)))
        idle()
    }

    // ── Place when detached ──

    @Test fun firstTimeInTheMiddleSixteenNinthsWithinTheCards() {
        val w = window()
        w.setFloating(true)
        assertTrue(w.floating)
        val r = last()
        assertTrue("inside $r", bounds.contains(r))
        assertEquals(bounds.centerX().toFloat(), r.exactCenterX(), 1.5f)
        assertEquals(bounds.centerY().toFloat(), r.exactCenterY(), 1.5f)
        assertEquals(16f / 9f, r.width().toFloat() / r.height(), 0.01f)
        assertTrue("at most 85 % of the height", r.height() <= bounds.height() * 0.85f)
    }

    @Test fun keptPlaceComesBack() {
        val w = window(intArrayOf(200, 150, 640, 360))
        w.setFloating(true)
        assertEquals(Rect(200, 150, 840, 510), last())
    }

    @Test fun keptPlaceOutsideTheCardsIsBroughtBackIn() {
        // Saved with another layout or the bar elsewhere: the window comes back in, its size kept
        val w = window(intArrayOf(1700, 650, 400, 300))
        w.setFloating(true)
        assertEquals(Rect(1400, 400, 1800, 700), last())
    }

    @Test fun keptPlaceTooSmallGetsTheSmallestSize() {
        // At least 240 dp or a sixth of the width, 150 dp or a quarter of the height
        val w = window(intArrayOf(100, 100, 50, 40))
        w.setFloating(true)
        val r = last()
        assertEquals(300, r.width())
        assertEquals(175, r.height())
    }

    @Test fun dockedAgain() {
        val w = window(intArrayOf(200, 150, 640, 360))
        w.setFloating(true); w.startMoving()
        assertTrue(w.moving)
        w.setFloating(false)
        assertFalse(w.floating)
        assertFalse("docking ends the move mode", w.moving)
        // Docked, the touches are not taken
        assertFalse(w.onTouch(ev(MotionEvent.ACTION_DOWN, 300f, 300f, 0)))
    }

    @Test fun moveModeOnlyWhenDetached() {
        val w = window()
        w.startMoving()
        assertFalse(w.moving)
    }

    // ── Move mode ──

    @Test fun slideMovesItSizeKept() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 400f, 300f, 700f, 400f)
        assertEquals(Rect(400, 200, 1000, 600), last())
        assertTrue("still in the move mode: only OK ends it", w.moving)
    }

    @Test fun slideNearAnEdgeSnapsOnIt() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        // 10 px from the bottom of the cards' area: on it
        slide(w, 400f, 300f, 400f, 490f)
        assertEquals(Rect(100, 300, 700, 700), last())
    }

    @Test fun slideCannotTakeItOutOfTheCards() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 400f, 300f, 3000f, -800f)
        assertEquals(Rect(1200, 0, 1800, 400), last())
    }

    @Test fun cornerResizesWithTheRatioKept() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        // Bottom right corner pulled by 300 × 200: one and a half times bigger, top left fixed
        slide(w, 690f, 490f, 990f, 690f)
        assertEquals(Rect(100, 100, 1000, 700), last())
    }

    @Test fun cornerStopsAtTheCardsArea() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 690f, 490f, 1690f, 1490f)
        val r = last()
        assertTrue("inside $r", bounds.contains(r))
        assertEquals(1.5f, r.width().toFloat() / r.height(), 0.01f)
    }

    @Test fun topLeftCornerKeepsTheBottomRightFixed() {
        val w = window(intArrayOf(400, 200, 600, 400))
        w.setFloating(true); w.startMoving()
        // Pulled inwards: half the size, bottom right still at (1000, 600)
        slide(w, 410f, 210f, 710f, 410f)
        assertEquals(Rect(700, 400, 1000, 600), last())
    }

    @Test fun edgeChangesTheWidthAlone() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 690f, 300f, 890f, 300f)
        assertEquals(Rect(100, 100, 900, 500), last())
    }

    @Test fun bottomEdgeChangesTheHeightAlone() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 400f, 490f, 400f, 390f)
        assertEquals(Rect(100, 100, 700, 400), last())
    }

    @Test fun edgeNeverUnderTheSmallestSize() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        slide(w, 690f, 300f, -500f, 300f)
        assertEquals(300, last().width())
    }

    @Test fun touchJustPastAnEdgeTakesIt() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        // 20 px right of the window (within 32 dp): its right edge
        slide(w, 720f, 300f, 920f, 300f)
        assertEquals(Rect(100, 100, 900, 500), last())
    }

    @Test fun handleTouchedNotPulledChangesNothing() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        val before = kept.size
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 690f, 490f, 0)))
        w.onTouch(ev(MotionEvent.ACTION_MOVE, 695f, 492f, 30))
        w.onTouch(ev(MotionEvent.ACTION_UP, 695f, 492f, 60))
        assertEquals(before, kept.size)
        assertTrue(w.moving)
    }

    @Test fun farTouchIsTakenButDoesNothing() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        val before = kept.size
        // The cards under it see none of it, and the move mode goes on
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 1500f, 600f, 0)))
        w.onTouch(ev(MotionEvent.ACTION_MOVE, 1300f, 500f, 30))
        w.onTouch(ev(MotionEvent.ACTION_UP, 1300f, 500f, 60))
        assertEquals(before, kept.size)
        assertTrue(w.moving)
    }

    @Test fun flatTwoFingersChangeTheWidthAlone() {
        val w = window(intArrayOf(500, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 700f, 300f, 0)))
        w.onTouch(two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10, 700f, 300f, 900f, 300f))
        // 200 → 400 apart: twice the width around the middle (800), the height kept
        w.onTouch(two(MotionEvent.ACTION_MOVE, 40, 600f, 300f, 1000f, 300f))
        w.onTouch(two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 70, 600f, 300f, 1000f, 300f))
        w.onTouch(ev(MotionEvent.ACTION_UP, 600f, 300f, 80))
        idle()
        assertEquals(Rect(200, 100, 1400, 500), last())
    }

    @Test fun slantedTwoFingersKeepTheRatio() {
        val w = window(intArrayOf(500, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 700f, 200f, 0)))
        w.onTouch(two(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10, 700f, 200f, 900f, 400f))
        // Fingers half as far apart: half the size around the middle (800, 300)
        w.onTouch(two(MotionEvent.ACTION_MOVE, 40, 750f, 250f, 850f, 350f))
        w.onTouch(two(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 70, 750f, 250f, 850f, 350f))
        w.onTouch(ev(MotionEvent.ACTION_UP, 750f, 250f, 80))
        idle()
        val r = last()
        assertEquals(1.5f, r.width().toFloat() / r.height(), 0.02f)
        assertEquals(300, r.width())
        assertEquals(800f, r.exactCenterX(), 1f)
    }

    @Test fun stopMovingEndsTheMode() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true); w.startMoving()
        w.stopMoving()
        assertFalse(w.moving)
        assertTrue(w.floating)
    }

    // ── At rest ──

    @Test fun doubleTapPutsItBackInItsCard() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 0)))
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 50)))
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 120)))
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 170)))
        assertEquals(1, attached)
        idle(ViewConfiguration.getDoubleTapTimeout() + 50L)
        assertFalse("a double tap does not wake it", w.awake)
    }

    @Test fun singleTouchWakesItThenItRests() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 0)))
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 50)))
        assertFalse(w.awake)
        idle(ViewConfiguration.getDoubleTapTimeout() + 50L)
        assertTrue(w.awake)
        assertTrue(w.outlineLit)
        assertEquals(0, attached)
        // Awake, the touches go to the page
        assertFalse(w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 1000)))
        assertTrue(w.through)
        w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 1050))
        // Left alone for the time set (10 s by default): at rest again
        idle(11_000)
        assertFalse(w.awake)
        assertFalse(w.outlineLit)
    }

    @Test fun longPressOpensTheMenuWhenLetGo() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        assertTrue(w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 0)))
        idle(ViewConfiguration.getLongPressTimeout() + 50L)
        assertEquals("not before it is let go", 0, menus)
        w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 700))
        assertEquals(1, menus)
        assertEquals(0, attached)
    }

    @Test fun slideAtRestDoesNothing() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        val before = kept.size
        slide(w, 400f, 300f, 700f, 300f)
        idle(1000)
        assertEquals(before, kept.size)
        assertEquals(0, menus)
        assertEquals(0, attached)
    }

    @Test fun touchOutsideAtRestGoesToTheCards() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        assertFalse(w.onTouch(ev(MotionEvent.ACTION_DOWN, 1500f, 600f, 0)))
    }

    @Test fun closedWindowLeavesNothingWaiting() {
        val w = window(intArrayOf(100, 100, 600, 400))
        w.setFloating(true)
        w.onTouch(ev(MotionEvent.ACTION_DOWN, 400f, 300f, 0))
        w.onTouch(ev(MotionEvent.ACTION_UP, 400f, 300f, 50))
        w.close()
        idle(ViewConfiguration.getDoubleTapTimeout() + 50L)
        assertFalse(w.awake)
        assertNull(w.panel.parent)
    }
}
