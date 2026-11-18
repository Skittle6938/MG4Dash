package app.mg4dash.radar

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * The alerter's own HTTPS POST, over TLS 1.3 when it can: overpass-api.de only accepts TLS 1.3, which Android 9's own
 * TLS (and its HttpsURLConnection, which limits itself to TLS 1.2) lacks. Conscrypt, bundled for this only, brings it;
 * the certificates are checked against Android's own CAs and the host name as HttpsURLConnection does, nothing more
 * is trusted. One request per connection (Connection: close); gzip asked and undone; chunked answers read.
 */
object RadarHttp {
    /** [wire]: bytes of the body as received (compressed when gzip). */
    class Response(val code: Int, val body: ByteArray, val headers: Map<String, String>, val wire: Int = body.size)

    /** Conscrypt's TLS with Android's CAs; null when Conscrypt cannot load (another ABI): the platform's TLS then. */
    private val tls: SSLContext? by lazy {
        try {
            val provider = org.conscrypt.Conscrypt.newProvider()
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }
            SSLContext.getInstance("TLS", provider).apply { init(null, tmf.trustManagers, null) }
        } catch (_: Throwable) { null }
    }

    val tls13: Boolean get() = tls != null

    /**
     * POSTs [body] to [url] (https), on [network] when given. Throws IOException on network or TLS failures; the
     * answer otherwise, whatever its code.
     */
    fun post(url: URL, body: ByteArray, contentType: String, userAgent: String, network: android.net.Network?,
             connectMs: Int, readMs: Int): Response = request("POST", url, body, contentType, userAgent, network, connectMs, readMs)

    /** GET (the server's status). */
    fun get(url: URL, userAgent: String, network: android.net.Network?, connectMs: Int, readMs: Int): Response =
        request("GET", url, null, null, userAgent, network, connectMs, readMs)

    private fun request(method: String, url: URL, body: ByteArray?, contentType: String?, userAgent: String, network: android.net.Network?,
                        connectMs: Int, readMs: Int): Response {
        val ctx = tls ?: throw IOException("no TLS 1.3")
        val host = url.host
        val port = if (url.port > 0) url.port else 443
        val plain = network?.socketFactory?.createSocket() ?: Socket()
        val me = Thread.currentThread()
        open[me] = plain
        try {
            plain.connect(InetSocketAddress(host, port), connectMs)
            plain.soTimeout = readMs
            val s = ctx.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            try { org.conscrypt.Conscrypt.setHostname(s, host) } catch (_: Throwable) {}
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, s.session)) throw IOException("certificate does not match $host")
            val head = "$method ${url.file.ifEmpty { "/" }} HTTP/1.1\r\nHost: $host\r\nUser-Agent: $userAgent\r\nAccept-Encoding: gzip\r\n" +
                (if (body != null) "Content-Type: $contentType\r\nContent-Length: ${body.size}\r\n" else "") + "Connection: close\r\n\r\n"
            val out = s.outputStream
            out.write(head.toByteArray(Charsets.ISO_8859_1)); body?.let { out.write(it) }; out.flush()
            return s.inputStream.use { readResponse(it) }
        } finally {
            open.remove(me, plain)
            try { plain.close() } catch (_: Exception) {}
        }
    }

    /** The socket each thread is using now, so that a request can be cut from elsewhere (the preparation stopped). */
    private val open = java.util.concurrent.ConcurrentHashMap<Thread, Socket>()

    /** Cuts the request [thread] is making, if any: it ends at once with an IOException. */
    fun abort(thread: Thread?) {
        val t = thread ?: return
        open[t]?.let { try { it.close() } catch (_: Exception) {} }
    }

    /** Reads an HTTP/1.1 answer: status line, headers, body (Content-Length, chunked or until the end), gzip undone. */
    internal fun readResponse(raw: InputStream): Response {
        val inp = BufferedInputStream(raw)
        val status = line(inp) ?: throw IOException("no answer")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status: $status")
        val headers = HashMap<String, String>()
        while (true) {
            val l = line(inp) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        var body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> chunked(inp)
            headers["content-length"] != null -> exactly(inp, headers["content-length"]!!.toInt())
            else -> inp.readBytes()
        }
        val wire = body.size
        if (headers["content-encoding"]?.contains("gzip", ignoreCase = true) == true) body = GZIPInputStream(body.inputStream()).use { it.readBytes() }
        return Response(code, body, headers, wire)
    }

    private fun line(inp: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return b.toString("ISO-8859-1")
    }

    private fun exactly(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) { val r = inp.read(buf, off, n - off); if (r < 0) throw IOException("answer cut at $off / $n"); off += r }
        return buf
    }

    private fun chunked(inp: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = (line(inp) ?: throw IOException("answer cut")).substringBefore(';').trim().toInt(16)
            if (size == 0) { while (!(line(inp) ?: "").isEmpty()) {}; break }
            out.write(exactly(inp, size))
            line(inp)
        }
        return out.toByteArray()
    }
}
