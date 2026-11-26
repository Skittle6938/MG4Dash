package app.mg4dash

import android.os.Bundle
import app.mg4dash.map.NavRepo
import app.mg4dash.ui.NavArrowView
import app.mg4dash.ui.NavGlyph
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * MG4Link's roundabout_direction (#74, 26/11): 1 clockwise (left-hand traffic), 2 anticlockwise, 0 or absent as
 * before. turn_angle counts from the entry in the way round: anticlockwise 90 right, 180 straight on, 270 left;
 * clockwise in mirror.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NavRoundaboutDirectionTest {
    private fun extras(angle: Int, direction: Int?, side: Int = 3) = Bundle().apply {
        putInt("next_event_type", NavRepo.Turn.ROUNDABOUT); putInt("turn_side", side); putInt("turn_number", 2); putInt("turn_angle", angle)
        putString("road", "R"); if (direction != null) putInt("roundabout_direction", direction)
    }

    @Test fun absentIsZeroAndDrawsAsBefore() {
        val n = NavRepo.fromExtras(extras(90, null))
        assertEquals(0, n.roundaboutDirection)
        // As before: side 3 (none) and a right-hand traffic country → anticlockwise, 90 = right
        assertEquals(NavArrowView.roundaboutHeading(3, 2, 90, false), NavArrowView.roundaboutHeading(3, 2, 90, false, n.roundaboutDirection))
        assertEquals(90, NavArrowView.roundaboutHeading(3, 2, 90, false, 0))
    }

    @Test fun anticlockwise() {
        assertEquals(2, NavRepo.fromExtras(extras(90, 2)).roundaboutDirection)
        assertEquals(90, NavArrowView.roundaboutHeading(3, 2, 90, true, 2))    // even in a left-hand traffic country
        assertEquals(0, NavArrowView.roundaboutHeading(3, 2, 180, true, 2))
        assertEquals(270, NavArrowView.roundaboutHeading(3, 2, 270, true, 2))
    }

    @Test fun clockwiseIsTheMirror() {
        assertEquals(1, NavRepo.fromExtras(extras(90, 1)).roundaboutDirection)
        assertEquals(270, NavArrowView.roundaboutHeading(3, 2, 90, false, 1))   // even in a right-hand traffic country
        assertEquals(0, NavArrowView.roundaboutHeading(3, 2, 180, false, 1))
        assertEquals(90, NavArrowView.roundaboutHeading(3, 2, 270, false, 1))
        val ring = NavGlyph.shape(NavRepo.fromExtras(extras(90, 1)), leftTraffic = false) as NavGlyph.Shape.Round
        assertEquals(true, ring.javaClass.declaredFields.first { it.type == Boolean::class.javaPrimitiveType }.apply { isAccessible = true }.getBoolean(ring))
    }

    @Test fun unknownValuesAreZero() {
        assertEquals(0, NavRepo.fromExtras(extras(90, 7)).roundaboutDirection)
    }
}
