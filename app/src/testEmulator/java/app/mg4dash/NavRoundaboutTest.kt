package app.mg4dash

import app.mg4dash.map.NavRepo
import app.mg4dash.ui.NavArrowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The roundabout drawn on the guidance banner: its exit where Android Auto says (Marc, 18/11: it never showed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class NavRoundaboutTest {

    @Test fun exitHeadingTrafficOnTheRight() {
        // Counter-clockwise: 90° round = the exit on the right, 180 straight on, 270 on the left, 360 back
        assertEquals(90, NavArrowView.roundaboutHeading(2, 1, 90))
        assertEquals(0, NavArrowView.roundaboutHeading(2, 2, 180))
        assertEquals(270, NavArrowView.roundaboutHeading(2, 3, 270))
        assertEquals(180, NavArrowView.roundaboutHeading(2, 4, 360))
        // No side (newer Android Auto events): the car's country, here driving on the right
        assertEquals(0, NavArrowView.roundaboutHeading(3, 2, 180))
        assertEquals(45, NavArrowView.roundaboutHeading(null, null, 135))
    }

    @Test fun exitHeadingTrafficOnTheLeft() {
        // Clockwise: 90° round = the exit on the left
        assertEquals(270, NavArrowView.roundaboutHeading(1, 1, 90))
        assertEquals(0, NavArrowView.roundaboutHeading(1, 2, 180))
        assertEquals(90, NavArrowView.roundaboutHeading(1, 3, 270))
        assertEquals(270, NavArrowView.roundaboutHeading(3, 1, 90, leftTraffic = true))
    }

    @Test fun exitHeadingWithoutAngle() {
        // MG4Link sends -1 for no angle: the exit number as even exits
        assertEquals(90, NavArrowView.roundaboutHeading(2, 1, -1))
        assertEquals(0, NavArrowView.roundaboutHeading(2, 2, null))
        assertEquals(270, NavArrowView.roundaboutHeading(2, 5, -1))
        // Neither: straight on
        assertEquals(0, NavArrowView.roundaboutHeading(2, -1, -1))
    }

    /** Drawn: the exit's arrow is at its heading, off the entry line, for each exit and both directions. */
    @Test fun exitDrawnWhereItLies() {
        val size = 200
        val box = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
        for ((side, angle, heading) in listOf(Triple(2, 90, 90), Triple(2, 180, 0), Triple(2, 270, 270), Triple(1, 90, 270), Triple(1, 270, 90))) {
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val shape = app.mg4dash.ui.NavGlyph.shape(NavRepo.Nav(300, 20, "", NavRepo.Turn.ROUNDABOUT, "", side, 1, angle, null, null, ""), false)
            app.mg4dash.ui.NavGlyph.draw(android.graphics.Canvas(b), box, shape, -1)
            val (tip, centre) = app.mg4dash.ui.NavGlyph.tipAndOrigin(box, shape)
            // The head points from the roundabout's centre towards the exit
            val deg = (Math.toDegrees(Math.atan2((tip.x - centre.x).toDouble(), (centre.y - tip.y).toDouble())) + 360) % 360
            assertTrue("side $side angle $angle: tip at $deg°, wanted $heading°", Math.abs(((deg - heading + 540) % 360) - 180) < 5)
            // And it is drawn there, in full colour, just inside the tip
            val ix = (tip.x + (centre.x - tip.x) * 0.08f).toInt(); val iy = (tip.y + (centre.y - tip.y) * 0.08f).toInt()
            assertTrue("side $side angle $angle: nothing drawn at the tip", android.graphics.Color.alpha(b.getPixel(ix, iy)) > 200)
        }
    }

    /** Every drawing, every direction, every style and size: nothing reaches the outer 2 px (the tip was cut, 18/11). */
    @Test fun nothingCutAtTheEdges() {
        val shapes = ArrayList<app.mg4dash.ui.NavGlyph.Shape>()
        for (h in -180..180 step 15) shapes += app.mg4dash.ui.NavGlyph.Shape.Turn(h.toFloat())
        for (h in 0 until 360 step 15) for (cw in listOf(false, true)) for (n in listOf(null, 1, 12)) shapes += app.mg4dash.ui.NavGlyph.Shape.Round(h.toFloat(), n, cw)
        for (r in listOf(false, true)) { shapes += app.mg4dash.ui.NavGlyph.Shape.UTurn(r); shapes += app.mg4dash.ui.NavGlyph.Shape.Fork(r); shapes += app.mg4dash.ui.NavGlyph.Shape.Merge(r) }
        shapes += app.mg4dash.ui.NavGlyph.Shape.Arrival
        for (size in listOf(52, 190, 220)) for (s in shapes) {
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            app.mg4dash.ui.NavGlyph.draw(android.graphics.Canvas(b), android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat()), s, -1)
            for (i in 0 until size) for (k in 0 until 2) for ((x, y) in listOf(i to k, i to size - 1 - k, k to i, size - 1 - k to i))
                assertTrue("$s at $size px touches the edge at $x,$y", android.graphics.Color.alpha(b.getPixel(x, y)) == 0)
            // And something is drawn
            assertTrue("$s empty", android.graphics.Color.alpha(b.getPixel(size / 2, size / 2)) > 0 || (0 until size).any { x -> android.graphics.Color.alpha(b.getPixel(x, size / 2)) > 0 })
        }
    }

    /** The exit number exactly in the middle of the ring (Marc, 19/11): its ink centred within 1 px. */
    @Test fun exitNumberCentred() {
        for (size in listOf(176, 220, 300)) for (n in listOf(1, 2, 3, 4, 7)) for (h in listOf(0f, 90f, 270f)) {
            val box = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
            fun draw(num: Int?): android.graphics.Bitmap {
                val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                app.mg4dash.ui.NavGlyph.draw(android.graphics.Canvas(b), box, app.mg4dash.ui.NavGlyph.Shape.Round(h, num, false), -1)
                return b
            }
            val with = draw(n); val without = draw(null)
            var x0 = size; var x1 = -1; var y0 = size; var y1 = -1
            for (x in 0 until size) for (y in 0 until size) if (Math.abs(android.graphics.Color.alpha(with.getPixel(x, y)) - android.graphics.Color.alpha(without.getPixel(x, y))) > 100) {
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
            }
            assertTrue("no digit drawn", x1 >= 0)
            val (_, ring) = app.mg4dash.ui.NavGlyph.tipAndOrigin(box, app.mg4dash.ui.NavGlyph.Shape.Round(h, n, false))
            val cx = (x0 + x1 + 1) / 2f; val cy = (y0 + y1 + 1) / 2f
            assertTrue("$n at $size px, exit $h: digit centre $cx,$cy, ring ${ring.x},${ring.y}", Math.abs(cx - ring.x) <= 1.2f && Math.abs(cy - ring.y) <= 1.2f)
        }
    }

    /** A roundabout balanced (Marc, 19/11): its ring in the middle whatever the exit, entry and exit legs alike. */
    @Test fun roundaboutBalanced() {

        val tips = ArrayList<Double>()
        for (size in listOf(176, 220, 300)) { tips.clear(); for (h in listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)) for (cw in listOf(false, true)) {
            val box = android.graphics.RectF(0f, 0f, size.toFloat(), size.toFloat())
            val shape = app.mg4dash.ui.NavGlyph.Shape.Round(h, 2, cw)
            val (tip, c) = app.mg4dash.ui.NavGlyph.tipAndOrigin(box, shape)
            assertTrue("ring centre ${c.x},${c.y} at $size px, exit $h", Math.abs(c.x - size / 2f) <= 1f && Math.abs(c.y - size / 2f) <= 1f)
            tips += Math.hypot((tip.x - c.x).toDouble(), (tip.y - c.y).toDouble())
            val (entry, exit) = app.mg4dash.ui.NavGlyph.legs(shape)
            assertTrue("legs $entry / $exit", Math.abs(entry - exit) <= 0.05f * entry)
        }
        // The same reach for every exit
        assertTrue("reach ${tips.min()}..${tips.max()} at $size", tips.max() - tips.min() <= 1.0) }
    }
}
