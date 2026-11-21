package app.mg4dash.system

import android.content.Context
import java.io.File

/**
 * Files written to the USB stick, at its root or, when the car refuses it, elsewhere on it or in the car's memory (#110):
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

    /** MG4Dash's own folder on the stick (Android/data), read by the import only: a phone cannot open it (Android 11+). */
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

    /** Tests: the car's memory in place of its shared storage. */
    @Volatile internal var simulatedInternal: File? = null

    /** The car's own memory, its shared storage (what a file manager shows first), or null. */
    private fun internalRoot(): File? = simulatedInternal ?: try { android.os.Environment.getExternalStorageDirectory() } catch (_: Exception) { null }

    /**
     * The last resort when the car lets nothing be written on the stick (Marc, 21/11): an easy folder of the car's own
     * memory, Download/MG4Dash, rather than a folder of the stick a phone cannot open.
     */
    internal fun internalFolder(root: File?): File? = root?.let { File(File(it, android.os.Environment.DIRECTORY_DOWNLOADS), FOLDER) }

    /** A file written: its path (from the stick's root, or from the car's memory), and whether it is in the car's memory. */
    data class Written(val path: String, val internal: Boolean)

    /**
     * [text] written as [name] (#110): on the stick at its root, else at the root of its raw mount, else in a MG4Dash
     * folder (shared view then raw mount); the car refusing all of them, in Download/MG4Dash of the car's memory (the
     * message says the stick was refused). Null without a stick (nothing written) or with nothing writable.
     */
    fun write(context: Context, name: String, text: String): Written? {
        val usb = stick(context) ?: return null
        val raw = rawRoot(context, usb)
        val root = internalRoot()
        val inside = internalFolder(root)
        val f = writeTo(usb, raw, inside, name, text) ?: return null
        val isInside = inside != null && f.absolutePath.startsWith(inside.absolutePath)
        val base = if (isInside) root else listOfNotNull(usb, raw).firstOrNull { f.absolutePath.startsWith(it.absolutePath) }
        val rel = base?.let { f.absolutePath.removePrefix(it.absolutePath).trimStart('/', '\\').replace('\\', '/') } ?: f.absolutePath
        return Written(rel, isInside)
    }

    /** The places tried for [name], in order (see [write]); [inside] the car's memory, last. */
    internal fun places(usb: File, raw: File?, inside: File?, name: String): List<File> = listOfNotNull(
        File(usb, name),
        raw?.let { File(it, name) },
        File(File(usb, FOLDER), name),
        raw?.let { File(File(it, FOLDER), name) },
        inside?.let { File(it, name) },
    )

    /** The folders where a file of MG4Dash may be found on the stick (import), MG4Dash's former own folder included. */
    fun readDirs(context: Context): List<File> {
        val usb = stick(context) ?: return emptyList()
        val raw = rawRoot(context, usb)
        return listOfNotNull(usb, raw, File(usb, FOLDER), raw?.let { File(it, FOLDER) }, ownFolder(context, usb))
    }

    /** The first of [places] that takes the file: the file written, or null. */
    internal fun writeTo(usb: File, raw: File?, inside: File?, name: String, text: String): File? =
        places(usb, raw, inside, name).firstOrNull { f ->
            try { f.parentFile?.mkdirs(); f.writeText(text); true } catch (e: Exception) { Log.w(TAG, "write to ${f.absolutePath}: ${e.message}"); false }
        }?.also { Log.i(TAG, "written: ${it.absolutePath}") }
}