package app.mg4dash.showroom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The car in its colours (26/11, pages redesign). The car is a set of 3D renders of MG's configurator model: for each
 * finish (solid, metallic, matte, chrome) one render with a light paint (W, albedo 0.8) and one with a black paint
 * (K). Any colour c, in linear light, is then K + c / 0.8 × (W − K), exact for one bounce of light. Satin is a mix
 * of the matte and metallic renders. The black roof takes the solid black render under a mask; the wheels and the matt
 * black sills (all versions but the XPower) are separate layers. Images come from assets/showroom.
 */
object PaintShop {
    enum class View(val prefix: Char, val key: String) { SIDE('s', "side"), Q34('q', "q34") }

    enum class Finish(val file: String) { SOLID("solid"), METAL("metal"), MATTE("matte"), CHROME("chrome"), SATIN("satin") }

    /** The wheel layers, by their file name. */
    enum class Wheel(val id: String) { STEEL16("steel16"), AERO17("aero17"), LUX18("lux18"), XP18("xp18") }

    /** [body] 0: Standard / Comfort (no spoiler), 1: Luxury / XPower (double spoiler). */
    data class Config(
        val view: View, val body: Int, val paint: CarPaint, val roof: Boolean, val wheel: Wheel?, val trim: Boolean,
        val livery: Livery = Livery.NONE,
    )

    class Meta(val w: Int, val h: Int, val wheels: Map<String, RectF>, val roof: Map<Int, RectF>, val trim: RectF?, val anchors: JSONObject,
        val cam: FloatArray, val lo: FloatArray, val size: FloatArray)

    private const val DIR = "showroom/"
    private const val WREF = .8f
    private const val SATIN_MATTE = .4f   // satin = 40 % matte, 60 % metallic

