package app.mg4dash.system

import android.content.Context
import java.io.File

/**
 * Files written to the USB stick, at its root (or MG4Dash's own folder on it, Android/data/app.mg4dash/files, if the
 * root is read only): the web app's addresses (#98), the log (#36).
 */
object UsbExport {
    private const val TAG = "Usb"

    /** Tests and mockup renders: a stick at this folder instead of the car's volumes (ejected by [eject]). */
    @Volatile internal var simulated: File? = null

    /** The mounted USB stick's root, or null without one. */
    fun stick(context: Context): File? {
        simulated?.let { return it }
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? File } catch (_: Throwable) { null } }
    }

    /**
     * The stick unmounted safely (#106), as Android's "Eject": its files closed and written out, so another app's
     * recording (a dash cam's video) is not cut off half written when it is pulled out. MG4Dash, with the platform
     * signature, has the right to unmount a volume (MOUNT_UNMOUNT_FILESYSTEMS). Blocking (up to [waitMs]): not on the
     * main thread. Null when done; otherwise why not.
     */
    fun eject(context: Context, waitMs: Long = 6_000): String? {
        if (simulated != null) { simulated = null; return null }
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        val v = sm.storageVolumes.firstOrNull { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED } ?: return "no stick"
        val id = try { v.javaClass.getMethod("getId").invoke(v) as? String } catch (e: Throwable) { null } ?: return "no volume id"
        Log.i(TAG, "eject: unmounting $id")
        try { sm.javaClass.getMethod("unmount", String::class.java).invoke(sm, id) }
        catch (e: Throwable) { val c = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e; Log.w(TAG, "eject: ${c.javaClass.simpleName}: ${c.message}"); return c.message ?: c.javaClass.simpleName }
        // Unmounting is asynchronous: done once the volume is no longer mounted
        val until = android.os.SystemClock.uptimeMillis() + waitMs
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (stick(context) == null) { Log.i(TAG, "eject: done"); return null }
            Thread.sleep(250)
        }
        Log.w(TAG, "eject: still mounted after ${waitMs / 1000} s")
        return "still mounted"
    }

    /** MG4Dash's own folder on the stick, always writable, if the root is not. */
    fun ownFolder(context: Context, usb: File): File? =
        context.getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(usb.absolutePath) }

    /** [text] written as [name] on the stick: its path from the stick's root, or null (no stick, nothing writable). */
    fun write(context: Context, name: String, text: String): String? {
        val usb = stick(context) ?: return null
        return writeTo(usb, ownFolder(context, usb), name, text)?.absolutePath?.removePrefix(usb.absolutePath)?.trimStart('/', '\\')
    }

    /** At the root of [usb], otherwise in [own]: the file written, or null. */
    internal fun writeTo(usb: File, own: File?, name: String, text: String): File? =
        listOfNotNull(File(usb, name), own?.let { File(it.apply { mkdirs() }, name) }).firstOrNull { f ->
            try { f.writeText(text); true } catch (e: Exception) { Log.w(TAG, "write to ${f.absolutePath}: ${e.message}"); false }
        }?.also { Log.i(TAG, "written: ${it.absolutePath}") }
}
