package app.mg4dash

import android.content.Context
import app.mg4dash.energy.TripLog
import app.mg4dash.system.DriveMemory
import app.mg4dash.system.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Profiles, step 12: the trips marked with their profile, and the drive mode and regeneration kept per profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ProfileTripsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val sp get() = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    @Before fun clean() {
        listOf("mg4launcher", "mg4_sound_triggers", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        TripLog.profile = { null }
    }

    @org.junit.After fun reset() { TripLog.profile = { null } }

    private fun trip(at: Long, profile: String? = null) = TripLog.Trip(at, at + 600_000, 1000, 1010, 10f, 1.5f, profile = profile)

    /** The trips from before the profiles (no profile field) are read, as the first profile's. */
    @Test fun oldTripsRead() {
        sp.edit().putString("trip_log", "1000,2000,100,110,10.0,1.5,80,78,0.25").commit()
        val t = TripLog.load(sp).single()
        assertNull(t.profile)
        assertTrue(t.by("p1", "p1"))
        assertEquals(0.25f, t.price!!, 0.001f)
        // Written again with its profile, read back the same
        assertEquals(t.copy(profile = "p2"), TripLog.Trip.decode(t.copy(profile = "p2").encode()))
    }

    @Test fun markedAndFiltered() {
        TripLog.profile = { "p2" }
        TripLog.add(sp, trip(1_000))
        TripLog.profile = { "p1" }
        TripLog.add(sp, trip(2_000))
        val all = TripLog.load(sp)
        assertEquals(listOf("p1", "p2"), all.map { it.profile })
        assertEquals(1, all.count { it.by("p2", "p1") })
    }

    @Test fun fiftyPerProfile() {
        TripLog.profile = { "p1" }
        repeat(60) { TripLog.add(sp, trip(it * 1_000L)) }
        TripLog.profile = { "p2" }
        repeat(5) { TripLog.add(sp, trip(100_000L + it)) }
        val all = TripLog.load(sp)
        assertEquals(50, all.count { it.profile == "p1" })
        assertEquals(5, all.count { it.profile == "p2" })
    }

    /** A deleted profile's trips stay under All. */
    @Test fun deletedProfilesTrips() {
        TripLog.profile = { "p9" }
        TripLog.add(sp, trip(1_000))
        assertEquals(1, TripLog.load(sp).size)
        assertTrue(TripLog.load(sp).none { it.by("p1", "p1") })
    }

    @Test fun driveCommands() {
        val stored = DriveMemory.Drive(mode = 2, regen = 3, onePedal = 0)
        assertEquals(listOf("mode" to 2, "regen" to 3), DriveMemory.commands(stored, DriveMemory.Drive(4, 1, 0)))
        assertTrue("already so: nothing sent", DriveMemory.commands(stored, DriveMemory.Drive(2, 3, 0)).isEmpty())
        assertEquals(listOf("onePedal" to 1), DriveMemory.commands(DriveMemory.Drive(2, 3, 1), DriveMemory.Drive(2, 3, 0)))
        assertEquals(listOf("regen" to 3), DriveMemory.commands(stored, DriveMemory.Drive(2, 3, 1)))
        assertTrue("never noted: nothing", DriveMemory.commands(null, DriveMemory.Drive(2, 3, 0)).isEmpty())
        assertEquals(stored, DriveMemory.Drive.decode(stored.encode()))
    }

    /** The option off (default): a switch notes nothing. */
    @Test fun optionOffNoEffect() {
        val (b, _) = Profiles.create(ctx, "B", fromCurrent = true)
        Profiles.switchTo(ctx, b!!.id, "test")
        assertNull(ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).getString("drive_p1", null))
        // On: the drive of the profile left is noted
        sp.edit().putBoolean("profiles_drive", true).commit()
        Profiles.switchTo(ctx, "p1", "test")
        assertTrue(ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).getString("drive_${b.id}", null) != null)
    }
}
