package app.mg4dash.media

import app.mg4dash.media.LyricsRepo.Attempt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lyrics tried again for the current song when the network is not there yet or a request failed (27/11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LyricsRetryTest {
    private var clock = 0L
    private val waits = mutableListOf<Long>()
    private suspend fun wait(ms: Long) { waits += ms; clock += ms }

    @Test fun answerAtOnceNoWait() = runBlocking {
        val r = LyricsRepo.patiently(online = { true }, wait = ::wait) { Attempt.Done("lyrics") }
        assertEquals("lyrics", r); assertEquals(emptyList<Long>(), waits)
    }

    @Test fun notFoundIsAnAnswer() = runBlocking {
        var tries = 0
        val r = LyricsRepo.patiently<String>(online = { true }, wait = ::wait) { tries++; Attempt.Done(null) }
        assertNull(r); assertEquals(1, tries)
    }

    @Test fun networkComingAfterStartIsWaitedFor() = runBlocking {
        // The 4G up 7 s after the start: the lookup made then, not given up
        var tries = 0
        val r = LyricsRepo.patiently(online = { clock >= 7_000 }, wait = ::wait) { tries++; Attempt.Done("lyrics") }
        assertEquals("lyrics", r); assertEquals(1, tries); assertEquals(7_000L, clock)
    }

    @Test fun failedRequestTriedAgainEvery10s() = runBlocking {
        var tries = 0
        val r = LyricsRepo.patiently(online = { true }, wait = ::wait) { tries++; if (tries < 3) Attempt.Failed else Attempt.Done("lyrics") }
        assertEquals("lyrics", r); assertEquals(listOf(10_000L, 10_000L), waits)
    }

    @Test fun givenUpAfterAMinute() = runBlocking {
        var tries = 0
        val r = LyricsRepo.patiently<String>(online = { true }, wait = ::wait) { tries++; Attempt.Failed }
        assertNull(r); assertEquals(7, tries); assertEquals(60_000L, clock)
        clock = 0; waits.clear()
        val none = LyricsRepo.patiently<String>(online = { false }, wait = ::wait) { error("never tried offline") }
        assertNull(none); assertEquals(60_000L, clock)
    }
}
