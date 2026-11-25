package app.mg4dash.cards

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.R
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
            val expectedRows = if (size in setOf(HomeSlots.Size.TALL, HomeSlots.Size.LARGE, HomeSlots.Size.HALF)) 5 else 3
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

    @Test fun settingsMigrateToProfileCardKeysAndBackupScope() {
        Prefs(app).raw.edit()
            .putBoolean("lyrics_wifi_only", true)
            .putBoolean("wifi_only_mode", false)
            .putBoolean("lyrics_cache_enabled", false)
            .putInt("lyrics_cache_max_mb", 25)
            .putFloat("lyrics_overlay_scale", 1.25f)
            .putInt("lyrics_overlay_x", 42)
            .putInt("lyrics_overlay_y", 84)
            .commit()

        val migrated = LyricsCardPrefs(app)
        val cacheKey = CardPrefs(app, HomeSlots.Card.LYRICS).key("cache_enabled")
        assertTrue(migrated.wifiOnly)
        assertFalse(migrated.cacheEnabled)
        assertEquals(25, migrated.cacheMaxMb)
        assertEquals(1.25f, migrated.overlayScale)
        assertEquals(42, migrated.overlayX)
        assertEquals(84, migrated.overlayY)
        assertTrue("card-prefixed settings follow profiles", Profiles.isPersonal(cacheKey))
        assertTrue("card-prefixed settings are included in backups", SettingsBackup.inScope(cacheKey, SettingsBackup.Options()))
        assertFalse("legacy global preference is removed after migration", Prefs(app).raw.contains("lyrics_cache_enabled"))
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
