package app.mg4dash

import app.mg4dash.system.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordre des versions pour la mise à jour : 1.3.7-beta < 1.4.0 < 1.4.1-beta0 < 1.4.1-beta1 < 1.4.1. */
class UpdaterVersionTest {
    @Test fun order() {
        assertTrue(Updater.isNewer("1.4.0", "1.3.7-beta"))
        assertTrue(Updater.isNewer("1.4.1-beta0", "1.4.0"))
        assertTrue(Updater.isNewer("1.4.1-beta1", "1.4.1-beta0"))
        assertTrue(Updater.isNewer("1.4.1-beta10", "1.4.1-beta9"))
        assertTrue(Updater.isNewer("1.4.1", "1.4.1-beta3"))
        assertFalse(Updater.isNewer("1.4.1-beta3", "1.4.1"))
        assertFalse(Updater.isNewer("1.4.0", "1.4.0"))
        assertFalse(Updater.isNewer("1.4.1-beta0", "1.4.1-beta0"))
        assertTrue(Updater.isNewer("0.8.10", "0.8.2"))
    }

    @Test fun notesLanguage() {
        val md = "**Beta 1** english\n\n<details><summary>Français</summary>\n\n**Beta 1** français\n</details>\n<details><summary>Deutsch</summary>\ndeutsch\n</details>"
        org.junit.Assert.assertEquals("**Beta 1** english", Updater.localNotes(md, "en"))
        org.junit.Assert.assertEquals("**Beta 1** français", Updater.localNotes(md, "fr"))
        org.junit.Assert.assertEquals("deutsch", Updater.localNotes(md, "de"))
        org.junit.Assert.assertEquals("**Beta 1** english", Updater.localNotes(md, "it"))
        org.junit.Assert.assertEquals("old notes", Updater.localNotes("old notes", "fr"))
    }
}
