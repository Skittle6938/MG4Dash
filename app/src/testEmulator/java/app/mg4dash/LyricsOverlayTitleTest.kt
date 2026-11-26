package app.mg4dash

import android.os.Looper
import android.widget.TextView
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.ui.LyricsOverlay
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The floating pill drops the last song's lines as soon as the title changes (26/11), also while paused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class LyricsOverlayTitleTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    private fun cache(artist: String, title: String) {
        val key = LyricsRepo.key(artist, title)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(File(ctx.filesDir, "lyrics").apply { mkdirs() }, "$name.json").writeText(JSONObject().apply {
            put("artistName", artist); put("trackName", title); put("plainLyrics", JSONObject.NULL)
            put("syncedLyrics", "[00:00.00]Placeholder first line\n[00:30.00]Placeholder second line")
        }.toString())
    }

    private fun setNow(v: MediaHub.NowPlaying) {
        val f = MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (f.get(MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<MediaHub.NowPlaying>).value = v
    }

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS); Thread.sleep(5) }
    }

    private fun shown(): String =
        (LyricsOverlay::class.java.getDeclaredField("activeTextView").apply { isAccessible = true }.get(LyricsOverlay) as TextView).text.toString()

    @After fun tearDown() { LyricsOverlay.stop(); setNow(MediaHub.NowPlaying()) }

    @Test fun anotherTitleClearsTheLastSongsLines() {
        ShadowSettings.setCanDrawOverlays(true)
        cache("Pill Artist", "First Song")
        setNow(MediaHub.NowPlaying(available = true, packageName = "test.player", title = "First Song", artist = "Pill Artist",
            playing = true, durationMs = 120_000, positionMs = 1_000))
        assertTrue(LyricsOverlay.show(ctx))
        idle(800)
        assertEquals("Placeholder first line", shown())
        // The next song arrives paused: the old line goes at once
        setNow(MediaHub.NowPlaying(available = true, packageName = "test.player", title = "Second Song", artist = "Pill Artist",
            playing = false, durationMs = 120_000, positionMs = 0))
        idle(300)
        assertEquals(ctx.getString(R.string.ly_overlay_searching), shown())
    }
}
