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
    )

    class Meta(val w: Int, val h: Int, val wheels: Map<String, RectF>, val roof: Map<Int, RectF>, val trim: RectF?, val anchors: JSONObject)

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
            fun rect(a: org.json.JSONArray) = RectF(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat(), a.getDouble(3).toFloat())
            View.values().associateWith { v ->
                val m = j.getJSONObject(v.key)
                val wh = m.getJSONObject("wheels"); val rf = m.getJSONObject("roof")
                Meta(m.getInt("w"), m.getInt("h"),
                    wh.keys().asSequence().associateWith { rect(wh.getJSONArray(it)) },
                    rf.keys().asSequence().associate { it.toInt() to rect(rf.getJSONArray(it)) },
                    m.optJSONArray("trim")?.let { rect(it) }, m.getJSONObject("an"))
            }.also { meta = it }
        }
    }

    /** The pixels of one view, body and scale: decoded once, kept while used. */
    private class Pack(val w: Int, val h: Int) {
        var alpha: ByteArray? = null
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

    private fun key(c: Config, scale: Float) = listOf(c.view, scale, c.body, c.paint.id, if (c.roof) 1 else 0, c.wheel?.id ?: "", if (c.trim) 1 else 0).joinToString("|")

    /** Already painted: at once, else null. */
    fun cached(c: Config, scale: Float): Bitmap? = cache.get(key(c, scale))

    /** The car in [cfg] at [scale] (1, .5, .25) of the renders' size. */
    suspend fun render(ctx: Context, cfg: Config, scale: Float): Bitmap = withContext(Dispatchers.Default) {
        val k = key(cfg, scale)
        cache.get(k) ?: lock.withLock {
            cache.get(k) ?: compose(ctx, cfg, scale).also { cache.put(k, it) }
        }
    }

    /** The scale whose image is at least as wide as [px] on screen. */
    fun scaleFor(ctx: Context, view: View, px: Int): Float {
        val w = meta(ctx).getValue(view).w
        return listOf(.25f, .5f).firstOrNull { w * it >= px * .96f } ?: 1f
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
            packs.keys.filter { it.startsWith("$view") && !it.endsWith("@$scale") }.forEach { packs.remove(it) }
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
        val b = decode(ctx, "${pre(view, body)}_a.png", p.w, p.h)
        val px = pixels(b); b.recycle()
        ByteArray(px.size) { ((px[it] shr 16) and 0xFF).toByte() }.also { p.alpha = it }
    }

    private fun needRoof(ctx: Context, p: Pack, view: View, body: Int, scale: Float): ByteArray = p.roof ?: run {
        val box = meta(ctx).getValue(view).roof[body]
        val r = ByteArray(p.w * p.h)
        if (box != null) {
            val l = (box.left * scale).roundToInt(); val t = (box.top * scale).roundToInt()
            val bw = (box.width() * scale).roundToInt().coerceAtLeast(1); val bh = (box.height() * scale).roundToInt().coerceAtLeast(1)
            val b = decode(ctx, "${pre(view, body)}_roof.png", bw, bh)
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
        for (i in out.indices) {
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
