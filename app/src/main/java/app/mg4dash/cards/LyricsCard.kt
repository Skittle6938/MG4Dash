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
import app.mg4dash.media.LyricsChoices
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
    /** "Choose the lyrics" (26/11, was "refresh"): the picker of LRCLIB's results, "search again" in it. */
    private val choose = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_lyrics_choose); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_value))
        background = roundButton(); contentDescription = activity.getString(R.string.ly_choose)
        setOnClickListener { openPicker() }
    }
    private val overlayButton = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_lyrics_overlay); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(14), dp(14), dp(14), dp(14)); background = roundButton(LyricsOverlay.active)
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
    /** Synced lyrics: one reel that scrolls (26/11), the rows above only show plain lyrics. */
    private val reel = LyricsReel(activity).apply { visibility = View.GONE }
    private val status = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f); setTextColor(Themes.color(activity, R.color.text_sub))
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
        alpha = .60f; maxLines = 2; visibility = View.GONE
    }
    private val lyricsArea = LinearLayout(activity).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER
        setPadding(dp(0), dp(2), dp(0), dp(2))
    }
    /** The arrows left of the lyrics and the lyrics. */
    private val body = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val cardShift = LyricsShift(activity, big = false) { setShift(it) }.apply { visibility = View.GONE }
    /** The current song's shift of its lyrics (the arrows, kept for the song), in ms. */
    private var shiftMs = 0L
    private var lastMeasuredW = 0
    private var lastMeasuredH = 0
    private var visibleLyricsRows = 3
    private var activeFontSp = 25f
    /** The room of the lyrics (inside their padding) and the lines a long one may take there. */
    private var fitW = 0
    private var fitH = 0
    private var fitGap = 0
    private var wrapLines = 2
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
    private val lyricsFace: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private var karaokePx = 0f
    /** The plain lyrics in the karaoke, relative to the synced current line's size. */
    private val PLAIN_SCALE = .8f
    private var karaokeReel: LyricsReel? = null
    private var karaokeShift: LyricsShift? = null
    private var karaokeBox: FrameLayout? = null
    private var karaokeEmpty: View? = null
    private var karaokeEmptyText: TextView? = null
    private var karaokeEmptyFor: TextView? = null
    private var karaokeEmptyButton: View? = null
    private var lyricsCacheInfo = LyricsRepo.CacheInfo(0, 0)
    private val ticker = object : Runnable {
        override fun run() {
            if (!shouldRunTicker() || track?.synced == null) { tickerScheduled = false; return }
            val pos = position(latest)
            drawLines(pos); drawKaraoke(pos)
            // Woken when the next line's move starts, so it starts on time
            tickerScheduled = postDelayed(this, track?.synced?.let { LyricsScroll.untilNext(it, pos) } ?: 250L)
        }
    }

    init {
        orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL
        CardKit.pad(this)
        val top = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(header, LayoutParams(0, -2, 1f))
        top.addView(overlayButton, LayoutParams(dp(60), dp(60)).apply { marginEnd = dp(10) })
        top.addView(choose, LayoutParams(dp(60), dp(60)))
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
        lyricsArea.addView(reel, LayoutParams(-1, 0, 1f))
        reel.position = { position(latest) }
        lyricsArea.addView(status, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        body.addView(cardShift, LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        body.addView(lyricsArea, LayoutParams(0, -1, 1f))
        addView(body, LayoutParams(-1, 0, 1f))
        // The arrows shown or hidden change the lyrics' width: their size is fitted again
        lyricsArea.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol && or - ol > 0) { lastMeasuredW = 0; post { applyMeasuredSize(width, height) } }
        }
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
            val sidePad = dp(8)
            val verticalGap = (bodyHeight * .035f).toInt().coerceAtLeast(dp(8))
            lyricsArea.setPadding(sidePad, verticalGap, sidePad, verticalGap)
            fitW = bodyWidth - 2 * sidePad; fitH = bodyHeight - 2 * verticalGap; fitGap = verticalGap
            fitText()
            val activeTextSizePx = activeFontSp
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

    /** The text size from the room and the song (25/11): once per place and song, never line by line. */
    private fun fitText() {
        if (fitW <= 0 || fitH <= 0) return
        val count = visibleLyricsRows
        wrapLines = if (place.tall) 3 else 2
        val t = track
        val lyrics = t?.synced?.map { it.second } ?: t?.plain?.lines().orEmpty()
        // Margins: the current line's, two small ones for each other row; a little room left besides
        val margins = 2 * fitGap + (count - 1) * 2 * dp(2) + dp(6)
        activeFontSp = LyricsFit.size(fitW, fitH - margins, lyrics, wrapLines, count - 2, .6f, 0,
            minPx = dp(14).toFloat(), maxPx = fitH * .32f)
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
            refreshShift()
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
    private fun position(m: MediaHub.NowPlaying): Long = clock.lyricsAt(android.os.SystemClock.elapsedRealtime(), prefs.leadMs + shiftMs)

    /** The current song's kept shift, read again (a new song, everything cleared). */
    private fun refreshShift() {
        shiftMs = if (LyricsRepo.searchable(latest)) LyricsChoices.shiftMs(latest.artist, latest.title) else 0L
        paintShift()
    }

    /** An arrow or the count touched: [ms] is the song's shift from now on, kept for it. */
    private fun setShift(ms: Long) {
        val m = latest
        if (!LyricsRepo.searchable(m)) return
        LyricsChoices.setShift(m.artist, m.title, ms)
        shiftMs = LyricsChoices.shiftMs(m.artist, m.title)
        paintShift()
        val pos = position(m)
        drawLines(pos); drawKaraoke(pos)
        if (track?.synced != null && isActivelyDisplayed) startTicker()
    }

    /** The arrows: with synced lyrics only, when the setting allows them (and the card has the height for them). */
    private fun paintShift() {
        val on = track?.synced != null && prefs.shiftArrows
        val cardOn = on && (body.height == 0 || body.height >= dp(160))
        cardShift.paint(shiftMs)
        if ((cardShift.visibility == View.VISIBLE) != cardOn) cardShift.visibility = if (cardOn) View.VISIBLE else View.GONE
        karaokeShift?.let { k ->
            k.paint(shiftMs)
            if ((k.visibility == View.VISIBLE) != on) {
                k.visibility = if (on) View.VISIBLE else View.GONE
                // The lyrics kept in the middle, clear of the arrows on both sides
                karaokeBox?.let { b ->
                    val lp = b.layoutParams as FrameLayout.LayoutParams
                    lp.marginStart = if (on) dp(208) else 0; lp.marginEnd = lp.marginStart; b.layoutParams = lp
                }
            }
        }
    }

    private fun roundButton(on: Boolean = false) = CardKit.buttonBackground(activity, on).apply { cornerRadius = dp(60).toFloat() }

    /** The picker of the song's lyrics (LyricsPicker); the one chosen is shown at once. */
    private fun openPicker() {
        val a = hostActivity() ?: return
        val scope = liveScope ?: return
        val m = latest
        if (!LyricsRepo.searchable(m) || a.isFinishing) return
        val songKey = LyricsRepo.key(m.artist, m.title)
        LyricsPicker.open(a, scope, m, track?.id ?: 0L, onChosen = { t ->
            if (key == songKey) {
                requestJob?.cancel(); requestedKey = songKey
                track = t; fitText(); fitKaraoke()
                setStatus(when { t.instrumental -> activity.getString(R.string.ly_none); t.synced == null -> activity.getString(R.string.ly_plain); else -> "" })
                paintShift()
                val pos = position(latest)
                drawLines(pos); drawKaraoke(pos)
                if (t.synced != null && isActivelyDisplayed) startTicker()
            }
        }, onSearchAgain = { request(force = true, m = latest) })
    }

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
                track = cached; fitText(); fitKaraoke()
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
            // No network yet (just started): said, and the lookup below waits for it (27/11)
            if (!networkAvailable()) { setStatus(activity.getString(R.string.ly_offline)); drawLines(0) }
            if (!isActivelyDisplayed) return@launch
            val found = LyricsRepo.lookup(m.artist, m.title, m.durationMs, force)
            if (!isActivelyDisplayed || key != targetKey) return@launch
            track = found ?: cached; fitText(); fitKaraoke()
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
            isActivelyDisplayed = false; reel.run(false)
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
            setOnClickListener { closeKaraoke() }
        }
        val overlayToggle = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_lyrics_overlay); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(20), dp(20), dp(20), dp(20)); background = roundButton(LyricsOverlay.active)
            isSelected = LyricsOverlay.active
            imageTintList = android.content.res.ColorStateList.valueOf(if (isSelected) Themes.select(activity) else Themes.color(activity, R.color.text_value))
            contentDescription = activity.getString(R.string.lyrics_shortcut_short)
            setOnClickListener { toggleOverlay() }
        }
        val chooseKaraoke = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_lyrics_choose); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(22), dp(22), dp(22), dp(22)); background = roundButton()
            imageTintList = android.content.res.ColorStateList.valueOf(Themes.color(activity, R.color.text_value))
            contentDescription = activity.getString(R.string.ly_choose)
            setOnClickListener { openPicker() }
        }
        close.background = roundButton(); close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        header.addView(overlayToggle, LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(12) })
        header.addView(chooseKaraoke, LayoutParams(dp(76), dp(76)).apply { marginEnd = dp(12) })
        header.addView(close, LayoutParams(dp(76), dp(76)))
        header.setOnClickListener { closeKaraoke() }
        root.addView(header, LayoutParams(-1, -2))

        val content = FrameLayout(activity)
        val box = FrameLayout(activity)
        content.addView(box, FrameLayout.LayoutParams(-1, -1))
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
        box.addView(synced, FrameLayout.LayoutParams(-1, -1))
        synced.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) synced.post { fitKaraoke(); drawKaraoke(position(latest)) } }
        val reelView = LyricsReel(activity).apply { visibility = View.GONE; position = { position(latest) }; setPadding(dp(12), 0, dp(12), 0) }
        box.addView(reelView, FrameLayout.LayoutParams(-1, -1))
        karaokeReel = reelView
        val scroll = ScrollView(activity).apply { isFillViewport = true; visibility = View.GONE }
        val plain = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Themes.color(activity, R.color.text_value))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(dp(7).toFloat(), 1.12f); setPadding(dp(12), dp(24), dp(12), dp(32))
        }
        scroll.addView(plain, android.widget.FrameLayout.LayoutParams(-1, -2))
        box.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        // No lyrics (yet): what is going on, for which song, and "search again" when nothing was found (26/11)
        val empty = LinearLayout(activity).apply { orientation = VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        val emptyText = TextView(activity).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); typeface = Typeface.DEFAULT_BOLD
            setTextColor(Themes.color(activity, R.color.text_sub))
        }
        val emptyFor = TextView(activity).apply {
            gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setTextColor(Themes.color(activity, R.color.text_title))
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val emptyButton = app.mg4dash.ui.Rows.actionButton(activity, activity.getString(R.string.ly_search_again)) { request(force = true, m = latest) }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); minHeight = dp(76); setPadding(dp(40), 0, dp(40), 0)
        }
        empty.addView(emptyText, LayoutParams(-1, -2))
        empty.addView(emptyFor, LayoutParams(-1, -2).apply { topMargin = dp(14) })
        empty.addView(emptyButton, LayoutParams(-2, -2).apply { topMargin = dp(36) })
        content.addView(empty, FrameLayout.LayoutParams(-1, -1))
        val shiftColumn = LyricsShift(activity, big = true) { setShift(it) }.apply { visibility = View.GONE }
        content.addView(shiftColumn, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(8) })
        karaokeShift = shiftColumn; karaokeBox = box
        karaokeEmpty = empty; karaokeEmptyText = emptyText; karaokeEmptyFor = emptyFor; karaokeEmptyButton = emptyButton
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
                        karaokeTitle?.setTextSize(TypedValue.COMPLEX_UNIT_PX, dialogHeight * .042f)
                        karaokeArtist?.setTextSize(TypedValue.COMPLEX_UNIT_PX, dialogHeight * .030f)
                        karaokeGap = (dialogHeight * .012f).toInt()
                        karaokeRows.forEach { row ->
                            val lp = row.layoutParams as? LayoutParams
                            if (lp != null) { lp.topMargin = karaokeGap; lp.bottomMargin = karaokeGap; row.layoutParams = lp }
                        }
                        karaokeSynced?.post { fitKaraoke() }
                    }
                }
                paintShift()
                root.alpha = .5f; root.scaleX = .97f; root.scaleY = .97f
                root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
                drawKaraoke(position(latest))
            },
            onDismiss = {
                karaokeOpen = false; karaoke = null; karaokeRows = emptyList(); karaokeRoot = null; karaokeReel?.run(false); karaokeReel = null
                karaokeShift = null; karaokeBox = null; karaokeEmpty = null; karaokeEmptyText = null; karaokeEmptyFor = null; karaokeEmptyButton = null
                karaokeTitle = null; karaokeArtist = null
                karaokeOverlayButton = null
                karaokeSynced = null; karaokePlain = null; karaokePlainText = null; karaokeActiveIndex = -1
                paintOverlayButtons()
                isActivelyDisplayed = cardIsShown
            }
        )
    }

    private var karaokeGap = 0

    /** The karaoke's text size from its room and the song, as for the card. */
    private fun fitKaraoke() {
        val area = karaokeSynced ?: return
        val w = area.width; val h = area.height
        if (w <= 0 || h <= 0) return
        val t = track
        val lyrics = t?.synced?.map { it.second } ?: t?.plain?.lines().orEmpty()
        val others = karaokeRows.size - 2
        val px = LyricsFit.size(w - dp(24), h - karaokeRows.size * 2 * karaokeGap - dp(12), lyrics, 2, others, .58f, 0,
            minPx = dp(18).toFloat(), maxPx = h * .2f)
        karaokeRows.forEachIndexed { index, row ->
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, if (index == 3 || index == NEXT) px else px * .58f)
        }
        karaokePx = px
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
        paintShift()
        if (synced == null) {
            karaokeReel?.let { it.visibility = View.GONE; it.run(false) }
            val none = t?.plain == null
            karaokeSynced?.visibility = View.INVISIBLE
            karaokePlain?.visibility = if (!none) View.VISIBLE else View.GONE
            karaokePlainText?.let { pt ->
                pt.text = t?.plain.orEmpty()
                // Near the synced karaoke's current line (27/11: the fixed 22 sp was tiny on the full screen), scrolled
                val px = maxOf(karaokePx * PLAIN_SCALE, dp(30).toFloat())
                if (pt.textSize != px) pt.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
            }
            karaokeEmpty?.visibility = if (none) View.VISIBLE else View.GONE
            if (none) {
                val said = status.text.toString()
                karaokeEmptyText?.text = said
                val m = latest
                karaokeEmptyFor?.text = if (LyricsRepo.searchable(m)) activity.getString(R.string.ly_for, m.title, m.artist) else ""
                // "Search again" once a search ended without lyrics, not while one runs nor without a song
                val ended = LyricsRepo.searchable(m) && said.isNotBlank() && said != activity.getString(R.string.ly_searching) &&
                    said != activity.getString(R.string.ly_wifi_only)
                karaokeEmptyButton?.visibility = if (ended) View.VISIBLE else View.GONE
            }
            return
        }
        karaokeEmpty?.visibility = View.GONE
        // The rows keep their place (their size fits the text), the reel draws the lines and scrolls (26/11)
        karaokeSynced?.visibility = View.INVISIBLE
        karaokePlain?.visibility = View.GONE
        karaokeReel?.let { r ->
            r.visibility = View.VISIBLE
            r.setLines(synced)
            if (karaokePx > 0f) r.style(karaokePx, .58f, 2, Themes.color(activity, R.color.text_value), Themes.color(activity, R.color.text_sub), lyricsFace)
            r.run(karaokeOpen && latest.playing)
            r.invalidate()
        }
    }

    private fun drawLines(position: Long) {
        paintShift()
        val t = track
        val synced = t?.synced
        if (synced != null) {
            lines.forEach { it.visibility = View.GONE }
            reel.visibility = View.VISIBLE
            reel.setLines(synced)
            // The next line as big as the current one, to be read in advance (25/11)
            reel.style(activeFontSp, .60f, wrapLines, Themes.color(activity, R.color.text_value), Themes.color(activity, R.color.text_sub), lyricsFace)
            reel.run(isActivelyDisplayed && latest.playing)
            reel.invalidate()
            return
        }
        reel.visibility = View.GONE; reel.run(false)
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
            .stepper(activity.getString(R.string.ly_offset), activity.getString(R.string.ly_offset_sub), app.mg4dash.ui.Stepper(hostActivity() ?: return popup, -30, 30,
                label = { offsetLabel(it) }, typedTitle = activity.getString(R.string.ly_offset),
                parse = { t -> t.trim().removeSuffix("s").trim().replace(',', '.').toFloatOrNull()?.let { kotlin.math.round(it * 10).toInt() } },
                get = { prefs.offsetTenths }, set = { prefs.offsetTenths = it; drawLines(position(latest)); drawKaraoke(position(latest)) }))
            .toggle(activity.getString(R.string.ly_shift_arrows), activity.getString(R.string.ly_shift_arrows_sub), { prefs.shiftArrows }) {
                prefs.shiftArrows = it; paintShift()
            }
            .action(activity.getString(R.string.ly_titles), activity.getString(R.string.ly_titles_sub, LyricsChoices.count()),
                activity.getString(R.string.ly_titles_clear)) {
                LyricsChoices.clear(); refreshShift(); drawLines(position(latest)); drawKaraoke(position(latest))
            }
            .toggle(activity.getString(R.string.ly_overlay_previous), null, { prefs.overlayPrevious }) {
                prefs.overlayPrevious = it
                if (LyricsOverlay.active) LyricsOverlay.refreshPrevious()
            }
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

    /** "-0,4 s": the offset in the head unit's decimal style. */
    private fun offsetLabel(tenths: Int): String {
        val v = String.format(java.util.Locale.getDefault(), "%.1f", tenths / 10f)
        return (if (tenths > 0) "+" else "") + v + " s"
    }

    private fun cacheUsageText(): String {
        val info = lyricsCacheInfo
        return activity.getString(R.string.ly_cache_used, info.bytes / (1024f * 1024f), prefs.cacheMaxMb, info.tracks)
    }

}

/** The row of the next lyric line, under the current one (3), and its strength (25/11: too small and dark to read ahead). */
private const val NEXT = 4
private const val NEXT_ALPHA = .72f
