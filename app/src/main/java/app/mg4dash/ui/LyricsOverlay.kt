package app.mg4dash.ui

import android.content.Context
import android.content.Intent
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
import app.mg4dash.MainActivity
import app.mg4dash.system.Prefs
import app.mg4dash.media.MediaHub
import app.mg4dash.media.LyricsRepo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Manually enabled, non-focusable lyrics pill; text is fed by LyricsCard's existing ticker. */
object LyricsOverlay {
    @Volatile var active = false; private set
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var pill: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var current = ""; private var next = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaJob: Job? = null
    private var tickerJob: Job? = null
    private var track: LyricsRepo.Track? = null
    private var trackKey = ""
    private var latest = MediaHub.NowPlaying()
    private var positionAt = 0L
    private fun dp(v: Int) = ((app?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    fun toggle(context: Context): Boolean {
        if (active) { stop(); return false }
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = android.net.Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent); return false
        }
        app = context.applicationContext; wm = app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return start()
    }
    fun start(): Boolean {
        val c = app ?: return false
        if (active) return true
        val p = Prefs(c)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = GradientDrawable().apply { setColor(0xE60D121D.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), 0x26FFFFFF) }
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val activeLine = TextView(c).apply { setTextColor(-1); setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; text = current }
        val nextLine = TextView(c).apply { setTextColor(-1); alpha = .65f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = next }
        texts.addView(activeLine); texts.addView(nextLine)
        val screenWidth = c.resources.displayMetrics.widthPixels
        val pillWidth = (screenWidth * .45f).toInt().coerceIn(dp(480), dp(540))
        root.addView(texts, LinearLayout.LayoutParams(pillWidth - dp(62), -2))
        root.addView(TextView(c).apply { text = "×"; gravity = Gravity.CENTER; setTextColor(-1); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f); setOnClickListener { stop() } }, LinearLayout.LayoutParams(dp(32), dp(42)))
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (p.lyricsOverlayX >= 0) p.lyricsOverlayX else 80
            y = if (p.lyricsOverlayY >= 0) p.lyricsOverlayY else dp(40)
            title = "MG4Dash lyrics"
        }
        root.setOnTouchListener(object : View.OnTouchListener {
            var sx=0f; var sy=0f; var ox=0; var oy=0; var dragged=false
            override fun onTouch(v: View, e: MotionEvent): Boolean = when(e.action) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=lp.x; oy=lp.y; dragged=false; true }
                MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(6)) dragged=true; lp.x=ox+dx; lp.y=oy+dy; try { wm?.updateViewLayout(root,lp) } catch (_:Exception){}; true }
                MotionEvent.ACTION_UP -> { if (dragged) { p.lyricsOverlayX=lp.x; p.lyricsOverlayY=lp.y } else c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); true }
                else -> true
            }
        })
        return try { wm?.addView(root,lp); pill=root; params=lp; activeLine.tag="active"; nextLine.tag="next"; active=true; LyricsRepo.start(c); observeMedia(); true } catch (_:Exception) { false }
    }
    fun update(activeText: String, nextText: String) { current=activeText; next=nextText; val r=pill as? LinearLayout ?: return; (r.getChildAt(0) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text=activeText; (it.getChildAt(1) as? TextView)?.text=nextText } }
    private fun observeMedia() {
        mediaJob?.cancel()
        mediaJob = scope.launch {
            MediaHub.now.collect { now ->
                latest = now; positionAt = android.os.SystemClock.elapsedRealtime()
                if (!active) return@collect
                if (!now.playing) { tickerJob?.cancel(); tickerJob=null; return@collect }
                val nextKey = if (now.artist.isBlank() || now.title.isBlank()) "" else LyricsRepo.key(now.artist, now.title)
                if (nextKey.isNotEmpty() && nextKey != trackKey) {
                    trackKey = nextKey; track = null; update("Caricamento…", "")
                    tickerJob?.cancel()
                    tickerJob = scope.launch {
                        val cached = LyricsRepo.cached(now.artist, now.title)
                        val found = cached ?: LyricsRepo.lookup(now.artist, now.title, now.durationMs)
                        if (active && trackKey == nextKey) {
                            track = found
                            update(found?.synced?.firstOrNull()?.second ?: found?.plain?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "Testo non trovato" }, found?.synced?.getOrNull(1)?.second.orEmpty())
                            runTicker()
                        }
                    }
                } else if (track?.synced != null && tickerJob?.isActive != true) runTicker()
            }
        }
    }
    private fun runTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (active && latest.playing && track?.synced != null) {
                val pos = latest.positionMs + (android.os.SystemClock.elapsedRealtime() - positionAt)
                val list = track!!.synced!!
                val i = list.indexOfLast { it.first <= pos }.coerceAtLeast(0)
                update(list.getOrNull(i)?.second.orEmpty(), list.getOrNull(i+1)?.second.orEmpty())
                delay(250)
            }
        }
    }
    fun stop() { active=false; mediaJob?.cancel(); mediaJob=null; tickerJob?.cancel(); tickerJob=null; val v=pill; pill=null; try { if(v!=null) wm?.removeViewImmediate(v) } catch (_:Exception){}; params=null }
}
