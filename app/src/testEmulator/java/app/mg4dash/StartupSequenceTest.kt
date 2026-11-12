package app.mg4dash

import app.mg4dash.media.StartupSequence
import app.mg4dash.media.StartupSequence.Action
import app.mg4dash.media.StartupSequence.Input
import app.mg4dash.media.StartupSequence.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The startup audio sequence (#36), step by step with a simulated clock. */
class StartupSequenceTest {
    private val radio = "radio"; private val bt = "bt"; private val usb = "usb"

    private fun seq(mode: Mode = Mode.Auto(listOf(bt, radio)), play: Boolean = true) = StartupSequence(mode, play, radio)

    /** Runs [s] from [from] to [to] every 250 ms with [input] at each time; all actions with their time. */
    private fun run(s: StartupSequence, from: Long, to: Long, input: (Long) -> Input): List<Pair<Long, Action>> {
        val out = mutableListOf<Pair<Long, Action>>()
        var t = from
        while (t <= to) { s.step(input(t)).forEach { out += t to it }; t += 250 }
        return out
    }

    @Test fun noStartupSound() {
        val s = seq(play = true)
        var playing = false
        val a = run(s, 0, 13_000) { t -> Input(t, available = listOf(radio, bt), carSource = if (t > 300) bt else radio, playing = playing).also { if (t >= 1_500) playing = true } }
        assertEquals("the preferred source at once, without playing", Action.Switch(bt), a.first().second)
        assertEquals(0L, a.first().first)
        assertEquals("then play, explicitly, 1.5 s later", 1_500L to Action.Play, a[1])
        assertEquals(2, a.size)
        assertTrue("then normal operation", s.done)
    }

    @Test fun startupSound() {
        val s = seq(play = true)
        val a = run(s, 0, 9_000) { t ->
            val busy = t < 5_000
            Input(t, soundExpected = true, soundBusy = busy, soundEndAt = if (busy) null else 5_000, available = listOf(radio, bt), carSource = radio)
        }
        assertTrue("nothing while the sound plays", a.none { it.first < 6_000 })
        assertEquals("the source 1 s after the sound", 6_000L to Action.Switch(bt), a.first())
        assertEquals(7_500L to Action.Play, a[1])
    }

    @Test fun startupSoundThirtySecondsAtMost() {
        val s = seq(play = false)
        val a = run(s, 0, 31_000) { t -> Input(t, soundExpected = true, soundBusy = true, available = listOf(radio, bt), carSource = radio) }
        assertEquals("on with the source after 30 s, and its pause", listOf(30_000L to Action.Switch(bt), 30_000L to Action.Pause), a)
    }

    @Test fun pauseWantedSourcePlayingAtPowerOnWithoutASound() {
        val s = seq(play = false)
        val a = s.step(Input(0, playing = true))
        assertEquals("no startup sound: paused at once, no 1 s delay", listOf(Action.Pause), a)
        // Again only after a moment (the car takes the command): not one per step
        assertTrue(s.step(Input(250, playing = true)).isEmpty())
        assertEquals(listOf(Action.Pause), s.step(Input(1_500, playing = true)))
    }

    /** #36, beta 9: a pause sent during the sound went to the car's media player carrying it, and cut the sound. */
    @Test fun nothingAtAllWhileTheStartupSoundPlays() {
        val s = seq(play = false)
        // The car shows a player playing (its media player, carrying our sound): nothing is sent to it
        val during = run(s, 0, 6_750) { t -> Input(t, soundExpected = true, soundBusy = true, available = listOf(radio, bt), carSource = radio, playing = true) }
        assertTrue("no pause, no switch, no play during the sound", during.isEmpty())
        // Sound over at 7 s, a pause wanted: the switch and the pause at once, while MG4Dash still holds the focus
        val after = s.step(Input(7_000, soundExpected = true, soundEndAt = 7_000, available = listOf(radio, bt), carSource = radio, playing = false))
        assertEquals(listOf(Action.Switch(bt), Action.Pause), after)
    }

    @Test fun preferredSourceMissing() {
        // The first of the history missing: waited for 15 s, then the best one there
        val s = seq(Mode.Auto(listOf(bt, usb, radio)))
        val a = run(s, 0, 16_000) { t -> Input(t, available = listOf(radio, usb), carSource = radio) }
        assertEquals(15_000L to Action.Switch(usb), a.first())
        // Nothing of the history there: the radio
        val r = run(seq(Mode.Auto(listOf(bt, usb))), 0, 16_000) { t -> Input(t, carSource = "x", available = emptyList()) }
        assertEquals(15_000L to Action.Switch(radio), r.first())
    }

    @Test fun preferredSourceArrivingWithinFifteenSeconds() {
        val s = seq(Mode.Auto(listOf(bt, radio)))
        val a = run(s, 0, 10_000) { t -> Input(t, available = if (t >= 8_000) listOf(radio, bt) else listOf(radio), carSource = radio) }
        assertEquals("switched as soon as it is there", 8_000L to Action.Switch(bt), a.first())
    }

    @Test fun fixedAndCarModes() {
        val f = run(seq(Mode.Fixed(usb)), 0, 16_000) { t -> Input(t, available = listOf(radio), carSource = radio) }
        assertEquals("fixed source missing: the radio after 15 s", 15_000L to Action.Switch(radio), f.first())
        val c = run(seq(Mode.Car), 0, 1_000) { t -> Input(t, available = listOf(radio, bt), carSource = usb) }
        assertEquals("as the car: the car's source", 0L to Action.Switch(usb), c.first())
    }

    @Test fun sourceResumingOrStoppingByItselfWithinTenSeconds() {
        // Pause wanted: the radio resumes 3 s after the state was applied (the sound's focus given back)
        val s = seq(play = false)
        val a = run(s, 0, 13_000) { t -> Input(t, available = listOf(radio, bt), carSource = bt, playing = t in 4_500..5_000) }
        assertEquals("the pause with the switch, then again each time it resumes", listOf(0L to Action.Switch(bt), 0L to Action.Pause, 4_500L to Action.Pause, 5_000L to Action.Pause), a)
        // Play wanted: the source stops by itself, play again
        val p = seq(play = true)
        val b = run(p, 0, 13_000) { t -> Input(t, available = listOf(radio, bt), carSource = bt, playing = t > 1_500 && t !in 6_000..6_500) }
        assertEquals(listOf(0L to Action.Switch(bt), 1_500L to Action.Play, 6_000L to Action.Play), b)
        // After the 10 s: left alone
        assertTrue(p.done)
        assertTrue(p.step(Input(20_000, playing = false)).isEmpty())
    }

    @Test fun driverActionStopsAndKeepsTheChoice() {
        val s = seq(play = false)
        s.step(Input(0, available = listOf(radio, bt), carSource = radio))
        // Touch on play during the watch: stopped, the music plays on
        val a = s.step(Input(3_000, available = listOf(radio, bt), carSource = bt, playing = true, userAt = 2_900))
        assertTrue(a.isEmpty())
        assertTrue(s.done)
        assertEquals("driver's action", s.endedBy)
        // During the sound too
        val t = seq()
        t.step(Input(0, soundBusy = true))
        t.step(Input(1_000, soundBusy = true, userAt = 900))
        assertTrue(t.done)
        // A source the car changes to after it took ours: the wheel's source button, the radio's own screen
        val u = seq(play = true)
        u.step(Input(0, available = listOf(radio, bt), carSource = radio))
        u.step(Input(500, available = listOf(radio, bt), carSource = bt))
        u.step(Input(1_000, available = listOf(radio, bt), carSource = radio))
        assertEquals("source changed by the driver", u.endedBy)
    }

    /** #106: in Automatic, CarPlay (left out of the choice) the car already plays is not taken away for the radio. */
    @Test fun projectionTheCarAlreadyHasIsKept() {
        val s = seq(Mode.Auto(listOf(bt, radio)), play = true)
        val a = run(s, 0, 3_000) { t -> Input(t, available = listOf(radio, bt), carSource = "carplay", phoneDecides = { it == "carplay" }) }
        assertEquals(listOf(0L to Action.Switch("carplay")), a)
        assertEquals("left to the phone", s.endedBy)
    }

    /** #106: a projection the car plays is kept in every mode, and the radio after the wait never replaces it. */
    @Test fun projectionTheCarPlaysIsKeptInEveryMode() {
        val cp = "carplay"
        fun input(t: Long, carSource: String?) = Input(t, available = listOf(radio, bt), carSource = carSource, phoneDecides = { it == cp })
        for (mode in listOf(Mode.Auto(listOf(bt, radio)), Mode.Fixed(cp), Mode.Fixed(bt), Mode.Fixed(radio), Mode.Car)) {
            for (play in listOf(true, false)) {
                val s = seq(mode, play)
                val a = run(s, 0, 20_000) { t -> input(t, cp) }
                assertEquals("$mode, play $play: CarPlay kept, nothing else sent", listOf(0L to Action.Switch(cp)), a)
                assertEquals("left to the phone", s.endedBy)
            }
        }
    }

    /** #106: a fixed CarPlay source (never "available" to MG4Dash) connects after the start: kept, the radio not switched to. */
    @Test fun fixedCarPlayArrivingLateIsNotReplacedByTheRadio() {
        val cp = "carplay"
        val s = seq(Mode.Fixed(cp), play = false)
        // The startup sound, then CarPlay plays 12 s after the sound's end, before the 15 s wait is over
        val a = run(s, 0, 40_000) { t ->
            Input(t, soundExpected = true, soundBusy = t < 6_000, soundEndAt = if (t < 6_000) null else 6_000,
                available = listOf(radio, bt), carSource = if (t >= 18_000) cp else radio, phoneDecides = { it == cp })
        }
        assertTrue("never the radio", a.none { it.second == Action.Switch(radio) })
        assertEquals(listOf(18_000L to Action.Switch(cp)), a)
        // CarPlay there at the end of the wait too: the 15 s fallback keeps it
        val w = seq(Mode.Fixed(cp), play = true)
        val b = run(w, 0, 20_000) { t -> Input(t, available = listOf(radio), carSource = if (t >= 15_000) cp else null, phoneDecides = { it == cp }) }
        assertEquals(listOf(15_000L to Action.Switch(cp)), b)
    }

    @Test fun projectionLeavesPlaybackToThePhone() {
        val s = seq(Mode.Fixed("diauto"), play = true)
        val a = run(s, 0, 3_000) { t -> Input(t, available = listOf(radio, "diauto"), carSource = "diauto", phoneDecides = { it == "diauto" }) }
        assertEquals("the source chosen, nothing played", listOf(0L to Action.Switch("diauto")), a)
        assertEquals("left to the phone", s.endedBy)
    }

    @Test fun explicitCommandsOnly() {
        // Already in the wanted state: no command at all (a toggle here did the opposite)
        val s = seq(play = true)
        val a = run(s, 0, 13_000) { t -> Input(t, available = listOf(radio, bt), carSource = bt, playing = true) }
        assertEquals(listOf(0L to Action.Switch(bt)), a)
        // Every command is a switch, a play or a pause (exhaustive, checked by the compiler): never a toggle
        val p = run(seq(play = false), 0, 13_000) { t -> Input(t, available = listOf(radio, bt), carSource = bt, playing = true) }
        assertTrue(p.isNotEmpty() && p.all { (_, x) -> when (x) { is Action.Switch -> true; Action.Play -> true; Action.Pause -> true } })
        assertTrue("an explicit pause for a pause wanted", p.any { it.second == Action.Pause } && p.none { it.second == Action.Play })
    }

    @Test fun stateAtTheLastStop() {
        // READY frozen at the stop: it counts, whatever came after
        assertTrue(StartupSequence.lastState(frozen = true, last = false, lastAt = 1_000, before = true, aliveAt = 1_005))
        assertFalse(StartupSequence.lastState(frozen = false, last = true, lastAt = 1_000, before = true, aliveAt = 1_005))
        // No READY: a pause less than 10 s before the last heartbeat is the head unit's shutdown pause
        assertTrue("shutdown pause ignored", StartupSequence.lastState(null, last = false, lastAt = 100_000, before = true, aliveAt = 104_000))
        assertTrue("a pause after the last heartbeat too", StartupSequence.lastState(null, last = false, lastAt = 100_000, before = true, aliveAt = 98_000))
        assertFalse("a pause 10 s or more before: the driver's, kept however short", StartupSequence.lastState(null, last = false, lastAt = 100_000, before = true, aliveAt = 120_000))
        assertTrue(StartupSequence.lastState(null, last = true, lastAt = 100_000, before = false, aliveAt = 101_000))
    }
}
