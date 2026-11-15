package app.mg4dash

import android.provider.Settings
import app.mg4dash.system.BrightnessMemory
import app.mg4dash.system.BrightnessMemory.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The automatic brightness and its shift kept from one start to the next (the car resets them, 15/11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrightnessMemoryTest {
    @Test fun whatMatters() {
        // Automatic: the shift matters, not the level the sensor sets
        assertFalse(BrightnessMemory.differs(State(true, 0.4f, 30), State(true, 0.4f, 200)))
        assertTrue(BrightnessMemory.differs(State(true, 0f, 30), State(true, 0.4f, 30)))
        assertTrue("back to manual by the car", BrightnessMemory.differs(State(false, 0.4f, 30), State(true, 0.4f, 30)))
        // By hand: the level matters, not the shift
        assertFalse(BrightnessMemory.differs(State(false, 0f, 51), State(false, 0.6f, 51)))
        assertTrue(BrightnessMemory.differs(State(false, 0f, 128), State(false, 0f, 51)))
    }

    @Test fun rememberedThenPutBack() {
        val ctx = RuntimeEnvironment.getApplication()
        val cr = ctx.contentResolver
        ctx.getSharedPreferences("mg4launcher", 0).edit().clear().commit()
        assertNull("nothing chosen yet: nothing put back", BrightnessMemory.saved(ctx))
        Settings.System.putInt(cr, "screen_brightness_mode", 1)
        Settings.System.putFloat(cr, "screen_auto_brightness_adj", -0.4f)
        BrightnessMemory.chosen(ctx)
        // The car's reset at start: manual, no shift
        Settings.System.putInt(cr, "screen_brightness_mode", 0)
        Settings.System.putFloat(cr, "screen_auto_brightness_adj", 0f)
        BrightnessMemory.restore(ctx, "test")
        assertEquals(1, Settings.System.getInt(cr, "screen_brightness_mode"))
        assertEquals(-0.4f, Settings.System.getFloat(cr, "screen_auto_brightness_adj"), 1e-6f)
    }
}
