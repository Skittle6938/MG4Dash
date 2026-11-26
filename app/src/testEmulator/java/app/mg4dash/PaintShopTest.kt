package app.mg4dash

import app.mg4dash.showroom.CarPaint
import app.mg4dash.showroom.PaintShop
import org.junit.Assert.assertEquals
import org.junit.Test

/** The paint engine's formula (26/11, pages redesign): K + c / 0.8 × (W − K) in linear light, as in the mock-up. */
class PaintShopTest {
    @Test fun lightPaintGivesTheLightRender() {
        // c = 0.8 (the light render's albedo): the light render itself
        for (k in listOf(0, 40, 200)) for (w in listOf(0, 128, 255))
            assertEquals(PaintShop.LUT[w], PaintShop.channel(k, w, .8f), 1e-5f)
    }

    @Test fun blackPaintGivesTheBlackRender() {
        assertEquals(PaintShop.LUT[37], PaintShop.channel(37, 220, 0f), 1e-6f)
    }

    @Test fun backToSrgb() {
        for (i in listOf(0, 1, 64, 128, 200, 255)) assertEquals(i.toDouble(), PaintShop.toByte(PaintShop.LUT[i]).toDouble(), 1.0)
    }

    @Test fun satinIsAMixInLinearLight() {
        val a = intArrayOf(0xFF000000.toInt() or (255 shl 16)); val b = intArrayOf(0xFF000000.toInt())
        val m = PaintShop.mix(a, b, .5f)[0]
        assertEquals(PaintShop.toByte(.5f).toDouble(), ((m shr 16) and 0xFF).toDouble(), 0.0)
    }

    @Test fun officialColours() {
        assertEquals(8, CarPaint.OFFICIAL.size)
        assertEquals("blue", CarPaint.byId(null).id)
    }
}
