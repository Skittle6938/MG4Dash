package app.mg4dash

import app.mg4dash.car.SaicHvacPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The SAIC climate page closed with the service's own name (#112: SWI68 R69 refused the usual one). */
class SaicHvacPageTest {
    private val usual = "com.android.systemui.IStartActivityService"

    @Test fun theServicesOwnNameIsSent() {
        assertEquals(usual, SaicHvacPage.descriptorFor(usual))
        assertEquals("com.saicmotor.systemui.IStartActivityService", SaicHvacPage.descriptorFor("com.saicmotor.systemui.IStartActivityService"))
        assertEquals("unknown: the usual name", usual, SaicHvacPage.descriptorFor(null))
        assertEquals(usual, SaicHvacPage.descriptorFor(""))
        // SWI68 R59 to R73 (#112): IPageService, transaction 2 = closeHvac
        assertEquals("com.saicmotor.sdk.external.IPageService", SaicHvacPage.descriptorFor("com.saicmotor.sdk.external.IPageService"))
    }

    @Test fun anotherInterfaceGetsNothing() {
        assertNull("transaction 2 could be anything there", SaicHvacPage.descriptorFor("com.android.systemui.IHvacService"))
        assertNull(SaicHvacPage.descriptorFor("com.saicmotor.sdk.external.IOtherService"))
        assertNull(SaicHvacPage.descriptorFor("com.android.systemui.IStartActivityServiceV2"))
    }
}
