package app.mg4dash

import app.mg4dash.ui.EmbeddedApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The immersive setting left by 1.7.3 beta 2 for CarPlay, cleared so the SAIC status bar comes back over it (#104). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CarPlayBarTest {
    private val own = "app.mg4dash"

    @Test fun carPlayAloneCleared() =
        assertEquals(true to null, EmbeddedApp.withoutCarPlay("immersive.full=com.allgo.carplay.service", own))

    @Test fun embeddedAppKept() =
        assertEquals(true to "immersive.full=app.mg4dash,com.waze",
            EmbeddedApp.withoutCarPlay("immersive.full=app.mg4dash,com.waze,com.allgo.carplay.service", own))

    @Test fun nothingToClear() {
        assertEquals(false to null, EmbeddedApp.withoutCarPlay(null, own))
        assertEquals(false to "immersive.full=app.mg4dash,com.waze", EmbeddedApp.withoutCarPlay("immersive.full=app.mg4dash,com.waze", own))
    }

    @Test fun anotherAppsSettingLeftAlone() =
        assertEquals(false to "immersive.full=com.other,com.allgo.carplay.service",
            EmbeddedApp.withoutCarPlay("immersive.full=com.other,com.allgo.carplay.service", own))
}
