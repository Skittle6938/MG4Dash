package app.mg4dash

import app.mg4dash.ui.FxSound
import app.mg4dash.ui.PowerFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sounds of the full power animations (Marc, 14/11): one per style, one low volume for all, set in the settings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FxSoundTest {
    @Test fun eachAnimationHasItsSound() {
        assertEquals(R.raw.fx_hyperspace, FxSound.soundOf(PowerFx.HYPERSPACE))
        assertEquals(R.raw.fx_crackle, FxSound.soundOf(PowerFx.LIGHTNING))
        assertEquals(R.raw.fx_fire, FxSound.soundOf(PowerFx.FLAMES))
        assertNull(FxSound.soundOf(PowerFx.NONE))
    }

    @Test fun lowByDefaultAndWithinRange() {
        assertTrue(FxSound.DEFAULT_VOLUME in 1..40)
        assertEquals(0f, FxSound.gain(0), 0f)
        assertEquals(0.3f, FxSound.gain(30), 1e-6f)
        assertEquals(1f, FxSound.gain(150), 0f)
        assertEquals(0f, FxSound.gain(-5), 0f)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(FxSound.DEFAULT_VOLUME, app.mg4dash.system.Prefs(ctx).fxVolume)
    }

    @Test fun settingsTrialPlaysTheChosenAnimation() {
        assertEquals(PowerFx.LIGHTNING, FxSound.trialStyle(PowerFx.LIGHTNING, 5))
        assertEquals(PowerFx.FLAMES, FxSound.trialStyle(PowerFx.FLAMES, 0))
        assertEquals(PowerFx.HYPERSPACE, FxSound.trialStyle(PowerFx.HYPERSPACE, 2))
        assertEquals(PowerFx.NONE, FxSound.trialStyle(PowerFx.NONE, 1))
        // Random: the three sounds in turn
        assertEquals(listOf(PowerFx.LIGHTNING, PowerFx.FLAMES, PowerFx.HYPERSPACE, PowerFx.LIGHTNING),
            (0..3).map { FxSound.trialStyle(PowerFx.RANDOM, it) })
    }
}
