package app.mg4dash

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.car.CarBridge
import app.mg4dash.car.DriveSettings
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Firmware
import app.mg4dash.ui.DrivingCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Driving card (#38): MG4Control's routes and scales, never locked while driving, greyed only what the car does not answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class DrivingCardTest {
    @Test fun routesAsMG4Control() {
        listOf(Firmware.Gen.SWI69, Firmware.Gen.SWI131, Firmware.Gen.SWI132, Firmware.Gen.SWI173).forEach { assertEquals(DriveSettings.Route.A9, DriveSettings.routeOf(it)) }
        listOf(Firmware.Gen.SWI68, Firmware.Gen.SWI165).forEach { assertEquals(DriveSettings.Route.VSM_68, DriveSettings.routeOf(it)) }
        listOf(Firmware.Gen.SWI133, Firmware.Gen.UNKNOWN).forEach { assertEquals(DriveSettings.Route.VPM_133, DriveSettings.routeOf(it)) }
    }

    @Test fun customScales() {
        // CustomDriveScale: steering 1/2/3, pedal 1/0/2 (normal is zero)
        assertEquals(listOf(1, 2, 3), (0..2).map { DriveSettings.customValue(DriveSettings.Custom.STEERING, it) })
        assertEquals(listOf(1, 0, 2), (0..2).map { DriveSettings.customValue(DriveSettings.Custom.PEDAL, it) })
        (0..2).forEach { i -> DriveSettings.Custom.entries.forEach { s -> assertEquals(i, DriveSettings.customIndex(s, DriveSettings.customValue(s, i))) } }
        assertNull(DriveSettings.customIndex(DriveSettings.Custom.PEDAL, 5))
        assertNull(DriveSettings.customValue(DriveSettings.Custom.STEERING, 3))
    }

    private fun chips(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView && v.isClickable && v.text.isNotEmpty()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) chips(v.getChildAt(i), out)
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private val flow get() = CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }.get(CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<VehicleState>
    private var before: VehicleState? = null

    private fun state(f: (VehicleState) -> VehicleState) {
        if (before == null) before = flow.value
        flow.value = f(flow.value)
    }

    /** The car's state is shared by the tests that follow: as it was (not driving). */
    @org.junit.After fun restore() { before?.let { flow.value = it } }

    @Test fun changedWhileDrivingAndGreyedOnlyWhenNotAnswered() {
        val act = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        state { it.copy(speedKmh = 90f, driveMode = 3, regenLevel = 1, onePedal = 0, steeringMode = 2, brakePedalMode = 0, lasMode = 3, fcwState = 2, fcwBrakeMode = 2) }
        val card = DrivingCard(act).apply { form = DrivingCard.Form.FULL }
        card.show()
        fun chip(res: Int, last: Boolean = false) = chips(card).filter { it.text.toString() == act.getString(res) }.let { if (last) it.last() else it.first() }
        // Normal mode: steering and pedal greyed ("en mode Custom"), the rest usable at 90 km/h
        assertFalse(chip(R.string.cf_comfort).isEnabled)
        assertTrue(chip(R.string.dm_sport).isEnabled)
        chip(R.string.dm_sport).performClick()
        assertEquals(4, CarBridge.state.value.driveMode)
        chip(R.string.dm_custom).performClick()
        card.show()
        assertTrue(chip(R.string.cf_comfort).isEnabled)
        chip(R.string.rg_one_pedal).performClick()
        assertEquals(1, CarBridge.state.value.onePedal)
        // Front collision off (the last "Off" of the card): state 1
        chip(R.string.st_off, last = true).performClick()
        assertEquals(1, CarBridge.state.value.fcwState)
        // Not answered by this car: greyed
        state { it.copy(lasMode = null) }
        card.show()
        assertFalse(chip(R.string.assist).isEnabled)
    }
}
