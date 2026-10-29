package app.mg4dash.system

import android.app.ActivityManager
import android.content.Context
import java.io.File

/**
 * Consommation des applis et services de l'autoradio (#69). Mémoire : PSS donnée par le système pour
 * tous les processus. Processeur : en direct (temps CPU entre deux relevés de /proc/<pid>/stat) pour les
 * processus que MG4Dash peut lire (applis système) ; les autres (applis installées, Bluetooth…) sont
 * refusés par le système, on prend alors leur moyenne récente donnée par « dumpsys cpuinfo » ([Proc.cpuAverage]).
 */
object ProcessStats {
    data class Proc(val pid: Int, val packageName: String, val label: String, val service: Boolean,
                    val cpu: Float?, val cpuAverage: Boolean, val ramMb: Int?)

    private val lastTicks = HashMap<Int, Long>()
    private var lastTotal = -1L
    private var averages: Map<Int, Float> = emptyMap()
    private var averagesAt = 0L

    private fun totalTicks(): Long? = try {
        File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }.sum()
    } catch (_: Exception) { null }

    /** utime + stime du processus (champs 14 et 15 de /proc/<pid>/stat, après le nom entre parenthèses). */
    private fun procTicks(pid: Int): Long? = try {
        val s = File("/proc/$pid/stat").readText()
        val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
        f[11].toLong() + f[12].toLong()
    } catch (_: Exception) { null }

    /** % moyen par processus sur la dernière période du système (« 20% 5487/com.exemple: … »), relu toutes les 10 s. */
    private fun recentAverages(): Map<Int, Float> {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - averagesAt < 10_000 && averages.isNotEmpty()) return averages
        averagesAt = now
        averages = try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "cpuinfo"))
            val lines = p.inputStream.bufferedReader().readLines(); p.waitFor()
            val re = Regex("""^\s*([\d.]+)% (\d+)/""")
            lines.mapNotNull { l -> re.find(l)?.let { m -> m.groupValues[2].toInt() to m.groupValues[1].toFloat().coerceAtMost(100f) } }.toMap()
        } catch (e: Exception) { Log.w("Processes", "cpuinfo: ${e.message}"); emptyMap() }
        return averages
    }

    @Synchronized
    fun snapshot(context: Context): List<Proc> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager
        val procs = am.runningAppProcesses.orEmpty()
        val total = totalTicks()
        val dTotal = if (total != null && lastTotal > 0) total - lastTotal else 0L
        val mem = try { am.getProcessMemoryInfo(procs.map { it.pid }.toIntArray()) } catch (_: Exception) { null }
        val seen = HashSet<Int>()
        var avg: Map<Int, Float>? = null
        val out = procs.mapIndexed { i, p ->
            seen += p.pid
            val ticks = procTicks(p.pid)
            val prev = lastTicks[p.pid]
            if (ticks != null) lastTicks[p.pid] = ticks
            val live = if (ticks != null && prev != null && dTotal > 0) (100f * (ticks - prev) / dTotal).coerceIn(0f, 100f) else null
            val average = if (ticks == null) (avg ?: recentAverages().also { avg = it })[p.pid] ?: 0f else null
            val pkg = p.pkgList?.firstOrNull() ?: p.processName.substringBefore(':')
            val appLabel = try { pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString() } catch (_: Exception) { pkg }
            // Processus secondaire (« :service ») : son nom après le libellé de l'appli
            val label = if (p.processName.contains(':')) "$appLabel (${p.processName.substringAfter(':')})" else appLabel
            val service = pm.getLaunchIntentForPackage(pkg) == null || p.processName.contains(':')
            Proc(p.pid, pkg, label, service, live ?: average, live == null && average != null, mem?.getOrNull(i)?.totalPss?.let { it / 1024 })
        }
        lastTicks.keys.retainAll(seen)
        if (total != null) lastTotal = total
        return out
    }
}
