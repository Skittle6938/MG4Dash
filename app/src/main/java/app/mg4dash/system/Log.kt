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
    private val buffer = ArrayDeque<String>(MAX)
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
        buffer.addLast("${fmt.format(Date())} $level/$tag  $msg")
    }

    /** Most recent lines first. */
    @Synchronized
    fun snapshot(): List<String> = buffer.reversed()
}
