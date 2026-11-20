package app.mg4dash

import android.content.Context
import app.mg4dash.system.PhoneProfile
import app.mg4dash.system.Profiles
import app.mg4dash.ui.ShortcutTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Profiles, step 11: the Profile tile, and the switch by the paired phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ProfileTileTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Before fun clean() {
        listOf("mg4launcher", "mg4_sound_triggers", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        Profiles.manualThisStart = false
    }

    @Test fun tileWithTwoAndThreeProfiles() {
        assertNull("one profile: nothing to switch to", ShortcutTiles.nextProfile(listOf("p1"), "p1"))
        assertEquals("p2", ShortcutTiles.nextProfile(listOf("p1", "p2"), "p1"))
        assertEquals("p1", ShortcutTiles.nextProfile(listOf("p1", "p2"), "p2"))
        // Two profiles: a touch goes to the other, and the tile shows its name
        Profiles.create(ctx, "Sophie", fromCurrent = true)
        ShortcutTiles.act(ctx, ShortcutTiles.Tile.PROFILE, null)
        assertEquals("Sophie", Profiles.current(ctx).name)
        assertEquals("Sophie", ShortcutTiles.read(ctx, ShortcutTiles.Tile.PROFILE).state)
        ShortcutTiles.act(ctx, ShortcutTiles.Tile.PROFILE, null)
        assertEquals("Principal", Profiles.current(ctx).name)
    }

    private val list = listOf(Profiles.Profile("p1", "Marc", "AA:AA:AA:AA:AA:AA"), Profiles.Profile("p2", "Sophie", "BB:BB:BB:BB:BB:BB"), Profiles.Profile("p3", "Invité"))

    @Test fun pairedPhone() {
        assertEquals("p2", PhoneProfile.choose(list, "p1", "bb:bb:bb:bb:bb:bb", null, manual = false, decided = false))
        assertNull("already on it", PhoneProfile.choose(list, "p2", "BB:BB:BB:BB:BB:BB", null, manual = false, decided = false))
    }

    @Test fun phoneNotPaired() = assertNull(PhoneProfile.choose(list, "p1", "CC:CC:CC:CC:CC:CC", null, manual = false, decided = false))

    /** Two phones connected: the one of the calls wins over the music's. */
    @Test fun twoPhones() = assertEquals("p1", PhoneProfile.choose(list, "p3", "AA:AA:AA:AA:AA:AA", "BB:BB:BB:BB:BB:BB", manual = false, decided = false))

    /** A choice made by hand is kept; once per start. */
    @Test fun manualChoiceKept() {
        assertNull(PhoneProfile.choose(list, "p3", "AA:AA:AA:AA:AA:AA", null, manual = true, decided = false))
        assertNull(PhoneProfile.choose(list, "p3", "AA:AA:AA:AA:AA:AA", null, manual = false, decided = true))
        // A switch from Settings or the tile marks the choice
        Profiles.create(ctx, "B", fromCurrent = true)
        Profiles.switchTo(ctx, Profiles.list(ctx).last().id, "Settings")
        assertEquals(true, Profiles.manualThisStart)
    }
}
