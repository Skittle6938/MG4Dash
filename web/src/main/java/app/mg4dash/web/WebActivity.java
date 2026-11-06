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
 */
public class WebActivity extends Activity {
    private static final String TAG = "MG4DashWeb";
    static final String EXTRA_URL = "url";
    static final String EXTRA_BG = "bg";
    static final String EXTRA_FG = "fg";
    static final String EXTRA_LOADING = "loading";
    static final String EXTRA_ERROR = "error";
    static final String EXTRA_IMMERSIVE = "immersive";

    private WebView web;
    private TextView message;
    private String loaded;
    private boolean failed;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (onMainScreen()) { finish(); return; }
        Intent i = getIntent();
        int bg = i.getIntExtra(EXTRA_BG, Color.BLACK);
        FrameLayout root = new FrameLayout(this) {
            @Override
            public void onMovedToDisplay(int displayId, Configuration config) {
                super.onMovedToDisplay(displayId, config);
                if (displayId == Display.DEFAULT_DISPLAY) {
                    Log.i(TAG, "moved to the main screen: closed");
                    finish();
                }
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
        web.setBackgroundColor(bg);
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
        setContentView(root);
        immersive();
        load(i.getStringExtra(EXTRA_URL));
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
        if (onMainScreen()) { finish(); return; }
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
