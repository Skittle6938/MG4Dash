package app.mg4dash.system

import android.content.Context
import java.io.File

/**
 * Files written to the USB stick, at its root or, when the car refuses it, in a place a phone can read too (#110):
 * the web app's addresses (#98), the log (#36). See [write].
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
        if (simulated != null) { simulated = null; simulatedRaw = null; return null }
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

    /** MG4Dash's own folder on the stick, always writable, if the root is not; a phone cannot open it (Android 11+). */
    fun ownFolder(context: Context, usb: File): File? =
        context.getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(usb.absolutePath) }

    /**
     * The stick's own file system under Android's view of it (/mnt/media_rw/<id>): with the platform signature MG4Dash
     * may write there (WRITE_MEDIA_STORAGE) where the shared view refuses it (seen on SWI133, #110). Null if unknown.
     */
    fun rawRoot(context: Context, usb: File): File? {
        if (simulatedRaw != null) return simulatedRaw
        val f = File("/mnt/media_rw", usb.name)
        return if (usb.absolutePath != f.absolutePath && f.isDirectory) f else null
    }

    /** Tests: the raw mount of a simulated stick. */
    @Volatile internal var simulatedRaw: File? = null

    /** Folder for MG4Dash's files when the root is refused: visible from a phone or a computer. */
    const val FOLDER = "MG4Dash"

    /** A file written: its path from the stick's root, and whether it is in [ownFolder] (a phone can't open it). */
    data class Written(val path: String, val hidden: Boolean)

    /**
     * [text] written as [name] on the stick (#110): at its root, else at the root of its raw mount, else in a MG4Dash
     * folder (shared view then raw mount), and only last in [ownFolder]. Never in the car's own memory. Null without a
     * stick or with nothing writable.
     */
    fun write(context: Context, name: String, text: String): Written? {
        val usb = stick(context) ?: return null
        val own = ownFolder(context, usb)
        val f = writeTo(usb, rawRoot(context, usb), own, name, text) ?: return null
        val raw = rawRoot(context, usb)
        val rel = listOfNotNull(usb, raw).firstOrNull { f.absolutePath.startsWith(it.absolutePath) }
            ?.let { f.absolutePath.removePrefix(it.absolutePath).trimStart('/', '\\') } ?: f.absolutePath
        return Written(rel, own != null && f.absolutePath.startsWith(own.absolutePath))
    }

    /** The places tried for [name], in order (see [write]). */
    internal fun places(usb: File, raw: File?, own: File?, name: String): List<File> = listOfNotNull(
        File(usb, name),
        raw?.let { File(it, name) },
        File(File(usb, FOLDER), name),
        raw?.let { File(File(it, FOLDER), name) },
        own?.let { File(it, name) },
    )

    /** The folders where a file of MG4Dash may be found on the stick (import): the same places, without the names. */
    fun readDirs(context: Context): List<File> {
        val usb = stick(context) ?: return emptyList()
        val raw = rawRoot(context, usb)
        return listOfNotNull(usb, raw, File(usb, FOLDER), raw?.let { File(it, FOLDER) }, ownFolder(context, usb))
    }

    /** The first of [places] that takes the file: the file written, or null. */
    internal fun writeTo(usb: File, raw: File?, own: File?, name: String, text: String): File? =
        places(usb, raw, own, name).firstOrNull { f ->
            try { f.parentFile?.mkdirs(); f.writeText(text); true } catch (e: Exception) { Log.w(TAG, "write to ${f.absolutePath}: ${e.message}"); false }
        }?.also { Log.i(TAG, "written: ${it.absolutePath}") }
}