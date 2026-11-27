package app.mg4dash

import android.os.Looper
import app.mg4dash.car.VehicleState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The redesigned pages take a car state without crashing (26/11: the Energy page closed MG4Dash at the first state,
 * a bar no longer in the layout was resized): empty, plugged, charging, V2L, climate off and on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class PagesRenderTest {
    private val states = listOf(
        VehicleState(),
        VehicleState(connected = true, batteryPercent = 76, socPrecise = 76.2f, rangeKm = 284, chargeStatus = 7, chargeCtrl = 2),
        VehicleState(connected = true, batteryPercent = 50, rangeKm = 180, chargeStatus = 1, chargeCtrl = 1, chargeRemainMin = 95, hvacOn = false),
        VehicleState(connected = true, batteryPercent = 60, chargeStatus = 13, dischargeCtrl = 1, hvacOn = true, autoOn = true, fanSpeed = 4,
            fanDirection = 5, driverTemp = 22f, outsideTemp = 9f, defrostFront = true, defrostRear = true, driveMode = 7, steeringMode = 1,
            brakePedalMode = 2, enduranceMode = 1, lasMode = 3, fcwState = 2, fcwBrakeMode = 2, regenLevel = 6, onePedal = 1, tsr = 1, udw = 0),
    )

    private fun check(cls: Class<out app.mg4dash.ui.PageActivity>) {
        val a = Robolectric.buildActivity(cls).setup().visible().get()
        shadowOf(Looper.getMainLooper()).idle()
        for (s in states) { a.render(s); shadowOf(Looper.getMainLooper()).idle() }
    }

    /** AUTO as the car reports it (26/11): power 1, auto 1, fan 15, direction 7: the air still shows, at a medium speed. */
    @Test fun climateAutoShowsTheAir() {
        val a = Robolectric.buildActivity(app.mg4dash.pages.ClimateActivity::class.java).setup().visible().get()
        a.render(VehicleState(connected = true, hvacOn = true, autoOn = true, fanSpeed = 15, fanDirection = 7, driverTemp = 26f, outsideTemp = 5.5f))
        val air = app.mg4dash.pages.ClimateActivity::class.java.getDeclaredField("air").apply { isAccessible = true }.get(a) as app.mg4dash.showroom.AirFlow
        org.junit.Assert.assertEquals(.55f, air.power, 1e-3f)
        org.junit.Assert.assertTrue(air.face || air.feet || air.screen)
    }

    @Test fun energy() = check(app.mg4dash.pages.EnergyActivity::class.java)
    @Test fun vehicle() = check(app.mg4dash.pages.VehicleActivity::class.java)
    @Test fun climate() = check(app.mg4dash.pages.ClimateActivity::class.java)
}
