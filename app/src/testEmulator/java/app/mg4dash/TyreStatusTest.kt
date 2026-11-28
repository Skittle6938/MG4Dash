package app.mg4dash

import app.mg4dash.showroom.TyreStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TyreStatusTest {
    @Test fun pressureBandsUseStrictThresholds() {
        assertEquals(TyreStatus.NORMAL, TyreStatus.from(240, 0))
        assertEquals(TyreStatus.WATCH, TyreStatus.from(239, 0))
        assertEquals(TyreStatus.WATCH, TyreStatus.from(220, 0))
        assertEquals(TyreStatus.LOW_PRESSURE, TyreStatus.from(219, 0))
        assertEquals(TyreStatus.NORMAL, TyreStatus.from(320, 0))
    }

    @Test fun missingReadingsDoNotCreateOrHideWarnings() {
        assertEquals(TyreStatus.NORMAL, TyreStatus.from(null, null))
        assertEquals(TyreStatus.WATCH, TyreStatus.from(236, null))
        for (pressure in listOf(null, 219, 239, 250)) {
            assertEquals(TyreStatus.SENSOR_WARNING, TyreStatus.from(pressure, 1))
        }
    }
}
