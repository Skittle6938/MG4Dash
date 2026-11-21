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

    /** #110: the places in order, the car's own memory only last (21/11). */
    @Test fun theOrderOfThePlaces() {
        val usb = java.io.File("/storage/ABCD-1234"); val raw = java.io.File("/mnt/media_rw/ABCD-1234")
        val inside = java.io.File("/sdcard/Download/MG4Dash")
        assertEquals(listOf(java.io.File(usb, "x.txt"), java.io.File(raw, "x.txt"), java.io.File(usb, "MG4Dash/x.txt"),
            java.io.File(raw, "MG4Dash/x.txt"), java.io.File(inside, "x.txt")), UsbExport.places(usb, raw, inside, "x.txt"))
        // Without a raw mount or the car's memory: they are left out
        assertEquals(listOf(java.io.File(usb, "x.txt"), java.io.File(usb, "MG4Dash/x.txt")), UsbExport.places(usb, null, null, "x.txt"))
        assertEquals(java.io.File("/sdcard/Download/MG4Dash"), UsbExport.internalFolder(java.io.File("/sdcard")))
    }

    @Test fun rootRefusedThenTheRawMountThenTheCarsMemory() {
        // A "stick" that refuses everything: a plain file in place of a folder (nothing can be created under it)
        val refused = tmp.newFile("refused")
        val raw = tmp.newFolder("raw")
        val inside = java.io.File(tmp.root, "inside")
        assertEquals(java.io.File(raw, "b.txt"), UsbExport.writeTo(refused, raw, inside, "b.txt", "x"))
        // The raw mount refused too: the car's memory, last
        assertEquals(java.io.File(inside, "b.txt"), UsbExport.writeTo(refused, tmp.newFile("raw2"), inside, "b.txt", "x"))
        // Nothing writable: null
        assertNull(UsbExport.writeTo(refused, null, null, "b.txt", "x"))
    }

    @Test fun theWrittenPathAndWhereItIs() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val usb = tmp.newFolder("stick")
        val car = tmp.newFolder("car")
        UsbExport.simulated = usb; UsbExport.simulatedInternal = car
        try {
            assertEquals(UsbExport.Written("c.txt", internal = false), UsbExport.write(ctx, "c.txt", "x"))
            assertTrue(UsbExport.readDirs(ctx).contains(java.io.File(usb, "MG4Dash")))
            // A stick the car lets nothing be written on: Download/MG4Dash of the car's memory, said as such
            UsbExport.simulated = tmp.newFile("locked")
            val w = UsbExport.write(ctx, "d.txt", "y")
            assertEquals(UsbExport.Written("Download/MG4Dash/d.txt", internal = true), w)
            assertEquals("y", java.io.File(car, "Download/MG4Dash/d.txt").readText())
        } finally { UsbExport.simulated = null; UsbExport.simulatedInternal = null }
    }

    @Test fun nothingWithoutAStick() {
        // Robolectric has no removable volume: nothing written, the button is greyed
        assertNull(UsbExport.write(org.robolectric.RuntimeEnvironment.getApplication(), "c.txt", "x"))
    }
}
