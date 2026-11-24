package app.mg4dash

import android.content.Context
import app.mg4dash.system.Prefs
import app.mg4dash.system.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Profiles, step 9: the switch gives back exactly the same settings, the shared ones never move, every key is sorted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ProfilesTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val settings get() = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
    private val sound get() = ctx.getSharedPreferences("mg4_sound_triggers", Context.MODE_PRIVATE)

    @Before fun clean() {
        listOf("mg4launcher", "mg4_sound_triggers", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun mine() = settings.all.filterKeys { Profiles.isPersonal(it) }
    private fun shared() = settings.all.filterKeys { !Profiles.isPersonal(it) }

    @Test fun firstLaunchMakesTheMainProfile() {
        settings.edit().putInt("theme_bg", 3).commit()
        val l = Profiles.list(ctx)
        assertEquals(1, l.size)
        assertEquals("Principal", l.single().name)
        assertEquals(l.single(), Profiles.current(ctx))
        assertEquals(3, settings.getInt("theme_bg", 0))
    }

    @Test fun aToBToAGivesTheSameSettings() {
        settings.edit().putInt("theme_bg", 3).putInt("home_style", Prefs.STYLE_BIG).putString("slots_big_quad", "tl=climate;tr=media;bl=energy;br=hourly")
            .putStringSet("conn_icons_hidden", setOf("wifi", "bt")).putFloat("fx_volume", 0.4f).putBoolean("fullscreen", false)
            .putString("radar_snd_fixed", "beep").putLong("map_zoom_x", 1L)
            // shared
            .putFloat("battery_kwh", 61.7f).putInt("charge_limit", 80).putString("trip_rd_km", "12").commit()
        sound.edit().putString("trigger_1", "door").putBoolean("enabled", true).commit()
        val a = Profiles.current(ctx)
        val before = mine(); val beforeSound = sound.all; val beforeShared = shared()
        val (b, refused) = Profiles.create(ctx, "Sophie", fromCurrent = false)
        assertNull(refused)
        assertTrue(Profiles.switchTo(ctx, b!!.id, "test"))
        // B: the defaults, the shared settings as they were
        // The first install's defaults (full screen, vertical bar), nothing else
        assertEquals("B starts from the defaults", mapOf("fullscreen" to true, "vertical_dock" to true), mine())
        assertTrue(sound.all.isEmpty())
        assertEquals(beforeShared, shared())
        // B changes its own settings and a shared one
        settings.edit().putInt("theme_bg", 1).putBoolean("wide_bar", true).putInt("charge_limit", 90).commit()
        assertTrue(Profiles.switchTo(ctx, a.id, "test"))
        assertEquals(before, mine())
        assertEquals(beforeSound, sound.all)
        // The shared one changed by B stays changed: shared
        assertEquals(90, settings.getInt("charge_limit", 0))
        // And B finds its own again
        Profiles.switchTo(ctx, b.id, "test")
        assertEquals(1, settings.getInt("theme_bg", 0)); assertTrue(settings.getBoolean("wide_bar", false))
    }

    @Test fun startFromThisProfile() {
        settings.edit().putInt("theme_bg", 4).putString("home_layout", "map_center").commit()
        val (b, _) = Profiles.create(ctx, "Copie", fromCurrent = true)
        Profiles.switchTo(ctx, b!!.id, "test")
        assertEquals(4, settings.getInt("theme_bg", 0))
        assertEquals("map_center", settings.getString("home_layout", null))
    }

    /** The car switched off in the middle of a switch: finished at the next start. */
    @Test fun aCutSwitchIsFinished() {
        settings.edit().putInt("theme_bg", 2).commit()
        val a = Profiles.current(ctx)
        val (b, _) = Profiles.create(ctx, "B", fromCurrent = false)
        Profiles.switchTo(ctx, b!!.id, "test"); settings.edit().putInt("theme_bg", 4).commit()
        Profiles.switchTo(ctx, a.id, "test")
        // B's settings put away, then cut before they were put in place
        val store = ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE)
        store.edit().putString("switching", "${a.id}>${b.id}").commit()
        assertTrue(Profiles.resume(ctx))
        assertEquals(b.id, Profiles.current(ctx).id)
        assertEquals(4, settings.getInt("theme_bg", 0))
        assertFalse("nothing left to finish", Profiles.resume(ctx))
    }

    @Test fun rules() {
        assertEquals(Profiles.Refusal.EMPTY, Profiles.create(ctx, "  ", true).second)
        assertEquals(Profiles.Refusal.TOO_LONG, Profiles.create(ctx, "x".repeat(17), true).second)
        assertEquals(Profiles.Refusal.TAKEN, Profiles.create(ctx, "principal", true).second)
        for (i in 2..5) assertNull(Profiles.create(ctx, "P$i", true).second)
        assertEquals(Profiles.Refusal.FULL, Profiles.create(ctx, "P6", true).second)
        // Deleting the current one hands over first; never the last
        val cur = Profiles.current(ctx)
        assertNull(Profiles.delete(ctx, cur.id))
        assertTrue(Profiles.current(ctx).id != cur.id)
        while (Profiles.list(ctx).size > 1) Profiles.delete(ctx, Profiles.list(ctx).last().id)
        assertEquals(Profiles.Refusal.LAST, Profiles.delete(ctx, Profiles.current(ctx).id))
        // Renamed, with a phone
        assertNull(Profiles.update(ctx, Profiles.current(ctx).id, "Alex", "AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", Profiles.current(ctx).phone)
    }

    /** Review of step 14: a deleted profile's id is never given again, with its trips and drive memory. */
    @Test fun deletedIdNotReused() {
        val (b, _) = Profiles.create(ctx, "Paul", fromCurrent = true)
        ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).edit().putString("drive_${b!!.id}", "2,3,0").commit()
        Profiles.delete(ctx, b.id)
        assertNull(ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).getString("drive_${b.id}", null))
        val (s, _) = Profiles.create(ctx, "Sophie", fromCurrent = true)
        assertTrue("${s!!.id} != ${b.id}", s.id != b.id)
    }

    /** The start sound's car volume being restored after a cut stays with the car, not with a profile. */
    @Test fun volumeRestoreNotCopied() {
        sound.edit().putBoolean("sound_trigger_enabled", true).putBoolean("volume_restore_pending", true).putInt("volume_restore_before", 12).commit()
        val (b, _) = Profiles.create(ctx, "B", fromCurrent = false)
        Profiles.switchTo(ctx, b!!.id, "test")
        assertEquals("kept through the switch", true, sound.getBoolean("volume_restore_pending", false))
        assertEquals(false, sound.contains("sound_trigger_enabled"))
        sound.edit().putBoolean("volume_restore_pending", false).commit()
        Profiles.switchTo(ctx, "p1", "test")
        assertEquals("not brought back from the profile", false, sound.getBoolean("volume_restore_pending", true))
        assertEquals(true, sound.getBoolean("sound_trigger_enabled", false))
    }

    @Test fun damagedListGivesTheMainProfileAgain() {
        ctx.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).edit().putString("list", "{broken").commit()
        assertEquals("p1", Profiles.current(ctx).id)
    }

    /** A switch changes the theme's signature: the open screens rebuild even with the same theme. */
    @Test fun switchRebuildsTheScreens() {
        val sig = app.mg4dash.ui.Themes.signature(ctx)
        val (b, _) = Profiles.create(ctx, "Même thème", fromCurrent = true)
        Profiles.switchTo(ctx, b!!.id, "test")
        assertTrue(sig != app.mg4dash.ui.Themes.signature(ctx))
    }

    /** Every setting written in the code is sorted, personal or shared: nothing goes from one profile to another by mistake. */
    @Test fun everyKeyIsSorted() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        // Keys of other files (data use, crash guard), and strings that are not settings (JSON fields, package names)
        val others = setOf("alert_level", "popup", "reset_at", "show_4g", "show_wifi", "threshold", "updates_wifi_only", "times", "adb",
            "android", "band", "radio", "source", "browser_download_url", "name", "tag_name", "chunked", "gzip", "d", "t", "dresel", "error",
            "entity_id", "HfpClientConnectionService", "id", "ref", "k", "windows", "key_car_source_type", "lenspicker", "Resolver", "setupwizard",
            "mCurrentFocus", "mg4dash", "relative_humidity_2m", "time", "list", "current", "generation", "switching",
            // the chrono's runs and its pending restore (JSON fields, not settings)
            "at", "kind", "mode",
            // the profiles' own file
            "drive_")
        val call = Regex("""(?:getString|getInt|getBoolean|getFloat|getLong|getStringSet|putString|putInt|putBoolean|putFloat|putLong|putStringSet|remove|contains)\(\s*"([A-Za-z0-9_]+)""")
        val dyn = Regex(""""([a-z][a-z0-9_]*_)(?:"\s*\+|\$)""")
        val missing = sortedSetOf<String>()
        src.walkTopDown().filter { it.extension == "kt" && it.name != "Profiles.kt" }.forEach { f ->
            val t = f.readText()
            call.findAll(t).map { it.groupValues[1] }.filter { it !in others && !Profiles.classified(it) }.forEach { missing += "$it (${f.name})" }
            dyn.findAll(t).map { it.groupValues[1] }.filter { p -> p !in others && !Profiles.classified(p + "x") && t.contains("Prefs") }.forEach { missing += "$it* (${f.name})" }
        }
        assertTrue("settings sorted neither personal nor shared: $missing", missing.isEmpty())
    }
}
