package app.mg4dash

import app.mg4dash.ui.FxSound
import app.mg4dash.ui.PowerFx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sounds of the full power animations (14/11): one per style, one low volume for all, set in the settings. */
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
        assertEquals(0.3f * FxSound.MAX_GAIN, FxSound.gain(30), 1e-6f)
        assertEquals(FxSound.MAX_GAIN, FxSound.gain(150), 0f)
        assertEquals(0f, FxSound.gain(-5), 0f)
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        assertEquals(FxSound.DEFAULT_VOLUME, app.mg4dash.system.Prefs(ctx).fxVolume)
    }

    @Test fun offByDefaultUntilSwitchedOn() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = app.mg4dash.system.Prefs(ctx)
        assertFalse(FxSound.enabled(ctx))
        prefs.fxSound = true
        assertTrue(FxSound.enabled(ctx))
        prefs.fxSound = false
    }

    @Test fun settingsTrialPlaysTheTickedAnimations() {
        assertEquals(PowerFx.LIGHTNING, FxSound.trialStyle(setOf(PowerFx.LIGHTNING), 5))
        assertEquals(PowerFx.HYPERSPACE, FxSound.trialStyle(setOf(PowerFx.HYPERSPACE), 2))
        assertEquals(PowerFx.NONE, FxSound.trialStyle(emptySet(), 1))
        // Several ticked: each of them in turn, never the one left out
        assertEquals(listOf(PowerFx.LIGHTNING, PowerFx.HYPERSPACE, PowerFx.LIGHTNING),
            (0..2).map { FxSound.trialStyle(setOf(PowerFx.HYPERSPACE, PowerFx.LIGHTNING), it) })
    }

    /** 21/11: the draw only among the ticked ones; the old single choice carried over. */
    @Test fun drawnOnlyAmongTheTicked() {
        val prefs = app.mg4dash.system.Prefs(org.robolectric.RuntimeEnvironment.getApplication())
        val ticked = setOf(PowerFx.FLAMES, PowerFx.HYPERSPACE)
        repeat(200) { assertTrue(PowerFx.pick(ticked) in ticked) }
        assertEquals(PowerFx.LIGHTNING, PowerFx.pick(setOf(PowerFx.LIGHTNING)))
        assertEquals(PowerFx.NONE, PowerFx.pick(emptySet()))
        assertEquals(PowerFx.STYLES.toSet(), PowerFx.fromSetting(PowerFx.RANDOM))
        assertEquals(PowerFx.STYLES.toSet(), PowerFx.fromSetting(1))   // the old removed rocket counted as random
        assertEquals(setOf(PowerFx.FLAMES), PowerFx.fromSetting(PowerFx.FLAMES))
        assertEquals(emptySet<Int>(), PowerFx.fromSetting(PowerFx.NONE))
        // Stored as ticked, read back; the old choice no longer counts once set
        prefs.powerFx = PowerFx.NONE
        prefs.raw.edit().remove("power_fx_on").commit()
        assertEquals(emptySet<Int>(), prefs.powerFxSet)
        prefs.powerFxSet = setOf(PowerFx.HYPERSPACE, PowerFx.LIGHTNING)
        assertEquals(setOf(PowerFx.HYPERSPACE, PowerFx.LIGHTNING), prefs.powerFxSet)
        prefs.powerFxSet = emptySet()
        assertEquals(emptySet<Int>(), prefs.powerFxSet)
        prefs.raw.edit().remove("power_fx_on").putInt("power_fx", PowerFx.RANDOM).commit()
    }

    /** The radar alerts: their own, louder scale (100 % = full volume, 4 x the effects' top), never under audible. */
    @Test fun alertsLouderScale() {
        assertEquals(0.5f, FxSound.alertGain(100), 1e-6f)
        assertEquals(2f * FxSound.gain(100), FxSound.alertGain(100), 1e-6f)
        assertEquals(0.35f, FxSound.alertGain(70), 1e-6f)
        assertEquals(FxSound.MIN_ALERT_GAIN, FxSound.alertGain(5), 1e-6f)
    }
}
