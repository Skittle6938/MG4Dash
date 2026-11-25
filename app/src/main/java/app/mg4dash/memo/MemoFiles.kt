package app.mg4dash.memo

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The voice memos kept in the car: MG4Dash's own folder (files/memos), one .m4a per memo, named after its start
 * ("2026-11-24_22-41-05.m4a"). A memo being recorded is an .aac file (ADTS: every frame stands alone, so a power cut
 * loses nothing); it becomes an .m4a on Stop, or at the next start when cut short. Names given by the driver and the
 * waveform go in meta.json next to them.
 */
object MemoFiles {
    const val RECORDING_EXT = "aac"
    const val EXT = "m4a"
    private const val META = "meta.json"

    /** A memo: its file, when it started, its length (ms), its size, the driver's name for it, its waveform (0..100). */
    data class Memo(val file: File, val at: Long, val durationMs: Long, val size: Long, val name: String?, val levels: List<Int>)

    fun dir(c: Context): File = File(c.filesDir, "memos").apply { mkdirs() }

    private val stampFormat get() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun stamp(at: Long): String = stampFormat.format(Date(at))

    /** When a memo's file started, from its name; its date otherwise. */
    fun startOf(f: File): Long = try { stampFormat.parse(f.nameWithoutExtension)?.time } catch (_: Exception) { null } ?: f.lastModified()

    /** The memos, newest first. */
    fun list(c: Context): List<Memo> {
        val meta = meta(c)
        return dir(c).listFiles { f -> f.extension == EXT }.orEmpty().map { f ->
            val m = meta.optJSONObject(f.name)
            Memo(f, startOf(f), m?.optLong("ms", -1L)?.takeIf { it >= 0 } ?: duration(f), f.length(),
                m?.optString("name")?.takeIf { it.isNotBlank() },
                m?.optJSONArray("levels")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList())
        }.sortedByDescending { it.at }
    }

