package app.mg4dash.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What is kept per song (26/11): the lyrics' shift and the lyrics chosen, kept across restarts and cleared at once. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LyricsChoicesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun restart() {
        LyricsChoices::class.java.getDeclaredField("file").apply { isAccessible = true }.set(LyricsChoices, null)
        LyricsChoices.start(app)
    }

    @Before fun setUp() { app.filesDir.resolve("lyrics_choices.json").delete(); restart(); LyricsChoices.clear() }

    @Test fun shiftAndLyricsAreKeptPerSongAcrossARestart() {
        LyricsChoices.setShift("Artist A & Artist B", "Song (feat. C)", -1500)
        LyricsChoices.setLyrics("Artist A", "Song", 4242)
        restart()
        assertEquals(LyricsChoices.Choice(-1500, 4242), LyricsChoices.get("artist a", "Song - Remastered 2011"))
        assertEquals(0L, LyricsChoices.shiftMs("Artist A", "Other song"))
        assertEquals(1, LyricsChoices.count())
        LyricsChoices.setShift("Artist A", "Song", 0)
        assertEquals("the chosen lyrics stay", 1, LyricsChoices.count())
        LyricsChoices.setLyrics("Artist A", "Song", 0)
        assertEquals("nothing left: forgotten", 0, LyricsChoices.count())
    }

    @Test fun clearForgetsEverySong() {
        LyricsChoices.setShift("A", "1", 500); LyricsChoices.setShift("B", "2", -500)
        val v = LyricsChoices.version.value
        LyricsChoices.clear()
        assertEquals(0, LyricsChoices.count())
        assertEquals(v + 1, LyricsChoices.version.value)
        restart()
        assertEquals(0, LyricsChoices.count())
    }

    @Test fun shiftIsBounded() {
        LyricsChoices.setShift("A", "1", 99_000)
        assertEquals(LyricsChoices.MAX_MS, LyricsChoices.shiftMs("A", "1"))
    }

    @Test fun pickerListsEveryResultWithLyricsTheClosestFirst() {
        fun o(id: Long, artist: String, title: String, synced: String?, plain: String? = null, dur: Double = 200.0) = JSONObject().apply {
            put("id", id); put("artistName", artist); put("trackName", title); put("duration", dur)
            put("syncedLyrics", synced ?: JSONObject.NULL); put("plainLyrics", plain ?: JSONObject.NULL)
        }
        val list = listOf(o(1, "Other", "Song", "[00:01.00]x"), o(2, "Artist", "Song", null, "text"), o(3, "Artist", "Song", null),
            o(4, "Artist", "Song", "[00:01.00]y", dur = 201.0))
        val ranked = LyricsRepo.ranked(list, "Artist", "Song", 200_000)
        assertEquals("no lyrics: left out; the closest first", listOf(2L, 4L, 1L), ranked.map { it.optLong("id") })
        assertEquals(2L, LyricsRepo.best(JSONArray(list), "Artist", "Song", 200_000)?.optLong("id"))
        assertEquals(4L, LyricsRepo.decode("k", "Artist", "Song", list[3]).id)
    }
}
