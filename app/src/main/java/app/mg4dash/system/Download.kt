package app.mg4dash.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Downloads of the updates (MG4Dash, Radio Dash, the web engine), made to start at once on any connection:
 * - over a network whose internet Android validated: the Wi-Fi if it is, otherwise the 4G (not with
 *   "Updates on Wi-Fi only"); a Wi-Fi client without a working internet no longer holds it;
 * - IPv4 addresses first, 3 s each: GitHub's file server also has IPv6 ones, and on a Wi-Fi announcing an IPv6
 *   that does not get through, Android 9 waited 8 s on each before the IPv4 (a minute or more without a byte);
 * - given up after 20 s without a byte or 3 minutes in all, with a clear reason; a cut resumes where it stopped
 *   (Range), up to 6 tries; the file is written aside and renamed once complete.
 */
object Download {
    private const val TAG = "Update"
    const val CONNECT_MS = 3_000
    const val STALL_MS = 20_000
    const val TOTAL_MS = 3 * 60_000L
    const val TRIES = 6

    /** Why a download stopped: shown as is to the driver. */
    class Failure(val reason: Reason, val done: Long = 0, val total: Long = 0, cause: Throwable? = null) :
        IOException("$reason at $done of $total bytes", cause)

    enum class Reason { NO_ANSWER, STALLED, TOO_SLOW, NO_WIFI, HTTP }

    /** The wait between two tries (shortened by the tests). */
    internal var sleep: (Long) -> Unit = { Thread.sleep(it) }

    /** What the download does now: the screens show it. */
    sealed class Step {
        /** Connecting (try [attempt] of [TRIES]); [waitS] seconds before it, after a cut. */
        data class Connecting(val attempt: Int, val waitS: Int = 0) : Step()
        data class Receiving(val done: Long, val total: Long) : Step() {
            val pct: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
        }
    }

    /** IPv4 first, in the order given, then the others. */
    internal fun ipv4First(addrs: List<InetAddress>): List<InetAddress> = addrs.filter { it is Inet4Address } + addrs.filter { it !is Inet4Address }

    /** Wait before try [attempt] (2..): 5 s, then 10, 15… */
    internal fun waitBefore(attempt: Int): Long = 5_000L * (attempt - 1)

    /** Another try fits in the time left (3 minutes from [startedAt]). */
    internal fun tryFits(attempt: Int, startedAt: Long, now: Long): Boolean = attempt <= TRIES && now + waitBefore(attempt) - startedAt < TOTAL_MS

