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
 * Home Assistant dashboard. The page takes the touches; the widget's button changes card, as over an app.
 * Loaded when shown, paused when hidden. Without an address, or if the page does not load, a message
 * says so; a touch on it loads the page again.
 * Car version (system uid, refused by WebView): the page is shown by MG4Dash Web, a small separate app
 * embedded in the card like an app ([WebViewer]), installed the first time the card is shown.
 */
class WebCard(private val activity: android.app.Activity) : FrameLayout(activity) {
    private val prefs = Prefs(context)
    private var web: android.webkit.WebView? = null
    private var loaded: String? = null
    /** Car version: MG4Dash Web embedded in the card, and the address it shows. */
    private var appView: android.view.ViewGroup? = null
    private var appUrl: String? = null
    private var installing = false
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
            webViewClient = object : android.webkit.WebViewClient() {
                // Links stay in the card
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) = false
                override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (!request.isForMainFrame) return
                    Log.w(TAG, "page: ${error.description}")
                    showMessage(context.getString(R.string.wc_web_error))
                }
                override fun onPageFinished(view: android.webkit.WebView, url: String?) {
                    if (message.visibility != View.VISIBLE || message.text == context.getString(R.string.wc_web_loading)) message.visibility = View.GONE
                }
            }
        }.also { web = it; addView(it, 0, LayoutParams(-1, -1)) }
    } catch (e: Throwable) {
        // No WebView on this head unit (missing or disabled provider)
        Log.w(TAG, "WebView: ${e.message}"); null
    }

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
            showMessage(context.getString(R.string.wc_web_loading))
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
        if (appView != null && appUrl == url) return
        removeApp()
        showMessage(context.getString(R.string.wc_web_loading))
        val v = EmbeddedApp.create(activity, WebViewer.component,
            WebViewer.intent(context, url, Themes.color(context, R.color.bg_top), Themes.color(context, R.color.text_sub)),
            stopOnRelease = true) { message.visibility = View.GONE }
            ?: run { showMessage(context.getString(R.string.wc_web_unsupported)); return }
        addView(v, 0, LayoutParams(-1, -1))
        appView = v; appUrl = url
    }

    /** The embedded page goes with its virtual display (MG4Dash Web is stopped first). */
    private fun removeApp() {
        appView?.let { removeView(it) }
        appView = null; appUrl = null
    }

    /**
     * The card changes place (#97): MG4Dash Web goes with the old frame's virtual display, so it is
     * stopped now and started again where the card is shown next.
     */
    fun reset() { removeApp(); loaded = null }

    /** Hidden: the page stops its timers and videos. */
    fun pause() { web?.onPause() }

    companion object { private const val TAG = "WebCard" }
}
