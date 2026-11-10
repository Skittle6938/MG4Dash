package app.mg4dash

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.ui.DrivingCard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A driving choice shows at once, waits for the car's confirmation, and comes back if the car does not take it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class DrivingPendingTest {
    @After fun clear() = CarBridge.clearPending()

    private val normal = VehicleState(driveMode = 3, regenLevel = 1, onePedal = 0)

    @Test fun keptWhileTheCarRereadsTheOldValue() {
        CarBridge.setPending("driveMode", 4, 10_000)
        val (s, refused) = CarBridge.settle(normal.copy(driveMode = 4), normal, now = 1_000)
        assertEquals("no flicker back to Normal", 4, s.driveMode)
        assertNull(refused)
        assertEquals(setOf("driveMode"), CarBridge.drivePending.value)
    }

    @Test fun settledWhenConfirmed() {
        CarBridge.setPending("driveMode", 4, 10_000)
        val (s, refused) = CarBridge.settle(normal.copy(driveMode = 4), normal.copy(driveMode = 4), now = 2_000)
        assertEquals(4, s.driveMode)
        assertNull(refused)
        // The other settings follow the car as before
        assertEquals(1, s.regenLevel)
    }

    @Test fun backToTheRealValueWhenNotConfirmed() {
        CarBridge.setPending("driveMode", 4, 10_000)
        val (s, refused) = CarBridge.settle(normal.copy(driveMode = 4), normal, now = 10_001)
        assertEquals(3, s.driveMode)
        assertEquals("driveMode", refused)
        // Given up: the next reread does not say it again
        assertNull(CarBridge.settle(s, normal, now = 12_000).second)
    }

    private fun chips(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView && v.isClickable && v.text.isNotEmpty()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) chips(v.getChildAt(i), out)
        return out
    }

    @Test fun shownPendingThenSettled() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val card = DrivingCard(act).apply { form = DrivingCard.Form.FULL }
        val sport = act.getString(R.string.dm_sport)
        CarBridge.setPending("driveMode", 4, Long.MAX_VALUE)
        card.show(normal.copy(driveMode = 4))
        assertEquals(DrivingCard.PENDING, chips(card).first { it.text.toString() == sport }.tag)
        CarBridge.clearPending()
        card.show(normal.copy(driveMode = 4))
        assertNull(chips(card).first { it.text.toString() == sport }.tag)
    }
}
