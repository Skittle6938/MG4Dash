package app.mg4dash

import app.mg4dash.system.DriveMemory
import app.mg4dash.system.Prefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The drive mode kept by profile (24/11): each profile's own choice, the old shared switch as its starting value. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DriveByProfileTest {
    private val c get() = RuntimeEnvironment.getApplication()

    @Test fun eachProfileItsOwn() {
        c.getSharedPreferences(app.mg4dash.system.Profiles.FILE, 0).edit().clear().commit()
        Prefs(c).raw.edit().clear().commit()
        assertFalse(DriveMemory.enabled(c, "p1")); assertFalse(DriveMemory.enabled(c, "p2"))
        DriveMemory.setEnabled(c, "p2", true)
        assertFalse(DriveMemory.enabled(c, "p1")); assertTrue(DriveMemory.enabled(c, "p2"))
    }

    /** The old shared switch on: every profile starts with it on, until its own choice. */
    @Test fun oldSwitchIsTheStart() {
        c.getSharedPreferences(app.mg4dash.system.Profiles.FILE, 0).edit().clear().commit()
        Prefs(c).raw.edit().clear().putBoolean("profiles_drive", true).commit()
        assertTrue(DriveMemory.enabled(c, "p1")); assertTrue(DriveMemory.enabled(c, "p2"))
        DriveMemory.setEnabled(c, "p1", false)
        assertFalse(DriveMemory.enabled(c, "p1")); assertTrue(DriveMemory.enabled(c, "p2"))
    }
}
