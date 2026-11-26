package app.mg4dash.system

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.io.RandomAccessFile

/**
 * Keeps the head unit's storage from filling up (26/11: 233 MB left of 4.9 GB, an update could not install). Once a
 * day and when the car leaves READY: MG4Dash's own downloaded APKs (installed, or a day old), the old energy log
 * capped, its caches trimmed; and, only if the driver allows it, the car's own diagnostic logs (SAIC offline logger,
 * about 180 MB an hour) kept to their newest [CAR_LOGS_KEEP] bytes. Other apps and /data/local/tmp are out of reach.
 */
object StorageCleaner {
    private const val TAG = "Storage"
    private const val DAY_MS = 24L * 3600 * 1000
    /** Under this free space, Settings warns. */
    const val LOW_BYTES = 500L * 1024 * 1024
    /** The rotated energy log is kept to its last 2 MB (it was 8 MB). */
    internal const val OLD_ENERGY_MAX = 2L * 1024 * 1024
    /** Files in the cache directory older than this go (the map tiles are trimmed by osmdroid itself). */
    private const val CACHE_AGE_MS = 7 * DAY_MS
    /** The car's offline logs: what is kept, newest first. */
    const val CAR_LOGS_KEEP = 150L * 1024 * 1024
    private val CAR_LOGS = File("/sdcard/logs/offlinelogs")

    /** Free and total bytes of the data partition (where apps, their files and /sdcard are). */
    fun space(): Pair<Long, Long> = try {
        val s = StatFs(Environment.getDataDirectory().path)
        s.availableBytes to s.totalBytes
    } catch (_: Exception) { 0L to 0L }

    /** Once a day at most (or [force]): off the main thread. */
    fun runIfDue(context: Context, reason: String, force: Boolean = false) {
        val app = context.applicationContext
        val sp = Prefs(app).raw
        val now = System.currentTimeMillis()
        if (!force && now - sp.getLong("storage_clean_at", 0L) < DAY_MS) return
        sp.edit().putLong("storage_clean_at", now).apply()
        Thread({ try { run(app, reason) } catch (e: Exception) { Log.w(TAG, "clean: ${e.message}") } }, "storage-clean").start()
    }

    fun run(context: Context, reason: String) {
        val before = space().first
        var freed = 0L
        val now = System.currentTimeMillis()
        val ext = context.getExternalFilesDir(null)
        // Downloaded APKs: once a day old (an install is done well before), or the update one when its version is in
        ext?.listFiles { f -> f.isFile && f.name.endsWith(".apk") }?.forEach { f ->
            if (now - f.lastModified() > DAY_MS || installedAlready(context, f)) { val n = f.length(); if (f.delete()) freed += n }
        }
        // The rotated energy log: its newest part only
        ext?.let { File(it, "energy_log.old.csv") }?.takeIf { it.isFile && it.length() > OLD_ENERGY_MAX }?.let { freed += keepTail(it, OLD_ENERGY_MAX) }
        // Old files of the cache (copied APKs of the bundled helpers, shared pictures…), the tiles' folder aside
        context.cacheDir.listFiles()?.filter { it.name != "osmdroid-tiles" }?.forEach { f ->
            if (now - f.lastModified() > CACHE_AGE_MS) freed += deleteTree(f)
        }
        // The car's diagnostic logs, if allowed in Settings
        if (Prefs(context).raw.getBoolean("clean_car_logs", false)) freed += trimCarLogs(CAR_LOGS, CAR_LOGS_KEEP)
        Log.i(TAG, "cleaned ($reason): ${freed / 1024 / 1024} MB freed, free ${before / 1024 / 1024} → ${space().first / 1024 / 1024} MB")
    }

    /** An APK whose package is installed at that version or newer. */
    private fun installedAlready(context: Context, apk: File): Boolean = try {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(apk.path, 0) ?: return false
        @Suppress("DEPRECATION") val code = info.versionCode
        @Suppress("DEPRECATION") val inst = pm.getPackageInfo(info.packageName, 0).versionCode
        inst >= code
    } catch (_: Exception) { false }

    /** Keeps the last [max] bytes of [f], from a line start; returns the bytes freed. */
    internal fun keepTail(f: File, max: Long): Long {
        val len = f.length()
        if (len <= max) return 0L
        val tmp = File(f.parentFile, f.name + ".tmp")
        RandomAccessFile(f, "r").use { r ->
            r.seek(len - max)
            r.readLine()   // a cut line goes
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = r.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
            }
        }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return len - f.length()
    }

    /** All the files under [root], oldest deleted first, until [keep] bytes are left; returns the bytes freed. */
    internal fun trimCarLogs(root: File, keep: Long): Long {
        if (!root.isDirectory) return 0L
        val files = root.walkTopDown().filter { it.isFile }.toList()
        var total = files.sumOf { it.length() }
        var freed = 0L
        // The file being written (the newest of its folder, "logcat" without a date) is never touched
        val writing = files.groupBy { it.parentFile }.values.mapNotNull { g -> g.maxByOrNull { it.lastModified() } }.toSet()
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= keep) break
            if (f in writing) continue
            val n = f.length()
            if (f.delete()) { total -= n; freed += n }
        }
        // Empty folders of older starts
        root.walkBottomUp().filter { it.isDirectory && it != root && it.list()?.isEmpty() == true }.forEach { it.delete() }
        return freed
    }

    private fun deleteTree(f: File): Long {
        val n = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return if (f.deleteRecursively()) n else 0L
    }
}
