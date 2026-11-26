package app.mg4dash

import app.mg4dash.system.StorageCleaner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The storage cleaner (26/11: the head unit was full): the old energy log's tail, the car's logs newest first. */
class StorageCleanerTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun oldEnergyLogKeepsWholeLinesOfItsEnd() {
        val f = tmp.newFile("energy_log.old.csv")
        f.writeText((1..1000).joinToString("\n") { "line $it,123456789" } + "\n")
        val freed = StorageCleaner.keepTail(f, 2000)
        assertTrue(freed > 0)
        assertTrue(f.length() <= 2000)
        val lines = f.readLines()
        assertTrue(lines.first().startsWith("line "))
        assertEquals("line 1000,123456789", lines.last())
    }

    @Test fun carLogsKeepTheNewestAndTheFileBeingWritten() {
        val root = tmp.newFolder("offlinelogs")
        val dir = File(root, "Log_1/logcat").apply { mkdirs() }
        val files = (1..10).map { i -> File(dir, "logcat.$i").apply { writeBytes(ByteArray(1000)); setLastModified(1_000_000L + i * 1000) } }
        val writing = File(dir, "logcat").apply { writeBytes(ByteArray(1000)); setLastModified(2_000_000L) }
        val freed = StorageCleaner.trimCarLogs(root, 3500)
        assertEquals(8000, freed)
        assertTrue(writing.exists())
        assertFalse(files[0].exists()); assertTrue(files[9].exists()); assertTrue(files[8].exists())
    }

    @Test fun nothingToTrimUnderTheLimit() {
        val root = tmp.newFolder("logs")
        File(root, "a").writeBytes(ByteArray(100))
        assertEquals(0, StorageCleaner.trimCarLogs(root, 1000))
        assertEquals(0, StorageCleaner.trimCarLogs(File(root, "missing"), 1000))
    }
}
