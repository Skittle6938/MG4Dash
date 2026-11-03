package app.mg4dash.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tiles covered by the displayed map, at the zoom level osmdroid draws (integer part of the zoom):
 * a tile missing at this level is replaced on screen by an empty or blurred area (#15).
 */
object VisibleTiles {
    data class Tile(val z: Int, val x: Int, val y: Int)

    /** Metres covered by a map pixel at this latitude and zoom. */
    fun metersPerPixel(lat: Double, zoom: Double, tileSizePx: Int): Double =
        40_075_016.686 * cos(Math.toRadians(lat)) / (tileSizePx * 2.0.pow(zoom))

    /**
     * @param lat,lon map point used (the car in following mode)
     * @param tileSizePx size of a tile on screen at integer zoom 0 (osmdroid: TileSystem.getTileSize())
     * @param rotationDeg map orientation (osmdroid: -heading, 0 = north up)
     * @param pointBelowCenterPx on-screen distance between this point and the map's centre (car lower in heading mode)
     */
    fun around(lat: Double, lon: Double, zoom: Double, tileSizePx: Int, widthPx: Int, heightPx: Int,
               rotationDeg: Float = 0f, pointBelowCenterPx: Int = 0): List<Tile> {
        val z = floor(zoom).toInt()
        val n = 1 shl z
        // Size of a screen pixel, in tiles of level z
        val perPx = 1.0 / (tileSizePx * 2.0.pow(zoom - z))
        val a = Math.toRadians(rotationDeg.toDouble())
        val latRad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        // The top of the screen points to the heading: the centre is ahead of the point, seen from the map
        val cx = (lon + 180.0) / 360.0 * n + sin(-a) * pointBelowCenterPx * perPx
        val cy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n - cos(a) * pointBelowCenterPx * perPx
        // Rotated screen rectangle: its footprint on the tile grid
        val halfX = (abs(cos(a)) * widthPx + abs(sin(a)) * heightPx) / 2.0 * perPx
        val halfY = (abs(sin(a)) * widthPx + abs(cos(a)) * heightPx) / 2.0 * perPx
        val out = ArrayList<Tile>()
        for (y in floor(cy - halfY).toInt().coerceAtLeast(0)..floor(cy + halfY).toInt().coerceAtMost(n - 1)) {
            for (x in floor(cx - halfX).toInt()..floor(cx + halfX).toInt()) out.add(Tile(z, Math.floorMod(x, n), y))
        }
        return out.distinct()
    }
}
