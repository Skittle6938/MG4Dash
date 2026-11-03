package app.mg4dash.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.storage.StorageManager
import app.mg4dash.system.Log
import java.io.File

/** Finds and imports audio files from USB drives or local storage. */
object SoundImporter {
    private const val TAG = "SoundImporter"
    private val SUPPORTED_EXTS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")
    const val MAX_DURATION_MS = 30_000L
    const val MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L

    data class AudioItem(
        val file: File,
        val title: String,
        val durationMs: Long
    )

    /** Search mounted USB volumes and the internal Download/Music folders. */
    fun findAvailableAudioFiles(context: Context): List<AudioItem> {
        val found = mutableListOf<File>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

        // Search mounted USB storage first.
        sm?.storageVolumes?.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.forEach { vol ->
            try {
                val dir = vol.javaClass.getMethod("getPathFile").invoke(vol) as? File
                if (dir != null && dir.canRead()) {
                    scanDirectory(dir, found, maxDepth = 3)
                }
            } catch (e: Exception) {
                Log.w(TAG, "USB scan error: ${e.message}")
            }
        }

        // Also check other readable mounts exposed under /storage.
        val fallbackUsb = File("/storage").listFiles()?.filter {
            it.name != "emulated" && it.name != "self" && it.canRead()
        }.orEmpty()
        fallbackUsb.forEach { scanDirectory(it, found, maxDepth = 3) }

        // Search common internal media folders.
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS)
        ).forEach { dir ->
            if (dir != null && dir.exists() && dir.canRead()) {
                scanDirectory(dir, found, maxDepth = 2)
            }
        }

        // Deduplicate files and keep only supported durations.
        val retriever = MediaMetadataRetriever()
        val items = found.distinctBy { it.absolutePath }.mapNotNull { f ->
            try {
                retriever.setDataSource(f.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val dur = durStr?.toLongOrNull() ?: 0L
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: f.nameWithoutExtension
                if (dur in 100..MAX_DURATION_MS) {
                    AudioItem(f, title, dur)
                } else null
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.file.name.lowercase() }

        runCatching { retriever.release() }
        Log.i(TAG, "Found ${items.size} usable audio files")
        return items
    }

    private fun scanDirectory(dir: File, out: MutableList<File>, maxDepth: Int) {
        if (maxDepth < 0 || out.size > 200) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory && f.name != "LOST.DIR" && f.name != "Android") {
                scanDirectory(f, out, maxDepth - 1)
            } else if (f.isFile && f.extension.lowercase() in SUPPORTED_EXTS) {
                out.add(f)
            }
        }
    }

    /** Copy the selected file into app-private storage so it remains available after USB removal. */
    fun importSound(context: Context, source: File): File? {
        return try {
            if (!source.isFile || source.length() > MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "Audio file exceeds the ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB limit")
                return null
            }
            val destDir = SoundTriggerSettings.soundDir(context)
            val destFile = File(destDir, source.name)
            source.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_FILE_SIZE_BYTES) {
                            destFile.delete()
                            throw IllegalArgumentException("Audio file exceeds the size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            // Remove previous imported sounds.
            destDir.listFiles()?.filter { it.isFile && it != destFile }?.forEach { it.delete() }
            SoundTriggerSettings.setSelectedSoundPath(context, destFile.absolutePath)
            Log.i(TAG, "Imported audio ${source.name} to ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed importing sound: ${e.message}")
            null
        }
    }
}
