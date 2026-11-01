package app.mg4dash.map

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Tuiles couvertes par la carte affichée, au niveau de zoom que dessine osmdroid (partie entière du zoom) :
 * une tuile absente de ce niveau est remplacée à l'écran par une zone vide ou floue (#15).
 */
object VisibleTiles {
    data class Tile(val z: Int, val x: Int, val y: Int)

    /**
     * @param lat,lon point de la carte retenu (la voiture en mode suivi)
     * @param tileSizePx taille d'une tuile à l'écran au zoom entier 0 (osmdroid : TileSystem.getTileSize())
     * @param rotationDeg orientation de la carte (osmdroid : -cap, 0 = nord en haut)
     * @param pointBelowCenterPx distance à l'écran entre ce point et le centre de la carte (voiture plus bas en mode cap)
     */
    fun around(lat: Double, lon: Double, zoom: Double, tileSizePx: Int, widthPx: Int, heightPx: Int,
               rotationDeg: Float = 0f, pointBelowCenterPx: Int = 0): List<Tile> {
        val z = floor(zoom).toInt()
        val n = 1 shl z
        // Taille d'un pixel de l'écran, en tuiles du niveau z
        val perPx = 1.0 / (tileSizePx * 2.0.pow(zoom - z))
        val a = Math.toRadians(rotationDeg.toDouble())
        val latRad = Math.toRadians(lat.coerceIn(-85.05, 85.05))
        // Le haut de l'écran pointe vers le cap : le centre est devant le point, vu de la carte
        val cx = (lon + 180.0) / 360.0 * n + sin(-a) * pointBelowCenterPx * perPx
        val cy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n - cos(a) * pointBelowCenterPx * perPx
        // Rectangle de l'écran tourné : son encombrement sur la grille des tuiles
        val halfX = (abs(cos(a)) * widthPx + abs(sin(a)) * heightPx) / 2.0 * perPx
        val halfY = (abs(sin(a)) * widthPx + abs(cos(a)) * heightPx) / 2.0 * perPx
        val out = ArrayList<Tile>()
        for (y in floor(cy - halfY).toInt().coerceAtLeast(0)..floor(cy + halfY).toInt().coerceAtMost(n - 1)) {
            for (x in floor(cx - halfX).toInt()..floor(cx + halfX).toInt()) out.add(Tile(z, Math.floorMod(x, n), y))
        }
        return out.distinct()
    }
}
