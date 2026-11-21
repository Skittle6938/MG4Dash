package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mg4dash.R
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Compact synchronized lyric card; it consumes MediaHub's shared state and never controls playback. */
class LyricsCard(private val activity: Activity) : LinearLayout(activity) {
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
    private var tick: Runnable? = null
    private var latest = MediaHub.NowPlaying()
    private var positionAt = 0L
    private var lastSyncedIndex = -1

    init {
        LyricsRepo.start(activity)
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
        setOnLongClickListener { settings(); true }
        drawLines(0)
    }

    private fun dp(v: Int) = (v * d).toInt()
    private fun launchView(block: suspend () -> Unit): Job? =
        findViewTreeLifecycleOwner()?.lifecycleScope?.launch { block() }

    fun show(m: MediaHub.NowPlaying) {
        latest = m
        positionAt = android.os.SystemClock.elapsedRealtime()
        if (!m.available || m.artist.isBlank() || m.title.isBlank() || m.analog) {
            if (key.isNotEmpty()) { key = ""; track = null; requestJob?.cancel() }
            setStatus(activity.getString(R.string.ly_waiting))
            drawLines(0); return
        }
        val next = LyricsRepo.key(m.artist, m.title)
        if (next != key) {
            key = next; track = null; lastSyncedIndex = -1; request(force = false, m = m)
        } else if (track != null) drawLines(position(m))
    }

    private fun position(m: MediaHub.NowPlaying): Long = (m.positionMs + if (m.playing) android.os.SystemClock.elapsedRealtime() - positionAt else 0L)
        .coerceAtMost(if (m.durationMs > 0) m.durationMs else Long.MAX_VALUE)

    private fun request(force: Boolean, m: MediaHub.NowPlaying = MediaHub.now.value) {
        if (m.artist.isBlank() || m.title.isBlank()) { setStatus(activity.getString(R.string.ly_waiting)); return }
        val targetKey = LyricsRepo.key(m.artist, m.title)
        key = targetKey
        requestJob?.cancel()
        setStatus(activity.getString(R.string.ly_searching))
        val scope = findViewTreeLifecycleOwner()?.lifecycleScope ?: return
        requestJob = scope.launch {
            if (!force) delay(1000)
            val cached = LyricsRepo.cached(m.artist, m.title)
            if (cached != null) {
                track = cached
                setStatus(if (cached.synced == null) activity.getString(R.string.ly_plain) else "")
                drawLines(position(MediaHub.now.value))
                if (!force) return@launch
            }
            if (prefs.lyricsWifiOnly && !LyricsRepo.wifiConnected(activity)) {
                setStatus(activity.getString(R.string.ly_wifi_only)); drawLines(0); return@launch
            }
            if (!networkAvailable()) { setStatus(activity.getString(R.string.ly_offline)); drawLines(0); return@launch }
            val found = LyricsRepo.lookup(m.artist, m.title, m.durationMs, force)
            if (key != targetKey) return@launch
            track = found ?: cached
            setStatus(when {
                track == null -> activity.getString(R.string.ly_none)
                track?.synced == null -> activity.getString(R.string.ly_plain)
                else -> ""
            })
            drawLines(position(MediaHub.now.value))
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val r = object : Runnable { override fun run() { if (track?.synced != null || track?.plain != null) drawLines(position(latest)); postDelayed(this, 300) } }
        tick = r; post(r)
    }
    override fun onDetachedFromWindow() { tick?.let(::removeCallbacks); requestJob?.cancel(); super.onDetachedFromWindow() }
}
