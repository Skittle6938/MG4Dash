package app.mg4dash.map

import android.content.Context
import android.graphics.drawable.Drawable
import app.mg4dash.system.DataUsage
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase

/**
 * Map tiles counted as "map" in the 4G data counter: osmdroid downloads them on its own threads,
 * whose connections get the map's tag here.
 */
class TaggedTileProvider(context: Context) : MapTileProviderBasic(context) {
    override fun createDownloaderProvider(check: INetworkAvailablityCheck?, source: ITileSource?): MapTileDownloader =
        super.createDownloaderProvider(check, source).also { it.setTileDownloader(TaggedDownloader()) }

    private class TaggedDownloader : TileDownloader() {
        override fun downloadTile(index: Long, cache: IFilesystemCache?, source: OnlineTileSourceBase?): Drawable? =
            DataUsage.tag(DataUsage.Use.MAP) { super.downloadTile(index, cache, source) }

        override fun downloadTile(index: Long, redirects: Int, url: String?, cache: IFilesystemCache?, source: OnlineTileSourceBase?): Drawable? =
            DataUsage.tag(DataUsage.Use.MAP) { super.downloadTile(index, redirects, url, cache, source) }
    }
}