    private fun duration(f: File): Long = try {
        val r = android.media.MediaMetadataRetriever()
        try { r.setDataSource(f.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L }
        finally { r.release() }
    } catch (_: Exception) { 0L }

    @Synchronized
    private fun meta(c: Context): JSONObject = try { JSONObject(File(dir(c), META).readText()) } catch (_: Exception) { JSONObject() }

    @Synchronized
    private fun editMeta(c: Context, edit: (JSONObject) -> Unit) {
        val o = meta(c); edit(o)
        val f = File(dir(c), META); val tmp = File(dir(c), "$META.tmp")
        tmp.writeText(o.toString()); tmp.renameTo(f)
    }

    /** Length and waveform of a memo just made. */
    fun saveInfo(c: Context, f: File, ms: Long, levels: List<Int>) = editMeta(c) { o ->
        val m = o.optJSONObject(f.name) ?: JSONObject()
        m.put("ms", ms); m.put("levels", org.json.JSONArray(levels)); o.put(f.name, m)
    }

    fun rename(c: Context, f: File, name: String?) = editMeta(c) { o ->
        val m = o.optJSONObject(f.name) ?: JSONObject()
        if (name.isNullOrBlank()) m.remove("name") else m.put("name", name.trim()); o.put(f.name, m)
    }

    fun delete(c: Context, f: File) { f.delete(); editMeta(c) { it.remove(f.name) } }

    fun deleteAll(c: Context) = list(c).forEach { delete(c, it.file) }

    /** The memos' waveform: [n] values 0..100 from the levels read while recording (highest of each part). */
    fun downsample(levels: List<Int>, n: Int = 48): List<Int> {
        if (levels.isEmpty()) return emptyList()
        if (levels.size <= n) return levels
        return List(n) { i -> levels.subList(i * levels.size / n, maxOf((i + 1) * levels.size / n, i * levels.size / n + 1)).max() }
    }

    /**
     * Its name on the stick: "2026-11-24_22-41.m4a", or the driver's name before the date
     * ("Idée cadeau_2026-11-24_22-41.m4a"), without the characters a stick refuses.
     */
    fun exportName(m: Memo): String {
        val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(m.at))
        val name = m.name?.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "")?.trim()?.take(60)
        return (if (name.isNullOrEmpty()) date else "${name}_$date") + ".$EXT"
    }

    /** When a memo was made: "Aujourd'hui 22:41", "23 nov. 18:42". */
    fun whenText(c: Context, at: Long): String {
        val time = android.text.format.DateFormat.getTimeFormat(c).format(Date(at))
        val today = java.util.Calendar.getInstance(); val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val day = if (today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && today.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR))
            c.getString(app.mg4dash.R.string.mm_today)
            else SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "d MMM"), Locale.getDefault()).format(Date(at))
        return "$day $time"
    }

    /** "1:24", "12:06", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ── ADTS (the recording) to MP4 (.m4a) ──

    /** An ADTS frame: where its AAC data starts and ends in the file, and the stream's settings. */
    data class Frame(val start: Int, val end: Int, val profile: Int, val rateIndex: Int, val channels: Int)

    val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /** The frames of an ADTS stream; a frame cut short at the end (power cut) is left out. */
    fun frames(b: ByteArray): List<Frame> {
        val out = ArrayList<Frame>()
        var i = 0
        while (i + 7 <= b.size) {
            if (b[i].toInt() and 0xFF != 0xFF || b[i + 1].toInt() and 0xF0 != 0xF0) { i++; continue }
            val protectionAbsent = b[i + 1].toInt() and 1
            val profile = ((b[i + 2].toInt() shr 6) and 3) + 1
            val rate = (b[i + 2].toInt() shr 2) and 0xF
            val ch = ((b[i + 2].toInt() and 1) shl 2) or ((b[i + 3].toInt() shr 6) and 3)
            val len = ((b[i + 3].toInt() and 3) shl 11) or ((b[i + 4].toInt() and 0xFF) shl 3) or ((b[i + 5].toInt() and 0xFF) shr 5)
            val header = if (protectionAbsent == 1) 7 else 9
            if (len < header || i + len > b.size) break
            out += Frame(i + header, i + len, profile, rate, ch)
            i += len
        }
        return out
    }

    /** The decoder's setup (AudioSpecificConfig) for the stream. */
    fun csd(profile: Int, rateIndex: Int, channels: Int): ByteArray =
        byteArrayOf(((profile shl 3) or (rateIndex shr 1)).toByte(), (((rateIndex and 1) shl 7) or (channels shl 3)).toByte())

    /** The length (ms) of [frames] (1024 samples each). */
    fun lengthMs(frames: List<Frame>): Long {
        val f = frames.firstOrNull() ?: return 0
        val rate = RATES.getOrElse(f.rateIndex) { 44100 }
        return frames.size * 1024L * 1000 / rate
    }

    /**
     * [adts] turned into [m4a] (same sound, no re-encoding). True when written; the ADTS file is left to the caller.
     */
    fun toM4a(adts: File, m4a: File): Boolean {
        val bytes = adts.readBytes()
        val frames = frames(bytes)
        if (frames.isEmpty()) return false
        val f0 = frames[0]
        val rate = RATES.getOrElse(f0.rateIndex) { 44100 }
        val tmp = File(m4a.parentFile, m4a.name + ".part")
        return try {
            val mux = android.media.MediaMuxer(tmp.absolutePath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val fmt = android.media.MediaFormat.createAudioFormat(android.media.MediaFormat.MIMETYPE_AUDIO_AAC, rate, maxOf(1, f0.channels))
            fmt.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd(f0.profile, f0.rateIndex, maxOf(1, f0.channels))))
            val track = mux.addTrack(fmt)
            mux.start()
            val info = android.media.MediaCodec.BufferInfo()
            val buf = java.nio.ByteBuffer.allocate(8192)
            frames.forEachIndexed { n, fr ->
                buf.clear(); buf.put(bytes, fr.start, fr.end - fr.start); buf.flip()
                info.set(0, fr.end - fr.start, n * 1024L * 1_000_000 / rate, android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME)
                mux.writeSampleData(track, buf, info)
            }
            mux.stop(); mux.release()
            tmp.renameTo(m4a)
        } catch (e: Exception) {
            app.mg4dash.system.Log.w("Memo", "to m4a: ${e.message}"); tmp.delete(); false
        }
    }
}
