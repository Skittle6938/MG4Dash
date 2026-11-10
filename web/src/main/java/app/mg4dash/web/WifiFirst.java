package app.mg4dash.web;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The page on the Wi-Fi first, as MG4Dash's own traffic (its WifiFirst): as soon as a Wi-Fi is connected and a quick
 * check (3 s at most) gets through it, this app's process, its WebView included, uses it, even before Android
 * validates it; a Wi-Fi without internet (a hotspot without data) leaves Android's default network (the 4G). Checked
 * again every 2 minutes and when the page fails, so that it goes back to the Wi-Fi once it works again.
 * [onChange] is told when the network changes (the page reloaded if it had failed).
 */
final class WifiFirst {
    private static final String TAG = "MG4DashWeb";
    private static final String CHECK_URL = "http://connectivitycheck.gstatic.com/generate_204";
    private static final int CHECK_MS = 3000;
    private static final long RECHECK_MS = 120_000L;

    private static ConnectivityManager cm;
    private static volatile Network wifi;
    private static volatile Network bound;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable recheck = () -> evaluate("every 2 min");
    static volatile Runnable onChange;

    private WifiFirst() {}

    static synchronized void start(Context context) {
        if (cm != null) return;
        ConnectivityManager c = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (c == null) return;
        cm = c;
        try {
            c.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { wifi = network; evaluate("Wi-Fi #" + network + " connected"); }
                @Override public void onLost(Network network) {
                    if (!network.equals(wifi)) return;
                    wifi = null;
                    if (network.equals(bound)) use(null, "Wi-Fi #" + network + " lost");
                }
            });
        } catch (Exception e) { Log.w(TAG, "no network callback: " + e.getMessage()); }
    }

    /** The page failed: the Wi-Fi checked again now. */
    static void recheck() { evaluate("the page failed"); }

    private static void evaluate(String why) {
        handler.removeCallbacks(recheck);
        final Network w = wifi;
        if (w == null) return;
        new Thread(() -> {
            boolean ok;
            try { ok = probe(w); } catch (Exception e) { ok = false; }
            if (!w.equals(wifi)) return;
            if (ok && !w.equals(bound)) use(w, why + ": internet through the Wi-Fi");
            else if (!ok && bound != null) use(null, why + ": no internet through the Wi-Fi");
            handler.postDelayed(recheck, RECHECK_MS);
        }, "WifiFirst").start();
    }

    private static void use(Network n, String why) {
        bound = n;
        try { cm.bindProcessToNetwork(n); } catch (Exception e) { Log.w(TAG, "bind: " + e.getMessage()); }
        Log.i(TAG, why + " -> page on " + (n != null ? "the Wi-Fi #" + n : "Android's default network"));
        Runnable r = onChange;
        if (r != null) handler.post(r);
    }

    /** Google's empty page (204) through [n] within 3 s, not a hotspot's login page. */
    private static boolean probe(Network n) throws Exception {
        HttpURLConnection c = (HttpURLConnection) n.openConnection(new URL(CHECK_URL));
        try {
            c.setConnectTimeout(CHECK_MS); c.setReadTimeout(CHECK_MS); c.setInstanceFollowRedirects(false); c.setUseCaches(false);
            return c.getResponseCode() == 204;
        } finally { c.disconnect(); }
    }
}
