package app.mg4dash.memo

import android.content.Context
import android.media.AudioAttributes
import app.mg4dash.system.Log
import app.mg4dash.system.UsbExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Plays a memo back (the card's and the window's ▶): one at a time; touched again, it pauses or goes on. Decoded and
 * played in MG4Dash's own process (25/11: MediaPlayer reads through the media server, which SELinux keeps out of a
 * platform app's files: "Prepare failed").
 */
object MemoPlayer {
    private var playback: Playback? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val _file = MutableStateFlow<File?>(null)
    /** The memo loaded (playing or paused), or null. */
    val file: StateFlow<File?> = _file
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    fun toggle(f: File) {
        val p = playback
        if (p != null && _file.value == f) {
            p.paused = !p.paused
            _playing.value = !p.paused
            return
        }
        stop()
        try {
            lateinit var pb: Playback
            pb = Playback(f) { main.post { if (playback === pb) stop() } }
            pb.start()
            playback = pb
            _file.value = f; _playing.value = true
        } catch (e: Exception) { Log.w("Memo", "play ${f.name}: ${e.javaClass.simpleName}: ${e.message}"); stop() }
    }

    /** Where it is (ms) in the memo loaded. */
    fun position(): Int = playback?.positionMs() ?: 0

    fun stop() {
        playback?.stop()
        playback = null; _file.value = null; _playing.value = false
    }

    /** One memo decoded (MediaExtractor, MediaCodec) to an AudioTrack, on its own thread. */
    private class Playback(private val file: File, private val ended: () -> Unit) {
        @Volatile var paused = false
        @Volatile private var running = false
        @Volatile private var track: android.media.AudioTrack? = null
        @Volatile private var rate = 16000
        private var thread: Thread? = null
        private val extractor = android.media.MediaExtractor()
        private var codec: android.media.MediaCodec? = null

        fun start() {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: run { extractor.release(); throw IllegalStateException("no sound in the file") }
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val c = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            try { c.configure(format, null, null, 0); c.start() } catch (e: Exception) { c.release(); extractor.release(); throw e }
            codec = c
            running = true
            thread = Thread({ loop(c) }, "memo-player").apply { start() }
        }

        private fun newTrack(rate: Int, channels: Int): android.media.AudioTrack {
            val mask = if (channels >= 2) android.media.AudioFormat.CHANNEL_OUT_STEREO else android.media.AudioFormat.CHANNEL_OUT_MONO
            val min = android.media.AudioTrack.getMinBufferSize(rate, mask, android.media.AudioFormat.ENCODING_PCM_16BIT)
            return android.media.AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(mask).build())
                .setBufferSizeInBytes(maxOf(min, 8192) * 2).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        }

        private fun loop(c: android.media.MediaCodec) {
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var channels = 1
            var written = 0L
            var finished = false
            try {
                while (running) {
                    val t = track
                    if (paused) { if (t?.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) t.pause(); Thread.sleep(40); continue }
                    if (t != null && t.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) t.play()
                    if (!inputDone) {
                        val ii = c.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val n = extractor.readSampleData(c.getInputBuffer(ii)!!, 0)
                            if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { c.queueInputBuffer(ii, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val oi = c.dequeueOutputBuffer(info, 10_000)
                    if (oi == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = c.outputFormat
                        rate = f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        if (info.size > 0) {
                            val tr = track ?: newTrack(rate, channels).also { track = it; it.play() }
                            val ob = c.getOutputBuffer(oi)!!
                            val pcm = ByteArray(info.size)
                            ob.position(info.offset); ob.get(pcm)
                            tr.write(pcm, 0, pcm.size)
                            written += pcm.size / (2 * channels)
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { finished = true; break }
                    }
                }
                // The last sound played out before it ends
                val tr = track
                if (finished && tr != null) {
                    val until = System.currentTimeMillis() + 5_000
                    while (running && tr.playbackHeadPosition < written && System.currentTimeMillis() < until) Thread.sleep(30)
                }
            } catch (e: Exception) {
                Log.w("Memo", "play ${file.name}: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                close()
                if (running) { running = false; ended() }
            }
        }

        fun positionMs(): Int = track?.let { (it.playbackHeadPosition.toLong() * 1000 / rate).toInt() } ?: 0

        fun stop() {
            running = false
            thread?.let { if (it !== Thread.currentThread()) it.join(1000) }
            close()
        }

        @Synchronized private fun close() {
            runCatching { track?.pause(); track?.flush() }; runCatching { track?.release() }; track = null
            runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
            runCatching { extractor.release() }
        }
    }
}

/**
 * The memos to the USB stick (24/11): in MG4Dash/Memos, at the stick's root or its raw mount (whichever the car lets
 * MG4Dash write). Moving copies, checks each copy's size, then deletes it from the car.
 */
object MemoUsb {
    private const val TAG = "Memo"

    /** The stick's folder for the memos, made and writable, or null (no stick, or nothing writable). */
    fun folder(c: Context): File? {
        val usb = UsbExport.stick(c) ?: return null
        val raw = UsbExport.rawRoot(c, usb)
        return listOfNotNull(usb, raw).map { File(File(it, UsbExport.FOLDER), "Memos") }.firstOrNull { d ->
            try { d.mkdirs(); val probe = File(d, ".probe"); probe.writeText("x"); probe.delete(); true } catch (_: Exception) { false }
        }
    }

    /** Free space on the stick (bytes), or null without one. */
    fun free(c: Context): Long? = UsbExport.stick(c)?.usableSpace

    /** Its volume name ("KINGSTON"), or null. */
    fun label(c: Context): String? = try {
        val sm = c.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        sm.storageVolumes.firstOrNull { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }?.getDescription(c)
    } catch (_: Exception) { null }

    /** The copy to put on the stick for [m]: its export name, numbered when another file has it. */
    internal fun target(dir: File, name: String, size: Long): File {
        var f = File(dir, name)
        var n = 2
        while (f.exists() && f.length() != size) { f = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.')); n++ }
        return f
    }

    /** Copies (or moves) [memos]; [progress] after each. Returns how many went, or null without a writable stick. Off the main thread. */
    fun export(c: Context, memos: List<MemoFiles.Memo>, move: Boolean, progress: (Int, Int) -> Unit): Int? {
        val dir = folder(c) ?: return null
        var done = 0
        memos.forEachIndexed { i, m ->
            try {
                val out = target(dir, MemoFiles.exportName(m), m.size)
                if (!(out.exists() && out.length() == m.size)) {
                    val tmp = File(dir, out.name + ".part")
                    m.file.inputStream().use { inp -> tmp.outputStream().use { o -> inp.copyTo(o); o.fd.sync() } }
                    if (!tmp.renameTo(out)) { tmp.delete(); throw java.io.IOException("rename") }
                }
                // Checked before anything is deleted from the car
                if (out.length() == m.size) {
                    done++
                    if (move) MemoFiles.delete(c, m.file)
                } else Log.w(TAG, "copy of ${m.file.name}: size ${out.length()} instead of ${m.size}")
            } catch (e: Exception) { Log.w(TAG, "copy of ${m.file.name}: ${e.message}") }
            progress(i + 1, memos.size)
        }
        Log.i(TAG, "${if (move) "moved" else "copied"} $done / ${memos.size} to ${dir.absolutePath}")
        MemoRecorder.touched()
        return done
    }
}
