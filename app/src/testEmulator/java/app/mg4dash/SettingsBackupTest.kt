package app.mg4dash

import android.content.Context
import app.mg4dash.sound.SoundTriggerSettings
import app.mg4dash.system.Profiles
import app.mg4dash.system.SettingsBackup
import app.mg4dash.system.UsbExport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The settings backup on the USB stick (#129): saved, found, restored; the token and the history as chosen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SettingsBackupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val c: Context get() = RuntimeEnvironment.getApplication()
    private fun sp(name: String) = c.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val settings get() = sp("mg4launcher")

    @After fun eject() { UsbExport.simulated = null; UsbExport.simulatedRaw = null; UsbExport.simulatedInternal = null }

    private fun setUp() {
        settings.edit().putInt("theme_mode", 1).putString("theme_accent", "#FF00D2FF").putFloat("kwh_price", 0.21f)
            .putString("web_urls", "[\"https://a.example\"]").putString("ha_token", "secret").putString("ha_url", "http://ha.local")
            .putString("trips_list", "[1,2]").putFloat("soh_kwh", 61.5f)
            .putBoolean("valet_mode", true).putString("upd_new_app", "9.9.9").putString("last_playing", "radio").commit()
        Profiles.list(c)
        sp("mg4_sound_triggers").edit().putBoolean("startup_sound_enabled", true).putInt("trigger_volume_level", 12)
            .putString("selected_sound_file", File(SoundTriggerSettings.soundDir(c), "jingle.mp3").absolutePath)
            .putBoolean("volume_restore_pending", true).commit()
        File(SoundTriggerSettings.soundDir(c), "jingle.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    /** What a head unit just reset has: nothing. */
    private fun reset() {
        listOf("mg4launcher", "mg4_profiles", "mg4_sound_triggers").forEach { sp(it).edit().clear().commit() }
        SoundTriggerSettings.soundDir(c).listFiles()?.forEach { it.delete() }
    }

    @Test fun fileName() {
        val at = java.util.Calendar.getInstance().apply { set(2026, java.util.Calendar.NOVEMBER, 23, 8, 52, 10) }.timeInMillis
        assertEquals("mg4dash_settings_20261123_0852.json", SettingsBackup.fileName(at))
    }

    @Test fun savedOnTheStickThenRestoredAfterAReset() {
        setUp()
        UsbExport.simulated = tmp.newFolder("usb")
        val w = SettingsBackup.save(c, SettingsBackup.Options(history = true, haToken = false), "1.7.4")!!
        assertFalse(w.internal)
        assertTrue(w.path.startsWith("mg4dash_settings_"))
        val found = SettingsBackup.find(c)
        assertEquals(1, found.size)
        assertEquals("1.7.4", found[0].version); assertTrue(found[0].history); assertFalse(found[0].haToken)

        reset()
        assertTrue(SettingsBackup.restore(c, JSONObject(found[0].file.readText())))
        assertEquals(1, settings.getInt("theme_mode", 0))
        assertEquals("#FF00D2FF", settings.getString("theme_accent", null))
        assertEquals(0.21f, settings.getFloat("kwh_price", 0f), 0.0001f)
        assertEquals("[\"https://a.example\"]", settings.getString("web_urls", null))
        assertEquals("http://ha.local", settings.getString("ha_url", null))
        // The history, chosen
        assertEquals("[1,2]", settings.getString("trips_list", null))
        assertEquals(61.5f, settings.getFloat("soh_kwh", 0f), 0.0001f)
        // Not saved: the token (not chosen) and this install's state
        assertNull(settings.getString("ha_token", null))
        assertFalse(settings.contains("valet_mode")); assertFalse(settings.contains("upd_new_app")); assertFalse(settings.contains("last_playing"))
        // The profiles and the start sound, with its file
        assertEquals("p1", sp("mg4_profiles").getString("current", null))
        assertTrue(sp("mg4_sound_triggers").getBoolean("startup_sound_enabled", false))
        assertEquals(12, sp("mg4_sound_triggers").getInt("trigger_volume_level", 0))
        assertFalse(sp("mg4_sound_triggers").contains("volume_restore_pending"))
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(SoundTriggerSettings.soundDir(c), "jingle.mp3").readBytes().toList())
    }

    @Test fun theTokenOnlyWhenAskedAndNeverInTheFileOtherwise() {
        setUp()
        // A second profile keeps its own copy of its settings, the token with them
        Profiles.create(c, "Marie", fromCurrent = true)
        val without = SettingsBackup.build(c, SettingsBackup.Options(haToken = false), "1.7.4")
        assertFalse("the token nowhere in the file", without.contains("secret"))
        val with = SettingsBackup.build(c, SettingsBackup.Options(haToken = true), "1.7.4")
        assertTrue(with.contains("secret"))
        reset()
        SettingsBackup.restore(c, JSONObject(with))
        assertEquals("secret", settings.getString("ha_token", null))
    }

    @Test fun withoutTheHistoryTheCurrentHistoryStays() {
        setUp()
        val text = SettingsBackup.build(c, SettingsBackup.Options(history = false), "1.7.4")
        assertFalse(text.contains("trips_list"))
        settings.edit().putString("trips_list", "[7]").putInt("theme_mode", 0).commit()
        SettingsBackup.restore(c, JSONObject(text))
        assertEquals("[7]", settings.getString("trips_list", null))
        assertEquals(1, settings.getInt("theme_mode", 0))
    }

    /** A backup of an older or newer version: its unknown keys and its wrongly typed values are skipped, the rest restored. */
    @Test fun anotherVersionsBackup() {
        val j = JSONObject().put("app", "MG4Dash").put("format", 1).put("version", "1.6.0").put("created", 1L)
            .put("history", false).put("ha_token", false)
            .put("settings", JSONObject()
                .put("theme_mode", JSONArray().put("i").put(2))
                .put("no_such_setting", JSONArray().put("b").put(true))
                .put("units", JSONArray().put("i").put("not a number"))
                .put("valet_mode", JSONArray().put("b").put(true)))
        reset()
        assertTrue(SettingsBackup.restore(c, j))
        assertEquals(2, settings.getInt("theme_mode", 0))
        assertFalse(settings.contains("no_such_setting"))
        assertFalse(settings.contains("units"))
        assertFalse(settings.contains("valet_mode"))
        // Not a backup of MG4Dash, or of a format to come: nothing done
        assertFalse(SettingsBackup.restore(c, JSONObject().put("app", "Other")))
        assertFalse(SettingsBackup.restore(c, JSONObject(j.toString()).put("format", 99)))
    }

    /** A value this version keeps with another type than the backup's is left as it is (its reader would fail). */
    @Test fun aValueOfAnotherTypeIsKept() {
        settings.edit().putInt("units", 1).commit()
        val j = JSONObject().put("app", "MG4Dash").put("format", 1).put("settings", JSONObject().put("units", JSONArray().put("s").put("km")))
        SettingsBackup.restore(c, j)
        assertEquals(1, settings.getInt("units", 0))
    }

    /** Every key the backup leaves out on purpose is a real setting (no typo hiding a key that would then be saved). */
    @Test fun theExcludedKeysAreKnown() {
        (SettingsBackup.LOCAL + SettingsBackup.HISTORY).forEach { assertTrue(it, Profiles.classified(it)) }
        assertTrue(Profiles.classified("backup_history")); assertTrue(Profiles.classified("backup_token"))
    }

    @Test fun theNewestFirstAndOtherFilesIgnored() {
        val usb = tmp.newFolder("usb"); UsbExport.simulated = usb
        fun put(name: String, created: Long) = File(usb, name).writeText(JSONObject().put("app", "MG4Dash").put("format", 1).put("created", created).toString())
        put("mg4dash_settings_20261101_1000.json", 100); put("mg4dash_settings_20261123_0852.json", 300)
        File(usb, "mg4dash_settings_broken.json").writeText("{")
        File(usb, "mg4dash_log_20261123_0852.txt").writeText("log")
        assertEquals(listOf(300L, 100L), SettingsBackup.find(c).map { it.created })
    }
}
