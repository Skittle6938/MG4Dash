package app.mg4dash.web;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The page's sound as a player of the head unit (#98): Chromium 66 in a WebView publishes no media
 * session, so MG4Dash's media card stayed on the previous source and the steering wheel buttons went
 * to it. A script of the page reports its audio or video (playing, title, artist, picture); this
 * session shows it to MG4Dash and Android, and its commands (play, pause, next, previous) go back to the
 * page: its own media session handlers if it set some, otherwise its player.
 */
final class WebMedia {
    private static final String TAG = "MG4DashWeb";

    /** Script of each page: reports its media, and acts on them (window.__mg4dashAct). */
    static final String JS = "(function () {"
        + "if (window.__mg4dashMedia || !window.MG4DashMedia) return; window.__mg4dashMedia = true;"
        + "var B = window.MG4DashMedia, ms = navigator.mediaSession, acts = {};"
        // The page's own handlers (YouTube's next video…), kept as it sets them
        + "if (ms && ms.setActionHandler) { var set = ms.setActionHandler.bind(ms);"
        + "  ms.setActionHandler = function (a, h) { acts[a] = h; try { set(a, h); } catch (e) {} }; }"
        + "function media() { var all = document.querySelectorAll('video, audio'), best = null;"
        + "  for (var i = 0; i < all.length; i++) { var m = all[i];"
        + "    if (!m.paused && !m.ended) return m; if (!best && m.currentSrc) best = m; }"
        + "  return best; }"
        + "var last = '';"
        + "function report() { try {"
        + "  var m = media(), md = ms && ms.metadata;"
        + "  var title = (md && md.title) || document.title || '', artist = (md && md.artist) || location.hostname || '';"
        + "  var art = md && md.artwork && md.artwork.length ? md.artwork[md.artwork.length - 1].src : '';"
        + "  var playing = !!(m && !m.paused && !m.ended);"
        + "  var dur = m && isFinite(m.duration) ? Math.round(m.duration * 1000) : 0, pos = m ? Math.round(m.currentTime * 1000) : 0;"
        + "  var s = JSON.stringify([!!m, playing, title, artist, art, dur, Math.round(pos / 5000)]);"
        + "  if (s === last) return; last = s;"
        + "  B.state(!!m, playing, title, artist, art, dur, pos);"
        + "} catch (e) {} }"
        + "['play', 'playing', 'pause', 'ended', 'loadedmetadata', 'emptied', 'seeked'].forEach(function (t) {"
        + "  document.addEventListener(t, function () { setTimeout(report, 50); }, true); });"
        + "setInterval(report, 2000);"
        + "function key(k) { var t = document.activeElement || document.body;"
        + "  ['keydown', 'keyup'].forEach(function (type) { t.dispatchEvent(new KeyboardEvent(type, { key: k, code: 'Key' + k, shiftKey: true, bubbles: true })); }); }"
        + "window.__mg4dashAct = function (a) { try {"
        + "  var m = media(), name = { play: 'play', pause: 'pause', next: 'nexttrack', prev: 'previoustrack' }[a];"
        + "  if (acts[name]) { acts[name]({ action: name }); }"
        + "  else if (a === 'play') { if (m) m.play(); }"
        + "  else if (a === 'pause') { if (m) m.pause(); }"
        + "  else {"
        + "    var b = document.querySelector(a === 'next'"
        + "      ? '.ytp-next-button, [aria-label*=\"next\" i], [aria-label*=\"suivant\" i]'"
        + "      : '.ytp-prev-button, [aria-label*=\"previous\" i], [aria-label*=\"précédent\" i]');"
        + "    if (b) b.click();"
        + "    else if (a === 'prev' && m) m.currentTime = 0;"
        + "    else key(a === 'next' ? 'N' : 'P');"
        + "  }"
        + "} catch (e) {} setTimeout(report, 400); };"
        + "report();"
        + "})();";

    private final MediaSession session;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String title = "", artist = "", artUrl = "";
    private long duration;
    private Bitmap art;

    WebMedia(Context context, WebView web) {
        this.web = web;
        session = new MediaSession(context, "MG4Dash Web");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { act("play"); }
            @Override public void onPause() { act("pause"); }
            @Override public void onStop() { act("pause"); }
            @Override public void onSkipToNext() { act("next"); }
            @Override public void onSkipToPrevious() { act("prev"); }
        }, main);
    }

    /** For the page's script (addJavascriptInterface). */
    Object bridge() { return new Bridge(); }

    final class Bridge {
        @JavascriptInterface
        public void state(boolean has, boolean playing, String t, String a, String artSrc, long dur, long pos) {
            main.post(() -> update(has, playing, t, a, artSrc, dur, pos));
        }
    }

    private void act(String a) {
        Log.i(TAG, "media: " + a + " from the head unit");
        web.evaluateJavascript("window.__mg4dashAct && __mg4dashAct('" + a + "')", null);
    }

    private void update(boolean has, boolean playing, String t, String a, String artSrc, long dur, long pos) {
        if (!has) {
            if (session.isActive()) { session.setActive(false); Log.i(TAG, "media: none in the page"); }
            return;
        }
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f, SystemClock.elapsedRealtime())
            .build());
        boolean changed = !t.equals(title) || !a.equals(artist) || dur != duration;
        title = t; artist = a; duration = dur;
        if (!artSrc.equals(artUrl)) { artUrl = artSrc; art = null; changed = true; fetchArt(artSrc); }
        if (changed) metadata();
        // Active once it plays: MG4Dash and the steering wheel buttons find it, paused too
        if (playing && !session.isActive()) { session.setActive(true); Log.i(TAG, "media: playing, session active"); }
    }

    private void metadata() {
        MediaMetadata.Builder m = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
        if (art != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(m.build());
    }

    /** The page's picture for the track, small (about 320 px), read apart. */
    private void fetchArt(String src) {
        if (src.isEmpty() || !src.startsWith("http")) return;
        new Thread(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(src).openConnection();
                c.setConnectTimeout(8000); c.setReadTimeout(8000);
                byte[] data;
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 4_000_000) out.write(buf, 0, n);
                    data = out.toByteArray();
                } finally { c.disconnect(); }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= 320) s *= 2;
                o = new BitmapFactory.Options(); o.inSampleSize = s;
                b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } catch (Exception e) { Log.w(TAG, "media picture: " + e.getMessage()); }
            Bitmap got = b;
            main.post(() -> { if (src.equals(artUrl) && got != null) { art = got; metadata(); } });
        }, "mg4dash-art").start();
    }

    void release() {
        session.setActive(false);
        session.release();
    }
}
