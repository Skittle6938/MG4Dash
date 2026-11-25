package app.mg4dash.ui

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mg4dash.system.Maintenance
import app.mg4dash.cards.LyricsCardPrefs
import app.mg4dash.R
import app.mg4dash.ui.Themes
import app.mg4dash.media.MediaHub
import app.mg4dash.media.LyricsRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Manually enabled, non-focusable lyrics pill; text is fed by LyricsCard's existing ticker. */
object LyricsOverlay {
    @Volatile var active = false; private set
    private var app: Context? = null
    private var themedContext: Context? = null
    private var wm: WindowManager? = null
    private var pill: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var current = ""; private var next = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaJob: Job? = null
    private var tickerJob: Job? = null
    private var fetchJob: Job? = null
    private var pauseJob: Job? = null
    private var pauseExpired = false
    private var track: LyricsRepo.Track? = null
    private var trackKey = ""
    private var latest = MediaHub.NowPlaying()
    private var positionAt = 0L
    private var positionAnchorMs = 0L
    private var activeTextView: TextView? = null
    private var nextTextView: TextView? = null
    private var closeView: TextView? = null
    private var rootView: LinearLayout? = null
    private fun applyScale() {
        val c = themedContext ?: app ?: return
        val s = LyricsCardPrefs(c).overlayScale
        val (sw, sh) = screenSize(c)
        val root = rootView ?: return
        (root.background as? GradientDrawable)?.apply {
            setColor(Themes.color(c, R.color.panel_raised))
            setStroke(dp(1), Themes.color(c, R.color.panel_border))
        }
        activeTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f * s)
        activeTextView?.setTextColor(Themes.color(c, R.color.text_value))
        nextTextView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * s)
        nextTextView?.setTextColor(Themes.color(c, R.color.text_sub))
        root.setPadding(dp((22*s).toInt()), dp((14*s).toInt()), dp((22*s).toInt()), dp((14*s).toInt()))
        val closeSize = dp(maxOf(36f, 42f*s).toInt())
        val gap = dp((12f*s).toInt())
        closeView?.let { it.layoutParams=LinearLayout.LayoutParams(closeSize,closeSize) }
        val width=pillWidth(sw, s)
        val textColumn=root.getChildAt(0)
        val textWidth = (width - root.paddingLeft - root.paddingRight - closeSize - gap).coerceAtLeast(dp(100))
        textColumn.layoutParams=LinearLayout.LayoutParams(textWidth,-2).apply { marginEnd = gap }
        params?.width=width
        clampPosition(sh)
        try { params?.let { wm?.updateViewLayout(root,it) } } catch (_:Exception) {}
    }
    private fun screenSize(context: Context): Pair<Int, Int> {
        val manager = wm
        if (android.os.Build.VERSION.SDK_INT >= 30 && manager != null) {
            try {
                val bounds = manager.currentWindowMetrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
            } catch (_: Exception) { }
        }
        return context.resources.displayMetrics.widthPixels to context.resources.displayMetrics.heightPixels
    }

    private fun pillWidth(screenWidth: Int, scale: Float): Int {
        val max = (screenWidth * .92f).toInt().coerceAtLeast(1)
        val min = dp((600f * scale).toInt()).coerceAtMost(max)
        val preferred = (screenWidth * .55f).toInt().coerceAtMost(max)
        val scaledMax = dp((680f * scale).toInt()).coerceAtMost(max)
        return preferred.coerceIn(min, scaledMax.coerceAtLeast(min))
    }

    private fun clampPosition(screenHeight: Int = app?.let { screenSize(it).second } ?: 0) {
        val v=rootView ?: return; val lp=params ?: return
        if(v.width<=0 || v.height<=0) return
        val sw=app?.let { screenSize(it).first } ?: return
        val nx=lp.x.coerceIn(0,(sw-v.width).coerceAtLeast(0))
        val maxY=(screenHeight-v.height).coerceAtLeast(0)
        val ny=lp.y.coerceIn(0,maxY)
        if(nx!=lp.x || ny!=lp.y) { lp.x=nx; lp.y=ny; try { wm?.updateViewLayout(v,lp) } catch (_:Exception){} }
    }
    fun setScale(value: Float) { app?.let { LyricsCardPrefs(it).overlayScale=value.coerceIn(.70f,1.40f) }; applyScale() }
    private fun dp(v: Int) = ((app?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    private fun requestPermission(context: Context): Boolean {
        return try {
            if (Settings.canDrawOverlays(context)) true else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                false
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.ly_overlay_permission_error), Toast.LENGTH_SHORT).show()
            false
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.ly_overlay_permission_error), Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun toggle(context: Context): Boolean {
        if (active) { stop(); return false }
        if (!requestPermission(context)) return false
        app = context.applicationContext; themedContext = Themes.themed(app!!, big = false)
        wm = app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return start()
    }
    fun show(context: Context): Boolean {
        if (active) return true
        if (!requestPermission(context)) return false
        app = context.applicationContext
        themedContext = Themes.themed(app!!, big = false)
        wm = app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return start()
    }
    fun start(): Boolean {
        val c = themedContext ?: app ?: return false
        if (active) return true
        val p = LyricsCardPrefs(c)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply {
                setColor(Themes.color(c, R.color.panel_raised)); cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Themes.color(c, R.color.panel_border))
            }
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val activeLine = TextView(c).apply { setTextColor(Themes.color(c, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f); typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; text = current }
        val nextLine = TextView(c).apply { setTextColor(Themes.color(c, R.color.text_sub)); alpha = .70f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = next }
        texts.addView(activeLine); texts.addView(nextLine)
        val screenWidth = screenSize(c).first
        val pillWidth = pillWidth(screenWidth, p.overlayScale)
        root.addView(texts, LinearLayout.LayoutParams((pillWidth - dp(74)).coerceAtLeast(dp(100)), -2))
        root.addView(TextView(c).apply { text = "×"; gravity = Gravity.CENTER; setTextColor(Themes.color(c, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); setOnClickListener { stop() } }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val lp = WindowManager.LayoutParams(pillWidth, -2, WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (p.overlayX >= 0) p.overlayX else 80
            y = if (p.overlayY >= 0) p.overlayY else dp(40)
            title = "MG4Dash lyrics"
        }
        root.setOnTouchListener(object : View.OnTouchListener {
            var sx=0f; var sy=0f; var ox=0; var oy=0; var dragged=false
            override fun onTouch(v: View, e: MotionEvent): Boolean = when(e.action) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=lp.x; oy=lp.y; dragged=false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt()
                    if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(6)) dragged=true
                    val (availableWidth, availableHeight) = screenSize(c)
                    val measuredWidth = root.width.takeIf { it > 0 } ?: lp.width
                    val measuredHeight = root.height
                    lp.x=(ox+dx).coerceIn(0,(availableWidth-measuredWidth).coerceAtLeast(0))
                    lp.y=(oy+dy).coerceIn(0,(availableHeight-measuredHeight).coerceAtLeast(0))
                    try { wm?.updateViewLayout(root,lp) } catch (_:Exception){}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) { clampPosition(); p.overlayX=lp.x; p.overlayY=lp.y }
                    true
                }
                else -> true
            }
        })
        rootView=root; activeTextView=activeLine; nextTextView=nextLine
        val closeButton=root.getChildAt(1) as TextView; closeView=closeButton
        root.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_-> clampPosition() }
        return try {
            val windowManager = wm ?: return false
            try {
                windowManager.addView(root, lp)
            } catch (_: SecurityException) {
                // Platform builds can create a system-error window; fall back to the
                // user-granted overlay type on non-platform builds.
                lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                windowManager.addView(root, lp)
            }
            pill=root; params=lp; activeLine.tag="active"; nextLine.tag="next"; active=true
            LyricsRepo.start(app ?: c); applyScale(); observeMedia(); true
        } catch (_:Exception) { false }
    }
    fun update(activeText: String, nextText: String) { current=activeText; next=nextText; val r=pill as? LinearLayout ?: return; (r.getChildAt(0) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text=activeText; (it.getChildAt(1) as? TextView)?.text=nextText } }
    private fun observeMedia() {
        mediaJob?.cancel()
        mediaJob = scope.launch {
            MediaHub.now.collect { now ->
                latest = now
                if (!active) return@collect
                val c = themedContext ?: app ?: return@collect
                val nextKey = if (!now.available || now.analog || now.artist.isBlank() || now.title.isBlank()) "" else LyricsRepo.key(now.artist, now.title)
                if (nextKey.isNotEmpty()) {
                    positionAnchorMs = now.positionMs.coerceAtLeast(0L)
                    positionAt = android.os.SystemClock.elapsedRealtime()
                }
                if (nextKey.isEmpty()) {
                    if (now.androidAutoSpeaking && trackKey.isNotEmpty()) {
                        pauseJob?.cancel(); pauseJob = null
                        tickerJob?.cancel(); tickerJob = null
                        pauseExpired = false
                        rootView?.visibility = View.VISIBLE
                        renderTrackAt(positionAnchorMs)
                        return@collect
                    }
                    pauseJob?.cancel(); pauseJob = null
                    pauseExpired = false
                    tickerJob?.cancel(); tickerJob = null
                    fetchJob?.cancel(); fetchJob = null
                    trackKey = ""; track = null
                    rootView?.visibility = View.VISIBLE
                    update("", "")
                    return@collect
                }
                if (!now.playing) {
                    tickerJob?.cancel(); tickerJob = null
                    if (now.androidAutoSpeaking) {
                        pauseJob?.cancel(); pauseJob = null
                        pauseExpired = false
                        rootView?.visibility = View.VISIBLE
                        renderTrackAt(positionAnchorMs)
                        return@collect
                    }
                    if (!pauseExpired && pauseJob?.isActive != true) {
                        pauseJob = scope.launch {
                            delay(10_000L)
                            if (active && !latest.playing && !latest.androidAutoSpeaking) {
                                pauseExpired = true
                                update("", "")
                                rootView?.visibility = View.GONE
                            }
                        }
                    }
                    return@collect
                }
                if (Maintenance.on(c) || !Settings.canDrawOverlays(c)) {
                    stop()
                    return@collect
                }
                pauseJob?.cancel(); pauseJob = null
                val wasHiddenAfterPause = pauseExpired || rootView?.visibility != View.VISIBLE
                pauseExpired = false
                rootView?.visibility = View.VISIBLE
                if (nextKey.isNotEmpty() && nextKey != trackKey) {
                    tickerJob?.cancel(); tickerJob = null
                    fetchJob?.cancel(); fetchJob = null
                    trackKey = nextKey; track = null; update(c.getString(R.string.ly_overlay_loading), "")
                    fetchJob = scope.launch {
                        val cached = LyricsRepo.cached(now.artist, now.title)
                        val found = cached ?: LyricsRepo.lookup(now.artist, now.title, now.durationMs)
                        if (active && trackKey == nextKey) {
                            track = found
                            update(if (found?.instrumental == true) c.getString(R.string.ly_none) else found?.synced?.firstOrNull()?.second ?: found?.plain?.lineSequence()?.firstOrNull().orEmpty().ifBlank { c.getString(R.string.ly_overlay_not_found) }, found?.synced?.getOrNull(1)?.second ?: found?.plain?.lineSequence()?.drop(1)?.firstOrNull().orEmpty())
                            runTicker()
                        }
                    }
                } else if (track != null && tickerJob?.isActive != true) {
                    runTicker()
                } else if (track == null && fetchJob?.isActive != true) {
                    fetchJob?.cancel(); fetchJob = null
                    val resumeKey = nextKey
                    trackKey = resumeKey
                    update(c.getString(R.string.ly_overlay_loading), "")
                    fetchJob = scope.launch {
                        val cached = LyricsRepo.cached(now.artist, now.title)
                        val found = cached ?: LyricsRepo.lookup(now.artist, now.title, now.durationMs)
                        if (active && trackKey == resumeKey) {
                            track = found
                            update(if (found?.instrumental == true) c.getString(R.string.ly_none) else found?.synced?.firstOrNull()?.second ?: found?.plain?.lineSequence()?.firstOrNull().orEmpty().ifBlank { c.getString(R.string.ly_overlay_not_found) }, found?.synced?.getOrNull(1)?.second ?: found?.plain?.lineSequence()?.drop(1)?.firstOrNull().orEmpty())
                            runTicker()
                        }
                    }
                } else if (wasHiddenAfterPause) {
                    rootView?.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun runTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (active && latest.playing && track != null) {
                // PlaybackState often publishes metadata/state without regular position callbacks.
                // Keep a monotonic local clock anchored to the most recent MediaHub position sample.
                val elapsed = (android.os.SystemClock.elapsedRealtime() - positionAt).coerceAtLeast(0L)
                val duration = latest.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
                val pos = (positionAnchorMs + elapsed).coerceAtMost(duration)
                val currentTrack = track ?: break
                renderTrackAt(pos)
                delay(250)
            }
        }
    }
    private fun renderTrackAt(positionMs: Long) {
        val currentTrack = track ?: return
        val synced = currentTrack.synced
        if (synced != null) {
            val i = synced.indexOfLast { it.first <= positionMs }.coerceAtLeast(0)
            update(synced.getOrNull(i)?.second.orEmpty(), synced.getOrNull(i + 1)?.second.orEmpty())
        } else {
            val plain = currentTrack.plain?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
            val i = if (plain.isEmpty()) -1 else (positionMs / 5_000L).toInt().coerceAtMost(plain.lastIndex)
            update(plain.getOrNull(i).orEmpty(), plain.getOrNull(i + 1).orEmpty())
        }
    }
    fun stop() { active=false; mediaJob?.cancel(); mediaJob=null; tickerJob?.cancel(); tickerJob=null; fetchJob?.cancel(); fetchJob=null; pauseJob?.cancel(); pauseJob=null; pauseExpired=false; track=null; trackKey=""; current=""; next=""; val v=pill; pill=null; try { if(v!=null) wm?.removeViewImmediate(v) } catch (_:Exception){}; params=null; rootView=null; activeTextView=null; nextTextView=null; closeView=null; themedContext=null }
}
