package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest

/**
 * "Recent web engine" option (#98): the car's WebView (Chromium 66, 2018) cannot read the JavaScript of today's
 * sites (ABRP, Waze, OpenStreetMap: black or empty in the web app card). On head units whose WebView is signed with
 * AOSP's public test key, as the MG4's, a newer one installs as an update of that system app: Chromium 138, the last
 * for Android 9, LineageOS's prebuilt, downloaded from LineageOS itself (pinned commit, checked by its SHA-256) and
 * signed here with that public key (apksig, car assets webengine/), nothing being hosted by MG4Dash. Its system
 * partition is left as it is; [revert] takes the update off, Chromium 66 coming back.
 */
object WebEngine {
    private const val TAG = "WebEngine"
    const val PKG = "com.android.webview"
    const val VERSION = "138.0.7204.63"
    private const val VERSION_CODE = 720406301L
    /**
     * LineageOS's prebuilt WebView for arm64 at commit f6f994f9912b26d1dffd100f6de959f9dfd31414 of
     * android_external_chromium-webview_prebuilt_arm64: a Git LFS file kept by LineageOS's own LFS server (GitHub has
     * only the pointer), whose batch API gives a download link valid for a minute.
     */
    private const val LFS_BATCH = "https://review.lineageos.org/LineageOS/android_external_chromium-webview_prebuilt_arm64.git/info/lfs/objects/batch"
    const val SIZE = 243509357L
    /** SHA-256 of that file (its LFS object id), checked before signing it. */
    private const val SHA256 = "446ead71292578830d99e690fd8dded73bc63d85df80f0cfba37762ccb330326"
    private const val KEY = "webengine/testkey.pk8"
    private const val CERT = "webengine/testkey.x509.pem"
    /** AOSP's test key, which signs the car's WebView. */
    private const val TESTKEY = "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
    private const val ACTION_RESULT = "app.mg4dash.WEB_ENGINE_RESULT"

    @Suppress("DEPRECATION")
    private fun info(context: Context) = try {
        context.packageManager.getPackageInfo(PKG, android.content.pm.PackageManager.GET_SIGNATURES)
    } catch (_: Exception) { null }

    @Suppress("DEPRECATION")
    private fun code(i: android.content.pm.PackageInfo) = i.versionCode.toLong()

    /** Version of the WebView in use ("66.0.3359.158"), or null. */
    fun current(context: Context): String? = info(context)?.versionName

    /** The recent engine is installed (an update of the system WebView, at least this version). */
    fun installed(context: Context): Boolean = info(context)?.let { i ->
        code(i) >= VERSION_CODE && (i.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } == true

    /** This head unit can take it: Android 9, 64-bit ARM, its WebView signed with AOSP's test key and older. */
    fun possible(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT != 28 || "arm64-v8a" !in android.os.Build.SUPPORTED_ABIS) return false
        if (!hasKey(context)) return false
        val i = info(context) ?: return false
        if (code(i) >= VERSION_CODE) return false
        @Suppress("DEPRECATION")
        val cert = i.signatures?.firstOrNull()?.toByteArray() ?: return false
        return hex(MessageDigest.getInstance("SHA-256").digest(cert)) == TESTKEY
    }

    private fun hasKey(context: Context) = try { context.assets.open(KEY).close(); true } catch (_: Exception) { false }

