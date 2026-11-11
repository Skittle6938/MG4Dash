package app.mg4dash.system

import android.content.Context
import java.io.File

/**
 * Files written to the USB stick, at its root (or MG4Dash's own folder on it, Android/data/app.mg4dash/files, if the
 * root is read only): the web app's addresses (#98), the log (#36).
 */
object UsbExport {
    private const val TAG = "Usb"

    /** The mounted USB stick's root, or null without one. */
    fun stick(context: Context): File? {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? File } catch (_: Throwable) { null } }
    }

    /** MG4Dash's own folder on the stick, always writable, if the root is not. */
    fun ownFolder(context: Context, usb: File): File? =
        context.getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(usb.absolutePath) }

    /** [text] written as [name] on the stick: its path from the stick's root, or null (no stick, nothing writable). */
    fun write(context: Context, name: String, text: String): String? {
        val usb = stick(context) ?: return null
        return writeTo(usb, ownFolder(context, usb), name, text)?.absolutePath?.removePrefix(usb.absolutePath)?.trimStart('/')
    }

    /** At the root of [usb], otherwise in [own]: the file written, or null. */
    internal fun writeTo(usb: File, own: File?, name: String, text: String): File? =
        listOfNotNull(File(usb, name), own?.let { File(it.apply { mkdirs() }, name) }).firstOrNull { f ->
            try { f.writeText(text); true } catch (e: Exception) { Log.w(TAG, "write to ${f.absolutePath}: ${e.message}"); false }
        }?.also { Log.i(TAG, "written: ${it.absolutePath}") }
}
