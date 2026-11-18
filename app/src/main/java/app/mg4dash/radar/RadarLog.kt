package app.mg4dash.radar

import android.content.Context
import java.io.File

/**
 * The alerter's download log, kept on the car (Marc, 18/11: the system log no longer held the trip's downloads):
 * one line per request, its time, what it was (live or preparation, how many tiles, its level), the server, the
 * outcome (HTTP code, error), how long it took and how much came. No position: neither coordinates nor tile names.
 * Two files of [MAX_BYTES] at most in the app's own folder (radar_log.txt, then radar_log.old.txt); it leaves the car
 * only when read over adb.
 */
object RadarLog {
    const val MAX_BYTES = 256 * 1024L
    private val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "radar_log.txt")

    @Synchronized
    fun add(context: Context, what: String, server: String, outcome: String, ms: Long, bytes: Int) {
        try {
            val f = file(context)
            if (f.length() > MAX_BYTES) { val old = File(f.parentFile, "radar_log.old.txt"); old.delete(); f.renameTo(old) }
            val host = runCatching { java.net.URL(server).host }.getOrDefault(server)
            f.appendText("${stamp.format(java.util.Date())}\t$what\t$host\t$outcome\t${ms} ms\t${bytes / 1024} KB\n")
        } catch (_: Exception) {}
    }
}