    /**
     * The network to use: a validated Wi-Fi, else a validated other one (none with Wi-Fi only), else Android's
     * default (null). [wifiOnly] with a Wi-Fi that is not validated: [Reason.NO_WIFI].
     */
    internal fun choose(nets: List<Pair<Network, NetworkCapabilities>>, wifiOnly: Boolean): Network? {
        val ok = nets.filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        ok.firstOrNull { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.let { return it.first }
        if (wifiOnly) {
            if (nets.any { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }) throw Failure(Reason.NO_WIFI)
            return null
        }
        return ok.firstOrNull()?.first
    }

    private fun network(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        @Suppress("DEPRECATION")
        val nets = try { cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } } } catch (_: Exception) { emptyList() }
        val n = choose(nets, DataUsage.updatesWifiOnly(context))
        Log.i(TAG, "download network: ${n?.let { cm.getNetworkCapabilities(it)?.let { c -> if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi" else "mobile" } } ?: "default"}")
        return n
    }

    /** [url] into [out], what is missing of it only; [step] follows it. Network: off the main thread. */
    fun fetch(context: Context, url: String, out: File, sizeHint: Long, step: (Step) -> Unit) {
        val part = File(out.parentFile, out.name + ".part")
        out.delete()
        // What came of an earlier try of the same file resumes; another file's (an older version) starts over
        val of = File(out.parentFile, out.name + ".url")
        if (!part.exists() || (try { of.readText() } catch (_: Exception) { "" }) != url) { part.delete(); of.writeText(url) }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val net = network(context)
        var attempt = 1
        while (true) {
            step(Step.Connecting(attempt))
            try {
                once(net, URL(url), part, sizeHint, startedAt, step)
                break
            } catch (e: IOException) {
                val f = e as? Failure ?: Failure(Reason.STALLED, part.length(), sizeHint, e)
                if (f.reason == Reason.HTTP || f.reason == Reason.NO_WIFI || f.reason == Reason.TOO_SLOW) throw f
                val now = android.os.SystemClock.elapsedRealtime()
                if (!tryFits(attempt + 1, startedAt, now)) throw if (attempt + 1 > TRIES) f else Failure(Reason.TOO_SLOW, part.length(), sizeHint, e)
                attempt++
                val wait = waitBefore(attempt)
                Log.w(TAG, "download cut at ${part.length()} bytes (${e.message}), try $attempt in ${wait / 1000} s")
                step(Step.Connecting(attempt, (wait / 1000).toInt()))
                sleep(wait)
            }
        }
        if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
    }

    /** One try: redirects followed (5 at most), the rest asked with Range, appended to [part]. */
    private fun once(net: Network?, start: URL, part: File, sizeHint: Long, startedAt: Long, step: (Step) -> Unit) {
        val have = part.length()
        var url = start
        var hops = 0
        while (true) {
            val r = request(net, url, have)
            if (r.code in 300..399 && hops < 5) {
                val loc = r.headers["location"] ?: run { r.close(); throw Failure(Reason.HTTP) }
                r.close(); url = URL(url, loc); hops++; continue
            }
            r.use {
                if (r.code != 200 && r.code != 206) throw Failure(Reason.HTTP, have, sizeHint, IOException("HTTP ${r.code}"))
                val append = r.code == 206
                var done = if (append) have else 0L
                val length = r.headers["content-length"]?.toLongOrNull()
                val total = if (length != null) (if (append) done + length else length) else sizeHint
                java.io.FileOutputStream(part, append).use { o ->
                    val buf = ByteArray(64 * 1024)
                    step(Step.Receiving(done, total))
                    while (true) {
                        val n = try { r.body.read(buf) } catch (e: java.net.SocketTimeoutException) { throw Failure(Reason.STALLED, done, total, e) }
                        if (n < 0) break
                        o.write(buf, 0, n); done += n
                        step(Step.Receiving(done, total))
                        if (android.os.SystemClock.elapsedRealtime() - startedAt > TOTAL_MS) throw Failure(Reason.TOO_SLOW, done, total)
                    }
                }
                if (total > 0 && done < total) throw Failure(Reason.STALLED, done, total)
            }
            return
        }
    }

    /** An HTTP/1.1 answer: its status, its headers (names in lower case), its body. */
    internal class Response(val code: Int, val headers: Map<String, String>, val body: InputStream, private val socket: Socket) : java.io.Closeable {
        override fun close() { try { socket.close() } catch (_: Exception) {} }
    }

    /**
     * GET [url] from byte [from], the connection made here: HttpURLConnection tries the addresses in the system's
     * order, 8 s or more each, and cannot be told IPv4 first.
     */
    private fun request(net: Network?, url: URL, from: Long): Response {
        val host = url.host
        val https = url.protocol.equals("https", true)
        val port = if (url.port > 0) url.port else if (https) 443 else 80
        val addrs = try { ipv4First((net?.getAllByName(host) ?: InetAddress.getAllByName(host)).toList()) }
            catch (e: java.net.UnknownHostException) { throw Failure(Reason.NO_ANSWER, cause = e) }
        var sock: Socket? = null
        for (a in addrs) {
            val s = Socket()
            try {
                net?.bindSocket(s)
                android.net.TrafficStats.tagSocket(s)
                s.connect(InetSocketAddress(a, port), CONNECT_MS)
                sock = s; break
            } catch (e: IOException) {
                Log.w(TAG, "$host at ${a.hostAddress}: ${e.message}")
                try { s.close() } catch (_: Exception) {}
            }
        }
        val plain = sock ?: throw Failure(Reason.NO_ANSWER)
        plain.soTimeout = STALL_MS
        val s: Socket = if (!https) plain else {
            val ssl = (javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory).createSocket(plain, host, port, true) as javax.net.ssl.SSLSocket
            ssl.startHandshake()
            if (!javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                ssl.close(); throw javax.net.ssl.SSLPeerUnverifiedException("certificate does not match $host")
            }
            ssl
        }
        val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("User-Agent: MG4Dash\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n")
            if (from > 0) append("Range: bytes=").append(from).append("-\r\n")
            append("\r\n")
        }
        s.getOutputStream().apply { write(req.toByteArray(Charsets.ISO_8859_1)); flush() }
        val input = BufferedInputStream(s.getInputStream())
        val status = line(input) ?: throw IOException("no answer from $host")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad answer: $status")
        val headers = HashMap<String, String>()
        while (true) {
            val h = line(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':'); if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val body: InputStream = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> Chunked(input)
            else -> input
        }
        return Response(code, headers, body, s)
    }

    /** A header line, without its CR LF; null at the end of the stream. */
    internal fun line(input: InputStream): String? {
        val b = java.io.ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            b.write(c)
        }
    }

    /** Chunked body (a redirect's page, at most: the files come with their length). */
    internal class Chunked(input: InputStream) : FilterInputStream(input) {
        private var left = 0L
        private var ended = false
        override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ended) return -1
            if (left == 0L) {
                val size = line(`in`)?.substringBefore(';')?.trim()?.toLongOrNull(16) ?: run { ended = true; return -1 }
                if (size == 0L) { ended = true; return -1 }
                left = size
            }
            val n = `in`.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) { ended = true; return -1 }
            left -= n
            if (left == 0L) line(`in`)
            return n
        }
    }
}
