package app.mg4dash.system

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.R
import java.util.Calendar

/**
 * Data used by MG4Dash on the car's 4G, over the last 30 days, by use.
 *
 * The car's 4G is not a "mobile" network for Android: it goes through the T-box interface (tbox0),
 * that Android records as ETHERNET (metered, roaming). The Wi-Fi client (wlan0) is WIFI, so it is
 * left out by counting ETHERNET only.
 * MG4Dash runs with the system uid, shared by about 60 packages: its own traffic is found by the tag
 * that each use puts on its connections ([tag], TrafficStats). MG4Dash Web has its own uid.
 * Android's counters (NetworkStatsService, read through its hidden interface: the public API
 * of Android 9 refuses ETHERNET) survive restarts, but per tag they are kept about 15 days:
 * the totals of each day are therefore copied here, the last days being read again at each poll.
 */
object DataUsage {
    private const val TAG = "Data"

    /** Uses, in the order of the screen. [tag]: 0 = counted by uid (MG4Dash Web). */
    enum class Use(val tag: Int, val label: Int, val colorRes: Int) {
        MAP(0x4D470001, R.string.du_map, R.color.accent_cold),
        UPDATES(0x4D470002, R.string.du_updates, R.color.accent_battery),
        WEB(0, R.string.du_web, R.color.accent_energy),
        WEATHER(0x4D470004, R.string.du_weather, R.color.accent_heat),
        HA(0x4D470005, R.string.du_ha, R.color.accent_action),
        OTHER(0x4D470006, R.string.du_other, R.color.text_title),
    }

    const val DAYS = 30
    /** MG's limit, about 1 GB a month. */
    const val MG_LIMIT = 1_000_000_000L
    /** Alert thresholds offered (bytes); 750 MB by default. */
    val THRESHOLDS = longArrayOf(250_000_000, 500_000_000, 750_000_000, 900_000_000, 1_000_000_000, 1_500_000_000, 2_000_000_000)
    const val DEFAULT_THRESHOLD = 750_000_000L
    /** Scale of the quota bar: 1 GB, then 2, 5 and 10 GB when it is exceeded. */
    fun scale(bytes: Long): Long = longArrayOf(1, 2, 5, 10, 20).map { it * 1_000_000_000L }.firstOrNull { bytes <= it } ?: 50_000_000_000L

    /** One day: its date (yyyymmdd) and the bytes of each use, in [Use] order. */
    class Day(val date: Int, val bytes: LongArray) {
        val total: Long get() = bytes.sum()
    }

    /** The last 30 days, oldest first (today last). [available]: false if this head unit cannot be read. */
    class Snapshot(val days: List<Day>, val available: Boolean) {
        val total: Long get() = days.sumOf { it.total }
        fun of(use: Use): Long = days.sumOf { it.bytes[use.ordinal] }
    }

    // ── Tagging ──

    /** Runs [block] with the connections it opens counted for [use] (sockets take the thread's tag when created). */
    inline fun <T> tag(use: Use, block: () -> T): T {
        val before = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(use.tag)
        try { return block() } finally { TrafficStats.setThreadStatsTag(before) }
    }

    // ── Settings ──

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences("data_usage", Context.MODE_PRIVATE)

    fun threshold(context: Context): Long = sp(context).getLong("threshold", DEFAULT_THRESHOLD)
    fun setThreshold(context: Context, v: Long) { sp(context).edit().putLong("threshold", v).apply(); checkAlert(context.applicationContext) }
    /** Popup message when the threshold is reached. */
    fun popup(context: Context): Boolean = sp(context).getBoolean("popup", true)
    fun setPopup(context: Context, v: Boolean) = sp(context).edit().putBoolean("popup", v).apply()
    /** Updates (startup check, check and download) only on Wi-Fi. */
    fun updatesWifiOnly(context: Context): Boolean = sp(context).getBoolean("updates_wifi_only", false)
    fun setUpdatesWifiOnly(context: Context, v: Boolean) = sp(context).edit().putBoolean("updates_wifi_only", v).apply()

    /** True when the updates must wait for Wi-Fi now (option on and the car not on a Wi-Fi). */
    fun updatesBlocked(context: Context): Boolean = updatesWifiOnly(context) && !StatusMonitor.read(context).wifiConnected

    // ── Reading ──

    @Volatile var last: Snapshot? = null
        private set
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()
    /** Called on the main thread after each reading. */
    fun listen(l: (Snapshot) -> Unit) { listeners += l; last?.let(l) }
    fun unlisten(l: (Snapshot) -> Unit) { listeners -= l }

    private var worker: Handler? = null
    private val main = Handler(Looper.getMainLooper())
    private const val POLL_MS = 10 * 60 * 1000L

