package app.mg4dash.showroom

import android.graphics.Bitmap

/**
 * The top view photo of the MG4 (a red car, the Sound page's) repainted in the driver's colour (27/11): the red paint
 * is found in the photo (red well above green and blue), its shading kept from the red channel and its white sheen
 * kept on top; glass, mirrors and lights stay as they are. With the black roof, the roof between the windscreen and
 * the rear window is painted black too. No livery: a flat photo has no shape to lay one on.
 */
object TopPaint {
    /** The photo's paint at its plain parts, in the red channel: shading 1. */
    private const val REF_RED = 185f

    /** The roof panel in the photo (fractions of its width and height). */
    internal val ROOF = floatArrayOf(.28f, .38f, .73f, .735f)

    private const val BLACK = 0x101114

    fun paint(src: Bitmap, hex: Int, blackRoof: Boolean): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val inRoof = blackRoof && x >= ROOF[0] * w && x <= ROOF[2] * w && y >= ROOF[1] * h && y <= ROOF[3] * h
            px[i] = pixel(px[i], if (inRoof) BLACK else hex)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** One pixel of the photo with its red paint changed to [hex]; anything else as it is. Internal for the tests. */
    internal fun pixel(p: Int, hex: Int): Int {
        val a = p ushr 24; val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        val m = ((r - maxOf(g, b) - 25) / 55f).coerceIn(0f, 1f)
        if (m <= 0f || a == 0) return p
        val shade = r / REF_RED
        val sheen = minOf(g, b) * .7f
        fun ch(c: Int, o: Int) = Math.round(o + ((c * shade + sheen).coerceIn(0f, 255f) - o) * m)
        val tr = (hex shr 16) and 0xFF; val tg = (hex shr 8) and 0xFF; val tb = hex and 0xFF
        return (a shl 24) or (ch(tr, r) shl 16) or (ch(tg, g) shl 8) or ch(tb, b)
    }
}
