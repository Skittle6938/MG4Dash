package app.mg4dash

import app.mg4dash.radar.RadarRepo
import app.mg4dash.radar.RadarSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The alerter's speed: the car's, read often, set right on the GPS on steady stretches (18/11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RadarSpeedTest {

    /** One fix a second for [n] s at [car] / [gps] km/h, steady and precise, from [t0]. */
    private fun RadarSpeed.cruise(t0: Long, n: Int, car: Float, gps: Float, acc: Float = 5f, bearing: (Int) -> Float = { 90f }): Int {
        var folded = 0
        for (i in 0..n) if (gps(t0 + i * 1000L, car, gps, acc, 0.3f, bearing(i))) folded++
        return folded
    }

    @Test fun factorUsedOnlyAfterThreeStretches() {
        val s = RadarSpeed()
        assertEquals(100f, s.shown(100f, 96f)!!, 0.001f)
        // The dashboard 4 % high: the GPS 96 for the car's 100
        assertEquals(1, s.cruise(0, 5, 100f, 96f))
        assertEquals(1f, s.used, 0f)
        s.cruise(10_000, 5, 100f, 96f); s.cruise(20_000, 5, 100f, 96f)
        assertEquals(3, s.windows)
        assertEquals(0.96f, s.used, 0.001f)
        assertEquals(48f, s.shown(50f, null)!!, 0.01f)
    }

    @Test fun onlySteadyStretchesCount() {
        // Too slow
        assertEquals(0, RadarSpeed().cruise(0, 8, 25f, 24f))
        // A poor fix
        assertEquals(0, RadarSpeed().cruise(0, 8, 100f, 96f, acc = 30f))
        // Turning 10°/s
        assertEquals(0, RadarSpeed().cruise(0, 8, 100f, 96f, bearing = { it * 10f }))
        // Accelerating 1 m/s² (3.6 km/h a second)
        val s = RadarSpeed()
        var folded = 0
        for (i in 0..8) if (s.gps(i * 1000L, 60f + i * 3.6f, 58f + i * 3.6f, 5f, 0.3f, 90f)) folded++
        assertEquals(0, folded)
        // A gap in the fixes starts the stretch again
        val g = RadarSpeed()
        assertFalse(g.gps(0, 100f, 96f, 5f, null, 90f))
        assertFalse(g.gps(4_000, 100f, 96f, 5f, null, 90f))
        assertFalse(g.gps(8_000, 100f, 96f, 5f, null, 90f))
        assertEquals(0, g.windows)
        // Heading across north (359° → 1°) is steady
        assertEquals(1, RadarSpeed().cruise(0, 5, 100f, 96f, bearing = { if (it % 2 == 0) 359f else 1f }))
    }

    @Test fun factorSmoothedAndBounded() {
        val s = RadarSpeed(0.96f, 10)
        // One stretch at 1.00 moves it a fifth of the way
        s.cruise(0, 5, 100f, 100f)
        assertEquals(0.968f, s.k, 0.001f)
        // A ratio far off is not taken at all
        val w = s.windows
        s.cruise(10_000, 5, 100f, 70f)
        assertEquals(w, s.windows)
        // Within the bounds, whatever is stored
        assertEquals(RadarSpeed.MAX_K, RadarSpeed(1.3f, 5).k, 0f)
        assertEquals(RadarSpeed.MIN_K, RadarSpeed(0.5f, 5).k, 0f)
    }

    @Test fun fallbacks() {
        val s = RadarSpeed(0.95f, 5)
        // No car speed: the GPS's
        assertEquals(80f, s.shown(null, 80f)!!, 0f)
        // No GPS (a tunnel): the car's times the factor
        assertEquals(95f, s.shown(100f, null)!!, 0.001f)
        assertNull(s.shown(null, null))
    }

    /**
     * 20/11: the speed shown stopped for a few seconds, then went on. The worker was busy (tiles read from disk,
     * the roads' graph rebuilt, the matching): the speed is read on a thread of its own and keeps following the car.
     */
    @Test fun speedFollowsWhileTheWorkerIsBusy() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("radar_speed", android.content.Context.MODE_PRIVATE).edit().putFloat("k", 1f).putInt("windows", 0).commit()
        val before = RadarRepo.carSpeed
        val v = java.util.concurrent.atomic.AtomicReference(50f)
        RadarRepo.carSpeed = { v.get() }
        RadarRepo.start(ctx)
        try {
            fun waitFor(want: Float, withinMs: Long) {
                val until = System.currentTimeMillis() + withinMs
                while (System.currentTimeMillis() < until) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                    val got = RadarRepo.state.value.speedKmh
                    if (got != null && Math.abs(got - want) < 0.01f) return
                    Thread.sleep(30)
                }
                throw AssertionError("speed ${RadarRepo.state.value.speedKmh}, wanted $want within $withinMs ms")
            }
            waitFor(50f, 5_000)
            // The worker busy for 4 s, as with a big tile rebuilt
            val worker = RadarRepo::class.java.getDeclaredField("worker\$delegate").apply { isAccessible = true }.get(RadarRepo)
                .let { (it as Lazy<*>).value as android.os.Handler }
            val busy = java.util.concurrent.CountDownLatch(1)
            worker.post { busy.countDown(); Thread.sleep(4_000) }
            busy.await()
            v.set(90f)
            // Within a second, not after the worker's 4 s
            waitFor(90f, 1_500)
        } finally {
            RadarRepo.stop()
            RadarRepo.carSpeed = before
        }
    }

    /** Between the GPS fixes, the car's speed reaches the card at once, without any position. */
    @Test fun carSpeedReachesTheStateWithoutFixes() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("radar_speed", android.content.Context.MODE_PRIVATE).edit().putFloat("k", 0.95f).putInt("windows", 5).commit()
        val before = RadarRepo.carSpeed
        var v = 40f
        RadarRepo.carSpeed = { v }
        RadarRepo.start(ctx)
        try {
            fun waitFor(want: Float) {
                val until = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < until) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
                    val got = RadarRepo.state.value.speedKmh
                    if (got != null && Math.abs(got - want) < 0.01f) return
                    Thread.sleep(50)
                }
                throw AssertionError("speed ${RadarRepo.state.value.speedKmh}, wanted $want")
            }
            waitFor(38f)
            v = 100f
            waitFor(95f)
            assertTrue(RadarRepo.running)
        } finally {
            RadarRepo.stop()
            RadarRepo.carSpeed = before
        }
    }
}
