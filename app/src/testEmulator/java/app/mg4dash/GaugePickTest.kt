package app.mg4dash

import android.view.MotionEvent
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.SegmentedGauge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Fan and temperature picked by touching or sliding along their bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GaugePickTest {
    private fun touch(g: SegmentedGauge, action: Int, x: Float) =
        MotionEvent.obtain(0, 0, action, x, 10f, 0).also { g.onTouchEvent(it); it.recycle() }

    @Test fun slidingShowsAtOnceAndPicksOnceWhenLifted() {
        val g = SegmentedGauge(RuntimeEnvironment.getApplication()).apply { segments = 11 }
        g.layout(0, 0, 1100, 20)
        val picked = mutableListOf<Int>()
        g.onSegmentPick = { picked += it }
        touch(g, MotionEvent.ACTION_DOWN, 150f)
        assertEquals("level 2 shown at once", 2, g.value)
        touch(g, MotionEvent.ACTION_MOVE, 750f)
        assertEquals("follows the finger", 8, g.value)
        assertEquals("nothing sent while sliding", emptyList<Int>(), picked)
        touch(g, MotionEvent.ACTION_UP, 760f)
        assertEquals("sent once, on release", listOf(7), picked)
    }

    @Test fun temperatureSlots() {
        val lo = VehicleState.tempLo; val hi = VehicleState.tempHi
        try {
            // SWI69 EU: LO 15.5, 16..30, HI 30.5 in 17 slots
            VehicleState.tempLo = 15.5f; VehicleState.tempHi = 30.5f
            assertEquals(15.5f, CarBridge.tempForSlot(0))
            assertEquals(16f, CarBridge.tempForSlot(1))
            assertEquals(30f, CarBridge.tempForSlot(15))
            assertEquals(30.5f, CarBridge.tempForSlot(16))
            // 18..32 versions
            VehicleState.tempLo = 17.5f; VehicleState.tempHi = 32.5f
            assertEquals(18f, CarBridge.tempForSlot(1))
            assertEquals(32f, CarBridge.tempForSlot(15))
        } finally { VehicleState.tempLo = lo; VehicleState.tempHi = hi }
    }
}
