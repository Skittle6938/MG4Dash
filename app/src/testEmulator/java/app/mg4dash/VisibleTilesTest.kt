package app.mg4dash

import app.mg4dash.map.VisibleTiles
import app.mg4dash.map.VisibleTiles.Tile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tuiles de la carte affichée (#15), sur des cas calculables à la main. */
class VisibleTilesTest {
    // Au zoom 2 la grille fait 4 × 4 ; le point (0, 0) est le coin commun des tuiles x 1-2, y 1-2
    @Test fun pointAtTheCornerOfFourTiles() {
        val t = VisibleTiles.around(0.0, 0.0, 2.0, 256, 100, 100)
        assertEquals(setOf(Tile(2, 1, 1), Tile(2, 2, 1), Tile(2, 1, 2), Tile(2, 2, 2)), t.toSet())
    }

    @Test fun smallWindowInsideOneTile() {
        // Centre de la tuile x 2, y 1 du zoom 2
        val t = VisibleTiles.around(40.97, 45.0, 2.0, 256, 50, 50)
        assertEquals(listOf(Tile(2, 2, 1)), t)
    }

    @Test fun fractionalZoomUsesTheLowerLevelWithLargerTiles() {
        // Zoom 2,5 : on dessine le niveau 2 agrandi, une fenêtre de 300 px tient donc dans moins de tuiles
        val t = VisibleTiles.around(40.97, 45.0, 2.5, 256, 300, 300)
        assertTrue(t.all { it.z == 2 })
        assertEquals(setOf(Tile(2, 2, 1)), t.toSet())
    }

    @Test fun carLowerOnScreenLooksAheadInItsHeading() {
        // Une tuile de décalage, cap nord (0°) : le centre remonte d'une tuile
        val north = VisibleTiles.around(40.97, 45.0, 2.0, 256, 10, 10, 0f, 256)
        assertEquals(listOf(Tile(2, 2, 0)), north)
        // Cap est : rotation -90°, le centre part vers l'est
        val east = VisibleTiles.around(40.97, 45.0, 2.0, 256, 10, 10, -90f, 256)
        assertEquals(listOf(Tile(2, 3, 1)), east)
    }

    @Test fun metersPerPixelAtEquatorAndParis() {
        assertEquals(156_543.0, VisibleTiles.metersPerPixel(0.0, 0.0, 256), 1.0)
        // Zoom 16 à Paris : environ 1,6 m par pixel, donc 2 pixels pour un écart GPS de plus de 3 m
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
