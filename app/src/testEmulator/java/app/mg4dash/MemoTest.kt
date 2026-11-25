package app.mg4dash

import app.mg4dash.memo.MemoFiles
import app.mg4dash.memo.MemoRecorder
import app.mg4dash.memo.MemoUsb
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The voice memos (24/11): the recording's frames kept after a cut, names on the stick, the waveform. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MemoTest {

    /** An ADTS frame: AAC-LC, 16 kHz (index 8), mono, [payload] bytes of data. */
    private fun frame(payload: Int): ByteArray {
        val len = payload + 7
        val h = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), ((1 shl 6) or (8 shl 2) or 0).toByte(),
            ((1 shl 6) or ((len shr 11) and 3)).toByte(), ((len shr 3) and 0xFF).toByte(), (((len and 7) shl 5) or 0x1F).toByte(), 0xFC.toByte())
        return h + ByteArray(payload) { it.toByte() }
    }

    @Test
    fun framesSurviveAPowerCut() {
        val whole = (1..50).map { frame(40 + it) }.reduce { a, b -> a + b }
        val f = MemoFiles.frames(whole)
        assertEquals(50, f.size)
        assertEquals(2, f[0].profile); assertEquals(8, f[0].rateIndex); assertEquals(1, f[0].channels)
        assertEquals(41, f[0].end - f[0].start)
        // 50 frames of 1024 samples at 16 kHz
        assertEquals(3200L, MemoFiles.lengthMs(f))
        // Cut in the middle of the last frame: the frames before it are all kept
        val cut = whole.copyOf(whole.size - 20)
        assertEquals(49, MemoFiles.frames(cut).size)
        // AAC-LC, 16 kHz, mono: the decoder's setup 0x14 0x08
        assertArrayEquals(byteArrayOf(0x14, 0x08), MemoFiles.csd(2, 8, 1))
    }

    @Test
    fun namesOnTheStick() {
        val at = java.util.Calendar.getInstance().apply { set(2026, 10, 24, 22, 41, 5) }.timeInMillis
        val m = MemoFiles.Memo(File("x.m4a"), at, 84_000, 1000, null, emptyList())
        assertEquals("2026-11-24_22-41.m4a", MemoFiles.exportName(m))
        assertEquals("Idée cadeau_2026-11-24_22-41.m4a", MemoFiles.exportName(m.copy(name = "Idée: cadeau?")))
        assertEquals("2026-11-24_22-41-05", MemoFiles.stamp(at))
        assertEquals(at / 1000, MemoFiles.startOf(File(MemoFiles.stamp(at) + ".m4a")) / 1000)
    }

    @Test
    fun anotherFileOfTheSameNameIsNotOverwritten() {
        val dir = createTempDir()
        File(dir, "a.m4a").writeBytes(ByteArray(10))
        // Same size: the same memo already copied, written over (nothing lost)
        assertEquals("a.m4a", MemoUsb.target(dir, "a.m4a", 10).name)
        // Another size: numbered
        assertEquals("a (2).m4a", MemoUsb.target(dir, "a.m4a", 20).name)
        dir.deleteRecursively()
    }

    @Test
    fun microphoneAskedThenRefusalSaid() {
        val a = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val shadow = org.robolectric.Shadows.shadowOf(a)
        // Not allowed yet: the first press asks for it with Android's dialog, nothing silently lost (25/11)
        MemoRecorder.start(a)
        assertEquals(android.Manifest.permission.RECORD_AUDIO, shadow.lastRequestedPermission.requestedPermissions.single())
        assertEquals(MemoRecorder.PERMISSION_REQUEST, shadow.lastRequestedPermission.requestCode)
        // Refused: the card says why
        MemoRecorder.permissionResult(a, false)
        assertEquals(MemoRecorder.Note.Kind.NO_MIC, MemoRecorder.note.value?.kind)
        // Allowed: no more asking, the recording service is started
        shadow.grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        MemoRecorder.permissionResult(a, true)
        val started = org.robolectric.Shadows.shadowOf(a.application).nextStartedService
        assertEquals(app.mg4dash.memo.MemoService::class.java.name, started?.component?.className)
    }

    @Test
    fun waveformAndClock() {
        val levels = List(480) { if (it in 100..110) 90 else 10 }
        val w = MemoFiles.downsample(levels)
        assertEquals(48, w.size)
        assertEquals(90, w[10]); assertEquals(10, w[0])
        assertEquals(listOf(1, 2), MemoFiles.downsample(listOf(1, 2)))
        assertEquals("1:24", MemoFiles.clock(84_000)); assertEquals("12:06", MemoFiles.clock(726_000)); assertEquals("1:02:03", MemoFiles.clock(3_723_000))
        assertEquals(0, MemoRecorder.toLevel(0)); assertEquals(100, MemoRecorder.toLevel(32767))
        assertTrue(MemoRecorder.toLevel(1000) in 1..99)
    }

    /** The in-process recorder's frames (25/11) read back as the ones MediaRecorder wrote: rate, mono, length. */
    @Test
    fun adtsRecorderHeadersParse() {
        for (rate in listOf(16000, 44100)) {
            val out = java.io.ByteArrayOutputStream()
            val sizes = listOf(5, 120, 371, 2000)
            sizes.forEach { n -> out.write(app.mg4dash.memo.AdtsRecorder.header(n + 7, rate)); out.write(ByteArray(n) { it.toByte() }) }
            val frames = app.mg4dash.memo.MemoFiles.frames(out.toByteArray())
            org.junit.Assert.assertEquals(sizes.size, frames.size)
            frames.zip(sizes).forEach { (f, n) ->
                org.junit.Assert.assertEquals(n, f.end - f.start)
                org.junit.Assert.assertEquals(2, f.profile)
                org.junit.Assert.assertEquals(1, f.channels)
                org.junit.Assert.assertEquals(rate, app.mg4dash.memo.MemoFiles.RATES[f.rateIndex])
            }
        }
    }
}
