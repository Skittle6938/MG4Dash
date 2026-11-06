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

/**
 * "Web app" card of the map widget (#97), off by default: a web page chosen in the edit mode, e.g. a
 * Home Assistant dashboard. The page takes the touches; the widget's button changes card, as over an app.
 * Loaded when shown, paused when hidden. Without an address, or if the page does not load, a message
 * says so; a touch on it loads the page again.
 */
class WebCard(context: Context) : FrameLayout(context) {
    private val prefs = Prefs(context)
    private var web: android.webkit.WebView? = null
    private var loaded: String? = null
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
        if (url.isBlank()) { web?.visibility = View.GONE; showMessage(context.getString(R.string.wc_web_none)); return }
        val w = webView() ?: run { showMessage(context.getString(R.string.wc_web_unsupported)); return }
        w.visibility = View.VISIBLE
        w.onResume()
        if (url != loaded) {
            loaded = url
            showMessage(context.getString(R.string.wc_web_loading))
            w.loadUrl(url)
        }
    }

    /** Hidden: the page stops its timers and videos. */
    fun pause() { web?.onPause() }

    companion object { private const val TAG = "WebCard" }
}
