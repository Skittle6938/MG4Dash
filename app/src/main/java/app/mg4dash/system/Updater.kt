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
 * Mise à jour de MG4Dash depuis les releases GitHub (pré-versions comprises).
 * Téléchargement de l'APK, puis installation par PackageInstaller (uid système) : Android
 * vérifie lui-même que l'APK est signé avec la même clé. Après l'installation, l'accueil
 * est relancé ([Relaunch]).
 */
object Updater {
    private const val TAG = "Update"
    private const val API = "https://api.github.com/repos/Skittle6938/MG4Dash/releases?per_page=10"
    /** Dernière release non bêta (GitHub ignore les pré-versions et les brouillons). */
    private const val API_STABLE = "https://api.github.com/repos/Skittle6938/MG4Dash/releases/latest"

    data class Release(val version: String, val url: String, val size: Long, val notes: String)

    /** Dernière release publiée (pré-versions comprises si [beta]), ou null s'il n'y en a pas. Réseau : hors du fil principal. */
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

    /** Dernière release non bêta, même plus ancienne que les dix dernières pré-versions. Réseau : hors du fil principal. */
    fun latestStable(): Release? = parse(JSONObject(get(API_STABLE)))

    private fun get(api: String): String {
        val c = URL(api).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "MG4Dash")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Release avec son APK, ou null si elle n'en a pas. */
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

    /** Notes de release en texte simple (le markdown de GitHub sans gras ni code, puces « • »). */
    fun plainNotes(md: String): String = md
        .replace("**", "").replace("`", "")
        .lines().joinToString("\n") { l -> l.trim().let { if (it.startsWith("- ")) "•  " + it.drop(2) else it } }
        .trim()

    /**
     * true si [remote] est plus récente que la version installée (comparaison numérique 0.8.2 < 0.8.10).
     * À numéros égaux, la version finale l'emporte sur la bêta (1.4.1-beta3 < 1.4.1), et les bêtas
     * se suivent par leur numéro (1.4.1-beta0 < 1.4.1-beta1 ; « -beta » sans numéro compte pour 0).
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
            pa == null -> pb != null      // finale contre bêta : plus récente
            pb == null -> false           // bêta contre finale : plus ancienne
            else -> pa > pb
        }
    }

    /** Télécharge l'APK ; [progress] reçoit 0..100. Réseau : hors du fil principal. */
    fun download(context: Context, r: Release, fileName: String = "update.apk", progress: (Int) -> Unit): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val out = File(dir, fileName)
        var url = URL(r.url)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 8000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "MG4Dash")
            val code = c.responseCode
            if (code in 300..399 && hops < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); hops++; continue }
            if (code != 200) throw IllegalStateException("HTTP $code")
            break
        }
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        } finally { c.disconnect() }
        Log.i(TAG, "downloaded ${r.version} (${out.length()} bytes)")
        return out
    }

    /** Installe l'APK téléchargé ; MG4Dash est arrêté puis relancé par Android. */
    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Équivalent de « pm install -f » : la voiture refuse sinon les applis hors système sur la mémoire interne
            setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
            try {
                val f = javaClass.getField("installFlags")
                // INSTALL_INTERNAL (0x10) ; INSTALL_DONT_KILL_APP (0x1000) : on garde la main pour recompiler
                // proprement avant de redémarrer (voir Result)
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

    /** Résultat de l'installation (journal). */
    class Result : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install result: $status $msg")
            if (status != PackageInstaller.STATUS_SUCCESS) return
            // Après une installation, la compilation faite par la voiture était parfois inutilisable
            // (ClassNotFoundException au démarrage) ; une recompilation « speed » la remplace.
            recompile(context)
            restart(context)
        }
    }

    /** Recompile MG4Dash en mode « speed » (équivalent de adb shell cmd package compile -m speed -f). */
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

    /** Redémarre MG4Dash sur la nouvelle version. */
    private fun restart(context: Context) {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_CANCEL_CURRENT)
        (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Après une mise à jour, l'accueil MG4Dash est rouvert. */
    class Relaunch : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            Log.i(TAG, "update installed: ${BuildConfig.VERSION_NAME}")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
