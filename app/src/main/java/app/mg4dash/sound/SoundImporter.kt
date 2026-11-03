package app.mg4dash.sound

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.storage.StorageManager
import app.mg4dash.system.Log
import java.io.File

/**
 * Gestione ricerca e importazione di file audio da memorie USB o storage locale.
 */
object SoundImporter {
    private const val TAG = "SoundImporter"
    private val SUPPORTED_EXTS = setOf("mp3", "wav", "ogg", "m4a", "flac", "aac")
    const val MAX_DURATION_MS = 30_000L // Massimo 30 secondi per suoni di benvenuto / trigger

    data class AudioItem(
        val file: File,
        val title: String,
        val durationMs: Long
    )

    /**
     * Cerca file audio sia nei volumi USB montati che nella memoria interna Download/Music.
     */
    fun findAvailableAudioFiles(context: Context): List<AudioItem> {
        val found = mutableListOf<File>()
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

        // 1. Storage USB
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

        // 2. Storage esterno / Altri mount
        val fallbackUsb = File("/storage").listFiles()?.filter {
            it.name != "emulated" && it.name != "self" && it.canRead()
        }.orEmpty()
        fallbackUsb.forEach { scanDirectory(it, found, maxDepth = 3) }

        // 3. Cartelle multimediali interne
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS)
        ).forEach { dir ->
            if (dir != null && dir.exists() && dir.canRead()) {
                scanDirectory(dir, found, maxDepth = 2)
            }
        }

        // Filtra unici e analizza durata
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

    /**
     * Copia il file selezionato all'interno della directory privata dell'app,
     * così che rimanga disponibile anche dopo la rimozione della chiavetta USB.
     */
    fun importSound(context: Context, source: File): File? {
        return try {
            val destDir = SoundTriggerSettings.soundDir(context)
            val destFile = File(destDir, source.name)
            source.inputStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Pulisce vecchi suoni non più usati
            destDir.listFiles()?.filter { it.isFile && it != destFile }?.forEach { it.delete() }
            SoundTriggerSettings.setSelectedSoundPath(context, destFile.absolutePath)
            Log.i(TAG, "Imported sound ${source.name} to ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed importing sound: ${e.message}")
            null
        }
    }
}
