package app.mg4dash.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Offline tests for LRCLIB's LRC parsing and search-result ranking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LyricsRepoTest {
    @Test fun parseSortsTimestampsAndSupportsOneToThreeFractionDigits() {
        val parsed = LyricsRepo.parse(
            """
            [00:02.5]Later
            [00:01.20][00:03.000]Same line
            [00:04]Whole second
            [00:00.00]
            [invalid]Ignored
            """.trimIndent()
        )

        assertEquals(
            listOf(1_200L to "Same line", 2_500L to "Later", 3_000L to "Same line", 4_000L to "Whole second"),
            parsed,
        )
    }

    @Test fun bestSearchResultPrefersArtistTitleAndDurationMatchWithLyrics() {
        val results = JSONArray(
            """
            [
              {"artistName":"Test Artist","trackName":"Song","duration":180,"syncedLyrics":""},
              {"artistName":"Other Artist","trackName":"Song","duration":180,"plainLyrics":"wrong artist"},
              {"artistName":"Test Artist","trackName":"Other Song","duration":180,"plainLyrics":"wrong title"},
              {"artistName":"Test Artist","trackName":"Song [Remastered]","duration":182,"plainLyrics":"best match"}
            ]
            """.trimIndent()
        )

        val best = LyricsRepo.best(results, "Test Artist", "Song (Official Video)", 180_000)

        assertNotNull(best)
        assertEquals("Song [Remastered]", best!!.optString("trackName"))
        assertEquals("best match", best.optString("plainLyrics"))
    }

    @Test fun instrumentalSearchResultWithNullLyricsIsAcceptedAndDecodesWithoutNullText() {
        val results = JSONArray(
            """
            [{"artistName":"Richard Strauss","trackName":"Also sprach Zarathustra","duration":500,"instrumental":true,"syncedLyrics":null,"plainLyrics":null}]
            """.trimIndent()
        )

        val best = LyricsRepo.best(results, "Richard Strauss", "Also sprach Zarathustra", 500_000)
        assertNotNull(best)
        val track = LyricsRepo.decode("key", "Richard Strauss", "Also sprach Zarathustra", best!!)
        assertEquals(null, track.synced)
        assertEquals(null, track.plain)
    }

    @Test fun nullLyricsFieldsDecodeAsAbsentText() {
        val track = LyricsRepo.decode(
            "key", "Artist", "Instrumental",
            JSONObject("""{"instrumental":true,"syncedLyrics":null,"plainLyrics":null}""")
        )

        assertEquals(null, track.synced)
        assertEquals(null, track.plain)
    }

    /** CarPlay's guests and joint artists (#135): searched as given, then with the main artist only. */
    @Test fun searchesDropGuestsAndTryTheMainArtist() {
        assertEquals("Song", LyricsRepo.clean("Song (feat. Guest)"))
        assertEquals("Song", LyricsRepo.clean("Song [with Guest]"))
        assertEquals("Song", LyricsRepo.clean("Song ft. Guest"))
        assertEquals("Song", LyricsRepo.clean("Song - 2011 Remaster"))
        assertEquals("Malcolm X", LyricsRepo.mainArtist("Malcolm X"))
        assertEquals("Band One", LyricsRepo.mainArtist("Band One & Band Two"))
        assertEquals("Band One", LyricsRepo.mainArtist("Band One, Band Two"))
        assertEquals(listOf("Band One & Band Two Song", "Band One Song"), LyricsRepo.searches("Band One & Band Two", "Song (feat. Guest)"))
        assertEquals(listOf("Band Song"), LyricsRepo.searches("Band", "Song"))
    }
}
