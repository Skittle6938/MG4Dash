package app.mg4dash.cards

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
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
import app.mg4dash.R
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.OptionsPopup
import app.mg4dash.ui.Themes
import app.mg4dash.ui.Toasts
import app.mg4dash.ui.LyricsOverlay
import app.mg4dash.ui.LyricsOverlayReceiver
import app.mg4dash.ui.Windows
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Compact synchronized lyric card; it consumes MediaHub's shared state and never controls playback. */
class LyricsCard(context: Context) : LinearLayout(context), HomeCard {
    override val card = HomeSlots.Card.LYRICS
    override val view: View get() = this
    private val activity: Context = context
    private val d = resources.displayMetrics.density
    private val prefs = LyricsCardPrefs(activity)
    private val header = CardKit.title(activity, activity.getString(R.string.wc_lyrics).uppercase())
    private val refresh = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_refresh_lyrics); imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_sub))
        background = CardKit.buttonBackground(activity); contentDescription = activity.getString(R.string.ly_refresh)
        setOnClickListener { request(force = true) }
    }
    private val overlayButton = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_lyrics_overlay); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(11), dp(11), dp(11), dp(11)); background = CardKit.buttonBackground(activity, LyricsOverlay.active)
        imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_value))
        isSelected = LyricsOverlay.active
        contentDescription = activity.getString(R.string.lyrics_shortcut_short); setOnClickListener { toggleOverlay() }
    }
    private val lines = (0..6).map { TextView(activity).apply {
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
        setTextColor(Themes.color(activity, R.color.text_sub))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        includeFontPadding = false
    } }
    private val status = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTextColor(Themes.color(activity, R.color.text_sub))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
        alpha = .60f; maxLines = 2; visibility = View.GONE
    }
    private val lyricsArea = LinearLayout(activity).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(0), dp(2), dp(0), dp(2))
    }
    private var lastMeasuredW = 0
    private var lastMeasuredH = 0
    private var visibleLyricsRows = 3
    private var activeFontSp = 25f
    private var track: LyricsRepo.Track? = null
    private var key = ""
    private var requestJob: Job? = null
    private var latest = MediaHub.NowPlaying()
    private val clock = app.mg4dash.media.LyricsClock()
    private var requestedKey: String? = null
    private var tickerScheduled = false
    private val live = CardScope()
    private var liveScope: CoroutineScope? = null
    private var cardIsShown = false
    private var place = CardPlace(HomeSlots.Size.SMALL)
    private var karaokeOpen = false
    private var isActivelyDisplayed = false
    private var karaoke: Dialog? = null
    private var karaokeRoot: View? = null
    private var karaokeRows: List<TextView> = emptyList()
    private var karaokeOverlayButton: ImageButton? = null
    private var karaokeTitle: TextView? = null
    private var karaokeArtist: TextView? = null
    private var karaokeSynced: LinearLayout? = null
    private var karaokePlain: ScrollView? = null
    private var karaokePlainText: TextView? = null
    private var karaokeActiveIndex = -1
    private var lyricsCacheInfo = LyricsRepo.CacheInfo(0, 0)
    private val ticker = object : Runnable {
        override fun run() {
            if (!shouldRunTicker() || track?.synced == null) { tickerScheduled = false; return }
            val pos = position(latest)
            drawLines(pos); drawKaraoke(pos)
            tickerScheduled = postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
        CardKit.pad(this)
        val top = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(header, LayoutParams(0, -2, 1f))
        top.addView(overlayButton, LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        top.addView(refresh, LayoutParams(dp(48), dp(48)))
        addView(top, LayoutParams(-1, -2))
        lines.forEachIndexed { i, line ->
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (i == 3) 25f else 17f)
            line.alpha = if (i == 3) 1f else .55f
            line.visibility = if (i in 2..4) View.VISIBLE else View.GONE
            if (i == 3) {
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
        isClickable = true
        drawLines(0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyMeasuredSize(w, h)
    }

    private fun applyMeasuredSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == lastMeasuredW && h == lastMeasuredH)) return
        lastMeasuredW = w
        lastMeasuredH = h
        post {
            val bodyHeight = lyricsArea.height.takeIf { it > 0 } ?: (h - dp(62)).coerceAtLeast(1)
            val bodyWidth = lyricsArea.width.takeIf { it > 0 } ?: (w - paddingLeft - paddingRight)
            // Five lines on a medium or tall place, three on a small or wide one
            visibleLyricsRows = if (place.tall || place.medium) 5 else 3
            val count = visibleLyricsRows
            val heightBasedPx = bodyHeight * if (count == 5) .125f else .22f
            val activeTextSizePx = minOf(heightBasedPx, bodyWidth * .068f)
            activeFontSp = activeTextSizePx
            val sidePad = dp(8)
            val verticalGap = (bodyHeight * .035f).toInt().coerceAtLeast(dp(8))
            lyricsArea.setPadding(sidePad, verticalGap, sidePad, verticalGap)
            val radius = count / 2
            lines.forEachIndexed { index, line ->
                val distance = kotlin.math.abs(index - 3)
                line.visibility = if (distance <= radius) View.VISIBLE else View.GONE
                line.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (index == 3 || index == NEXT) activeTextSizePx else activeTextSizePx * .60f)
                line.alpha = when (index) { 3 -> 1f; NEXT -> NEXT_ALPHA; else -> .50f }
                val lp = line.layoutParams as? LayoutParams
                if (lp != null) {
                    val margin = if (index == 3) verticalGap else dp(2)
                    if (lp.topMargin != margin || lp.bottomMargin != margin) { lp.topMargin = margin; lp.bottomMargin = margin; line.layoutParams = lp }
                }
            }
            drawLines(position(latest))
        }
    }

    override fun place(p: CardPlace) {
        if (place == p) return
        place = p
        lastMeasuredW = 0; lastMeasuredH = 0
        if (width > 0 && height > 0) applyMeasuredSize(width, height)
    }

    private fun dp(v: Int) = (v * d).toInt()

    /** Karaoke rows: the current one, the next one in the main text colour (read in advance), the others fading. */
    private fun rowAlpha(i: Int) = when {
        i == 3 -> 1f
        i == NEXT -> NEXT_ALPHA
        else -> when (kotlin.math.abs(i - 3)) { 1 -> .50f; 2 -> .36f; else -> .25f }
    }
    private fun launchView(block: suspend () -> Unit): Job? =
        liveScope?.launch { block() }

    fun show(m: MediaHub.NowPlaying) {
        latest = m
        paintOverlayButtons()
        clock.update(m, android.os.SystemClock.elapsedRealtime())
        if (!isActivelyDisplayed) return
        showCurrentTrack()
    }

    private fun showCurrentTrack() {
        val m = latest
        if (!LyricsRepo.searchable(m)) {
            if (key.isNotEmpty()) {
                key = ""; track = null; requestedKey = null
                requestJob?.cancel(); requestJob = null
                if (!LyricsOverlay.active) LyricsRepo.cancelActiveRequest()
            }
            // On the radio, said plainly instead of waiting for a song that never comes (25/11)
            setStatus(activity.getString(if (m.available && LyricsRepo.radio(m)) R.string.ly_radio else R.string.ly_waiting))
            drawLines(0); drawKaraoke(0); return
        }
        val next = LyricsRepo.key(m.artist, m.title)
        if (next != key) {
            key = next; track = null; requestedKey = null; karaokeActiveIndex = -1
            requestJob?.cancel(); requestJob = null
            lines.forEach { it.text = "" }
            karaokeTitle?.text = m.title
            karaokeArtist?.text = m.artist
            request(force = false, m = m)
        } else if (track != null) {
            drawLines(position(m)); drawKaraoke(position(m))
        } else if (requestedKey != next) request(force = false, m = m)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun position(m: MediaHub.NowPlaying): Long = clock.lyricsAt(android.os.SystemClock.elapsedRealtime())

    private fun request(force: Boolean, m: MediaHub.NowPlaying = MediaHub.now.value) {
        if (!isActivelyDisplayed) return
        if (!LyricsRepo.searchable(m)) { showCurrentTrack(); return }
        val targetKey = LyricsRepo.key(m.artist, m.title)
        key = targetKey
        requestedKey = targetKey
        requestJob?.cancel()
        if (!LyricsOverlay.active) LyricsRepo.cancelActiveRequest()
        setStatus(activity.getString(R.string.ly_searching))
        val scope = liveScope ?: return
        requestJob = scope.launch {
            if (!force) delay(1000)
            val cached = LyricsRepo.cached(m.artist, m.title)
            if (cached != null) {
                track = cached
                setStatus(when { cached.instrumental -> activity.getString(R.string.ly_none); cached.synced == null -> activity.getString(R.string.ly_plain); else -> "" })
                drawLines(position(MediaHub.now.value))
                if (!force) {
                    drawKaraoke(position(latest))
                    if (cached.synced != null && isActivelyDisplayed) startTicker()
                    return@launch
                }
            }
            if (!LyricsRepo.networkAllowed(activity)) {
                setStatus(activity.getString(R.string.ly_wifi_only)); drawLines(0); return@launch
            }
            if (!networkAvailable()) { setStatus(activity.getString(R.string.ly_offline)); drawLines(0); return@launch }
            if (!isActivelyDisplayed) return@launch
            val found = LyricsRepo.lookup(m.artist, m.title, m.durationMs, force)
            if (!isActivelyDisplayed || key != targetKey) return@launch
            track = found ?: cached
            setStatus(when {
                track == null -> activity.getString(R.string.ly_none)
                track?.instrumental == true -> activity.getString(R.string.ly_none)
                track?.synced == null -> activity.getString(R.string.ly_plain)
                else -> ""
            })
            drawLines(position(MediaHub.now.value))
            drawKaraoke(position(MediaHub.now.value))
            if (track?.synced != null && isActivelyDisplayed) {
                startTicker()
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

    override fun shown(on: Boolean) {
        if (on == cardIsShown) return
        cardIsShown = on
        if (!on) {
            removeCallbacks(ticker)
            tickerScheduled = false
            requestJob?.cancel(); requestJob = null
            requestedKey = null
            isActivelyDisplayed = false
            karaoke?.dismiss()
            if (!LyricsOverlay.active) LyricsRepo.cancelActiveRequest()
            liveScope = null
            live.set(false) {}
            return
        }
        live.set(true) {
            liveScope = this
            isActivelyDisplayed = true
            LyricsRepo.start(activity)
            launch { MediaHub.now.collect { show(it) } }
            show(MediaHub.now.value)
        }
    }

    private fun shouldRunTicker() = isActivelyDisplayed

    private fun startTicker() {
        removeCallbacks(ticker)
        tickerScheduled = true
        tickerScheduled = post(ticker)
    }

    private fun toggleOverlay() {
        LyricsOverlay.toggle(activity)
        paintOverlayButtons()
    }

    private fun paintOverlayButtons() {
        val selected = LyricsOverlay.active
        val tint = if (selected) Themes.select(activity) else Themes.color(activity, R.color.text_value)
        listOfNotNull(overlayButton, karaokeOverlayButton).forEach {
            it.isSelected = selected
            it.imageTintList = android.content.res.ColorStateList.valueOf(tint)
            it.background = CardKit.buttonBackground(activity, selected)
        }
    }

    private fun openKaraoke() {
        val activity = hostActivity() ?: return
        if (karaokeOpen || activity.isFinishing || activity.isDestroyed || !cardIsShown) return
        val root = LinearLayout(activity).apply {
            orientation = VERTICAL; setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(Themes.color(activity, R.color.bg_bottom))
        }
        val header = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val labels = LinearLayout(activity).apply { orientation = VERTICAL }
        val songTitle = TextView(activity).apply {
            text = latest.title.ifBlank { activity.getString(R.string.wc_lyrics) }; maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Themes.color(activity, R.color.text_value)); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        val artist = TextView(activity).apply {
            text = latest.artist; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Themes.color(activity, R.color.text_sub)); alpha = .7f
        }
        labels.addView(songTitle)
        labels.addView(artist, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        header.addView(labels, LayoutParams(0, -2, 1f))
        val close = TextView(activity).apply {
            text = "×"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f); includeFontPadding = false
            setTextColor(Themes.color(activity, R.color.text_value)); background = CardKit.buttonBackground(activity)
            contentDescription = activity.getString(R.string.st_close)
            setOnClickListener { closeKaraoke() }; setBackgroundResource(R.drawable.bg_chip)
        }
        val overlayToggle = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_lyrics_overlay); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = CardKit.buttonBackground(activity, LyricsOverlay.active)
            isSelected = LyricsOverlay.active
            imageTintList = android.content.res.ColorStateList.valueOf(if (isSelected) Themes.select(activity) else Themes.color(activity, R.color.text_value))
            contentDescription = activity.getString(R.string.lyrics_shortcut_short)
            setOnClickListener { toggleOverlay() }
        }
        val refreshKaraoke = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_refresh_lyrics); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14)); background = CardKit.buttonBackground(activity)
            imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_value))
            contentDescription = activity.getString(R.string.ly_refresh)
            setOnClickListener { request(force = true, m = latest) }
        }
        header.addView(overlayToggle, LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) })
        header.addView(refreshKaraoke, LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(8) })
        header.addView(close, LayoutParams(dp(56), dp(56)))
        header.setOnClickListener { closeKaraoke() }
        root.addView(header, LayoutParams(-1, -2))

        val content = FrameLayout(activity)
        val synced = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER }
        val rows = (0 until 7).map { i -> TextView(activity).apply {
            gravity = Gravity.CENTER; setTextColor(if (i == 3) Themes.color(activity, R.color.text_value) else Themes.color(activity, R.color.text_sub)); includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 1f)
            maxLines = if (i == 3 || i == NEXT) 2 else 1; ellipsize = android.text.TextUtils.TruncateAt.END
            alpha = rowAlpha(i)
            if (i == 3 || i == NEXT) setLineSpacing(0f, 1.08f)
        } }
        rows.forEachIndexed { i, line ->
            val lp = LayoutParams(-1, -2)
            if (i == 3) { lp.topMargin = dp(18); lp.bottomMargin = dp(18) } else { lp.topMargin = dp(7); lp.bottomMargin = dp(7) }
            synced.addView(line, lp)
        }
        content.addView(synced, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(activity).apply { isFillViewport = true; visibility = View.GONE }
        val plain = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Themes.color(activity, R.color.text_value))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(7).toFloat(), 1.12f); setPadding(dp(12), dp(24), dp(12), dp(32))
        }
        scroll.addView(plain, android.widget.FrameLayout.LayoutParams(-1, -2))
        content.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })

        karaokeRows = rows; karaokeTitle = songTitle; karaokeArtist = artist
        karaokeSynced = synced; karaokePlain = scroll; karaokePlainText = plain; karaokeRoot = root
        karaokeOpen = true
        karaokeOverlayButton = overlayToggle
        karaoke = Windows.fullscreen(activity, root,
            prepare = { dialog ->
                dialog.window?.apply {
                    addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    decorView.systemUiVisibility = activity.window.decorView.systemUiVisibility
                    decorView.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
                        if (hasFocus && decorView.viewTreeObserver.isAlive) Fullscreen.applyDecor(decorView, Prefs(activity).fullscreen)
                    }
                }
            },
            shown = { dialog ->
                dialog.window?.apply {
                    Fullscreen.applyDecor(decorView, Prefs(activity).fullscreen)
                    clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                    setBackgroundDrawable(ColorDrawable(Themes.color(activity, R.color.bg_bottom)))
                    setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply { dimAmount = .18f }
                }
                dialog.window?.decorView?.post {
                    val dialogHeight = dialog.window?.decorView?.height ?: 0
                    if (dialogHeight > 0) {
                        val activePx = dialogHeight * .085f
                        karaokeTitle?.setTextSize(TypedValue.COMPLEX_UNIT_PX, dialogHeight * .042f)
                        karaokeArtist?.setTextSize(TypedValue.COMPLEX_UNIT_PX, dialogHeight * .030f)
                        karaokeRows.forEachIndexed { index, row ->
                            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (index == 3 || index == NEXT) activePx else activePx * .58f)
                            val lp = row.layoutParams as? LayoutParams
                            if (lp != null) { val gap=(dialogHeight*.012f).toInt(); lp.topMargin=gap; lp.bottomMargin=gap; row.layoutParams=lp }
                        }
                    }
                }
                root.alpha = .5f; root.scaleX = .97f; root.scaleY = .97f
                root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                drawKaraoke(position(latest))
            },
            onDismiss = {
                karaokeOpen = false; karaoke = null; karaokeRows = emptyList(); karaokeRoot = null
                karaokeTitle = null; karaokeArtist = null
                karaokeOverlayButton = null
                karaokeSynced = null; karaokePlain = null; karaokePlainText = null; karaokeActiveIndex = -1
                paintOverlayButtons()
                isActivelyDisplayed = cardIsShown
            }
        )
    }

    private fun hostActivity(): Activity? {
        var current: Context? = activity
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
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
            karaokeRows.getOrNull(3)?.let { center -> center.text = status.text; center.visibility = if (t?.plain == null) View.VISIBLE else View.GONE }
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
            line.setTextColor(Themes.color(activity, if (row == 3 || row == NEXT) R.color.text_value else R.color.text_sub))
            if (row != 3) line.alpha = rowAlpha(row)
        }
    }

    private fun drawLines(position: Long) {
        val t = track
        val synced = t?.synced
        if (synced != null) {
            val i = synced.indexOfLast { it.first <= position }.coerceAtLeast(0)
            val radius = visibleLyricsRows / 2
            val availableWidth = (lyricsArea.width - lyricsArea.paddingLeft - lyricsArea.paddingRight).coerceAtLeast(1)
            val activeText = synced.getOrNull(i)?.second.orEmpty()
            val activePx = activeFontSp * (1f - .18f * ((activeText.length * activeFontSp * .53f / availableWidth).coerceAtLeast(1f) - 1f).coerceAtMost(1f))
            for (row in lines.indices) {
                val distance = kotlin.math.abs(row - 3)
                val ix = i + row - 3
                lines[row].text = synced.getOrNull(ix)?.second.orEmpty()
                lines[row].setTextColor(Themes.color(activity, if (row == 3 || row == NEXT) R.color.text_value else R.color.text_sub))
                // The next line as big as the current one, to be read in advance (25/11)
                lines[row].setTextSize(TypedValue.COMPLEX_UNIT_PX, if (row == 3 || row == NEXT) activePx else activeFontSp * .60f)
                lines[row].maxLines = if (row == 3 || (visibleLyricsRows == 5 && distance == 1)) 2 else 1
                lines[row].ellipsize = android.text.TextUtils.TruncateAt.END
                lines[row].alpha = when (row) { 3 -> 1f; NEXT -> NEXT_ALPHA; else -> .45f }
                lines[row].visibility = if (distance <= radius) View.VISIBLE else View.GONE
                lines[row].typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            return
        }
        val plain = t?.plain?.lineSequence()?.filter { it.isNotBlank() }?.toList().orEmpty()
        val ix = if (plain.isEmpty()) -1 else (position / 5000).toInt().coerceAtMost(plain.lastIndex)
        for (row in lines.indices) {
            lines[row].text = plain.getOrNull(ix + row - 3).orEmpty()
            lines[row].visibility = if (kotlin.math.abs(row - 3) <= visibleLyricsRows / 2) View.VISIBLE else View.GONE
            lines[row].setTextColor(Themes.color(activity, if (row == 3) R.color.text_value else R.color.text_sub))
            lines[row].setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            lines[row].typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            lines[row].alpha = .85f
        }
    }

    override fun options(popup: OptionsPopup): OptionsPopup {
        val maxes = listOf(5, 10, 20, 25, 50, 100)
        val launchControl = activity.packageManager.getLaunchIntentForPackage("com.mg4.control")
        popup.toggle(activity.getString(R.string.ly_wifi), null, { prefs.wifiOnly }) { prefs.wifiOnly = it }
            .toggle(activity.getString(R.string.ly_cache), null, { prefs.cacheEnabled }) { prefs.cacheEnabled = it }
            .slider(activity.getString(R.string.ly_max), maxes.lastIndex,
                { maxes.indexOf(prefs.cacheMaxMb).coerceAtLeast(0) }, { "${maxes[it.coerceIn(0, maxes.lastIndex)]} MB" },
                enabled = { prefs.cacheEnabled }) { value ->
                prefs.cacheMaxMb = maxes[value.coerceIn(0, maxes.lastIndex)]
                launchView { LyricsRepo.trim(prefs.cacheMaxMb); lyricsCacheInfo = LyricsRepo.info(); popup.refresh() }
            }
            .note { cacheUsageText() }
            .also { popup -> launchView { lyricsCacheInfo = LyricsRepo.info(); popup.refresh() } }
            .slider(activity.getString(R.string.ly_overlay_size), 14,
                { ((prefs.overlayScale - .70f) / .05f).toInt().coerceIn(0, 14) }, { "${70 + it * 5}%" }) { value ->
                val scale = (.70f + value * .05f).coerceIn(.70f, 1.40f)
                prefs.overlayScale = scale
                if (LyricsOverlay.active) LyricsOverlay.setScale(scale)
            }
            .action(activity.getString(R.string.ly_overlay_reset), null, activity.getString(R.string.ly_overlay_reset)) {
                prefs.overlayScale = 1f
                if (LyricsOverlay.active) LyricsOverlay.setScale(1f)
            }
            .action(activity.getString(R.string.ly_clear), null, activity.getString(R.string.ly_clear)) {
                launchView { LyricsRepo.clear(); lyricsCacheInfo = LyricsRepo.info(); popup.refresh(); Toasts.show(activity, R.string.ly_cleared) }
            }
            .column()
            .note(activity.getString(R.string.lyrics_mg4control_title))
            .note(activity.getString(R.string.lyrics_mg4control_guide))
            .action(activity.getString(R.string.lyrics_mg4control_copy), null, activity.getString(R.string.lyrics_mg4control_copy)) {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MG4Dash overlay action", LyricsOverlayReceiver.ACTION_TOGGLE))
                Toasts.show(activity, R.string.lyrics_mg4control_copied)
            }
        if (launchControl != null) popup.action(activity.getString(R.string.lyrics_mg4control_open), null, activity.getString(R.string.lyrics_mg4control_open)) {
            hostActivity()?.startActivity(launchControl)
        }
        return popup
    }

    private fun cacheUsageText(): String {
        val info = lyricsCacheInfo
        return activity.getString(R.string.ly_cache_used, info.bytes / (1024f * 1024f), prefs.cacheMaxMb, info.tracks)
    }

}

/** The row of the next lyric line, under the current one (3), and its strength (25/11: too small and dark to read ahead). */
private const val NEXT = 4
private const val NEXT_ALPHA = .72f
