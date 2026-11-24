package app.mg4dash.rescue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Updating MG4Dash…" (#132): shown while the rescue recompiles MG4Dash after an update, in place of Android's black
 * "Phone is starting" screen (MG4Dash, the home, is stopped meanwhile). A home of its own, enabled only for that time
 * ([RescueService]), below MG4Dash's homes and above Android's waiting one. In MG4Dash's theme colours, published by
 * MG4Dash ("mg4dash_rescue_colors"); it closes once MG4Dash is back in front, or after 3 minutes whatever happens.
 */
public class UpdatingActivity extends Activity {
    static final String COLORS_KEY = "mg4dash_rescue_colors";
    private static final long MAX_MS = 3 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** bgTop, bgBottom, text, sub, accent: MG4Dash's published theme, or its default one (Night, blue). */
    static int[] colors(String published) {
        int[] c = { 0xFF070C1B, 0xFF0C1531, 0xFFFFFFFF, 0xFFA0AEC0, 0xFF00D2FF };
        if (published == null) return c;
        String[] p = published.split(",");
        if (p.length != c.length) return c;
        int[] out = new int[c.length];
        try { for (int i = 0; i < c.length; i++) out[i] = Color.parseColor(p[i].trim()); } catch (Exception e) { return c; }
        return out;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int[] c = colors(Settings.Global.getString(getContentResolver(), COLORS_KEY));
        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] { c[0], c[1] }));
        TextView title = new TextView(this);
        title.setText(R.string.rescueapp_updating);
        title.setTextColor(c[2]);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText(R.string.rescueapp_updating_sub);
        sub.setTextColor(c[3]);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) (10 * d);
        root.addView(sub, lp);
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(c[4]));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams((int) (360 * d), (int) (8 * d));
        blp.topMargin = (int) (32 * d);
        root.addView(bar, blp);
        setContentView(root);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        handler.postDelayed(this::finish, MAX_MS);
    }

    /** Covered (MG4Dash back in front): its job is done. */
    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { }
}
