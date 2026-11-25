package app.mg4dash.memo

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * The microphone to an ADTS file, all in MG4Dash's own process (25/11: MediaRecorder writes through the media server,
 * which SELinux keeps out of a platform app's files: "Output file descriptor is invalid", nothing recorded). Same file
 * as before: AAC-LC frames that each stand alone, so a power cut loses nothing.
 */
internal class AdtsRecorder(private val file: File, private val rate: Int, private val bitRate: Int) {
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var peak = 0
    /** The loudest sample of the whole recording and the frames written, for the log (a silent microphone shows 0). */
    @Volatile var loudest = 0; private set
    @Volatile var frames = 0; private set
    private var record: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var out: FileOutputStream? = null
    private var thread: Thread? = null

    /** The loudest sample since the last call (0..32767), as [MediaRecorder.getMaxAmplitude]. */
    val maxAmplitude: Int get() = peak.also { peak = 0 }

    /** Opens the microphone and the encoder; throws when either refuses. */
    fun start() {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "microphone: $rate Hz refused ($min)" }
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("microphone not opened") }
        val c = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) { rec.release(); throw e }
        val o = try { FileOutputStream(file) } catch (e: Exception) { c.release(); rec.release(); throw e }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { o.close() }; c.release(); rec.release(); throw IllegalStateException("microphone busy")
        }
        record = rec; codec = c; out = o; running = true
        thread = Thread({ loop(rec, c, o) }, "memo-recorder").apply { start() }
    }

    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Ends the stream: the last frames written, the file closed. */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(3000)
        thread = null
    }

    fun release() {
        running = false
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        runCatching { out?.close() }; out = null
    }

    private fun loop(rec: AudioRecord, c: MediaCodec, o: FileOutputStream) {
        val pcm = ByteArray(4096)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var eos = false
        try {
            while (true) {
                if (!eos) {
                    val n = rec.read(pcm, 0, pcm.size)
                    if (n > 0) {
                        var p = 0
                        var i = 0
                        while (i + 1 < n) { val v = abs(((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()); if (v > p) p = v; i += 2 }
                        if (p > peak) peak = p
                        if (p > loudest) loudest = p
                    }
                    val ending = !running
                    if ((n > 0 && !paused) || ending) {
                        val ii = c.dequeueInputBuffer(10_000)
                        if (ii >= 0) {
                            val pts = samples * 1_000_000L / rate
                            if (ending) { c.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true }
                            else {
                                val ib = c.getInputBuffer(ii)!!
                                ib.clear()
                                val len = minOf(n, ib.remaining())
                                ib.put(pcm, 0, len)
                                c.queueInputBuffer(ii, 0, len, pts, 0)
                                samples += len / 2
                            }
                        }
                    }
                }
                // The encoded frames, each with its ADTS header
                while (true) {
                    val oi = c.dequeueOutputBuffer(info, if (eos) 10_000 else 0)
                    if (oi < 0) break
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val ob = c.getOutputBuffer(oi)!!
                        val frame = ByteArray(info.size)
                        ob.position(info.offset); ob.get(frame)
                        o.write(header(info.size + 7, rate)); o.write(frame); frames++
                    }
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        } catch (e: Exception) {
            app.mg4dash.system.Log.w("Memo", "recorder: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            app.mg4dash.system.Log.i("Memo", "recorded ${file.name}: $frames frames, loudest sample $loudest / 32767")
            runCatching { o.flush() }
            release()
        }
    }

    companion object {
        private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** The 7-byte ADTS header of an AAC-LC mono frame [length] bytes long, header included. */
        fun header(length: Int, rate: Int): ByteArray {
            val idx = RATES.indexOf(rate).let { if (it < 0) 4 else it }
            val profile = 2; val channels = 1
            return byteArrayOf(
                0xFF.toByte(), 0xF1.toByte(),
                (((profile - 1) shl 6) or (idx shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(),
                ((length and 0x7FF) shr 3).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }
    }
}
