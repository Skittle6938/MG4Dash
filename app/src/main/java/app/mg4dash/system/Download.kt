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
 *   (Range), up to 6 tries; the file is written aside and renamed once complete;
 * - the network chosen again at each try: one that went away (ENONET) is replaced at once by the current one, or
 *   once by no binding at all, without counting a try; with no validated network, the next try starts as soon as
 *   Android validates one instead of after a fixed wait.
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

    enum class Reason { NO_ANSWER, STALLED, TOO_SLOW, NO_WIFI, HTTP, NETWORK_GONE }

    /** The wait between two tries (shortened by the tests). */
    internal var sleep: (Long) -> Unit = { Thread.sleep(it) }

    /** What the download does now: the screens show it. */
    sealed class Step {
        /** No network with a working internet: waiting for one. */
        data object Waiting : Step()
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

    /** A network to try: [net] null for Android's default, not bound; [validated]: its internet checked by Android. */
    internal class Pick(val net: Network?, val label: String, val validated: Boolean, val tbox: Boolean = false)

    /** The T-box asked of Android at the time of the try (see [Tbox]): Android does not list it while the Wi-Fi client is on. */
    internal val TBOX = Pick(null, "T-box 4G (asked)", false, tbox = true)

    /**
     * The networks of a try, in order (owner's choice): a connected Wi-Fi first, validated or not yet (the head unit's
     * check can lag or fail behind a hotspot), then the mobile one (not with "Updates on Wi-Fi only"), then Android's
     * default without binding. Validated ones first within each kind. With no mobile network listed, the T-box is
     * asked of Android after the Wi-Fi (or first without one).
     */
    internal fun order(nets: List<Pair<Network, NetworkCapabilities>>, wifiOnly: Boolean, label: (Network, Boolean) -> String = { n, w -> (if (w) "Wi-Fi #" else "mobile #") + n }): List<Pick> {
        fun ok(c: NetworkCapabilities) = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val online = nets.filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }.sortedBy { (_, c) -> if (ok(c)) 0 else 1 }
        val wifi = online.filter { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        val other = online.filter { (_, c) -> !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        // Wi-Fi only: never the default either (it would go out on the 4G)
        if (wifiOnly) { if (wifi.isEmpty()) throw Failure(Reason.NO_WIFI); return wifi.map { (n, c) -> Pick(n, label(n, true), ok(c)) } }
        return wifi.map { (n, c) -> Pick(n, label(n, true), ok(c)) } +
            (if (other.isEmpty()) listOf(TBOX) else other.map { (n, c) -> Pick(n, label(n, false), ok(c)) }) +
            Pick(null, "default (not bound)", false)
    }

    /** The networks now, chosen again at each try (replaced by the tests). */
    internal var networks: (Context) -> List<Pick> = { current(it) }

    /** Waits up to [ms] for a network with a validated internet; true as soon as there is one (replaced by the tests). */
    internal var awaitValidated: (Context, Long) -> Boolean = { c, ms -> waitForNetwork(c, ms) }

    /** One try over one network (replaced by the tests). */
    internal var attempt: (Network?, URL, File, Long, Long, (Step) -> Unit) -> Unit = { n, u, f, size, at, st -> once(n, u, f, size, at, st) }

    /** Wait for a network before the first try, when there is none but Android's default. */
    internal const val FIRST_WAIT_MS = 5_000L

    private fun current(context: Context): List<Pick> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return listOf(Pick(null, "default (not bound)", false))
        @Suppress("DEPRECATION")
        val nets = try { cm.allNetworks.mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } } } catch (_: Exception) { emptyList() }
        return order(nets, DataUsage.updatesWifiOnly(context))
    }

    private fun waitForNetwork(context: Context, ms: Long): Boolean {
        if (ms <= 0) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run { sleep(ms); return false }
        val seen = java.util.concurrent.CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) seen.countDown()
            }
        }
        try {
            cm.registerNetworkCallback(android.net.NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        } catch (_: Exception) { sleep(ms); return false }
        return try { seen.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS) } finally { try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    }

    /** [url] into [out], what is missing of it only; [step] follows it. Network: off the main thread. */
    fun fetch(context: Context, url: String, out: File, sizeHint: Long, step: (Step) -> Unit) {
        val part = File(out.parentFile, out.name + ".part")
        out.delete()
        // What came of an earlier try of the same file resumes; another file's (an older version) starts over
        val of = File(out.parentFile, out.name + ".url")
        if (!part.exists() || (try { of.readText() } catch (_: Exception) { "" }) != url) { part.delete(); of.writeText(url) }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        fun left() = TOTAL_MS - (android.os.SystemClock.elapsedRealtime() - startedAt)
        var picks = networks(context)
        if (picks.none { it.net != null || it.tbox }) {
            // Only Android's default: a moment for a network to come up first
            step(Step.Waiting)
            if (awaitValidated(context, minOf(FIRST_WAIT_MS, left()))) picks = networks(context)
        }
        var tryNo = 1
        while (true) {
            step(Step.Connecting(tryNo))
            var last: Failure? = null
            var done = false
            for (p in picks) {
                val before = part.length()
                Log.i(TAG, "download try $tryNo on ${p.label}${if (p.net != null && !p.validated) " (not validated)" else ""}")
                try {
                    over(context, p) { net -> attempt(net, URL(url), part, sizeHint, startedAt, step) }
                    done = true; break
                } catch (e: IOException) {
                    val f = e as? Failure ?: Failure(Reason.STALLED, part.length(), sizeHint, e)
                    if (f.reason == Reason.HTTP || f.reason == Reason.NO_WIFI || f.reason == Reason.TOO_SLOW) throw f
                    last = f
                    // Nothing came over this network (no answer, or gone): the next one at once; a cut after some bytes is a try
                    if (part.length() > before || (f.reason != Reason.NO_ANSWER && f.reason != Reason.NETWORK_GONE)) break
                    Log.w(TAG, "${p.label}: ${f.reason} (${f.cause?.message ?: f.message}), next network")
                    if (left() <= 0) break
                }
            }
            if (done) break
            val f = last ?: Failure(Reason.NO_ANSWER)
            if (left() <= 0) throw Failure(Reason.TOO_SLOW, part.length(), sizeHint, f)
            if (!tryFits(tryNo + 1, startedAt, android.os.SystemClock.elapsedRealtime())) throw if (tryNo + 1 > TRIES) f else Failure(Reason.TOO_SLOW, part.length(), sizeHint, f)
            tryNo++
            val wait = waitBefore(tryNo)
            picks = networks(context)
            if (picks.none { it.validated }) {
                // No working internet now: the next try as soon as Android validates a network, the usual wait at most
                Log.w(TAG, "download cut at ${part.length()} bytes (${f.message}), try $tryNo when a network is back (${wait / 1000} s at most)")
                step(Step.Waiting)
                awaitValidated(context, minOf(wait, left()))
            } else {
                Log.w(TAG, "download cut at ${part.length()} bytes (${f.message}), try $tryNo in ${wait / 1000} s")
                step(Step.Connecting(tryNo, (wait / 1000).toInt()))
                sleep(wait)
            }
            picks = networks(context)
        }
        if (!part.renameTo(out)) { part.copyTo(out, overwrite = true); part.delete() }
    }

    /** [block] over [p]'s network; for the T-box, asked of Android and released after. No T-box: [Reason.NO_ANSWER]. */
    internal fun <T> over(context: Context, p: Pick, block: (Network?) -> T): T {
        if (!p.tbox) return block(p.net)
        val lease = tboxLease(context) ?: throw Failure(Reason.NO_ANSWER, cause = IOException("no T-box network"))
        return try { block(lease.network) } finally { lease.close() }
    }

    /** The T-box on demand (replaced by the tests). */
    internal var tboxLease: (Context) -> Tbox.Lease? = { Tbox.request(it) }

    /**
     * A short exchange (the update check) over the networks of [networks] in order, the next one when one fails:
     * the Wi-Fi first, then the T-box (not with "Updates on Wi-Fi only"). The last failure if all fail.
     */
    fun <T> firstWorking(context: Context, what: String, block: (Network?) -> T): T {
        var last: Exception? = null
        for (p in networks(context)) {
            try {
                val r = over(context, p, block)
                Log.i(TAG, "$what on ${p.label}")
                return r
            } catch (e: Exception) {
                Log.w(TAG, "$what on ${p.label}: ${e.message}")
                last = e
            }
        }
        throw last ?: IOException("$what: no network")
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
            // Bound to a network that has just gone (ENONET): another network is chosen, see fetch
            try { net?.bindSocket(s) } catch (e: IOException) {
                Log.w(TAG, "$host: network $net gone: ${e.message}")
                try { s.close() } catch (_: Exception) {}
                throw Failure(Reason.NETWORK_GONE, cause = e)
            }
            try {
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
