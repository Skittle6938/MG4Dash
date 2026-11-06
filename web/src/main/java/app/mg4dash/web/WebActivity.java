package app.mg4dash.web;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

    private WebView web;
    private FrameLayout root;
    /** Full screen video of the page, over it (#98). */
    private View video;
    private WebChromeClient.CustomViewCallback videoDone;
    private TextView message;
    private String loaded;
    private boolean failed;
    /** Size of the virtual display the page was last drawn for (dp), to see the card go full screen. */
    private int shownW, shownH;

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
                    return;
                }
                resized(config);
            }
        };
        root.setBackgroundColor(bg);

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
        });
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
            public void onPageFinished(WebView view, String url) {
                if (!failed) message.setVisibility(View.GONE);
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
        Configuration c = getResources().getConfiguration();
        shownW = c.screenWidthDp; shownH = c.screenHeightDp;
        load(i.getStringExtra(EXTRA_URL));
    }

    /**
     * Card in full screen and back (#98): the virtual display, the window and the WebView take the new size,
     * but Chromium 66 kept drawing its last frame at the old one, frozen, the rest black. Taken out of the
     * view tree and put back, it draws again at the new size, without reloading the page (the video goes on).
     */
    private void resized(Configuration config) {
        if (web == null || (config.screenWidthDp == shownW && config.screenHeightDp == shownH)) return;
        Log.i(TAG, "size " + shownW + "x" + shownH + " -> " + config.screenWidthDp + "x" + config.screenHeightDp + ": WebView reattached");
        shownW = config.screenWidthDp; shownH = config.screenHeightDp;
        root.post(() -> {
            if (web == null) return;
            int at = root.indexOfChild(web);
            if (at < 0) return;
            root.removeViewAt(at);
            root.addView(web, at, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.requestLayout();
            web.invalidate();
        });
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
        if (web != null) web.onResume();
        immersive();
    }

    @Override
    protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
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
