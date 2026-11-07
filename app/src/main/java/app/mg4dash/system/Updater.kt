package app.mg4dash.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import app.mg4dash.BuildConfig
import app.mg4dash.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * MG4Dash update from GitHub releases (pre-releases included).
 * APK download, then installation through PackageInstaller (system uid): Android
 * itself checks that the APK is signed with the same key. After installation, home
 * is relaunched ([Relaunch]).
 */
object Updater {
    private const val TAG = "Update"
    private const val API = "https://api.github.com/repos/Skittle6938/MG4Dash/releases?per_page=10"
    /** Latest non-beta release (GitHub ignores pre-releases and drafts). */
    private const val API_STABLE = "https://api.github.com/repos/Skittle6938/MG4Dash/releases/latest"

    data class Release(val version: String, val url: String, val size: Long, val notes: String)

    /** Latest published release (pre-releases included if [beta]), or null if none. Network: off the main thread. */
    fun latest(beta: Boolean = true, api: String = API): Release? {
        val arr = JSONArray(get(api))
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            if (!beta && r.optBoolean("prerelease")) continue
            parse(r)?.let { return it }
        }
        return null
    }

    /** Latest non-beta release, even older than the last ten pre-releases. Network: off the main thread. */
    fun latestStable(): Release? = parse(JSONObject(get(API_STABLE)))

    private fun get(api: String): String = DataUsage.tag(DataUsage.Use.UPDATES) { getUntagged(api) }
    private fun getUntagged(api: String): String {
        val c = URL(api).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "MG4Dash")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Release with its APK, or null if it has none. */
    private fun parse(r: JSONObject): Release? {
        val assets = r.optJSONArray("assets") ?: return null
        for (j in 0 until assets.length()) {
            val a = assets.getJSONObject(j)
            if (a.getString("name").endsWith(".apk")) {
                return Release(r.getString("tag_name").trimStart('v'), a.getString("browser_download_url"),
                    a.optLong("size"), r.optString("body"))
            }
        }
        return null
    }

    /** Name of the language blocks in the release notes (#73): `<details><summary>Français</summary> … </details>`. */
    private val LANG_NAMES = mapOf("fr" to "Français", "de" to "Deutsch", "es" to "Español", "it" to "Italiano", "nl" to "Nederlands", "tr" to "Türkçe")

    /**
     * Notes in the head unit's language (#73): this language's collapsible block if it exists, otherwise the
     * English text (everything before the first block). Old notes without blocks stay unchanged.
     */
    fun localNotes(md: String, lang: String = java.util.Locale.getDefault().language): String {
        val english = md.substringBefore("<details>").trim()
        val name = LANG_NAMES[lang] ?: return english
        val re = Regex("""<details>\s*<summary>\s*""" + Regex.escape(name) + """\s*</summary>(.*?)</details>""", RegexOption.DOT_MATCHES_ALL)
        return re.find(md)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: english
    }

    /** Release notes as plain text (GitHub markdown without bold or code, "•" bullets), in the head unit's language. */
    fun plainNotes(md: String): String = localNotes(md)
        .replace("**", "").replace("`", "")
        .lines().joinToString("\n") { l -> l.trim().let { if (it.startsWith("- ")) "•  " + it.drop(2) else it } }
        .trim()

    /**
     * true if [remote] is newer than the installed version (numeric comparison 0.8.2 < 0.8.10).
     * With equal numbers, the final version wins over the beta (1.4.1-beta3 < 1.4.1), and betas
     * follow by their number (1.4.1-beta0 < 1.4.1-beta1; "-beta" with no number counts as 0).
     */
    fun isNewer(remote: String, local: String = BuildConfig.VERSION_NAME): Boolean {
        fun core(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun pre(v: String): Int? = if (!v.contains('-')) null else v.substringAfter('-').filter { it.isDigit() }.toIntOrNull() ?: 0
        val a = core(remote); val b = core(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val pa = pre(remote); val pb = pre(local)
        return when {
            pa == null -> pb != null      // final against beta: newer
            pb == null -> false           // beta against final: older
            else -> pa > pb
        }
    }

    /** Warning shown at the top of update windows: download size, advised connection. */
    fun dataWarning(context: Context, r: Release): String = context.getString(app.mg4dash.R.string.upd_data_warning,
        String.format(java.util.Locale.getDefault(), "%.1f", r.size / 1_000_000f))

    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Only one MG4Dash update at a time: a double press, or the startup window and the settings
     * together, started two downloads into the same file and two installations, with one
     * recompilation in the middle of the other (risk of damaged compiled code). false if one is already running.
     */
    fun begin(): Boolean = busy.compareAndSet(false, true).also { if (!it) Log.i(TAG, "update already running, ignored") }

    /** End of a failed update (on success, MG4Dash restarts). */
    fun end() { busy.set(false) }

    /** Downloads the APK; [progress] receives 0..100. Network: off the main thread. */
    fun download(context: Context, r: Release, fileName: String = "update.apk", progress: (Int) -> Unit): File =
        DataUsage.tag(DataUsage.Use.UPDATES) { downloadUntagged(context, r, fileName, progress) }
    /**
     * A cut (the car's Wi-Fi changing access point, a tunnel) no longer loses what came (#98): up to 6 tries, 5 s
     * apart then longer, each one asking GitHub for the rest only (Range), from the release's address again since
     * the address it sends to only lasts a few minutes.
     */
    private fun downloadUntagged(context: Context, r: Release, fileName: String, progress: (Int) -> Unit): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, fileName)
        out.delete()
        var tries = 0
        while (true) {
            try {
                fetch(r, out, progress)
                break
            } catch (e: java.io.IOException) {
                if (++tries >= 6) throw e
                Log.w(TAG, "download cut at ${out.length()} bytes (${e.message}), try ${tries + 1} in ${5 * tries} s")
                Thread.sleep(5000L * tries)
            }
        }
        Log.i(TAG, "downloaded ${r.version} (${out.length()} bytes)")
        return out
    }

    /** What is missing of [out] (all of it the first time), added at its end. */
    private fun fetch(r: Release, out: File, progress: (Int) -> Unit) {
        val have = out.length()
        var url = URL(r.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MG4Dash")
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); hops++; continue }
            if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
            break
        }
        try {
            // 200: the server sends everything again, the file starts over
            val append = c.responseCode == 206
            var done = if (append) have else 0L
            val total = if (append) done + c.contentLengthLong.coerceAtLeast(0) else c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { input ->
                java.io.FileOutputStream(out, append).use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
            if (total > 0 && done < total) throw java.io.IOException("ended at $done of $total bytes")
        } finally { c.disconnect() }
    }

    /** Installs the downloaded APK; MG4Dash is stopped then relaunched by Android. */
    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Equivalent of "pm install -f": otherwise the car refuses non-system apps on internal storage
            setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
            try {
                val f = javaClass.getField("installFlags")
                // INSTALL_INTERNAL (0x10); INSTALL_DONT_KILL_APP (0x1000): keep control to recompile
                // cleanly before restarting (see Result)
                f.setInt(this, f.getInt(this) or 0x00000010 or 0x00001000)
            } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { input ->
                s.openWrite("base.apk", 0, apk.length()).use { o -> input.copyTo(o); s.fsync(o) }
            }
            val intent = Intent(context, Result::class.java)
            val sender = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT)
            Log.i(TAG, "install started (session $id)")
            s.commit(sender.intentSender)
        }
    }

    /** Installation result (log). */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install result: $status $msg")
            if (status != PackageInstaller.STATUS_SUCCESS) { end(); return }
            // No more recompilation here: as MG4Dash is often home, Android could relaunch it while
            // it rewrote its compiled code, which then stayed damaged (screen stuck at the next start,
            // reproduced on 31/10). The compilation done by the installation is enough; Rescue repairs otherwise.
            restart(context)
        }
    }

    /** Recompiles MG4Dash in "speed" mode (equivalent of adb shell cmd package compile -m speed -f). */
    fun recompile(context: Context) {
        try {
            val pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)
            val m = pm.javaClass.methods.first { it.name == "performDexOptMode" && it.parameterTypes.size == 6 }
            val ok = m.invoke(pm, context.packageName, false, "speed", true, true, null)
            Log.i(TAG, "recompile: $ok")
        } catch (e: Throwable) {
            Log.w(TAG, "recompile failed: ${(e.cause ?: e).message}")
        }
    }

    /** Restarts MG4Dash on the new version. */
    private fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** After an update, MG4Dash home is reopened. */
    class Relaunch : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            Log.i(TAG, "update installed: ${BuildConfig.VERSION_NAME}")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
