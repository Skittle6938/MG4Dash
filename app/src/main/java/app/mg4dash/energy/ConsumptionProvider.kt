package app.mg4dash.energy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Carte de consommation pour DiAuto (en test), en lecture seule :
 * query(content://app.mg4dash.consumption/map) → une ligne :
 *  - enabled (INTEGER) : 1 si le réglage « Carte de consommation » est activé, sinon 0
 *  - json (TEXT) : la table au format de l'export (ConsumptionMap.toJson), cases mesurées seulement
 *  - aux_w (REAL ou NULL) : puissance hors roulage (clim + accessoires, phares compris), en W
 *  - updated_at (INTEGER) : heure (ms) de la dernière modification de la table
 * notifyChange sur cette adresse quand la table change ; sinon DiAuto relit toutes les 60 s.
 */
class ConsumptionProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.path?.trim('/') != "map") return null
        val ctx = context ?: return null
        val enabled = ConsumptionMap.enabled(ctx)
        val aux = EnergyMonitor.climateW + EnergyMonitor.accessoriesW
        return MatrixCursor(arrayOf("enabled", "json", "aux_w", "updated_at")).apply {
            addRow(arrayOf<Any?>(if (enabled) 1 else 0, ConsumptionMap.toJson(ConsumptionMap.table(ctx)),
                aux.toDouble(), ConsumptionMap.updatedAt(ctx)))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        val MAP_URI: Uri = Uri.parse("content://app.mg4dash.consumption/map")
    }
}
