package app.mg4dash.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mg4dash.R
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Compact synchronized lyric card; it consumes MediaHub's shared state and never controls playback. */
class LyricsCard(private val activity: Activity) : LinearLayout(activity), LifecycleEventObserver {
    private val d = resources.displayMetrics.density
    private val prefs = Prefs(activity)
    private val header = TextView(activity).apply {
        text = activity.getString(R.string.wc_lyrics).uppercase(); setTextColor(Themes.color(activity, R.color.text_title));
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); letterSpacing = .1f
    }
    private val refresh = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_refresh_lyrics); imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_sub))
        setBackgroundResource(R.drawable.bg_chip); contentDescription = activity.getString(R.string.ly_refresh)
        setOnClickListener { request(force = true) }
    }
    private val lines = (0..2).map { TextView(activity).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    } }
    private val status = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTextColor(android.graphics.Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
        alpha = .60f; maxLines = 2; visibility = View.GONE
    }
    private val lyricsArea = LinearLayout(activity).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(0), dp(2), dp(0), dp(2))
    }
    private var track: LyricsRepo.Track? = null
    private var key = ""
    private var requestJob: Job? = null
    private var latest = MediaHub.NowPlaying()
    private var positionAt = 0L
    private var lastSyncedIndex = -1
    private var requestedKey: String? = null
    private var attached = false
    private var activityStarted = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
    private var karaokeOpen = false
    private var isActivelyDisplayed = false
    private var karaoke: Dialog? = null
    private var karaokeRoot: View? = null
    private var karaokeRows: List<TextView> = emptyList()
    private var karaokeTitle: TextView? = null
    private var karaokeArtist: TextView? = null
    private var karaokeSynced: LinearLayout? = null
    private var karaokePlain: ScrollView? = null
    private var karaokePlainText: TextView? = null
    private var karaokeActiveIndex = -1
    private val ticker = object : Runnable {
        override fun run() {
            if (!isActivelyDisplayed || track?.synced == null) return
            val pos = position(latest)
            drawLines(pos); drawKaraoke(pos)
            postDelayed(this, 250)
        }
    }

    init {
        LyricsRepo.start(activity)
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(this)
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val top = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(header, LayoutParams(0, -2, 1f)); top.addView(refresh, LayoutParams(dp(38), dp(38)))
        addView(top, LayoutParams(-1, -2))
        lines.forEachIndexed { i, line ->
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 1) 25f else 16f)
            line.alpha = if (i == 0) .40f else if (i == 2) .45f else 1f
            if (i == 1) {
                line.maxLines = 2
                line.setLineSpacing(0f, 1.08f)
            }
            val lp = LayoutParams(-1, -2)
            if (i == 1) { lp.topMargin = dp(7); lp.bottomMargin = dp(7) }
            lyricsArea.addView(line, lp)
        }
        lyricsArea.addView(status, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(lyricsArea, LayoutParams(-1, 0, 1f))
        setOnClickListener { openKaraoke() }
        setOnLongClickListener { settings(); true }
        drawLines(0)
    }

    private fun dp(v: Int) = (v * d).toInt()
    private fun launchView(block: suspend () -> Unit): Job? =
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch { block() }

    fun show(m: MediaHub.NowPlaying) {
        latest = m
        positionAt = android.os.SystemClock.elapsedRealtime()
        if (!isActivelyDisplayed) return
        showCurrentTrack()
    }

    private fun showCurrentTrack() {
        val m = latest
        if (!m.available || m.artist.isBlank() || m.title.isBlank() || m.analog) {
            if (key.isNotEmpty()) {
                key = ""; track = null; requestedKey = null
                requestJob?.cancel(); requestJob = null; LyricsRepo.cancelActiveRequest()
            }
            setStatus(activity.getString(R.string.ly_waiting))
            drawLines(0); drawKaraoke(0); return
        }
        val next = LyricsRepo.key(m.artist, m.title)
        if (next != key) {
            key = next; track = null; requestedKey = null; lastSyncedIndex = -1; karaokeActiveIndex = -1
            karaokeTitle?.text = m.title
            karaokeArtist?.text = m.artist
            request(force = false, m = m)
        } else if (track != null) {
            drawLines(position(m)); drawKaraoke(position(m))
        } else if (requestedKey != next) request(force = false, m = m)
    }

    private fun position(m: MediaHub.NowPlaying): Long = (m.positionMs + if (m.playing) android.os.SystemClock.elapsedRealtime() - positionAt else 0L)
        .coerceAtMost(if (m.durationMs > 0) m.durationMs else Long.MAX_VALUE)

    private fun request(force: Boolean, m: MediaHub.NowPlaying = MediaHub.now.value) {
        if (!isActivelyDisplayed) return
        if (m.artist.isBlank() || m.title.isBlank()) { setStatus(activity.getString(R.string.ly_waiting)); return }
        val targetKey = LyricsRepo.key(m.artist, m.title)
        key = targetKey
        requestedKey = targetKey
        requestJob?.cancel(); LyricsRepo.cancelActiveRequest()
        setStatus(activity.getString(R.string.ly_searching))
        val scope = (activity as? LifecycleOwner)?.lifecycleScope ?: return
        requestJob = scope.launch {
            if (!force) delay(1000)
            val cached = LyricsRepo.cached(m.artist, m.title)
            if (cached != null) {
                track = cached
                setStatus(if (cached.synced == null) activity.getString(R.string.ly_plain) else "")
                drawLines(position(MediaHub.now.value))
                if (!force) {
                    drawKaraoke(position(latest))
                    if (cached.synced != null && isActivelyDisplayed) { removeCallbacks(ticker); post(ticker) }
                    return@launch
                }
            }
            if (prefs.lyricsWifiOnly && !LyricsRepo.wifiConnected(activity)) {
                setStatus(activity.getString(R.string.ly_wifi_only)); drawLines(0); return@launch
            }
            if (!networkAvailable()) { setStatus(activity.getString(R.string.ly_offline)); drawLines(0); return@launch }
            if (!isActivelyDisplayed) return@launch
            val found = LyricsRepo.lookup(m.artist, m.title, m.durationMs, force)
            if (!isActivelyDisplayed || key != targetKey) return@launch
            track = found ?: cached
            setStatus(when {
                track == null -> activity.getString(R.string.ly_none)
                track?.synced == null -> activity.getString(R.string.ly_plain)
                else -> ""
            })
            drawLines(position(MediaHub.now.value))
            drawKaraoke(position(MediaHub.now.value))
            if (track?.synced != null && isActivelyDisplayed) {
                removeCallbacks(ticker); post(ticker)
            }
        }
    }

    private fun networkAvailable(): Boolean = try {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val n = cm.activeNetwork
        if (android.os.Build.VERSION.SDK_INT >= 23) cm.getNetworkCapabilities(n)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true else true
    } catch (_: Exception) { false }

    private fun setStatus(text: CharSequence) {
        status.text = text
        status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_START -> activityStarted = true
            Lifecycle.Event.ON_STOP -> activityStarted = false
            Lifecycle.Event.ON_DESTROY -> {
                activityStarted = false
                (activity as? LifecycleOwner)?.lifecycle?.removeObserver(this)
                karaoke?.dismiss()
            }
            else -> Unit
        }
        updateActiveState()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        updateActiveState()
    }

    override fun onDetachedFromWindow() {
        attached = false
        updateActiveState()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateActiveState()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateActiveState()
    }

    private fun updateActiveState() {
        val visible = activityStarted && (karaokeOpen || (attached && isShown && windowVisibility == View.VISIBLE))
        if (visible == isActivelyDisplayed) return
        isActivelyDisplayed = visible
        if (visible) {
            LyricsRepo.start(activity)
            removeCallbacks(ticker)
            if (track?.synced != null) post(ticker)
            showCurrentTrack()
        } else {
            removeCallbacks(ticker)
            requestJob?.cancel(); requestJob = null
            requestedKey = null
            LyricsRepo.cancelActiveRequest()
        }
    }

    private fun openKaraoke() {
        if (karaokeOpen || activity.isFinishing || activity.isDestroyed) return
        val dialog = Dialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(0xEE0A0E17.toInt())
        }
        val header = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(activity).apply { orientation = VERTICAL }
        val songTitle = TextView(activity).apply {
            text = latest.title.ifBlank { activity.getString(R.string.wc_lyrics) }; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val artist = TextView(activity).apply {
            text = latest.artist; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Color.WHITE); alpha = .65f
        }
        labels.addView(songTitle)
        labels.addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(labels, LayoutParams(0, -2, 1f))
        val close = TextView(activity).apply {
            text = "×"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setTextColor(Color.WHITE); setBackgroundResource(R.drawable.bg_chip)
            contentDescription = activity.getString(R.string.st_close)
            setOnClickListener { closeKaraoke() }
        }
        header.addView(close, LayoutParams(dp(54), dp(54)))
        header.setOnClickListener { closeKaraoke() }
        root.addView(header, LayoutParams(-1, -2))

        val content = FrameLayout(activity)
        val synced = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        val rows = (0 until 7).map { i -> TextView(activity).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 3) 30f else 20f)
            maxLines = if (i == 3) 2 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = when (kotlin.math.abs(i - 3)) { 0 -> 1f; 1 -> .50f; 2 -> .36f; else -> .25f }
            if (i == 3) setLineSpacing(0f, 1.08f)
        } }
        rows.forEachIndexed { i, line ->
            val lp = LayoutParams(-1, -2)
            if (i == 3) { lp.topMargin = dp(10); lp.bottomMargin = dp(10) }
            synced.addView(line, lp)
        }
        content.addView(synced, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(activity).apply { isFillViewport = true; visibility = View.GONE }
        val plain = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(7).toFloat(), 1.12f); setPadding(dp(12), dp(24), dp(12), dp(32))
        }
        scroll.addView(plain, android.widget.FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        karaokeRows = rows; karaokeTitle = songTitle; karaokeArtist = artist
        karaokeSynced = synced; karaokePlain = scroll; karaokePlainText = plain; karaokeRoot = root
        karaokeOpen = true
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            karaokeOpen = false; karaoke = null; karaokeRows = emptyList(); karaokeRoot = null
            karaokeTitle = null; karaokeArtist = null
            karaokeSynced = null; karaokePlain = null; karaokePlainText = null; karaokeActiveIndex = -1
            updateActiveState()
        }
        karaoke = dialog
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xEE0A0E17.toInt()))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .18f }
        }
        root.alpha = .5f; root.scaleX = .97f; root.scaleY = .97f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        drawKaraoke(position(latest))
        updateActiveState()
    }

    private fun closeKaraoke() {
        val dialog = karaoke ?: return
        karaokeRoot?.animate()?.alpha(.4f)?.setDuration(130)?.withEndAction { dialog.dismiss() } ?: dialog.dismiss()
    }

    private fun drawKaraoke(position: Long) {
        if (!karaokeOpen) return
        val t = track
        val synced = t?.synced
        if (synced == null) {
            karaokeSynced?.visibility = if (t?.plain == null) View.VISIBLE else View.GONE
            karaokePlain?.visibility = if (t?.plain != null) View.VISIBLE else View.GONE
            karaokePlainText?.text = t?.plain.orEmpty().ifBlank { status.text }
            karaokeRows.getOrNull(3)?.let { center -> center.text = status.text; center.visibility = if (synced == null && t?.plain == null) View.VISIBLE else View.GONE }
            return
        }
        karaokeSynced?.visibility = View.VISIBLE
        karaokePlain?.visibility = View.GONE
        karaokeRows.getOrNull(3)?.visibility = View.VISIBLE
        val index = synced.indexOfLast { it.first <= position }.coerceAtLeast(0)
        if (index != karaokeActiveIndex) {
            karaokeActiveIndex = index
            karaokeRows.getOrNull(3)?.let { active ->
                active.alpha = .35f; active.scaleX = .97f; active.scaleY = .97f
                active.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
            }
        }
        karaokeRows.forEachIndexed { row, line ->
            val lyric = synced.getOrNull(index + row - 3)?.second.orEmpty()
            line.text = lyric
            val distance = kotlin.math.abs(row - 3)
            if (row != 3) line.alpha = when (distance) { 1 -> .50f; 2 -> .36f; else -> .25f }
        }
    }

    private fun drawLines(position: Long) {
        val t = track
        val synced = t?.synced
        if (synced != null) {
            val i = synced.indexOfLast { it.first <= position }.coerceAtLeast(0)
            if (i != lastSyncedIndex) {
                lastSyncedIndex = i
                lines[1].alpha = .5f
                lines[1].animate().alpha(1f).setDuration(150).start()
            }
            for (row in 0..2) {
                val ix = i + row - 1
                lines[row].text = synced.getOrNull(ix)?.second.orEmpty()
                lines[row].setTextColor(android.graphics.Color.WHITE)
                lines[row].setTextSize(TypedValue.COMPLEX_UNIT_SP, if (row == 1) 25f else 16f)
                lines[row].maxLines = if (row == 1) 2 else 1
                if (row == 0) lines[row].alpha = .40f
                if (row == 2) lines[row].alpha = .45f
                lines[row].typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            return
        }
        val plain = t?.plain?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
        val ix = if (plain.isEmpty()) -1 else (position / 5000).toInt().coerceAtMost(plain.lastIndex)
        for (row in 0..2) {
            lines[row].text = plain.getOrNull(ix + row - 1).orEmpty()
            lines[row].setTextColor(android.graphics.Color.WHITE)
            lines[row].setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            lines[row].typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            lines[row].alpha = .85f
        }
    }

    private fun settings() {
        val maxes = listOf(5, 10, 20, 25, 50, 100)
        val info = TextView(activity).apply { setTextColor(Themes.color(activity, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
        fun updateInfo() { launchView { val c = LyricsRepo.info(); info.text = activity.getString(R.string.ly_cache_used, c.bytes / (1024f * 1024f), prefs.lyricsCacheMaxMb, c.tracks) } }
        OptionsPopup(activity, activity.getString(R.string.wc_lyrics))
            .toggle(activity.getString(R.string.ly_wifi), null, { prefs.lyricsWifiOnly }) { prefs.lyricsWifiOnly = it }
            .toggle(activity.getString(R.string.ly_cache), null, { prefs.lyricsCacheEnabled }) { prefs.lyricsCacheEnabled = it }
            .choice(activity.getString(R.string.ly_max), null, maxes.map { "$it MB" },
                { maxes.indexOf(prefs.lyricsCacheMaxMb).coerceAtLeast(0) }, enabled = { prefs.lyricsCacheEnabled }) {
                prefs.lyricsCacheMaxMb = maxes[it]; launchView { LyricsRepo.trim(maxes[it]); updateInfo() }
            }
            .note(info)
            .action(activity.getString(R.string.ly_clear)) {
                launchView { LyricsRepo.clear(); updateInfo(); Toasts.show(activity, R.string.ly_cleared) }
            }
            .show()
        updateInfo()
    }

}