    /** Reads the counters now, then every 10 minutes. */
    fun start(context: Context) {
        if (worker != null) return
        val app = context.applicationContext
        val t = HandlerThread("data-usage").apply { start() }
        worker = Handler(t.looper).also { h ->
            h.postDelayed(object : Runnable {
                override fun run() { poll(app); h.postDelayed(this, POLL_MS) }
            }, 20_000)
        }
    }

    /** New reading as soon as possible (settings tab opened). */
    fun refreshSoon(context: Context) {
        val app = context.applicationContext
        // Emulator and PC screenshots: simulated days, at once
        if (BuildConfig.MOCK) { poll(app); return }
        val h = worker ?: run { start(app); worker } ?: return
        h.post { poll(app) }
    }

    private fun poll(context: Context) {
        val s = try { read(context) } catch (e: Throwable) { Log.w(TAG, "reading: ${e.message}"); null } ?: return
        // In the log when the total changes by 1 MB or more (or the counters' state)
        val prev = last
        if (prev == null || prev.available != s.available || Math.abs(prev.total - s.total) >= 1_000_000L)
            Log.i(TAG, "4G, $DAYS days: ${s.total / 1000} kB, today ${s.days.last().total / 1000} kB " +
                Use.values().joinToString(" ", "(", ")") { "${it.name.lowercase()} ${s.of(it) / 1000}" } + if (s.available) "" else ", counters unavailable")
        last = s
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.forEach { it(s) } else main.post { listeners.forEach { it(s) } }
        checkAlert(context)
    }

    /** Days kept by Android per tag (15) and read again at each poll: the older ones are frozen here. */
    private const val REREAD_DAYS = 8

    private fun read(context: Context): Snapshot {
        val sp = sp(context)
        val today = Calendar.getInstance()
        val dates = (DAYS - 1 downTo 0).map { back -> dayStart(today, -back) }
        if (BuildConfig.MOCK) return Snapshot(dates.mapIndexed { i, c -> mockDay(i, ymd(c)) }, true)
        val resetAt = sp.getLong("reset_at", 0L)
        val available = try {
            Stats(context).use { stats ->
                val e = sp.edit()
                val webUid = try { context.packageManager.getPackageUid(WebViewer.PKG, 0) } catch (_: Exception) { -1 }
                for (back in 0 until REREAD_DAYS) {
                    val start = dayStart(today, -back)
                    val from = maxOf(start.timeInMillis, resetAt)
                    val to = dayStart(today, -back + 1).timeInMillis
                    val bytes = LongArray(Use.values().size)
                    if (from < to) for (u in Use.values()) {
                        bytes[u.ordinal] = when {
                            u.tag != 0 -> stats.bytes(android.os.Process.myUid(), u.tag, from, to)
                            webUid >= 0 -> stats.bytes(webUid, 0, from, to)
                            else -> 0L
                        }
                    }
                    e.putString("d" + ymd(start), bytes.joinToString(","))
                }
                // Days older than the window: no longer needed
                sp.all.keys.filter { it.startsWith("d") && (it.drop(1).toIntOrNull() ?: 0) < ymd(dates.first()) }.forEach { e.remove(it) }
                e.apply()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "counters unavailable: ${t.javaClass.simpleName} ${t.message}")
            false
        }
        val days = dates.map { c ->
            val d = ymd(c)
            val v = sp.getString("d$d", null)?.split(',')?.map { it.toLongOrNull() ?: 0L }
            Day(d, LongArray(Use.values().size) { v?.getOrNull(it) ?: 0L })
        }
        return Snapshot(days, available)
    }

    /** Counter back to zero: what was used before now is no longer counted. */
    fun reset(context: Context) {
        val sp = sp(context)
        val e = sp.edit().putLong("reset_at", System.currentTimeMillis()).putInt("alert_level", 0)
        sp.all.keys.filter { it.startsWith("d") }.forEach { e.remove(it) }
        e.apply()
        refreshSoon(context)
    }