    /** sRGB byte → linear. */
    internal val LUT = FloatArray(256) { i -> val c = i / 255f; if (c <= .04045f) c / 12.92f else ((c + .055f) / 1.055f).pow(2.4f) }
    private const val ON = 16383
    /** Linear (0..ON) → sRGB byte. */
    internal val OLUT = IntArray(ON + 1) { i -> val c = i / ON.toFloat(); (255 * (if (c <= .0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - .055f)).roundToInt() }

    internal fun toByte(v: Float): Int = OLUT[if (v >= 1f) ON else if (v <= 0f) 0 else (v * ON).toInt()]

    /** One colour channel: [k] and [w] the black and light renders (sRGB bytes), [c] the paint (linear). */
    internal fun channel(k: Int, w: Int, c: Float): Float { val kl = LUT[k]; return kl + c / WREF * (LUT[w] - kl) }

    private var meta: Map<View, Meta>? = null

    fun meta(c: Context): Map<View, Meta> = meta ?: synchronized(this) {
        meta ?: run {
            val j = JSONObject(c.assets.open(DIR + "car.json").bufferedReader().use { it.readText() })
            fun floats(a: org.json.JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            fun rect(a: org.json.JSONArray) = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            View.values().associateWith { v ->
                val m = j.getJSONObject(v.key)
                val wh = m.getJSONObject("wheels"); val rf = m.getJSONObject("roof")
                Meta(m.getInt("w"), m.getInt("h"),
                    wh.keys().asSequence().associateWith { rect(wh.getJSONArray(it)) },
                    rf.keys().asSequence().associate { it.toInt() to rect(rf.getJSONArray(it)) },
                    m.optJSONArray("trim")?.let { rect(it) }, m.getJSONObject("an"),
                    floats(m.getJSONArray("cam")), floats(j.getJSONObject("pos").getJSONArray("lo")), floats(j.getJSONObject("pos").getJSONArray("size")))
            }.also { meta = it }
        }
    }

    /** The pixels of one view, body and scale: decoded once, kept while used. */
    private class Pack(val w: Int, val h: Int) {
        var alpha: ByteArray? = null
        /** Each pixel's 3D point (m) and normal (−127..127), valid where [valid]: only for fun paints and liveries. */
        var pos: FloatArray? = null
        var nrm: ByteArray? = null
        var valid: BooleanArray? = null
        val fin = HashMap<Finish, Pair<IntArray, IntArray>>()
        var roof: ByteArray? = null
        var trim: Bitmap? = null
        val wheels = HashMap<Wheel, Bitmap>()
    }

    private val packs = HashMap<String, Pack>()
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val lock = Mutex()

    private fun key(c: Config, scale: Float) = listOf(c.view, scale, c.body, c.paint.id, c.livery.id, if (c.roof) 1 else 0, c.wheel?.id ?: "", if (c.trim) 1 else 0).joinToString("|")

    /** Already painted: at once, else null. */
    fun cached(c: Config, scale: Float): Bitmap? = cache.get(key(c, scale))

    /** The car in [cfg] at [scale] (1, .5, .25) of the renders' size. */
    suspend fun render(ctx: Context, cfg: Config, scale: Float): Bitmap = withContext(Dispatchers.Default) {
        val k = key(cfg, scale)
        cache.get(k) ?: lock.withLock {
            cache.get(k) ?: fromDisk(ctx, cfg.view, k)?.also { cache.put(k, it) } ?: run {
                val t0 = android.os.SystemClock.uptimeMillis()
                compose(ctx, cfg, scale).also {
                    cache.put(k, it)
                    toDisk(ctx, cfg.view, k, it)
                    app.mg4dash.system.Log.i("Perf", "car painted ${cfg.view} ${it.width}×${it.height} (${cfg.paint.id}, ${cfg.livery.id}) in ${android.os.SystemClock.uptimeMillis() - t0} ms")
                }
            }
        }
    }

    // ── The last painted car of each view kept on disk (26/11): the first page after a start shows it at once ──
    private fun diskDir(ctx: Context) = java.io.File(ctx.filesDir, "car_paint")   // files, not cache: the storage cleaner leaves it
    private fun diskKey(k: String) = "$k|${app.mg4dash.BuildConfig.VERSION_CODE}"

    private fun fromDisk(ctx: Context, view: View, k: String): Bitmap? = try {
        val dir = diskDir(ctx)
        val keyFile = java.io.File(dir, "${view.key}.key"); val png = java.io.File(dir, "${view.key}.png")
        if (keyFile.isFile && png.isFile && keyFile.readText() == diskKey(k)) {
            val t0 = android.os.SystemClock.uptimeMillis()
            BitmapFactory.decodeFile(png.path)?.also { app.mg4dash.system.Log.i("Perf", "car read from disk ${view} in ${android.os.SystemClock.uptimeMillis() - t0} ms") }
        } else null
    } catch (_: Exception) { null }

    private fun toDisk(ctx: Context, view: View, k: String, b: Bitmap) {
        Thread({
            try {
                val dir = diskDir(ctx).apply { mkdirs() }
                val tmp = java.io.File(dir, "${view.key}.tmp")
                tmp.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }   // lossless
                tmp.renameTo(java.io.File(dir, "${view.key}.png"))
                java.io.File(dir, "${view.key}.key").writeText(diskKey(k))
            } catch (e: Exception) { app.mg4dash.system.Log.w("Showroom", "car to disk: ${e.message}") }
        }, "car-paint-save").start()
    }

    /** The scale whose image is at least as wide as [px] on screen. */
    fun scaleFor(ctx: Context, view: View, px: Int): Float {
        // The exact size it is shown at (in hundredths, so the cache keys stay few): no scaling at each draw (26/11)
        val w = meta(ctx).getValue(view).w
        return (kotlin.math.ceil(px * 100.0 / w) / 100.0).toFloat().coerceIn(.1f, 1f)
    }

    private fun decode(ctx: Context, name: String, w: Int, h: Int): Bitmap {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inScaled = false }
        val full = ctx.assets.open(DIR + name).use { BitmapFactory.decodeStream(it, null, o) } ?: error("image $name")
        if (full.width == w && full.height == h) return full
        return Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun pack(ctx: Context, view: View, body: Int, scale: Float): Pack {
        val m = meta(ctx).getValue(view)
        val w = (m.w * scale).roundToInt(); val h = (m.h * scale).roundToInt()
        return packs.getOrPut("$view$body@$scale") {
            // One scale at a time: the others are dropped (memory)
            // (the thumbnails' small scale stays aside)
            if (scale > .125f) packs.keys.filter { it.startsWith("$view") && !it.endsWith("@$scale") && !it.endsWith("@0.125") }.forEach { packs.remove(it) }
            Pack(w, h)
        }
    }

    private fun pre(view: View, body: Int) = "${view.prefix}$body"

    private fun needFinish(ctx: Context, p: Pack, view: View, body: Int, f: Finish): Pair<IntArray, IntArray> {
        p.fin[f]?.let { return it }
        val r = if (f == Finish.SATIN) {
            val a = needFinish(ctx, p, view, body, Finish.MATTE); val b = needFinish(ctx, p, view, body, Finish.METAL)
            mix(a.first, b.first, SATIN_MATTE) to mix(a.second, b.second, SATIN_MATTE)
        } else {
            val wb = decode(ctx, "${pre(view, body)}_${f.file}W.webp", p.w, p.h); val kb = decode(ctx, "${pre(view, body)}_${f.file}K.webp", p.w, p.h)
            (pixels(wb) to pixels(kb)).also { wb.recycle(); kb.recycle() }
        }
        p.fin[f] = r
        return r
    }

    /** [t] of [a] and 1 − [t] of [b], mixed in linear light. */
    internal fun mix(a: IntArray, b: IntArray, t: Float): IntArray = IntArray(a.size) { i ->
        val x = a[i]; val y = b[i]
        fun ch(s: Int) = toByte(LUT[(x shr s) and 0xFF] * t + LUT[(y shr s) and 0xFF] * (1 - t))
        (x and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun needAlpha(ctx: Context, p: Pack, view: View, body: Int): ByteArray = p.alpha ?: run {
        val b = decode(ctx, "${pre(view, body)}_a.webp", p.w, p.h)
        val px = pixels(b); b.recycle()
        ByteArray(px.size) { ((px[it] shr 16) and 0xFF).toByte() }.also { p.alpha = it }
    }

    /** The full-size picture, then one pixel out of N: never smoothed, a coordinate's two bytes are in different channels. */
    private fun nearest(ctx: Context, name: String, w: Int, h: Int): IntArray {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inScaled = false }
        val full = ctx.assets.open(DIR + name).use { BitmapFactory.decodeStream(it, null, o) } ?: error("image $name")
        val fw = full.width; val fh = full.height
        val src = pixels(full); full.recycle()
        if (fw == w && fh == h) return src
        return IntArray(w * h) { i -> val x = (i % w) * fw / w; val y = (i / w) * fh / h; src[y * fw + x] }
    }

    private fun needPos(ctx: Context, p: Pack, view: View, body: Int) {
        if (p.pos != null) return
        val m = meta(ctx).getValue(view)
        val d1 = nearest(ctx, "${pre(view, body)}_p1.webp", p.w, p.h)
        val d2 = nearest(ctx, "${pre(view, body)}_p2.webp", p.w, p.h)
        val dn = nearest(ctx, "${pre(view, body)}_n.webp", p.w, p.h)
        val n = p.w * p.h
        val P = FloatArray(n * 3); val N = ByteArray(n * 3); val V = BooleanArray(n)
        val lo = m.lo; val sz = m.size
        for (i in 0 until n) {
            val q = dn[i]
            if ((q ushr 24) < 128) continue
            V[i] = true
            val a = d1[i]; val b = d2[i]
            fun r(c: Int) = (c shr 16) and 0xFF
            fun g(c: Int) = (c shr 8) and 0xFF
            fun bl(c: Int) = c and 0xFF
            P[i * 3] = lo[0] + sz[0] * (((bl(a) shl 8) or bl(b)) / 65535f)
            P[i * 3 + 1] = lo[1] + sz[1] * (((r(a) shl 8) or g(a)) / 65535f)
            P[i * 3 + 2] = lo[2] + sz[2] * (((r(b) shl 8) or g(b)) / 65535f)
            N[i * 3] = (r(q) - 128).toByte(); N[i * 3 + 1] = (g(q) - 128).toByte(); N[i * 3 + 2] = (bl(q) - 128).toByte()
        }
        p.pos = P; p.nrm = N; p.valid = V
    }

    private fun needDecals(ctx: Context, names: List<String>) {
        val missing = names.filter { it !in Shade.decals }
        if (missing.isEmpty()) return
        val add = missing.associateWith { n ->
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inPremultiplied = false; inScaled = false }
            val b = ctx.assets.open(DIR + "decal_$n.png").use { BitmapFactory.decodeStream(it, null, o) } ?: error("decal $n")
            Shade.Decal(b.width, b.height, pixels(b)).also { b.recycle() }
        }
        Shade.decals = Shade.decals + add
    }

    private fun needRoof(ctx: Context, p: Pack, view: View, body: Int, scale: Float): ByteArray = p.roof ?: run {
        val box = meta(ctx).getValue(view).roof[body]
        val r = ByteArray(p.w * p.h)
        if (box != null) {
            val l = (box.left * scale).roundToInt(); val t = (box.top * scale).roundToInt()
            val bw = (box.width() * scale).roundToInt().coerceAtLeast(1); val bh = (box.height() * scale).roundToInt().coerceAtLeast(1)
            val b = decode(ctx, "${pre(view, body)}_roof.webp", bw, bh)
            val px = pixels(b); b.recycle()
            for (y in 0 until bh) for (x in 0 until bw) {
                val X = l + x; val Y = t + y
                if (X in 0 until p.w && Y in 0 until p.h) r[Y * p.w + X] = ((px[y * bw + x] shr 16) and 0xFF).toByte()
            }
        }
        r.also { p.roof = it }
    }

    private fun layer(ctx: Context, name: String): Bitmap {
        val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        return ctx.assets.open(DIR + name).use { BitmapFactory.decodeStream(it, null, o) } ?: error("image $name")
    }

    /** Fun paints and liveries: each pixel's colour from its 3D point and normal ([Shade]). */
    private fun shaded(p: Pack, m: Meta, cfg: Config, scale: Float, a: ByteArray, W: IntArray, K: IntArray, R: ByteArray?, KS: IntArray?, out: IntArray) {
        val P = p.pos!!; val N = p.nrm!!; val V = p.valid!!
        val c0 = cfg.paint.linear
        val lum = .2126f * c0[0] + .7152f * c0[1] + .0722f * c0[2]
        val s = Shade.S().apply { aa = (if (cfg.view == View.SIDE) .0026f else .0034f) / scale; k = if (lum > .30f) Shade.C_BLACK else Shade.C_WHITE }
        val cam = m.cam
        val pfx = cfg.paint.fx; val lfx = cfg.livery.fx
        for (i in out.indices) {
            val al = a[i].toInt() and 0xFF
            if (al == 0) continue
            var r: Float; var g: Float; var b: Float; var er = 0f; var eg = 0f; var eb = 0f
            if (V[i]) {
                val x = P[i * 3]; val y = P[i * 3 + 1]; val z = P[i * 3 + 2]
                val nx = N[i * 3] / 127f; val ny = N[i * 3 + 1] / 127f; val nz = N[i * 3 + 2] / 127f
                val vx = cam[0] - x; val vy = cam[1] - y; val vz = cam[2] - z
                val f = (nx * vx + ny * vy + nz * vz) / kotlin.math.sqrt(vx * vx + vy * vy + vz * vz)
                s.x = x; s.y = y; s.z = z; s.nx = nx; s.ny = ny; s.nz = nz; s.f = f.coerceIn(0f, 1f)
                s.r = c0[0]; s.g = c0[1]; s.b = c0[2]; s.er = 0f; s.eg = 0f; s.eb = 0f
                pfx?.invoke(s); lfx?.invoke(s)
                r = s.r / WREF; g = s.g / WREF; b = s.b / WREF; er = s.er; eg = s.eg; eb = s.eb
            } else { r = c0[0] / WREF; g = c0[1] / WREF; b = c0[2] / WREF }
            val w = W[i]; val k = K[i]
            val kr = LUT[(k shr 16) and 0xFF]; val kg = LUT[(k shr 8) and 0xFF]; val kb = LUT[k and 0xFF]
            r = kr + r * (LUT[(w shr 16) and 0xFF] - kr) + er
            g = kg + g * (LUT[(w shr 8) and 0xFF] - kg) + eg
            b = kb + b * (LUT[w and 0xFF] - kb) + eb
            if (R != null && KS != null) {
                val t = (R[i].toInt() and 0xFF) / 255f
                if (t > 0f) { val q = KS[i]; r += (LUT[(q shr 16) and 0xFF] - r) * t; g += (LUT[(q shr 8) and 0xFF] - g) * t; b += (LUT[q and 0xFF] - b) * t }
            }
            out[i] = (al shl 24) or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
        }
    }

    private fun compose(ctx: Context, cfg: Config, scale: Float): Bitmap {
        val p = pack(ctx, cfg.view, cfg.body, scale)
        val m = meta(ctx).getValue(cfg.view)
        val a = needAlpha(ctx, p, cfg.view, cfg.body)
        val (W, K) = needFinish(ctx, p, cfg.view, cfg.body, cfg.paint.finish)
        val roofOn = cfg.roof
        val R = if (roofOn) needRoof(ctx, p, cfg.view, cfg.body, scale) else null
        val KS = if (roofOn) needFinish(ctx, p, cfg.view, cfg.body, Finish.SOLID).second else null
        val out = IntArray(p.w * p.h)
        val cr = cfg.paint.linear[0]; val cg = cfg.paint.linear[1]; val cb = cfg.paint.linear[2]
        val pfx = cfg.paint.fx; val lfx = cfg.livery.fx
        if (pfx != null || lfx != null) {
            needPos(ctx, p, cfg.view, cfg.body); needDecals(ctx, cfg.livery.decals)
            shaded(p, m, cfg, scale, a, W, K, R, KS, out)
        } else for (i in out.indices) {
            val al = a[i].toInt() and 0xFF
            if (al == 0) continue
            val w = W[i]; val k = K[i]
            var r = channel((k shr 16) and 0xFF, (w shr 16) and 0xFF, cr)
            var g = channel((k shr 8) and 0xFF, (w shr 8) and 0xFF, cg)
            var b = channel(k and 0xFF, w and 0xFF, cb)
            if (R != null && KS != null) {
                val t = (R[i].toInt() and 0xFF) / 255f
                if (t > 0f) {
                    val s = KS[i]
                    r += (LUT[(s shr 16) and 0xFF] - r) * t; g += (LUT[(s shr 8) and 0xFF] - g) * t; b += (LUT[s and 0xFF] - b) * t
                }
            }
            out[i] = (al shl 24) or (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)
        }
        val bmp = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
        // Straight alpha in, the bitmap premultiplies
        bmp.setPixels(out, 0, p.w, 0, 0, p.w, p.h)
        val c = Canvas(bmp)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun put(img: Bitmap, box: RectF) = c.drawBitmap(img, Rect(0, 0, img.width, img.height),
            RectF(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale), paint)
        if (cfg.trim) m.trim?.let { box -> put(p.trim ?: layer(ctx, "${cfg.view.prefix}_trim.webp").also { p.trim = it }, box) }
        cfg.wheel?.let { wh -> m.wheels[wh.id]?.let { box -> put(p.wheels.getOrPut(wh) { layer(ctx, "${cfg.view.prefix}_wh_${wh.id}.webp") }, box) } }
        return bmp
    }
}
