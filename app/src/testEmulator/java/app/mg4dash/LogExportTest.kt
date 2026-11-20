package app.mg4dash

import app.mg4dash.settings.LogExport
import app.mg4dash.system.UsbExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

/** The log exported to the USB stick (#36): its name, its content, where it is written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogExportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun fileName() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 11, 18, 3, 27) }.timeInMillis
        assertEquals("mg4dash_log_20261111_1803.txt", LogExport.fileName(at))
    }

    @Test fun reportFirstThenTheWholeLogOldestFirst() {
        val text = LogExport.text("Log · MG4Dash 1.7.3", "Firmware: SWI165", listOf("18:03 newest", "18:02 middle", "18:01 oldest"))
        val lines = text.lines()
        assertEquals("Log · MG4Dash 1.7.3", lines[0])
        assertTrue("the Compatibility report at the top", text.indexOf("Firmware: SWI165") < text.indexOf("── Log ──"))
        val log = lines.dropWhile { it != "── Log ──" }.drop(1).filter { it.isNotEmpty() }
        assertEquals(listOf("18:01 oldest", "18:02 middle", "18:03 newest"), log)
    }

    @Test fun writtenAtTheRootFirst() {
        val usb = tmp.newFolder("usb")
        val f = UsbExport.writeTo(usb, tmp.newFolder("raw"), tmp.newFolder("own"), "a.txt", "hello")
        assertEquals(java.io.File(usb, "a.txt"), f)
        assertEquals("hello", f!!.readText())
    }

    /** #110: the places in order, MG4Dash's own folder (a phone cannot open it) only last. */
    @Test fun theOrderOfThePlaces() {
        val usb = java.io.File("/storage/ABCD-1234"); val raw = java.io.File("/mnt/media_rw/ABCD-1234")
        val own = java.io.File(usb, "Android/data/app.mg4dash/files")
        assertEquals(listOf(java.io.File(usb, "x.txt"), java.io.File(raw, "x.txt"), java.io.File(usb, "MG4Dash/x.txt"),
            java.io.File(raw, "MG4Dash/x.txt"), java.io.File(own, "x.txt")), UsbExport.places(usb, raw, own, "x.txt"))
        // Without a raw mount or an own folder: they are left out
        assertEquals(listOf(java.io.File(usb, "x.txt"), java.io.File(usb, "MG4Dash/x.txt")), UsbExport.places(usb, null, null, "x.txt"))
    }

    @Test fun rootRefusedThenTheRawMountThenOwnFolder() {
        // A "stick" that refuses everything: a plain file in place of a folder (nothing can be created under it)
        val refused = tmp.newFile("refused")
        val raw = tmp.newFolder("raw")
        val own = java.io.File(tmp.root, "own")
        assertEquals(java.io.File(raw, "b.txt"), UsbExport.writeTo(refused, raw, own, "b.txt", "x"))
        // The raw mount refused too: MG4Dash's own folder, last
        assertEquals(java.io.File(own, "b.txt"), UsbExport.writeTo(refused, tmp.newFile("raw2"), own, "b.txt", "x"))
        // Nothing writable: null, never anywhere else
        assertNull(UsbExport.writeTo(refused, null, null, "b.txt", "x"))
    }

    @Test fun theWrittenPathAndWhetherAPhoneCanOpenIt() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val usb = tmp.newFolder("stick")
        UsbExport.simulated = usb
        try {
            val w = UsbExport.write(ctx, "c.txt", "x")
            assertEquals(UsbExport.Written("c.txt", hidden = false), w)
            assertTrue(UsbExport.readDirs(ctx).contains(java.io.File(usb, "MG4Dash")))
        } finally { UsbExport.simulated = null }
    }

    @Test fun nothingWithoutAStick() {
        // Robolectric has no removable volume: nothing written, the button is greyed
        assertNull(UsbExport.write(org.robolectric.RuntimeEnvironment.getApplication(), "c.txt", "x"))
    }
}
