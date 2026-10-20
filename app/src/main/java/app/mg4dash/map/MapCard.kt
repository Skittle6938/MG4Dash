package app.mg4dash.map

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Location
import android.os.Build
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import app.mg4dash.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/**
 * Carte OpenStreetMap sombre centrée sur la voiture (remplace la tuile Android Auto,
 * déjà présente dans le dock). Suit la position tant que l'utilisateur ne la déplace pas ;
 * le bouton « recentrer » reprend le suivi.
 */
class MapCard(private val context: Context, holder: ViewGroup) {

    private val map: MapView?
    private val marker: Marker?
    /** Captures d'écran (PC) : extrait de carte fixe, tuiles OpenStreetMap fournies par les tests. */
    private var preview: android.view.View? = null
    private var follow = true
    private var lastTouch = 0L
    /** Niveau de zoom voulu : le suivi l'utilise, sinon chaque position annulait le zoom en cours. */
    private var zoomLevel = app.mg4dash.system.Prefs(context).mapZoom.coerceIn(14, 18).toDouble()
    /** Carte orientée selon le cap de la voiture (sinon nord en haut) ; 2e appui sur « recentrer ». */
    var headingUp = app.mg4dash.system.Prefs(context).mapHeadingUp
        private set
    private companion object { const val RESUME_FOLLOW_MS = 20_000L }

