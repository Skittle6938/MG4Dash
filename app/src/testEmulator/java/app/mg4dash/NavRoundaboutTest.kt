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

/** The roundabout drawn on the guidance banner: its exit where Android Auto says (18/11: it never showed). */
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
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val size = 200
        fun draw(side: Int, angle: Int): android.graphics.Bitmap {
            val v = NavArrowView(ctx)
            v.set(NavRepo.Nav(300, 20, "", NavRepo.Turn.ROUNDABOUT, "", side, 1, angle, null, null, ""))
            v.measure(android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(size, android.view.View.MeasureSpec.EXACTLY))
            v.layout(0, 0, size, size)
            val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(b))
            return b
        }
        /** Whether anything is drawn around the point at [deg] (clockwise from up) and [frac] of the size from the centre. */
        fun inked(b: android.graphics.Bitmap, deg: Int, frac: Float): Boolean {
            val a = Math.toRadians(deg - 90.0)
            val x = (size / 2 + size * frac * Math.cos(a)).toInt(); val y = (size / 2 + size * frac * Math.sin(a)).toInt()
            for (dx in -3..3) for (dy in -3..3) if (android.graphics.Color.alpha(b.getPixel((x + dx).coerceIn(0, size - 1), (y + dy).coerceIn(0, size - 1))) > 0) return true
            return false
        }
        for ((side, angle, heading) in listOf(Triple(2, 90, 90), Triple(2, 180, 0), Triple(2, 270, 270), Triple(1, 90, 270), Triple(1, 270, 90))) {
            val b = draw(side, angle)
            assertTrue("side $side angle $angle: exit at $heading°", inked(b, heading, 0.33f))
            // The other sides stay empty there (the entry comes from below, at 180°)
            for (other in listOf(0, 90, 270).filter { it != heading }) assertTrue("side $side angle $angle: nothing at $other°", !inked(b, other, 0.33f))
        }
    }
}
