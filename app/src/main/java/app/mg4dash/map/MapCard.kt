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
 * Dark OpenStreetMap map centred on the car (replaces the Android Auto tile,
 * already in the dock). Follows the position as long as the user does not move it;
 * the "recentre" button resumes following.
 */
class MapCard(private val context: Context, holder: ViewGroup) {

    private val map: MapView?
    private val marker: Marker?
    /** Screenshots (PC): fixed map extract, OpenStreetMap tiles provided by the tests. */
    private var preview: android.view.View? = null
    private var follow = true
    private var lastTouch = 0L
    /** One finger moves the map: in full screen; as a widget, it swipes between the pages. */
    var oneFingerPan = false

    /** Touch on the map (brings back the hidden buttons, #15). */
    var onTouch: (() -> Unit)? = null
    /** Long press on the map: choose an app to show in its place (#41). */
    var onLongPress: (() -> Unit)? = null
    /** Wanted zoom level: following uses it, otherwise each position cancelled the zoom in progress. */
    private var zoomLevel = app.mg4dash.system.Prefs(context).mapZoom.coerceIn(14, 18).toDouble()
    /** Map oriented to the car's heading (otherwise north up); second press on "recentre". */
    var headingUp = app.mg4dash.system.Prefs(context).mapHeadingUp
        private set
    private companion object {
        const val RESUME_FOLLOW_MS = 20_000L
        const val TILES_CHECK_MS = 5_000L
        const val MIN_MOVE_PX = 2f
        const val DEFAULT_MAP_W = 600
        const val DEFAULT_MAP_H = 420
        /** Car below the centre in heading mode, as a share of the map's height. */
        const val HEADING_OFFSET = 0.38f
    }

