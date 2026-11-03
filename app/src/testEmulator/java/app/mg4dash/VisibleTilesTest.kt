package app.mg4dash

import app.mg4dash.map.VisibleTiles
import app.mg4dash.map.VisibleTiles.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tiles of the displayed map (#15), on cases computable by hand. */
class VisibleTilesTest {
    // At zoom 2 the grid is 4 × 4; point (0, 0) is the common corner of tiles x 1-2, y 1-2
    @Test fun pointAtTheCornerOfFourTiles() {
        val t = VisibleTiles.around(0.0, 0.0, 2.0, 256, 100, 100)
        assertEquals(setOf(Tile(2, 1, 1), Tile(2, 2, 1), Tile(2, 1, 2), Tile(2, 2, 2)), t.toSet())
    }

    @Test fun smallWindowInsideOneTile() {
        // Centre of tile x 2, y 1 at zoom 2
        val t = VisibleTiles.around(40.97, 45.0, 2.0, 256, 50, 50)
        assertEquals(listOf(Tile(2, 2, 1)), t)
    }

    @Test fun fractionalZoomUsesTheLowerLevelWithLargerTiles() {
        // Zoom 2.5: level 2 is drawn enlarged, so a 300 px window fits in fewer tiles
        val t = VisibleTiles.around(40.97, 45.0, 2.5, 256, 300, 300)
        assertTrue(t.all { it.z == 2 })
        assertEquals(setOf(Tile(2, 2, 1)), t.toSet())
    }

    @Test fun carLowerOnScreenLooksAheadInItsHeading() {
        // One tile offset, north heading (0°): the centre moves up one tile
        val north = VisibleTiles.around(40.97, 45.0, 2.0, 256, 10, 10, 0f, 256)
        assertEquals(listOf(Tile(2, 2, 0)), north)
        // East heading: rotation -90°, the centre moves east
        val east = VisibleTiles.around(40.97, 45.0, 2.0, 256, 10, 10, -90f, 256)
        assertEquals(listOf(Tile(2, 3, 1)), east)
    }

    @Test fun metersPerPixelAtEquatorAndParis() {
        assertEquals(156_543.0, VisibleTiles.metersPerPixel(0.0, 0.0, 256), 1.0)
        // Zoom 16 in Paris: about 1.6 m per pixel, so 2 pixels for a GPS difference of more than 3 m
        assertEquals(1.57, VisibleTiles.metersPerPixel(48.85, 16.0, 256), 0.01)
    }

    @Test fun rotationWidensTheCoveredArea() {
        val flat = VisibleTiles.around(48.85, 2.35, 16.0, 256, 600, 420)
        val rotated = VisibleTiles.around(48.85, 2.35, 16.0, 256, 600, 420, 45f)
        assertTrue(rotated.size > flat.size)
    }

    @Test fun wrapsAroundLongitudeAndStaysInsideLatitude() {
        val t = VisibleTiles.around(84.9, 179.99, 2.0, 256, 200, 200)
        assertTrue(t.any { it.x == 0 } && t.any { it.x == 3 })
        assertTrue(t.all { it.y in 0..3 })
    }
}