    private fun dayStart(today: Calendar, offset: Int): Calendar = (today.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_MONTH, offset)
    }

    fun ymd(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    fun calendarOf(ymd: Int): Calendar = Calendar.getInstance().apply {
        clear(); set(ymd / 10000, ymd / 100 % 100 - 1, ymd % 100)
    }

    /** Emulator and PC screenshots: a plausible month (map most days, three updates). */
    private fun mockDay(i: Int, date: Int): Day {
        val r = java.util.Random(i * 7919L + 13)
        val drive = r.nextFloat() < 0.72f
        val mb = 1_000_000L
        val upd = when (i) { 6 -> 34; 13 -> 38; 22 -> 30; 27 -> 16; else -> 0 }
        return Day(date, longArrayOf(
            if (drive) ((2 + r.nextFloat() * 11) * mb).toLong() else 0L, upd * mb,
            if (drive) ((0.5f + r.nextFloat() * 3) * mb).toLong() else 0L, 370_000L, 300_000L, 200_000L))
    }

    /**
     * Android's network counters, through the hidden service interface (allowed to MG4Dash, signed with
     * the platform key): ETHERNET template, i.e. the T-box.
     */
    private class Stats(context: Context) : java.io.Closeable {
        private val session: Any
        private val template: Any
        private val history: java.lang.reflect.Method
        private val values: java.lang.reflect.Method

        init {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "netstats") as IBinder
            val svc = Class.forName("android.net.INetworkStatsService\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            // FLAG_POLL_ON_OPEN (1): the service reads the interfaces' counters before answering
            session = Class.forName("android.net.INetworkStatsService")
                .getMethod("openSessionForUsageStats", Int::class.javaPrimitiveType, String::class.java).invoke(svc, 1, context.packageName)!!
            val tc = Class.forName("android.net.NetworkTemplate")
            template = tc.getMethod("buildTemplateEthernet").invoke(null)!!
            val ip = Int::class.javaPrimitiveType
            history = Class.forName("android.net.INetworkStatsSession").getMethod("getHistoryForUid", tc, ip, ip, ip, ip)
            val lp = Long::class.javaPrimitiveType
            values = Class.forName("android.net.NetworkStatsHistory")
                .getMethod("getValues", lp, lp, Class.forName("android.net.NetworkStatsHistory\$Entry"))
        }

        /** Received + sent bytes of [uid] with [tag] (0: all of the uid) between [from] and [to]. */
        fun bytes(uid: Int, tag: Int, from: Long, to: Long): Long {
            // set = SET_ALL (-1), fields = FIELD_ALL (-1)
            val h = history.invoke(session, template, uid, -1, tag, -1) ?: return 0L
            val e = values.invoke(h, from, to, null) ?: return 0L
            val c = e.javaClass
            return c.getField("rxBytes").getLong(e) + c.getField("txBytes").getLong(e)
        }

        override fun close() {
            try { Class.forName("android.net.INetworkStatsSession").getMethod("close").invoke(session) } catch (_: Throwable) {}
        }
    }

    // ── Alert ──

    /** Alert to show (level: 1 threshold reached, 2 MG's limit exceeded); null when none is waiting. */
    @Volatile var pendingAlert: Int? = null
    private val alertListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun onAlert(l: () -> Unit) { alertListeners += l }
    fun offAlert(l: () -> Unit) { alertListeners -= l }

    /**
     * Once per crossing: the threshold, then MG's limit if the threshold is below it. The message comes
     * back only after the total went under the threshold again (old days leave the 30-day window).
     */
    private fun checkAlert(context: Context) {
        val s = last ?: return
        if (!s.available) return
        val sp = sp(context)
        val total = s.total; val thr = threshold(context)
        val level = when {
            total >= MG_LIMIT && thr < MG_LIMIT -> 2
            total >= thr -> 1
            else -> 0
        }
        val before = sp.getInt("alert_level", 0)
        if (level == before) return
        sp.edit().putInt("alert_level", level).apply()
        if (level > before && popup(context)) {
            Log.i(TAG, "alert level $level: ${total / 1_000_000} MB in $DAYS days (threshold ${thr / 1_000_000} MB)")
            pendingAlert = level
            main.post { alertListeners.forEach { it() } }
        }
    }

    // ── Text ──

    /** 412 Mo, 1,27 Go… */
    fun format(context: Context, bytes: Long): String {
        val loc = java.util.Locale.getDefault()
        return when {
            bytes >= 1_000_000_000L -> context.getString(R.string.du_gb, String.format(loc, if (bytes >= 10_000_000_000L) "%.1f" else "%.2f", bytes / 1e9))
            bytes >= 10_000_000L -> context.getString(R.string.du_mb, String.format(loc, "%d", bytes / 1_000_000L))
            else -> context.getString(R.string.du_mb, String.format(loc, "%.1f", bytes / 1e6))
        }
    }

    /** Thresholds and scale ends: 750 Mo, 1 Go, 1,5 Go. */
    fun formatRound(context: Context, bytes: Long): String {
        val loc = java.util.Locale.getDefault()
        return if (bytes >= 1_000_000_000L) {
            val g = bytes / 1e9
            context.getString(R.string.du_gb, if (g % 1.0 == 0.0) String.format(loc, "%d", g.toLong()) else String.format(loc, "%.1f", g))
        } else context.getString(R.string.du_mb, String.format(loc, "%d", bytes / 1_000_000L))
    }
}
