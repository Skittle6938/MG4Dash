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

    @Test fun writtenAtTheRootOrInMG4DashsFolder() {
        val usb = tmp.newFolder("usb")
        val f = UsbExport.writeTo(usb, null, "a.txt", "hello")
        assertEquals(java.io.File(usb, "a.txt"), f)
        assertEquals("hello", f!!.readText())
        // Root read only: MG4Dash's own folder on the stick
        val ro = tmp.newFolder("ro").apply { setWritable(false) }
        val own = java.io.File(tmp.root, "own")
        val g = UsbExport.writeTo(ro, own, "b.txt", "x")
        if (!java.io.File(ro, "b.txt").exists()) assertEquals(java.io.File(own, "b.txt"), g)
    }

    @Test fun nothingWithoutAStick() {
        // Robolectric has no removable volume: nothing written, the button is greyed
        assertNull(UsbExport.write(org.robolectric.RuntimeEnvironment.getApplication(), "c.txt", "x"))
    }
}
