package app.mg4dash.web;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Page of MG4Dash's "Web app" card (#97). MG4Dash starts this activity in the virtual display of its
 * map widget (ActivityView), with the address, its colours, its translated messages and its full
 * screen choice as extras: the page then takes the touches as any app shown in the card.
 * Moved to the main screen (MG4Dash's home rebuilt, its virtual display gone), it closes so as not to
 * cover the home screen; MG4Dash starts it again in the new card.
 * MG4Dash's keyboard (#98): the page says when one of its fields is touched or left, MG4Dash then shows its
 * keyboard over home and sends each key, typed here in the field. Android's keyboard no longer opens.
 * The card hidden, the page goes on (sound, live values) without drawing nor decoding the image of its videos.
 * [PageActivity]: the same page in full screen, with a close button (compatibility report, #95: the
 * car's browser refuses pages on some head units).
 */
public class WebActivity extends Activity {
    private static final String TAG = "MG4DashWeb";
    static final String EXTRA_URL = "url";
    static final String EXTRA_BG = "bg";
    static final String EXTRA_FG = "fg";
    static final String EXTRA_LOADING = "loading";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_IMMERSIVE = "immersive";
    static final String EXTRA_CLOSE = "close";
    /** MG4Dash's package when its keyboard types in the page's fields, otherwise absent (Android's keyboard). */
    static final String EXTRA_KEYBOARD = "keyboard";
    /** With MG4Dash (WebViewer): only holders of this permission, MG4Dash Web and the system, send or receive. */
    private static final String TALK = "app.mg4dash.web.permission.TALK";
    private static final String FIELD = "app.mg4dash.web.FIELD";
    private static final String KEY = "app.mg4dash.web.KEY";
    private static final String SHOWN = "app.mg4dash.web.SHOWN";

    private WebView web;
    private FrameLayout root;
    /** Full screen video of the page, over it (#98). */
    private View video;
    private WebChromeClient.CustomViewCallback videoDone;
    private TextView message;
    private String loaded;
    private boolean failed;
    /** Package of MG4Dash, whose keyboard types in the fields; null: Android's keyboard. */
    private String keyboardFor;
    /** MG4Dash was told that a field is touched (its keyboard is open). */
    private boolean fieldOpen;
    private boolean listening;
    /** The WebView is paused for the hidden card (MG4Dash said so). */
    private boolean pausedHidden;
    private boolean started;
    /** The page's sound as a player of the head unit (#98), in the card. */
    private WebMedia media;

    /** From MG4Dash: the keys of its keyboard, and whether the card is shown. */
    private final BroadcastReceiver fromCard = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (web == null) return;
            if (SHOWN.equals(i.getAction())) { shown(i.getBooleanExtra("shown", true)); return; }
            if (keyboardFor == null) return;
            String text = i.getStringExtra("text");
            if (text != null) type(text);
            String key = i.getStringExtra("key");
            if ("backspace".equals(key)) press(KeyEvent.KEYCODE_DEL);
            else if ("done".equals(key)) press(KeyEvent.KEYCODE_ENTER);
            else if ("clear".equals(key)) web.evaluateJavascript("window.__mg4dashClear && __mg4dashClear()", null);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (inCardOnly() && onMainScreen()) { finish(); return; }
        Intent i = getIntent();
        int bg = i.getIntExtra(EXTRA_BG, Color.BLACK);
        root = new FrameLayout(this) {
            @Override
            protected void onConfigurationChanged(Configuration config) {
                // A move to another screen comes as a configuration change (View.onMovedToDisplay is hidden)
                super.onConfigurationChanged(config);
                if (inCardOnly() && onMainScreen()) {
                    Log.i(TAG, "moved to the main screen: closed");
                    finish();
                }
            }
        };
        root.setBackgroundColor(bg);

        softwareVideo();
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(true);
        web.setBackgroundColor(bg);
        web.setWebChromeClient(new WebChromeClient() {
            // The car's position, for maps and dashboards (#98)
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            // Video in full screen (YouTube…): without its view, only the sound remained (#98)
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                hideVideo();
                video = view;
                videoDone = callback;
                view.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() { hideVideo(); }

            // As early as possible in the page, before its player chooses a format
            @Override
            public void onProgressChanged(WebView view, int progress) { pageScripts(); }
        });
        keyboardFor = inCardOnly() ? i.getStringExtra(EXTRA_KEYBOARD) : null;
        if (keyboardFor != null) {
            // Android's keyboard never opens over the page: MG4Dash's types in it
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
            web.addJavascriptInterface(new Fields(), "MG4DashFields");
        }
        // In the card: MG4Dash says when it is shown or hidden, whether its keyboard is used or not
        if (inCardOnly()) {
            media = new WebMedia(this, web);
            web.addJavascriptInterface(media.bridge(), "MG4DashMedia");
            IntentFilter f = new IntentFilter(KEY);
            f.addAction(SHOWN);
            registerReceiver(fromCard, f, TALK, null);
            listening = true;
            tell(new Intent(FIELD).putExtra("ready", true));
        }
        web.setWebViewClient(new WebViewClient() {
            // Web links stay in the card; other schemes (intent:, tel:, market:…) are ignored
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                Log.w(TAG, "page: " + error.getDescription());
                failed = true;
                showMessage(text(EXTRA_ERROR, "The page did not load. Touch to try again."));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                field(false, "text");
            }

            // The script that watches the fields, as soon as the page shows, and again at its end
            @Override
            public void onPageCommitVisible(WebView view, String url) { pageScripts(); watchFields(); }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!failed) message.setVisibility(View.GONE);
                pageScripts();
                watchFields();
            }
        });

        message = new TextView(this);
        message.setTextColor(i.getIntExtra(EXTRA_FG, Color.LTGRAY));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        message.setGravity(Gravity.CENTER);
        message.setBackgroundColor(bg);
        int p = (int) (32 * getResources().getDisplayMetrics().density);
        message.setPadding(p, p, p, p);
        message.setOnClickListener(v -> { if (failed) { String u = loaded; loaded = null; load(u); } });

        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(message, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!inCardOnly()) addClose(root, bg, i.getIntExtra(EXTRA_FG, Color.LTGRAY));
        setContentView(root);
        immersive();
        load(i.getStringExtra(EXTRA_URL));
    }

    /**
     * Watches the page's fields (main frame, shadow DOM included): a touch on one, or the focus moved to
     * another one, tells MG4Dash; leaving them too. A window that loses the focus (a key of MG4Dash's
     * keyboard) keeps its field: nothing said then. Also types what Android's virtual keyboard map does not
     * have (accents, €) and clears a field.
     */
    private static final String FIELDS_JS = "(function () {"
        + "if (window.__mg4dashType || !window.MG4DashFields) return;"
        + "var TEXT = /^(|text|search|email|url|tel|password|number)$/;"
        + "function editable(e) {"
        + "  if (!e || e.disabled || e.readOnly) return false;"
        + "  if (e.isContentEditable || e.tagName === 'TEXTAREA') return true;"
        + "  return e.tagName === 'INPUT' && TEXT.test((e.getAttribute('type') || '').toLowerCase());"
        + "}"
        + "function active() {"
        + "  var a = document.activeElement;"
        + "  while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;"
        + "  return a;"
        + "}"
        + "function kind(e) {"
        + "  var m = (e.getAttribute('inputmode') || '').toLowerCase();"
        + "  if (e.type === 'number' || m === 'numeric' || m === 'decimal') return 'number';"
        + "  return e.type === 'tel' || m === 'tel' ? 'tel' : 'text';"
        + "}"
        + "var tapped = 0;"
        + "function show() { var e = active(); if (editable(e)) MG4DashFields.field(true, kind(e)); }"
        // Only the driver's touches: a click by the page's script (a search sent) does not open it again
        + "document.addEventListener('pointerdown', function (ev) { if (ev.isTrusted) tapped = Date.now(); }, true);"
        + "document.addEventListener('click', function (ev) { if (ev.isTrusted) setTimeout(show, 0); }, true);"
        + "document.addEventListener('focusin', function () { if (Date.now() - tapped < 1000) show(); }, true);"
        + "document.addEventListener('focusout', function () {"
        + "  setTimeout(function () { if (!editable(active())) MG4DashFields.field(false, 'text'); }, 100);"
        + "}, true);"
        + "function value(e, v) {"
        + "  var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value');"
        + "  if (d && d.set) d.set.call(e, v); else e.value = v;"
        + "}"
        + "function changed(e, type, data) {"
        + "  e.dispatchEvent(new InputEvent('input', { bubbles: true, composed: true, inputType: type, data: data }));"
        + "}"
        + "window.__mg4dashType = function (s) {"
        + "  var e = active();"
        + "  if (!editable(e)) return;"
        + "  if (e.isContentEditable) { document.execCommand('insertText', false, s); return; }"
        + "  var at = null;"
        + "  try { at = e.selectionStart; } catch (x) {}"
        + "  if (typeof at === 'number') e.setRangeText(s, at, e.selectionEnd, 'end'); else value(e, e.value + s);"
        + "  changed(e, 'insertText', s);"
        + "};"
        + "window.__mg4dashClear = function () {"
        + "  var e = active();"
        + "  if (!editable(e)) return;"
        + "  if (e.isContentEditable) { document.execCommand('selectAll'); document.execCommand('delete'); return; }"
        + "  value(e, '');"
        + "  changed(e, 'deleteContentBackward', null);"
        + "};"
        + "})();";

    /**
     * Videos without image (#98): on the car, the hardware decoders (MTK, VP9 as H.264) give Chromium 66 no
     * picture, in the card as on the main screen, the sound alone playing. Chromium is told before its start to
     * decode the videos itself: VP9 by libvpx shows. It has no H.264 of its own on Android, so the pages are
     * told that H.264 (and HEVC, AV1) cannot be played: YouTube and others send VP9.
     */
    private static boolean softVideo;

    private static void softwareVideo() {
        if (softVideo) return;
        try {
            // Makes WebView's provider without starting Chromium: its command line is still Java's, copied at the start
            ClassLoader cl = WebView.getWebViewClassLoader();
            Class<?> cmd = Class.forName("org.chromium.base.CommandLine", true, cl);
            Object line = cmd.getMethod("getInstance").invoke(null);
            java.lang.reflect.Method add = cmd.getMethod("appendSwitch", String.class);
            add.invoke(line, "disable-accelerated-video-decode");
            softVideo = true;
            Log.i(TAG, "videos decoded by Chromium");
            // The card hidden, the paused WebView keeps playing (sound), Chromium dropping the image itself
            try {
                add.invoke(line, "disable-media-suspend");
            } catch (Throwable e) {
                Log.w(TAG, "media suspend: " + e);
            }
        } catch (Throwable e) {
            Log.w(TAG, "videos still by the hardware decoders: " + e);
        }
    }

    /**
     * Also a highest quality, for the processor: 720p, from window.__mg4dashMaxH, for the "width=…; height=…"
     * asked by YouTube and mediaCapabilities. The same in the card as in full screen: the player keeps the formats
     * it was told of at its start, so a video started small and then enlarged stayed in 144p, blurred.
     */
    private static final String NO_HW_ONLY_JS = "(function () {"
        + "if (window.__mg4dashSoftVideo) return;"
        + "window.__mg4dashSoftVideo = true;"
        + "var no = /avc[13]|hev1|hvc1|mp4v|av01/i;"
        + "var big = function (w, h) { w = +w || 0; h = +h || 0; return w > 0 && h > 0 && Math.min(w, h) > (window.__mg4dashMaxH || 480); };"
        + "var dim = function (t, k) { var m = new RegExp(k + '\\\\s*=\\\\s*(\\\\d+)', 'i').exec(t); return m ? m[1] : 0; };"
        + "var refused = function (t) { t = String(t || ''); return no.test(t) || big(dim(t, 'width'), dim(t, 'height')); };"
        + "if (window.MediaSource && MediaSource.isTypeSupported) {"
        + "  var is = MediaSource.isTypeSupported.bind(MediaSource);"
        + "  MediaSource.isTypeSupported = function (t) { return refused(t) ? false : is(t); };"
        + "}"
        + "var can = HTMLMediaElement.prototype.canPlayType;"
        + "HTMLMediaElement.prototype.canPlayType = function (t) { return no.test(String(t || '')) ? '' : can.call(this, t); };"
        + "var mc = navigator.mediaCapabilities;"
        + "if (mc && mc.decodingInfo) {"
        + "  var info = mc.decodingInfo.bind(mc);"
        + "  mc.decodingInfo = function (c) {"
        + "    var v = c && c.video;"
        + "    if (v && (no.test(v.contentType || '') || big(v.width, v.height)))"
        + "      return Promise.resolve({ supported: false, smooth: false, powerEfficient: false });"
        + "    return info(c);"
        + "  };"
        + "}"
        + "})();";

    /**
     * The card hidden, the WebView is paused: Chromium, told the page is hidden, stops decoding the videos' image
     * itself after a few seconds, the sound going on (no media suspend). The page is still told it is seen:
     * YouTube's mobile site pauses once hidden, and dashboards stop their live values. (A page's videoTracks,
     * to switch the image off, do not come with --enable-blink-features=AudioVideoTracks there.)
     */
    private static final String SEEN_JS = "(function () {"
        + "if (window.__mg4dashSeen) return;"
        + "window.__mg4dashSeen = true;"
        + "var seen = function (name, value) {"
        + "  try { Object.defineProperty(Document.prototype, name, { configurable: true, get: function () { return value; } }); } catch (e) {}"
        + "};"
        + "seen('hidden', false); seen('webkitHidden', false);"
        + "seen('visibilityState', 'visible'); seen('webkitVisibilityState', 'visible');"
        + "var stop = function (e) { e.stopImmediatePropagation(); };"
        + "window.addEventListener('visibilitychange', stop, true);"
        + "window.addEventListener('webkitvisibilitychange', stop, true);"
        + "})();";

    /** Out of sight (MG4Dash no longer shown, another app over it): the page's sound stops, as before. */
    private static final String PAUSE_JS = "document.querySelectorAll('video, audio').forEach(function (m) { try { m.pause(); } catch (e) {} });";

    /** Scripts of each page, as early as it allows, before its player chooses a format. */
    private void pageScripts() {
        if (web == null) return;
        // Only when Chromium decodes the videos itself: otherwise H.264 at least gives the sound
        if (softVideo) web.evaluateJavascript("window.__mg4dashMaxH = " + MAX_HEIGHT + ";" + NO_HW_ONLY_JS + YT_QUALITY_JS, null);
        if (softVideo && inCardOnly()) web.evaluateJavascript(SEEN_JS, null);
        if (media != null) web.evaluateJavascript(WebMedia.JS, null);
    }

    private static final int MAX_HEIGHT = 720;
    /** YouTube's quality chosen at the start: 480p, smooth for the processor; 720p can be chosen in its menu. */
    private static final int START_HEIGHT = 480;

    /**
     * YouTube: its quality chosen once at 480p (its own setting, kept by the page), unless the driver chose
     * another: in auto, a small player started at 144p and did not come up once enlarged.
     */
    private static final String YT_QUALITY_JS = "(function () {"
        + "if (!/(^|\\.)youtube\\.com$/.test(location.hostname)) return;"
        + "try {"
        + "  var k = 'yt-player-quality', now = Date.now(), v = localStorage.getItem(k);"
        + "  if (v) { var o = JSON.parse(v); if (o && o.expiration > now) return; }"
        + "  localStorage.setItem(k, JSON.stringify({ data: JSON.stringify({ quality: " + START_HEIGHT + ", previousQuality: 144 }),"
        + "    expiration: now + 30 * 24 * 3600 * 1000, creation: now }));"
        + "} catch (e) {}"
        + "})();";

    /** From MG4Dash: hidden, the page goes on (sound, live values) without drawing nor decoding the videos' image. */
    private void shown(boolean on) {
        web.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        if (video != null) video.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        // Paused only when Chromium no longer suspends the media (switch taken with the videos' decoding)
        if (!softVideo || on == !pausedHidden) return;
        pausedHidden = !on;
        if (!started) return;
        if (on) web.onResume(); else web.onPause();
        Log.i(TAG, on ? "card shown: WebView resumed" : "card hidden: WebView paused");
    }

    private void watchFields() {
        if (keyboardFor != null && web != null) web.evaluateJavascript(FIELDS_JS, null);
    }

    /** Called by the page's script, on Chromium's thread. */
    private final class Fields {
        @JavascriptInterface
        public void field(boolean on, String kind) { runOnUiThread(() -> WebActivity.this.field(on, kind)); }
    }

    /**
     * A field touched (MG4Dash opens its keyboard: [kind] "number" for digits only, "tel" for numbers and
     * symbols first, otherwise "text") or left (MG4Dash closes it).
     */
    private void field(boolean on, String kind) {
        if (keyboardFor == null || (!on && !fieldOpen)) return;
        fieldOpen = on;
        tell(new Intent(FIELD).putExtra("on", on).putExtra("kind", kind));
    }

    private void tell(Intent i) {
        try {
            sendBroadcast(i.setPackage(keyboardFor).addFlags(Intent.FLAG_RECEIVER_FOREGROUND), TALK);
        } catch (Exception e) { Log.w(TAG, "to MG4Dash: " + e.getMessage()); }
    }

    /**
     * A key of MG4Dash's keyboard, as from a hardware keyboard: the page gets its usual events and the
     * browser types it. What the virtual keyboard map does not have goes through the page's script.
     */
    private void type(String text) {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) {
            web.evaluateJavascript("window.__mg4dashType && __mg4dashType(" + org.json.JSONObject.quote(text) + ")", null);
            return;
        }
        for (KeyEvent e : events) web.dispatchKeyEvent(e);
    }

    private void press(int code) {
        long now = android.os.SystemClock.uptimeMillis();
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (web != null) load(intent.getStringExtra(EXTRA_URL));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inCardOnly() && onMainScreen()) { finish(); return; }
        immersive();
    }

    /**
     * Paused only (a touch on MG4Dash's home takes the focus back), the page stays in sight and goes on:
     * it only pauses once no longer seen.
     */
    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (web != null && !pausedHidden) web.onResume();
    }

    @Override
    protected void onStop() {
        started = false;
        if (web != null) {
            // Media are no longer suspended by Chromium (for the hidden card): out of sight, they stop here
            if (softVideo && inCardOnly()) web.evaluateJavascript(PAUSE_JS, null);
            web.onPause();
        }
        field(false, "text");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (listening) {
            listening = false;
            try { unregisterReceiver(fromCard); } catch (Exception ignored) {}
        }
        if (media != null) { media.release(); media = null; }
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void hideVideo() {
        if (video == null) return;
        root.removeView(video);
        video = null;
        if (videoDone != null) videoDone.onCustomViewHidden();
        videoDone = null;
    }

    /** Back: the full screen video, the previous page; the full screen page then closes (in the card, it stays). */
    @Override
    public void onBackPressed() {
        if (video != null) { hideVideo(); return; }
        if (web != null && web.canGoBack()) web.goBack();
        else if (!inCardOnly()) finish();
    }

    /** Shown in MG4Dash's card only (closes on the main screen); [PageActivity] is the full screen page. */
    boolean inCardOnly() { return true; }

    /** Round close button at the top right, over the page. */
    private void addClose(FrameLayout root, int bg, int fg) {
        float d = getResources().getDisplayMetrics().density;
        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(fg);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f);
        close.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable round = new android.graphics.drawable.GradientDrawable();
        round.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        round.setColor((bg & 0x00FFFFFF) | 0xE0000000);
        round.setStroke((int) (2 * d), (fg & 0x00FFFFFF) | 0x60000000);
        close.setBackground(round);
        String label = getIntent().getStringExtra(EXTRA_CLOSE);
        close.setContentDescription(label != null ? label : "Close");
        close.setOnClickListener(v -> finish());
        int size = (int) (72 * d), margin = (int) (16 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.topMargin = margin;
        lp.rightMargin = margin;
        root.addView(close, lp);
    }

    private void load(String url) {
        if (url == null || url.isEmpty()) return;
        if (url.equals(loaded) && !failed) return;
        loaded = url;
        failed = false;
        showMessage(text(EXTRA_LOADING, "Loading…"));
        web.loadUrl(url);
    }

    private void showMessage(String t) {
        message.setText(t);
        message.setVisibility(View.VISIBLE);
    }

    private String text(String extra, String fallback) {
        String t = getIntent().getStringExtra(extra);
        return t != null ? t : fallback;
    }

    /**
     * The page has the touches, so the focus: as MG4Dash, it asks for immersive mode when MG4Dash hides
     * the SAIC bars, otherwise they came back over home at the first touch.
     */
    @SuppressWarnings("deprecation")
    private void immersive() {
        if (!getIntent().getBooleanExtra(EXTRA_IMMERSIVE, false)) return;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    /** Shown on the main screen instead of MG4Dash's card. */
    @SuppressWarnings("deprecation")
    private boolean onMainScreen() {
        Display d = getWindowManager().getDefaultDisplay();
        return d != null && d.getDisplayId() == Display.DEFAULT_DISPLAY;
    }
}