    /** [input] signed with AOSP's test key (v1 and v2, as the car's WebView), into [output]. */
    private fun sign(context: Context, input: File, output: File) {
        val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(
            java.security.spec.PKCS8EncodedKeySpec(context.assets.open(KEY).use { it.readBytes() }))
        val cert = context.assets.open(CERT).use {
            java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) as java.security.cert.X509Certificate
        }
        val signer = com.android.apksig.ApkSigner.SignerConfig.Builder("CERT", key, listOf(cert)).build()
        com.android.apksig.ApkSigner.Builder(listOf(signer))
            .setInputApk(input).setOutputApk(output)
            .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false)
            .build().sign()
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** The file's download link, asked of LineageOS's LFS server. */
    private fun lfsUrl(): String {
        val c = java.net.URL(LFS_BATCH).openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = 8000; c.readTimeout = 30000
            c.setRequestProperty("Accept", "application/vnd.git-lfs+json")
            c.setRequestProperty("Content-Type", "application/vnd.git-lfs+json")
            c.setRequestProperty("User-Agent", "MG4Dash")
            val body = org.json.JSONObject().put("operation", "download").put("transfers", org.json.JSONArray().put("basic"))
                .put("objects", org.json.JSONArray().put(org.json.JSONObject().put("oid", SHA256).put("size", SIZE)))
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("LFS HTTP ${c.responseCode}")
            val json = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return json.getJSONArray("objects").getJSONObject(0).getJSONObject("actions").getJSONObject("download").getString("href")
        } finally { c.disconnect() }
    }

    /** What [install] is doing, for the settings' line. */
    enum class Step { DOWNLOAD, SIGN, INSTALL }

    /**
     * Downloads, checks, signs and installs it (off the main thread): [progress] gets the step and 0..100 while it
     * downloads; [done] on the main thread.
     */
    fun install(context: Context, progress: (Step, Int) -> Unit, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val dir = app.getExternalFilesDir(null) ?: app.filesDir
            val raw = File(dir, "webview-lineage.apk")
            val signed = File(dir, "webview.apk")
            fun clean() { raw.delete(); signed.delete() }
            try {
                val url = DataUsage.tag(DataUsage.Use.UPDATES) { lfsUrl() }
                Updater.download(app, Updater.Release(VERSION, url, SIZE, ""), raw.name) { progress(Step.DOWNLOAD, it) }
                val sum = MessageDigest.getInstance("SHA-256").let { md ->
                    raw.inputStream().use { i -> val buf = ByteArray(1 shl 16); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                    hex(md.digest())
                }
                if (sum != SHA256) throw IllegalStateException("checksum $sum")
                progress(Step.SIGN, 0)
                val t = System.currentTimeMillis()
                sign(app, raw, signed)
                raw.delete()
                Log.i(TAG, "signed in ${(System.currentTimeMillis() - t) / 1000} s")
                progress(Step.INSTALL, 0)
                val pi = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(PKG)
                    setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
                    try {
                        val f = javaClass.getField("installFlags")
                        f.setInt(this, f.getInt(this) or 0x00000010)   // INSTALL_INTERNAL, like "pm install -f"
                    } catch (e: Exception) { Log.w(TAG, "INSTALL_INTERNAL flag: ${e.message}") }
                }
                val id = pi.createSession(params)
                app.registerReceiver(result(app) { ok -> clean(); done(ok) }, IntentFilter(ACTION_RESULT), null, main)
                pi.openSession(id).use { s ->
                    signed.inputStream().use { input -> s.openWrite("base.apk", 0, signed.length()).use { o -> input.copyTo(o, 1 shl 16); s.fsync(o) } }
                    s.commit(sender(app, id))
                }
                Log.i(TAG, "installing WebView $VERSION (in use: ${current(app)})")
            } catch (e: Throwable) {
                Log.w(TAG, "install failed: ${e.message}")
                clean()
                main.post { done(false) }
            }
        }.start()
    }

    /** Takes the update off: the car's own WebView (Chromium 66) again. [done] on the main thread. */
    fun revert(context: Context, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        try {
            app.registerReceiver(result(app, done), IntentFilter(ACTION_RESULT), null, Handler(Looper.getMainLooper()))
            app.packageManager.packageInstaller.uninstall(PKG, sender(app, 0))
            Log.i(TAG, "WebView update removed")
        } catch (e: Exception) { Log.w(TAG, "revert failed: ${e.message}"); done(false) }
    }

    private fun sender(app: Context, id: Int) = android.app.PendingIntent.getBroadcast(app, id,
        Intent(ACTION_RESULT).setPackage(app.packageName), android.app.PendingIntent.FLAG_UPDATE_CURRENT).intentSender

    private fun result(app: Context, done: (Boolean) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
            try { app.unregisterReceiver(this) } catch (_: Exception) {}
            Log.i(TAG, "result: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            // MG4Dash Web starts again on the engine now in use
            if (status == PackageInstaller.STATUS_SUCCESS) try {
                val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, WebViewer.PKG)
            } catch (_: Throwable) {}
            done(status == PackageInstaller.STATUS_SUCCESS)
        }
    }
}