    init {
        // Robolectric (captures sur PC) ne sait pas dessiner osmdroid : on laisse le fond.
        if (Build.FINGERPRINT == "robolectric") {
            map = null; marker = null
            preview = screenshotPreview(holder)
        } else {
            val conf = Configuration.getInstance()
            conf.userAgentValue = "MG4Launcher/${context.packageName}"
            conf.osmdroidBasePath = File(context.filesDir, "osmdroid")
            conf.osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
            conf.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            val m = MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 5.0
                maxZoomLevel = 19.0
                controller.setZoom(zoomLevel)
                // Carte sombre en thème sombre ; tuiles d'origine en thème clair
                dark = darkMap(); darkCheckedAt = System.currentTimeMillis()
                if (dark == true) overlayManager.tilesOverlay.setColorFilter(darkFilter())
                overlayManager.tilesOverlay.loadingBackgroundColor = app.mg4dash.ui.Themes.color(context, R.color.bg_top)
                overlayManager.tilesOverlay.loadingLineColor = app.mg4dash.ui.Themes.color(context, R.color.panel_border)
            }
            val mk = Marker(m).apply {
                icon = ContextCompat.getDrawable(context, R.drawable.ic_car_marker)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setFlat(true)
                setInfoWindow(null)
            }
            m.overlays.add(mk)
            // Le suivi ne s'arrête que sur un vrai déplacement au doigt (les événements de défilement
            // de l'animation coupaient le suivi en roulant), et reprend tout seul après 20 s.
            var downX = 0f; var downY = 0f
            m.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
                    MotionEvent.ACTION_MOVE -> if (Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > 24) {
                        follow = false; lastTouch = System.currentTimeMillis()
                    }
                    // Zoom au doigt (pincement) : on garde le niveau choisi pour le suivi
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoomLevel = m.zoomLevelDouble
                }
                false
            }
            holder.addView(m, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            map = m; marker = mk
        }
    }

    /** Thème de la carte (#15) : auto (jour / nuit de la voiture, quel que soit le thème de l'appli), clair ou sombre. */
    private fun darkMap(): Boolean = when (app.mg4dash.system.Prefs(context).mapTheme) {
        1 -> false
        2 -> true
        else -> app.mg4dash.ui.Themes.isNight(context)
    }
    private var dark: Boolean? = null
    private var darkCheckedAt = 0L

    /** En auto, la carte suit le passage jour / nuit sans attendre que l'écran soit reconstruit. */
    private fun refreshDark(m: MapView) {
        val now = System.currentTimeMillis()
        if (now - darkCheckedAt < 30_000L) return
        darkCheckedAt = now
        val d = darkMap()
        if (d == dark) return
        dark = d
        m.overlayManager.tilesOverlay.setColorFilter(if (d) darkFilter() else null)
    }

    /** Inversion des couleurs + désaturation : rendu « nuit » lisible, sans clé ni style payant. */
    private fun darkFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val desat = ColorMatrix().apply { setSaturation(0.25f) }
        val dim = ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.8f, 0f, 0f, 0f,
            0f, 0f, 0.95f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f,
        ))
        invert.postConcat(desat)
        invert.postConcat(dim)
        return ColorMatrixColorFilter(invert)
    }

    val available: Boolean get() = map != null || preview != null

    private val tileCache by lazy { try { org.osmdroid.tileprovider.modules.SqlTileWriter() } catch (_: Throwable) { null } }

    /**
     * Tuile sous la voiture déjà en cache (trajet connu) : sans internet, la carte reste utile et
     * ne cède plus sa place à la consommation (coupure 4G de plus de 15 min constatée le 2026-10-18).
     */
    fun hasCachedTiles(location: Location?): Boolean {
        if (map == null || location == null) return false
        val z = zoomLevel.toInt()
        val n = 1 shl z
        val x = ((location.longitude + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        val lat = Math.toRadians(location.latitude)
        val y = ((1.0 - Math.log(Math.tan(lat) + 1.0 / Math.cos(lat)) / Math.PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
        return try {
            tileCache?.exists(TileSourceFactory.MAPNIK, org.osmdroid.util.MapTileIndex.getTileIndex(z, x, y)) == true
        } catch (_: Throwable) { false }
    }


    private fun screenshotPreview(holder: ViewGroup): android.view.View? = try {
        val cl = javaClass.classLoader!!
        val (cx, cy) = cl.getResourceAsStream("map_preview/center.txt")!!.bufferedReader().readText().trim().split(" ").map { it.toFloat() }
        val mosaic = android.graphics.Bitmap.createBitmap(5 * 256, 5 * 256, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(mosaic)
        for (x in 0..4) for (y in 0..4) {
            cl.getResourceAsStream("map_preview/tile_${x}_$y.png")?.use { android.graphics.BitmapFactory.decodeStream(it) }
                ?.let { canvas.drawBitmap(it, x * 256f, y * 256f, null) }
        }
        // Zone centrée sur le point, à la taille du widget
        val frame = object : android.widget.FrameLayout(context) {
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                val w = r - l; val h = b - t
                if (w <= 0 || h <= 0) return
                val img = getChildAt(0) as android.widget.ImageView
                val scale = maxOf(1.3f, w / 1000f, h / 1000f)
                img.imageMatrix = android.graphics.Matrix().apply { setTranslate(w / 2f / scale - cx, h / 2f / scale - cy); postScale(scale, scale) }
            }
        }
        frame.addView(android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.MATRIX
            setImageBitmap(mosaic)
            if (darkMap()) colorFilter = darkFilter()
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        val d = context.resources.displayMetrics.density
        frame.addView(android.widget.ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_car_marker))
        }, android.widget.FrameLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt(), android.view.Gravity.CENTER))
        holder.addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame
    } catch (_: Throwable) { null }

    fun update(location: Location) {
        val m = map ?: return
        refreshDark(m)
        val p = GeoPoint(location.latitude, location.longitude)
        marker?.position = p
        if (location.hasBearing()) marker?.rotation = -location.bearing
        if (!follow && System.currentTimeMillis() - lastTouch > RESUME_FOLLOW_MS) follow = true
        if (follow && headingUp && location.hasBearing() && location.speed > 1f) m.mapOrientation = -location.bearing
        // Cap en haut : la voiture descend vers le bas du widget pour montrer plus de route devant (issue #6)
        m.setMapCenterOffset(0, if (headingUp && follow) (m.height * 0.3f).toInt() else 0)
        if (follow) m.controller.animateTo(p, zoomLevel, 800L)
        m.invalidate()
    }

    /** 1er appui : reprendre le suivi ; appui suivant (déjà centrée) : nord en haut ↔ cap de la voiture. */
    fun recenter(location: Location?) {
        if (follow) {
            headingUp = !headingUp
            app.mg4dash.system.Prefs(context).mapHeadingUp = headingUp
            if (!headingUp) map?.mapOrientation = 0f
        }
        follow = true
        location?.let { update(it) }
    }

    fun zoom(delta: Int) {
        val m = map ?: return
        zoomLevel = (zoomLevel + delta).coerceIn(m.minZoomLevel, m.maxZoomLevel)
        m.controller.setZoom(zoomLevel)
    }

    fun onResume() = map?.onResume()
    fun onPause() = map?.onPause()
}