    init {
        // Robolectric (PC screenshots) cannot draw osmdroid: leave the background.
        if (Build.FINGERPRINT == "robolectric") {
            map = null; marker = null
            preview = screenshotPreview(holder)
        } else {
            val conf = Configuration.getInstance()
            conf.userAgentValue = "MG4Launcher/${context.packageName}"
            conf.osmdroidBasePath = File(context.filesDir, "osmdroid")
            conf.osmdroidTileCache = File(context.cacheDir, "osmdroid-tiles")
            conf.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
            // Tiles counted in the 4G data counter (map)
            val m = MapView(context, TaggedTileProvider(context.applicationContext)).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                isTilesScaledToDpi = true
                minZoomLevel = 5.0
                maxZoomLevel = 19.0
                controller.setZoom(zoomLevel)
                // Dark map in dark theme; original tiles in light theme
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
            // Following only stops on a real finger move (scroll events from the
            // animation cut following while driving), and resumes by itself after 20 s.
            var downX = 0f; var downY = 0f
            val longPress = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) { onLongPress?.invoke() }
            })
            m.setOnTouchListener { _, ev ->
                longPress.onTouchEvent(ev)
                // Widget: one finger is for the swipe between its pages, two fingers move and zoom the map
                if (ev.actionMasked == MotionEvent.ACTION_MOVE && ev.pointerCount == 1 && !oneFingerPan) return@setOnTouchListener true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; onTouch?.invoke() }
                    MotionEvent.ACTION_MOVE -> if (Math.hypot((ev.x - downX).toDouble(), (ev.y - downY).toDouble()) > 24) {
                        follow = false; lastTouch = System.currentTimeMillis()
                    }
                    // Pinch zoom: keep the chosen level for following
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zoomLevel = m.zoomLevelDouble
                }
                false
            }
            holder.addView(m, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            map = m; marker = mk
        }
    }

    /** Map theme (#15): auto (the car's day / night, whatever the app's theme), light or dark. */
    private fun darkMap(): Boolean = when (app.mg4dash.system.Prefs(context).mapTheme) {
        1 -> false
        2 -> true
        else -> app.mg4dash.ui.Themes.isNight(context)
    }
    private var dark: Boolean? = null
    private var darkCheckedAt = 0L

    /** In auto, the map follows day / night changes without waiting for the screen to be rebuilt. */
    private fun refreshDark(m: MapView) {
        val now = System.currentTimeMillis()
        if (now - darkCheckedAt < 30_000L) return
        darkCheckedAt = now
        val d = darkMap()
        if (d == dark) return
        dark = d
        m.overlayManager.tilesOverlay.setColorFilter(if (d) darkFilter() else null)
    }

    /** Colour inversion + desaturation: readable "night" look, with no key or paid style. */
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

    private var tilesCheckedAt = 0L
    private var tilesCached = false

    /**
     * All displayed tiles are cached, at the right zoom level (known route): without internet, the map
     * stays useful and does not give way to consumption. A single missing tile would leave an empty
     * or blurred area (#15). Checked every 5 s at most: this test goes through the cache database, on the main thread.
     */
    fun hasCachedTiles(location: Location?): Boolean {
        val m = map
        if (m == null || location == null) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (tilesCheckedAt != 0L && now - tilesCheckedAt < TILES_CHECK_MS) return tilesCached
        tilesCheckedAt = now
        // Map hidden: its last size stays known, and following is still around the car
        val w = m.width.takeIf { it > 0 } ?: DEFAULT_MAP_W
        val h = m.height.takeIf { it > 0 } ?: DEFAULT_MAP_H
        val center = if (follow) null else m.mapCenter
        val tiles = VisibleTiles.around(center?.latitude ?: location.latitude, center?.longitude ?: location.longitude, zoomLevel,
            org.osmdroid.util.TileSystem.getTileSize(), w, h, m.mapOrientation, if (follow && headingUp) (h * HEADING_OFFSET).toInt() else 0)
        tilesCached = try {
            tiles.all { tileCache?.exists(TileSourceFactory.MAPNIK, org.osmdroid.util.MapTileIndex.getTileIndex(it.z, it.x, it.y)) == true }
        } catch (_: Throwable) { false }
        return tilesCached
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
        // Area centred on the point, at the widget's size
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

    private var shownLocation: Location? = null
    private var shownState = ""

    /**
     * Position really changed on screen. GPS answers every second even when stopped: the 0.8 s animation that
     * followed kept the map moving 80 % of the time, at 30 frames per second, and loaded the whole screen.
     */
    private fun needsDrawing(m: MapView, location: Location, state: String): Boolean {
        val last = shownLocation
        val moved = last == null || location.distanceTo(last) / VisibleTiles.metersPerPixel(location.latitude, zoomLevel,
            org.osmdroid.util.TileSystem.getTileSize()) >= MIN_MOVE_PX
        if (!moved && state == shownState) return false
        shownLocation = location
        shownState = state
        return true
    }

    fun update(location: Location) {
        val m = map ?: return
        refreshDark(m)
        val p = GeoPoint(location.latitude, location.longitude)
        if (!follow && System.currentTimeMillis() - lastTouch > RESUME_FOLLOW_MS) follow = true
        // Heading rounded to 2°: when stopped it oscillates with no need for the icon to turn
        val state = "$follow|$headingUp|${m.height}|${if (location.hasBearing()) Math.round(location.bearing / 2f) else -1}"
        if (!needsDrawing(m, location, state)) return
        marker?.position = p
        if (location.hasBearing()) marker?.rotation = -location.bearing
        if (follow && headingUp && location.hasBearing() && location.speed > 1f) m.mapOrientation = -location.bearing
        // Heading up: the car moves towards the bottom of the widget to show more road ahead (issue #6)
        m.setMapCenterOffset(0, if (headingUp && follow) (m.height * HEADING_OFFSET).toInt() else 0)
        if (follow) m.controller.animateTo(p, zoomLevel, 800L)
        m.invalidate()
    }

    /** First press: resume following; next press (already centred): north up ↔ car heading. */
    fun recenter(location: Location?) {
        if (follow) {
            headingUp = !headingUp
            app.mg4dash.system.Prefs(context).mapHeadingUp = headingUp
            if (!headingUp) map?.mapOrientation = 0f
        }
        follow = true
        shownLocation = null
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
