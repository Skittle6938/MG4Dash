package app.mg4dash.system

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Log: logcat ("MG4L.<area>" tag) + memory buffer of the last 300 lines,
 * viewable in the launcher settings without ADB.
 */
object Log {
    private const val PREFIX = "MG4L."
    private const val MAX = 300
    private class Line(val at: Long, val text: String)
    private val buffer = ArrayDeque<Line>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun i(tag: String, msg: String) { android.util.Log.i(PREFIX + tag, msg); add("I", tag, msg) }
    fun w(tag: String, msg: String) { android.util.Log.w(PREFIX + tag, msg); add("W", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e(PREFIX + tag, msg, t)
        add("E", tag, if (t != null) "$msg : ${t.javaClass.simpleName} ${t.message}" else msg)
    }

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        if (buffer.size >= MAX) buffer.removeFirst()
        val now = System.currentTimeMillis()
        buffer.addLast(Line(now, "${line(now, level, tag)}  $msg"))
    }

    private fun line(at: Long, level: String, tag: String) = synchronized(fmt) { "${fmt.format(Date(at))} $level/$tag" }

    /** Most recent lines first. */
    @Synchronized
    fun snapshot(): List<String> = buffer.reversed().map { it.text }

    /** Most recent lines first, with the radio's lines among ours ([radioLines]). */
    fun snapshot(radio: List<Pair<Long, String>>): List<String> {
        val ours = synchronized(this) { buffer.map { it.at to it.text } }
        val since = ours.firstOrNull()?.first ?: 0L
        // Stable sort: lines of the same millisecond keep their order
        return (ours + radio.filter { it.first >= since }).sortedBy { it.first }.reversed().map { it.second }
    }

    private val LOGCAT = Regex("""^\s*(\d+)\.(\d{3})\d*\s+\d+\s+\d+\s+([VDIWEF])\s+(.+?)\s*: ?(.*)$""")

    /**
     * Radio Dash's lines (and the radio's own on the bands), which only go to logcat: in the window, they say
     * whether a band asked by MG4Dash got there (#80). The car runs MG4Dash as the system, which reads the
     * whole logcat; elsewhere only our own lines come back, and none of them match. Takes a moment: not on
     * the main thread.
     */
    fun radioLines(max: Int = 120): List<Pair<Long, String>> = try {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "epoch"))
        val out = ArrayDeque<Pair<Long, String>>(max)
        p.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { l ->
                val r = radioLine(l) ?: return@forEach
                if (out.size >= max) out.removeFirst()
                out.addLast(r)
            }
        }
        p.destroy()
        out.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** A logcat line ("-v epoch") of Radio Dash or of the radio on its band or source: time and our line format. */
    internal fun radioLine(l: String): Pair<Long, String>? {
        val (sec, ms, level, tag, msg) = LOGCAT.find(l)?.destructured ?: return null
        if (tag.startsWith(PREFIX) || !radio(tag, msg)) return null
        val at = sec.toLong() * 1000 + ms.toLong()
        return at to "${line(at, level, tag)}  $msg"
    }

    /** Radio Dash, and what the radio says about its band and its source. */
    private fun radio(tag: String, msg: String) = tag == "RadioDash" ||
        tag.contains("radio", ignoreCase = true) && (msg.contains("band", ignoreCase = true) || msg.contains("source", ignoreCase = true))
}
