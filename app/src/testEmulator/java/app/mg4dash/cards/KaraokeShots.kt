package app.mg4dash.cards

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The karaoke screen and the lyrics card as they are (26/11, for a study): build/screenshots/karaoke_*.png. */
@org.junit.experimental.categories.Category(app.mg4dash.Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class KaraokeShots {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun cache(artist: String, title: String, found: Boolean = true) {
        val key = LyricsRepo.key(artist, title)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(ctx.filesDir, "lyrics").apply { mkdirs() }
        // Placeholder lines, not real lyrics
        val lrc = (0 until 16).joinToString("\n") { i -> String.format("[00:%02d.00]Placeholder lyric line number %d of the song", i * 4, i + 1) }
        File(dir, "$name.json").writeText(JSONObject().apply {
            put("artistName", artist); put("trackName", title); put("plainLyrics", JSONObject.NULL)
            // Not found: what the card shows when the search gave nothing ("No lyrics found")
            if (found) put("syncedLyrics", lrc) else { put("syncedLyrics", JSONObject.NULL); put("instrumental", true) }
        }.toString())
    }

    private fun setNow(v: MediaHub.NowPlaying) {
        val f = MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<MediaHub.NowPlaying>).value = v
    }

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS); Thread.sleep(20) }
    }

    private fun save(v: View, name: String, w: Int = 1920, h: Int = 720) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File(File("build/screenshots").apply { mkdirs() }, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shoot(theme: Int, withLyrics: Boolean) {
        app.mg4dash.system.Prefs(ctx).raw.edit().clear().putInt("theme_mode", theme).commit()
        val t = if (theme == 2) "light" else "dark"; val l = if (withLyrics) "lyrics" else "none"
        val title = if (withLyrics) "Study Song" else "Unknown Song"
        cache("Study Artist", title, found = withLyrics)
        val now = MediaHub.NowPlaying(available = true, packageName = "test.player", title = title, artist = "Study Artist",
            playing = true, durationMs = 180_000, positionMs = 21_000)
        setNow(now)
        val act = Robolectric.buildActivity(Activity::class.java).setup().get()
        app.mg4dash.ui.Themes.apply(act)
        val a = act
        val card = LyricsCard(a)
        val holder = FrameLayout(a).apply { setBackgroundColor(app.mg4dash.ui.Themes.color(a, app.mg4dash.R.color.bg_bottom)) }
        holder.addView(card, FrameLayout.LayoutParams(620, 340))
        a.setContentView(holder)
        card.shown(true); card.show(now)
        idle(2500)
        save(holder, "karaoke_card_${t}_$l.png", 620, 340)
        card.performClick()
        idle(2500)
        val d = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        if (d != null) save(d.window!!.decorView, "karaoke_screen_${t}_$l.png")
        d?.dismiss(); card.shown(false)
    }

    @Test fun karaoke() {
        for (theme in listOf(1, 2)) for (withLyrics in listOf(true, false)) shoot(theme, withLyrics)
    }
}
