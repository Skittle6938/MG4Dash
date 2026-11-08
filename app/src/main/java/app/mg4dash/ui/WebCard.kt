package app.mg4dash.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.WebViewer

/**
 * "Web app" card of the map widget (#97), off by default: a web page chosen in the edit mode, e.g. a
 * Home Assistant dashboard. At rest, it is swiped like the other cards; a touch wakes it, it then takes the touches.
 * Loaded when shown, paused when hidden. Without an address, or if the page does not load, a message
 * says so; a touch on it loads the page again.
 * Car version (system uid, refused by WebView): the page is shown by MG4Dash Web, a small separate app
 * embedded in the card like an app ([WebViewer]), installed the first time the card is shown. Hidden, its page
 * goes on (sound, live values) without drawing. A touch on one of its fields opens MG4Dash's keyboard over
 * home, whose keys go to the page.
 */
class WebCard(private val activity: android.app.Activity) : FrameLayout(activity) {
    private val prefs = Prefs(context)
    private var web: android.webkit.WebView? = null
    private var loaded: String? = null
    /** Car version: MG4Dash Web embedded in the card, and the address it shows. */
    private var appView: android.view.ViewGroup? = null
    private var appUrl: String? = null
    private var installing = false
    /** Car version: MG4Dash's keyboard open for a field of the page, the field's kind, and what closes it. */
    private var keyboard: (() -> Unit)? = null
    private var keyboardKind: String? = null
    /** Where the keyboard goes over home (its left and right edges on screen), null for two thirds on the driver's side. */
    var keyboardSpan: ((View) -> IntArray?)? = null
    /** Car version: MG4Dash Web starts, or a field of its page is touched or left. */
    private val fields = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (appView == null) return
            when {
                i.getBooleanExtra("ready", false) -> WebViewer.shown(context, shownNow())
                i.getBooleanExtra("on", false) && shownNow() -> openKeyboard(i.getStringExtra("kind") ?: "text")
                else -> closeKeyboard()
            }
        }
    }
    private var listening = false
    private val message = TextView(context).apply {
        setTextColor(Themes.color(context, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        val p = (32 * resources.displayMetrics.density).toInt(); setPadding(p, p, p, p)
        setOnClickListener { loaded = null; show() }
    }

    init {
        setBackgroundColor(Themes.color(context, R.color.bg_top))
        addView(message, LayoutParams(-1, -1))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(): android.webkit.WebView? = web ?: try {
        android.webkit.WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mediaPlaybackRequiresUserGesture = true
            setBackgroundColor(Themes.color(context, R.color.bg_top))
            settings.setGeolocationEnabled(true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, true, false)
                }
            }
            webViewClient = object : android.webkit.WebViewClient() {
                // Links stay in the card
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = false
                override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (!request.isForMainFrame) return
                    Log.w(TAG, "page: ${error.description}")
                    showMessage(context.getString(R.string.wc_web_error))
                }
                override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                    if (message.visibility != View.VISIBLE || message.text.endsWith(context.getString(R.string.wc_web_loading))) message.visibility = View.GONE
                }
            }
        }.also { web = it; addView(it, 0, LayoutParams(-1, -1)) }
    } catch (e: Throwable) {
        // No WebView on this head unit (missing or disabled provider)
        Log.w(TAG, "WebView: ${e.message}"); null
    }

    /** "Loading…" under the site's name, which says which address of the list comes (#98). */
    private fun loading(url: String) = name(url) + "\n" + context.getString(R.string.wc_web_loading)

    private fun showMessage(t: String) { message.text = t; message.visibility = View.VISIBLE; message.bringToFront() }

    /** Shown: loads the address if it changed, otherwise resumes the page. */
    fun show() {
        val url = prefs.webUrl
        if (url.isBlank()) { web?.visibility = View.GONE; removeApp(); showMessage(context.getString(R.string.wc_web_none)); return }
        if (WebViewer.needed()) { showInApp(url); return }
        val w = webView() ?: run { showMessage(context.getString(R.string.wc_web_unsupported)); return }
        w.visibility = View.VISIBLE
        w.onResume()
        if (url != loaded) {
            loaded = url
            showMessage(loading(url))
            w.loadUrl(url)
        }
    }

    /** Car version: the page in MG4Dash Web, installed first if needed; a new address starts a new page. */
    private fun showInApp(url: String) {
        if (!WebViewer.ready(context)) {
            if (!WebViewer.bundled(context)) { showMessage(context.getString(R.string.wc_web_unsupported)); return }
            showMessage(context.getString(R.string.wc_web_installing))
            if (installing) return
            installing = true
            WebViewer.install(context) { ok ->
                installing = false
                when {
                    !ok -> showMessage(context.getString(R.string.wc_web_install_failed))
                    isAttachedToWindow && visibility == View.VISIBLE -> show()
                }
            }
            return
        }
        if (appView != null && appUrl == url) { WebViewer.shown(context, true); return }
        WebViewer.grantLocation(context)
        removeApp()
        showMessage(loading(url))
        var created: View? = null
        val v = EmbeddedApp.create(activity, WebViewer.component,
            WebViewer.intent(context, url, Themes.color(context, R.color.bg_top), Themes.color(context, R.color.text_sub)),
            stopOnRelease = true, newSurfaceOnResize = true) {
            created?.layoutParams = LayoutParams(-1, -1)
            message.visibility = View.GONE
        } ?: run { showMessage(context.getString(R.string.wc_web_unsupported)); return }
        // Created at the screen's width, then brought back to the card once started, as the map's app: Android 9
        // gives the layers of a virtual display twice its largest side at creation, and never more; created at
        // the card's size, the page was cut off in full screen (#98)
        addView(v, 0, LayoutParams(resources.displayMetrics.widthPixels, -1))
        created = v
        appView = v; appUrl = url
    }

    /** Full screen and back (#98): the page's layer follows the card's new place. */
    fun moved() { appView?.let { EmbeddedApp.moved(it) } }

    /** The embedded page goes with its virtual display (MG4Dash Web is stopped first). */
    private fun removeApp() {
        closeKeyboard()
        appView?.let { removeView(it) }
        appView = null; appUrl = null
    }

    /**
     * The card changes place (#97): MG4Dash Web goes with the old frame's virtual display, so it is
     * stopped now and started again where the card is shown next.
     */
    fun reset() { removeApp(); loaded = null }

    /** Hidden: the page stops its timers and videos; in MG4Dash Web, it goes on without drawing (sound kept). */
    fun pause() {
        web?.onPause()
        closeKeyboard()
        if (appView != null) WebViewer.shown(context, false)
    }

    /**
     * Home paused, which happens as soon as MG4Dash Web's page is touched (it takes the focus): only the page in
     * MG4Dash pauses. MG4Dash Web's one stays in sight and goes on, with the keyboard open for it.
     */
    fun pauseHome() { web?.onPause() }

    private fun shownNow() = visibility == View.VISIBLE && isShown

    /** A field of the page is touched: MG4Dash's keyboard over home, digits for a number, numbers and symbols first for a phone. */
    private fun openKeyboard(kind: String) {
        if (keyboard != null && keyboardKind == kind) return
        closeKeyboard()
        keyboardKind = kind
        keyboard = CarKeyboard.over(this, numeric = kind == "number", symbols = kind == "tel", span = { keyboardSpan?.invoke(this) },
            // A touch on the page (another field, the caret) keeps it open
            keepOn = { x, y -> IntArray(2).also { getLocationOnScreen(it) }.let { x >= it[0] && x < it[0] + width && y >= it[1] && y < it[1] + height } },
            onText = { WebViewer.key(context, text = it) },
            onBackspace = { WebViewer.key(context, key = "backspace") },
            onClear = { WebViewer.key(context, key = "clear") },
            onDone = { WebViewer.key(context, key = "done") },
            onClosed = { keyboard = null })
    }

    fun closeKeyboard() { keyboard?.invoke(); keyboard = null }

    /** Its keyboard open: the page stays awake while typing (#98). */
    val typing get() = keyboard != null

    /** The page's previous one (#98): in MG4Dash Web, its video first if one fills the page. */
    fun back() {
        if (appView != null) { WebViewer.key(context, key = "back"); return }
        web?.let { if (it.canGoBack()) it.goBack() }
    }

    /**
     * Long press on the card (#98): a small menu with Back (the page's previous one) and the addresses
     * entered in the edit mode, a touch on one showing it at once (the one shown: from its start again). [onMove]: the detached window's
     * "Move / resize", under Back.
     */
    fun menu(onMove: (() -> Unit)? = null) {
        val shown = prefs.webUrl
        val rows = mutableListOf(CardMenu.Row("←", context.getString(R.string.wc_web_back), null, false) { back() })
        if (onMove != null) rows += CardMenu.Row("✥", context.getString(R.string.wc_web_move), null, false) { onMove() }
        prefs.webUrls.forEach { url ->
            rows += CardMenu.Row(if (url == shown) "◉" else "○", name(url), url, url == shown) {
                // The address shown touched again: its page starts again from that address (#98)
                if (url == shown) { Log.i(TAG, "menu: ${name(url)} again"); reset(); show(); return@Row }
                prefs.webUrl = url
                Log.i(TAG, "menu: ${name(url)}")
                show()
            }
        }
        // Another address, entered as in the edit mode, added to the list and shown at once
        rows += CardMenu.Row("＋", context.getString(R.string.wc_other_address), null, false) {
            CardsEditor.askUrl(activity, "") { url ->
                if (url.isEmpty()) return@askUrl
                prefs.webUrls = prefs.webUrls.filter { it != url } + url
                prefs.webUrl = url
                Log.i(TAG, "menu: ${name(url)} added")
                show()
            }
        }
        CardMenu.show(activity, rows)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (WebViewer.needed() && !listening) {
            listening = true
            context.registerReceiver(fields, android.content.IntentFilter(WebViewer.FIELD), WebViewer.TALK, null)
        }
    }

    override fun onDetachedFromWindow() {
        closeKeyboard()
        if (listening) { listening = false; try { context.unregisterReceiver(fields) } catch (_: Exception) {} }
        super.onDetachedFromWindow()
    }

    /** Full screen and back: the keyboard was placed for the old size. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw != 0) closeKeyboard()
    }

    companion object {
        private const val TAG = "WebCard"

        /** A short name for an address: its site without "www." (with its path's first part for Google Maps and the like). */
        fun name(url: String): String {
            val u = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
            val host = u?.host?.removePrefix("www.")?.removePrefix("m.") ?: return url
            val first = u.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() && it.length <= 12 && host.startsWith("google.") }
            return if (first != null) "$host/$first" else host
        }
    }
}
