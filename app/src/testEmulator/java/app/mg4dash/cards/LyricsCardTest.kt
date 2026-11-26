package app.mg4dash.cards

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.LyricsChoices
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.system.Prefs
import app.mg4dash.system.Profiles
import app.mg4dash.system.SettingsBackup
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.LyricsOverlay
import app.mg4dash.ui.OptionsPopup
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Lyrics card states, supported places, options, and the shown/hidden lifecycle without network access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class LyricsCardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var activity: Activity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        app.filesDir.resolve("lyrics").deleteRecursively()
        LyricsRepo.start(app)
        runBlocking { LyricsRepo.clear() }
        Prefs(app).raw.edit().clear().commit()
        setNow(MediaHub.NowPlaying())
    }

    @After fun tearDown() {
        setNow(MediaHub.NowPlaying())
        app.filesDir.resolve("lyrics").deleteRecursively()
    }

    @Test fun emptyAndLoadingStatesAreShown() {
        val card = LyricsCard(activity)
        assertTrue("the whole card must accept touches so swipes can change cards", card.isClickable)
        card.shown(true)
        assertContains(card, activity.getString(R.string.ly_waiting))

        val now = trackNow()
        setNow(now)
        // Assert the immediate state before the one-second debounce can reach the offline result.
        card.show(now)
        assertContains(card, activity.getString(R.string.ly_searching))
        card.shown(false)
    }

    @Test fun cachedLyricsAreRenderedWithoutNetwork() {
        val now = trackNow()
        writeCachedTrack(now.artist, now.title)
        setNow(now)

        val card = LyricsCard(activity)
        card.shown(true)
        idle(1600)

        assertContains(card, "First cached line")
        assertContains(card, "Second cached line")
        assertTrue("cache result clears the loading status", cardText(card).none { it == activity.getString(R.string.ly_searching) })
        card.shown(false)
    }

    @Test fun missingLyricsShowNetworkErrorState() {
        LyricsCardPrefs(app).wifiOnly = true
        setNow(trackNow())

        val card = LyricsCard(activity)
        card.shown(true)
        idle(1500)

        assertContains(card, activity.getString(R.string.ly_wifi_only))
        assertEquals("network policy prevents any HTTP request", 0, activeConnectionCount())
        card.shown(false)
    }

    @Test fun allSupportedPlacesCanBeAssignedAndSized() {
        val card = LyricsCard(activity)
        activity.setContentView(card)
        shadowOf(Looper.getMainLooper()).idle()
        val dimensions = mapOf(
            HomeSlots.Size.SMALL to (594 to 274), HomeSlots.Size.MEDIUM to (594 to 410),
            HomeSlots.Size.TALL to (595 to 696), HomeSlots.Size.WIDE to (1197 to 410),
            HomeSlots.Size.LARGE to (1189 to 696), HomeSlots.Size.QUARTER to (659 to 252),
            HomeSlots.Size.HALF to (659 to 516), HomeSlots.Size.BAND to (1330 to 252),
        )

        dimensions.forEach { (size, wh) ->
            assertTrue("Lyrics should fit $size", HomeSlots.fits(card.card, size))
            card.place(CardPlace(size))
            card.measure(
                View.MeasureSpec.makeMeasureSpec(wh.first, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(wh.second, View.MeasureSpec.EXACTLY),
            )
            card.layout(0, 0, wh.first, wh.second)
            shadowOf(Looper.getMainLooper()).idle()
            val expectedRows = if (size in setOf(HomeSlots.Size.MEDIUM, HomeSlots.Size.TALL, HomeSlots.Size.LARGE, HomeSlots.Size.HALF)) 5 else 3
            assertEquals("$size uses the expected stable row count", expectedRows, privateValue(card, "visibleLyricsRows"))
        }
    }

    @Test fun hiddenCardCancelsItsWorkAndDoesNotRestartOnMediaChanges() {
        setNow(trackNow())
        val card = LyricsCard(activity)
        card.shown(true)
        idle(100)
        assertNotNull("shown card owns a lifecycle scope", privateValue(card, "liveScope"))
        assertTrue(privateValue(card, "isActivelyDisplayed") as Boolean)

        card.shown(false)
        assertNull("hidden card releases its lifecycle scope", privateValue(card, "liveScope"))
        assertFalse(privateValue(card, "isActivelyDisplayed") as Boolean)
        assertNull("hidden card cancels the pending track request", privateValue(card, "requestJob"))
        setNow(trackNow(title = "Changed while hidden"))
        idle(1400)
        assertEquals("no request remains active while hidden", 0, activeConnectionCount())
        assertEquals("no new track request was queued while hidden", null, privateValue(card, "requestedKey"))
    }

    @Test fun hiddenCardStopsItsTickerWhileOverlayRemainsActive() {
        val now = trackNow()
        writeCachedTrack(now.artist, now.title)
        setNow(now)
        val card = LyricsCard(activity)
        card.shown(true)
        idle(1500)
        assertTrue("the visible card schedules its lyrics ticker", privateValue(card, "tickerScheduled") as Boolean)

        setOverlayActive(true)
        try {
            card.shown(false)
            assertFalse("the hidden card ticker is stopped even while overlay remains active", privateValue(card, "tickerScheduled") as Boolean)
            assertTrue("overlay keeps its independent lifecycle", LyricsOverlay.active)
        } finally {
            setOverlayActive(false)
        }
    }

    @Test fun optionsAreAvailableWithoutStartingCardWork() {
        val card = LyricsCard(activity)
        val popup = OptionsPopup(activity, activity.getString(card.card.label), card.card.icon)

        assertNotNull(card.options(popup))
        @Suppress("UNCHECKED_CAST")
        val columns = privateValue(popup, "cols") as List<List<View>>
        assertEquals("lyrics controls and the MG4Control guide use the standard two-column popup", 2, columns.size)
        assertTrue(columns.all { it.isNotEmpty() })
        val cacheUsage = activity.getString(R.string.ly_cache_used, 0f, LyricsCardPrefs(app).cacheMaxMb, 0)
        assertTrue("cache usage appears in Lyrics options: $cacheUsage", columns.flatten().filterIsInstance<TextView>().any { it.text.toString() == cacheUsage })
        assertNull("probing options must not start the card scope", privateValue(card, "liveScope"))
        assertFalse("probing options must not mark the card visible", privateValue(card, "isActivelyDisplayed") as Boolean)
        assertTrue("options probing must not start a repository request", activeConnectionCount() == 0)
    }

    @Test fun overlayUsesRealDisplayWidthAndCanReachRightEdgeAtSeventyPercentScale() {
        assertEquals("1920 - the 142 px app inset remains reachable", 1172, LyricsOverlay.maxX(1920, 748))
        val screenWidth = 1920
        val pillWidthAtSeventyPercent = LyricsOverlay.pillWidth(screenWidth, .70f, 1f)
        val maxX = LyricsOverlay.maxX(screenWidth, pillWidthAtSeventyPercent)
        assertEquals(screenWidth, maxX + pillWidthAtSeventyPercent)
        assertTrue("overlay window can extend through system insets", LyricsOverlay.windowFlags and android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(0, LyricsOverlay.maxX(800, 900))
    }

    @Test fun karaokeUsesTheSharedFullscreenWindowHelper() {
        val card = LyricsCard(activity)
        card.shown(true)
        card.javaClass.getDeclaredMethod("openKaraoke").apply { isAccessible = true }.invoke(card)

        val dialog = privateValue(card, "karaoke") as? Dialog
        assertNotNull("karaoke dialog is created", dialog)
        assertTrue("karaoke window is shown", dialog!!.isShowing)
        dialog.dismiss()
        card.shown(false)
    }

    @Test fun settingsFollowProfilesAndBackups() {
        val prefs = LyricsCardPrefs(app)
        prefs.wifiOnly = true; prefs.cacheEnabled = false; prefs.cacheMaxMb = 25; prefs.overlayScale = 1.25f
        val again = LyricsCardPrefs(app)
        assertTrue(again.wifiOnly)
        assertFalse(again.cacheEnabled)
        assertEquals(25, again.cacheMaxMb)
        assertEquals(1.25f, again.overlayScale)
        val cacheKey = CardPrefs(app, HomeSlots.Card.LYRICS).key("cache_enabled")
        assertTrue("card-prefixed settings follow profiles", Profiles.isPersonal(cacheKey))
        assertTrue("card-prefixed settings are included in backups", SettingsBackup.inScope(cacheKey, SettingsBackup.Options()))
    }

    @Test fun radioSaysLyricsAreNotAvailable() {
        val card = LyricsCard(activity)
        card.shown(true)
        listOf(
            MediaHub.NowPlaying(available = true, packageName = "com.saicmotor.radio", source = "DAB", title = "FIP", artist = "11B", playing = true),
            MediaHub.NowPlaying(available = true, packageName = "com.saicmotor.radio", source = "FM", title = "France Inter", artist = "87.8 MHz", playing = true, analog = true),
        ).forEach { radio ->
            setNow(radio); card.show(radio); idle(1200)
            assertContains(card, activity.getString(R.string.ly_radio))
            assertEquals("nothing is looked up on the radio", 0, activeConnectionCount())
            assertNull(privateValue(card, "requestedKey"))
        }
        assertFalse(LyricsRepo.searchable(MediaHub.NowPlaying(available = true, packageName = "aa.player", source = "Radio FG", title = "Radio FG", artist = "Live")))
        assertTrue(LyricsRepo.searchable(trackNow()))
        card.shown(false)
    }

    @Test fun shiftArrowsMoveTheSongsLyricsAndAreKeptForIt() {
        app.filesDir.resolve("lyrics_choices.json").delete()
        LyricsChoices.clear()
        val now = trackNow()
        writeCachedTrack(now.artist, now.title)
        setNow(now)
        val card = LyricsCard(activity)
        activity.setContentView(card, ViewGroup.LayoutParams(620, 340))
        card.shown(true); card.show(now)
        idle(1600)
        val shift = privateValue(card, "cardShift") as LyricsShift
        assertEquals("arrows beside synced lyrics", View.VISIBLE, shift.visibility)
        // ▲ moves the lyrics back half a second, ▼ moves them on
        shift.getChildAt(0).performClick(); shift.getChildAt(0).performClick(); shift.getChildAt(2).performClick()
        assertEquals(-500L, LyricsChoices.shiftMs(now.artist, now.title))
        assertEquals(-500L, privateValue(card, "shiftMs"))
        assertEquals(LyricsShift.label(-500, false), (shift.getChildAt(1) as TextView).text.toString())
        // Kept for the song (also as "feat."), not for the next one
        assertEquals(-500L, LyricsChoices.shiftMs(now.artist + " feat. Guest", now.title + " (feat. Guest)"))
        val other = trackNow("Another Song"); setNow(other); card.show(other); idle(200)
        assertEquals(0L, privateValue(card, "shiftMs"))
        setNow(now); card.show(now); idle(1600)
        assertEquals(-500L, privateValue(card, "shiftMs"))
        // A touch on the count: back to 0, forgotten
        shift.getChildAt(1).performClick()
        assertEquals(0L, LyricsChoices.shiftMs(now.artist, now.title))
        assertEquals(0, LyricsChoices.count())
        // The setting hides them
        LyricsCardPrefs(app).shiftArrows = false
        card.show(now); idle(300)
        assertEquals(View.GONE, shift.visibility)
        card.shown(false)
    }

    @Test fun karaokeWithoutLyricsSaysForWhichSongAndOffersToSearchAgain() {
        val now = trackNow("Unknown Song")
        val key = LyricsRepo.key(now.artist, now.title)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(File(app.filesDir, "lyrics").apply { mkdirs() }, "$name.json").writeText(JSONObject().apply {
            put("artistName", now.artist); put("trackName", now.title); put("syncedLyrics", JSONObject.NULL)
            put("plainLyrics", JSONObject.NULL); put("instrumental", true)
        }.toString())
        setNow(now)
        val card = LyricsCard(activity)
        card.shown(true); card.show(now)
        idle(1600)
        card.javaClass.getDeclaredMethod("openKaraoke").apply { isAccessible = true }.invoke(card)
        idle(300)
        val empty = privateValue(card, "karaokeEmpty") as View
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(activity.getString(R.string.ly_none), (privateValue(card, "karaokeEmptyText") as TextView).text.toString())
        assertEquals(activity.getString(R.string.ly_for, now.title, now.artist), (privateValue(card, "karaokeEmptyFor") as TextView).text.toString())
        assertEquals(View.VISIBLE, (privateValue(card, "karaokeEmptyButton") as View).visibility)
        assertEquals("no arrows without synced lyrics", View.GONE, (privateValue(card, "karaokeShift") as View).visibility)
        (privateValue(card, "karaoke") as Dialog).dismiss()
        card.shown(false)
    }

    private fun trackNow(title: String = "Cached Song") = MediaHub.NowPlaying(
        available = true, packageName = "test.player", title = title, artist = "Test Artist",
        playing = true, durationMs = 120_000, positionMs = 0,
    )

    private fun setNow(value: MediaHub.NowPlaying) {
        val field = MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<MediaHub.NowPlaying>).value = value
    }

    private fun setOverlayActive(value: Boolean) {
        LyricsOverlay::class.java.getDeclaredField("active").apply { isAccessible = true }.setBoolean(LyricsOverlay, value)
    }

    private fun writeCachedTrack(artist: String, title: String) {
        val key = LyricsRepo.key(artist, title)
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        val dir = File(app.filesDir, "lyrics").apply { mkdirs() }
        File(dir, "$name.json").writeText(JSONObject().apply {
            put("artistName", artist)
            put("trackName", title)
            put("syncedLyrics", "[00:00.00]First cached line\n[00:01.00]Second cached line")
            put("plainLyrics", JSONObject.NULL)
        }.toString())
    }

    private fun idle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun cardText(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is LyricsReel -> if (view.visibility == View.VISIBLE) view.shownLines() else emptyList()
        is ViewGroup -> (0 until view.childCount).flatMap { cardText(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertContains(card: View, text: String) = assertTrue("expected '$text' in ${cardText(card)}", text in cardText(card))

    private fun privateValue(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun activeConnectionCount(): Int {
        val field = LyricsRepo::class.java.getDeclaredField("activeConnections").apply { isAccessible = true }
        return (field.get(LyricsRepo) as Set<*>).size
    }
}
