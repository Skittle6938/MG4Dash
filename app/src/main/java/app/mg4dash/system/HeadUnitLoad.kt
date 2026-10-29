package app.mg4dash.system

import java.io.File

/**
 * Charge de l'autoradio (#69) : processeur (écart entre deux lectures de /proc/stat) et mémoire
 * (/proc/meminfo, utilisée = totale − disponible). Null si le système refuse la lecture.
 */
object HeadUnitLoad {
    private var lastIdle = -1L
    private var lastTotal = -1L

    /** % du processeur depuis l'appel précédent (le premier appel ne donne rien). */
    fun cpuPercent(): Int? = try {
        val f = File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val idle = f[3] + f.getOrElse(4) { 0L }   // inactif + attente disque
        val total = f.sum()
        val dIdle = idle - lastIdle; val dTotal = total - lastTotal
        val prev = lastTotal
        lastIdle = idle; lastTotal = total
        if (prev < 0 || dTotal <= 0) null else (100 * (dTotal - dIdle) / dTotal).toInt().coerceIn(0, 100)
    } catch (e: Exception) { null }

    /** Mémoire utilisée et totale, en Mo. */
    fun memoryMb(): Pair<Int, Int>? = try {
        val m = File("/proc/meminfo").readLines().associate { l ->
            l.substringBefore(':') to (l.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L)
        }
        val total = m["MemTotal"] ?: 0L
        val avail = m["MemAvailable"] ?: 0L
        if (total <= 0) null else (((total - avail) / 1024).toInt() to (total / 1024).toInt())
    } catch (e: Exception) { null }
}
