package app.mg4dash.memo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import app.mg4dash.system.Log
import app.mg4dash.system.UsbExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays a memo back (the card's and the window's ▶): one at a time; touched again, it pauses or goes on. */
object MemoPlayer {
    private var player: MediaPlayer? = null
    private val _file = MutableStateFlow<File?>(null)
    /** The memo loaded (playing or paused), or null. */
    val file: StateFlow<File?> = _file
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    fun toggle(f: File) {
        val p = player
        if (p != null && _file.value == f) {
            if (p.isPlaying) p.pause() else p.start()
            _playing.value = p.isPlaying
            return
        }
        stop()
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(f.absolutePath); prepare(); start()
                setOnCompletionListener { stop() }
            }
            _file.value = f; _playing.value = true
        } catch (e: Exception) { Log.w("Memo", "play ${f.name}: ${e.message}"); stop() }
    }

    /** Where it is (ms) in the memo loaded. */
    fun position(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    fun stop() {
        runCatching { player?.stop() }; runCatching { player?.release() }
        player = null; _file.value = null; _playing.value = false
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
