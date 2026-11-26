package app.mg4dash.showroom

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The fun paints and the liveries (redesign step 5, 26/11; port of the mock-up's paint.js). Every pixel of the car
 * has a 3D point and a normal: the patterns are 3D textures, so they are right in both views. Colours are linear.
 */
object Shade {
    /** One pixel being painted: its point (m), normal, facing ratio [f], colour, added light, edge softness, contrast colour. */
    class S {
        var x = 0f; var y = 0f; var z = 0f
        var nx = 0f; var ny = 0f; var nz = 0f
        var f = 0f
        var r = 0f; var g = 0f; var b = 0f
        var er = 0f; var eg = 0f; var eb = 0f
        var aa = 0f
        var k: FloatArray = C_BLACK
    }

    /** Axles (m) and tyre radius. */
    const val YF = -1.273f
    const val RT = .3345f

    fun hex(h: Int) = floatArrayOf(PaintShop.LUT[(h shr 16) and 0xFF], PaintShop.LUT[(h shr 8) and 0xFF], PaintShop.LUT[h and 0xFF])

    val C_WHITE = hex(0xF4F4F2); val C_BLACK = hex(0x0B0B0D); val C_CYAN = hex(0x00B8F0); val C_MAGENTA = hex(0xE6007E)
    val C_GOLD = hex(0xFFD400); val C_OLIVE = hex(0x243018); val C_KHAKI = hex(0x6B5A36); val C_SOOT = hex(0x0E0F0C)
    val C_BROWN = hex(0x1A120A); val C_FR_BLUE = hex(0x0A3D91); val C_FR_RED = hex(0xE1000F)

    private fun clamp(v: Float, a: Float, b: Float) = if (v < a) a else if (v > b) b else v
    fun ss(a: Float, b: Float, x: Float): Float { var t = (x - a) / (b - a); t = if (t < 0) 0f else if (t > 1) 1f else t; return t * t * (3 - 2 * t) }
    fun fract(x: Float) = x - floor(x)

    // ── Noise: 32-bit integer hashing as Math.imul / >>> in JavaScript ──
    fun h3(x: Int, y: Int, z: Int): Float {
        var n = (x * 374761393) xor (y * 668265263) xor (z * 1440662683)
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)).toLong() and 0xFFFFFFFFL).toFloat() / 4294967296f
    }

    fun vn(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        var u = x - xi; var v = y - yi; var w = z - zi
        u = u * u * (3 - 2 * u); v = v * v * (3 - 2 * v); w = w * w * (3 - 2 * w)
        val a = h3(xi, yi, zi); val b = h3(xi + 1, yi, zi); val c = h3(xi, yi + 1, zi); val d = h3(xi + 1, yi + 1, zi)
        val e = h3(xi, yi, zi + 1); val f = h3(xi + 1, yi, zi + 1); val g = h3(xi, yi + 1, zi + 1); val h = h3(xi + 1, yi + 1, zi + 1)
        val p = a + (b - a) * u; val q = c + (d - c) * u; val r = e + (f - e) * u; val s = g + (h - g) * u
        val t = p + (q - p) * v; val k = r + (s - r) * v
        return t + (k - t) * w
    }

    fun fbm(x0: Float, y0: Float, z0: Float, o: Int): Float {
        var x = x0; var y = y0; var z = z0; var a = .5f; var s = 0f; var n = 0f
        for (i in 0 until o) { s += a * vn(x, y, z); n += a; x = x * 2.03f + 11.3f; y = y * 2.03f + 4.7f; z = z * 2.03f + 7.9f; a *= .5f }
        return s / n
    }

    /** Worley: distance to the nearest and second cell points, and the nearest cell's hash. */
    val WO = FloatArray(3)
    fun worley(x: Float, y: Float, z: Float) {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        var d1 = 9f; var d2 = 9f; var id = 0f
        for (k in -1..1) for (j in -1..1) for (i in -1..1) {
            val cx = xi + i; val cy = yi + j; val cz = zi + k; val h = h3(cx, cy, cz)
            val px = cx + h; val py = cy + fract(h * 57.31f); val pz = cz + fract(h * 913.7f)
            val dx = px - x; val dy = py - y; val dz = pz - z; val d = dx * dx + dy * dy + dz * dz
            if (d < d1) { d2 = d1; d1 = d; id = h } else if (d < d2) d2 = d
        }
        WO[0] = sqrt(d1); WO[1] = sqrt(d2); WO[2] = id
    }

    private val HS = FloatArray(3)
    private fun hsv(h0: Float, s: Float, v: Float) {
        val h = fract(h0) * 6; val i = h.toInt(); val f = h - i; val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) { 0 -> Triple(v, t, p); 1 -> Triple(q, v, p); 2 -> Triple(p, v, t); 3 -> Triple(p, q, v); 4 -> Triple(t, p, v); else -> Triple(v, p, q) }
        HS[0] = r.pow(2.2f); HS[1] = g.pow(2.2f); HS[2] = b.pow(2.2f)
    }

    class Ramp(stops: List<Pair<Float, Int>>) {
        private val st = stops.map { it.first to hex(it.second) }
        fun at(t: Float, s: S) { val c = color(t); s.r = c[0]; s.g = c[1]; s.b = c[2] }
        fun color(t: Float): FloatArray {
            var i = 1
            while (i < st.size - 1 && t > st[i].first) i++
            val a = st[i - 1]; val b = st[i]
            var k = (t - a.first) / (b.first - a.first); k = clamp(k, 0f, 1f); k = k * k * (3 - 2 * k)
            return floatArrayOf(a.second[0] + (b.second[0] - a.second[0]) * k, a.second[1] + (b.second[1] - a.second[1]) * k, a.second[2] + (b.second[2] - a.second[2]) * k)
        }
    }

    fun put(s: S, c: FloatArray, t: Float) { s.r += (c[0] - s.r) * t; s.g += (c[1] - s.g) * t; s.b += (c[2] - s.b) * t }

    // ── Decals: flat artwork in the plane of the car's side (y along the car, z up), ready-made pictures ──
    class Decal(val w: Int, val h: Int, val px: IntArray)
    private const val DK = 2048f / 4.6f
    private val DS = FloatArray(4)
    @Volatile var decals: Map<String, Decal> = emptyMap()

    private fun dsample(dc: Decal, y: Float, z: Float) {
        val fx = (y + 2.3f) * DK - .5f; val fy = (1.7f - z) * DK - .5f
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        if (x0 < 0 || y0 < 0 || x0 >= dc.w - 1 || y0 >= dc.h - 1) { DS[3] = 0f; return }
        val u = fx - x0; val v = fy - y0
        val i = y0 * dc.w + x0; val j = i + dc.w
        val p00 = dc.px[i]; val p10 = dc.px[i + 1]; val p01 = dc.px[j]; val p11 = dc.px[j + 1]
        val w00 = (1 - u) * (1 - v); val w10 = u * (1 - v); val w01 = (1 - u) * v; val w11 = u * v
        fun al(p: Int) = (p ushr 24).toFloat()
        val a = al(p00) * w00 + al(p10) * w10 + al(p01) * w01 + al(p11) * w11
        DS[3] = a / 255f
        if (a < 1f) return
        for ((k, sh) in listOf(0 to 16, 1 to 8, 2 to 0)) {
            fun ch(p: Int) = ((p shr sh) and 0xFF).toFloat()
            val c = (ch(p00) * al(p00) * w00 + ch(p10) * al(p10) * w10 + ch(p01) * al(p01) * w01 + ch(p11) * al(p11) * w11) / a
            DS[k] = PaintShop.LUT[Math.round(c).coerceIn(0, 255)]
        }
    }

    // ── Fun paints ──
    private val camRamp = Ramp(listOf(0f to 0x0FD3B0, .3f to 0x1C6BFF, .55f to 0x7A2BFF, .8f to 0xFF2D95, 1f to 0xFFB02E))
    private val sunRamp = Ramp(listOf(0f to 0x2A0845, .28f to 0x8E1558, .54f to 0xE8304A, .78f to 0xFF8A1F, 1f to 0xFFD84D))
    private val candyRamp = Ramp(listOf(0f to 0xFF4FA3, .36f to 0xC77DFF, .68f to 0x4CC9F0, 1f to 0x3DF5C8))
    private val nebRamp = Ramp(listOf(.32f to 0x02041A, .47f to 0x14104A, .60f to 0x4A1A80, .74f to 0xA0247A))
    private val TEAL_GLOW = floatArrayOf(.01f, .16f, .26f)

    val rainbow: (S) -> Unit = { s -> hsv(.86f * (s.y + 2.145f) / 4.29f + .05f * (s.z - .7f), .93f, 1f); s.r = HS[0]; s.g = HS[1]; s.b = HS[2] }
    val chameleon: (S) -> Unit = { s -> camRamp.at(clamp(1.35f * (1 - s.f) + .2f * (.5f + .5f * sin(1.15f * s.y + 2.3f * s.z)), 0f, 1f), s) }
    val galaxy: (S) -> Unit = { s ->
        val n = fbm(s.x * 1.5f + 3, s.y * 1.5f, s.z * 1.5f, 4); val m = fbm(s.x * 2.6f + 40, s.y * 2.6f, s.z * 2.6f + 9, 3)
        nebRamp.at(n, s); put(s, TEAL_GLOW, ss(.54f, .76f, m) * .75f)
        worley(s.x * 13, s.y * 13, s.z * 13)
        if (WO[2] > .3f) { val q = fract(WO[2] * 31.7f); val k = ss(.05f + .17f * q * q, 0f, WO[0]) * (.5f + 3 * q); s.er += k; s.eg += k * .95f; s.eb += k * .82f }
    }
    val sunset: (S) -> Unit = { s -> sunRamp.at(clamp((s.z - .16f) / 1.22f + .10f * (s.y / 2.1f), 0f, 1f), s) }
    val candy: (S) -> Unit = { s -> candyRamp.at(clamp(.5f + .16f * s.y + .5f * (s.z - .7f) + .5f * (fbm(s.x * 1.1f, s.y * 1.1f + 5, s.z * 1.1f, 2) - .5f), 0f, 1f), s) }
    val carbon: (S) -> Unit = { s ->
        worley(s.x * 24, s.y * 24, s.z * 24)
        val h = WO[2]; val g = .007f + .05f * h * h + .035f * fract(h * 71.3f) * s.f.pow(3)
        s.r = g; s.g = g; s.b = g * 1.04f
    }

    // ── Liveries: painted over whatever paint; s.k = contrast colour ──
    private fun side(s: S) = ss(.30f, .55f, abs(s.nx))
    private fun band(v: Float, a: Float, b: Float, aa: Float) = ss(a - aa, a + aa, v) * (1 - ss(b - aa, b + aa, v))
    private inline fun cells(s: S, sz: Float, fn: (Int, Int, Int, Float, Float) -> Unit) {
        var a = s.y; var b = s.z; var c = 0
        if (s.nz > .62f) { b = s.x; c = 1 } else if (abs(s.ny) > .75f) { a = s.x; c = 2 }
        val ia = floor(a / sz).toInt(); val ib = floor(b / sz).toInt()
        fn(ia, ib, c, a / sz - ia - .5f, b / sz - ib - .5f)
    }
    private val FL = floatArrayOf(1.0f, 1.75f, 1.25f, 1.95f, 1.45f)
    private val FO = hex(0x7E0A08)
    private val flRamp = Ramp(listOf(0f to 0xFFEC55, .3f to 0xFFBE0A, .62f to 0xFF7300, 1f to 0xE3200F))

    val rally: (S) -> Unit = { s ->
        val d = (s.y - .42f) - (s.z - .6f) * .62f; val a = s.aa
        put(s, C_CYAN, band(d, 0f, .15f, a)); put(s, C_MAGENTA, band(d, .19f, .34f, a)); put(s, C_GOLD, band(d, .38f, .53f, a))
        decals["num"]?.let { dsample(it, s.y, s.z); if (DS[3] > 0f) put(s, DS, DS[3] * side(s) * (if (s.x > 0) 1f else 0f)) }
    }
    val flames: (S) -> Unit = flames@{ s ->
        val w = side(s); if (w <= 0f || s.y < -1f) return@flames
        val dw = hypot(s.y - YF, s.z - RT)
        var m = 0f; var mi = 0f; var tt = 0f
        for (k in 0 until 5) {
            val L = FL[k]; val t = (s.y + .98f) / L
            if (t < 0f || t > 1f) continue
            val zc = .40f + .125f * k + .045f * sin(5.2f * t * L + k * 1.9f) + .07f * t * (k - 1.2f) * .6f
            val hw = .076f * sqrt(minOf(1f, t / .06f)) * (1 - t).pow(.75f) * (1 + .22f * sin(8 * t * L + k))
            val d = abs(s.z - zc)
            val a = 1 - ss(hw - s.aa, hw + s.aa, d); val b = 1 - ss(hw - .018f - s.aa, hw - .018f + s.aa, d)
            if (a > m) { m = a; tt = t }
            if (b > mi) mi = b
        }
        m *= ss(.45f, .465f, dw); if (m <= 0f) return@flames
        put(s, FO, m * w); put(s, flRamp.color(tt), mi * m * w)
    }
    val bolt: (S) -> Unit = { s -> decals["bolt"]?.let { dsample(it, s.y, s.z); if (DS[3] > 0f) put(s, DS, DS[3] * side(s)) } }
    val check: (S) -> Unit = check@{ s ->
        val g = ss(-.35f, 1.75f, s.y); if (g <= .02f) return@check
        cells(s, .115f) { i, j, _, u, v -> val m = maxOf(abs(u), abs(v)); val h = .5f * g; val a = s.aa / .115f; put(s, if (((i + j) and 1) != 0) C_BLACK else C_WHITE, 1 - ss(h - a, h + a, m)) }
    }
    val pixels: (S) -> Unit = { s ->
        val t = ss(-.5f, 2.0f, s.y).pow(1.25f) * .96f
        cells(s, .082f) { i, j, c, u, v ->
            val h = h3(i, j, c + 7)
            if (h <= t) {
                val q = fract(h * 91.7f); val m = maxOf(abs(u), abs(v)); val a = s.aa / .082f
                val col = when { q < .34f -> s.k; q < .52f -> C_CYAN; q < .68f -> C_MAGENTA; q < .82f -> C_GOLD; else -> floatArrayOf(s.r * .45f, s.g * .45f, s.b * .45f) }
                put(s, col, 1 - ss(.46f - a, .46f + a, m))
            }
        }
    }
    val tricolore: (S) -> Unit = { s ->
        val d = (s.y + .15f) + (s.z - .6f) * .62f; val a = s.aa
        put(s, C_FR_BLUE, band(d, -.27f, -.09f, a)); put(s, C_WHITE, band(d, -.09f, .09f, a)); put(s, C_FR_RED, band(d, .09f, .27f, a))
    }
    val camo: (S) -> Unit = { s ->
        val q = 2.3f
        val n1 = fbm(s.x * q + 7, s.y * q, s.z * q, 3); val n2 = fbm(s.x * q + 31, s.y * q + 9, s.z * q, 3); val n3 = fbm(s.x * q * 1.3f, s.y * q * 1.3f + 50, s.z * q * 1.3f, 2)
        put(s, C_KHAKI, ss(.54f, .555f, n2)); put(s, C_OLIVE, ss(.55f, .565f, n1)); put(s, C_SOOT, ss(.63f, .645f, n3))
    }
    val leopard: (S) -> Unit = { s ->
        worley(s.x * 6.4f, s.y * 6.4f, s.z * 6.4f)
        val br = vn(s.x * 15, s.y * 15, s.z * 15)
        val r = WO[0] / 6.4f * (1 + .7f * (vn(s.x * 11 + 5, s.y * 11, s.z * 11) - .5f)); val a = s.aa; val R = .026f + .012f * WO[2]
        val w2 = WO[2]
        put(s, floatArrayOf(s.r * .6f, s.g * .46f, s.b * .36f), 1 - ss(R - a, R + a, r))
        put(s, C_BROWN, band(r, R, R + .02f + .012f * w2, a) * ss(.36f, .44f, br))
    }
    val zebra: (S) -> Unit = { s ->
        val v = sin(17 * (s.y * .95f + s.z * .33f) + 11 * (fbm(s.x * 1.1f, s.y * 1.1f, s.z * 1.1f + 3, 2) - .5f)) + .9f * (fbm(s.x * 3, s.y * 3 + 8, s.z * 3, 2) - .5f)
        put(s, C_BLACK, ss(.05f, .2f, v))
    }
    val ladybug: (S) -> Unit = { s ->
        cells(s, .33f) { i, j, c, u, v ->
            val h = h3(i, j, c + 3); val du = u - (h - .5f) * .3f; val dv = v - (fract(h * 57.3f) - .5f) * .3f
            val r = hypot(du, dv) * .33f; val R = .07f + .035f * fract(h * 13.7f)
            put(s, C_BLACK, 1 - ss(R - s.aa, R + s.aa, r))
        }
    }
    val cow: (S) -> Unit = { s -> val n = fbm(s.x * 1.55f + 13, s.y * 1.55f, s.z * 1.55f + 2, 3); put(s, C_BLACK, ss(.545f, .56f, n)) }
}
