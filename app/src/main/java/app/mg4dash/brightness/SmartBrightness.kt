package app.mg4dash.brightness

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Smart automatic brightness (20/11), next to Android's own automatic mode ("D'origine", unchanged): the screen
 * set from the light outside (estimated: [LightEstimator], [SkyForecast]) through a curve learnt from the driver's
 * corrections ([BrightnessCurve]), one per profile and per light / dark look. Off by default.
 *
 * MG4Dash then sets the brightness itself (screen_brightness, the system's automatic mode off): every minute, and within
 * about 2 s when the position lights change; each change a ramp of about 3 s. The slider of the shortcuts menu adds a
 * point: once let go and left alone [STABLE_MS], the value is learnt. 0 % is the system's own minimum (10 of 255 on the
 * car): the screen is never off.
 */
object SmartBrightness {
    private const val TAG = "SmartBrightness"
    /** The mode chosen (Settings), personal: it follows the profile. */
    const val KEY_ON = "bright_smart"
    /** The learnt curves, personal: "bright_curve_dark", "bright_curve_light". */
    const val KEY_CURVE = "bright_curve_"
    /** A correction is learnt once the slider is left alone this long. */
    const val STABLE_MS = 4_000L
    /** The target is computed again this often. */
    const val TICK_MS = 60_000L
    /** The lights read this often, a change kept once read twice in a row (about 2 s). */
    const val LIGHTS_MS = 1_000L
    /** A change: over this long, in steps of [STEP_MS]; less than [MIN_CHANGE] of the range is not done. */
    const val RAMP_MS = 3_000L
    const val STEP_MS = 100L
    const val MIN_CHANGE = 0.02
    /** On a change of the lights: the ramp starts at once and lasts this long. */
    const val LIGHTS_RAMP_MS = 1_000L

    private const val SIDE_LIGHTS = 557880099   // VEH_SIDE_LGHT: 0 off, 3 on

    @Volatile private var ctx: Context? = null
    private val worker: Handler by lazy { Handler(HandlerThread("smart-brightness").apply { start() }.looper) }

    fun enabled(c: Context): Boolean = Prefs(c).raw.getBoolean(KEY_ON, false)

    /** The mode chosen in Settings: on, MG4Dash sets the brightness; off, Android's automatic mode is put back. */
    fun setEnabled(c: Context, on: Boolean) {
        Prefs(c).raw.edit().putBoolean(KEY_ON, on).apply()
        Log.i(TAG, if (on) "smart brightness on" else "smart brightness off: Android's automatic mode")
        if (on) { start(c); worker.post { retarget(LIGHTS_RAMP_MS) } }
        else try { Settings.System.putInt(c.contentResolver, "screen_brightness_mode", 1) } catch (_: Exception) {}
    }

    /** The curve of the current look (light or dark) of the current profile. */
    fun curveKey(c: Context): String = KEY_CURVE + if (app.mg4dash.ui.Themes.isLight(c)) "light" else "dark"
    fun curve(c: Context): BrightnessCurve = BrightnessCurve.decode(Prefs(c).raw.getString(curveKey(c), null))

    /** Learning forgotten: this curve (current profile and look), or [all] of them, every profile's. */
    fun reset(c: Context, all: Boolean) {
        val raw = Prefs(c).raw
        if (all) {
            raw.edit().apply { raw.all.keys.filter { it.startsWith(KEY_CURVE) }.forEach { remove(it) } }.apply()
            app.mg4dash.system.Profiles.forgetEverywhere(c, KEY_CURVE)
        } else raw.edit().remove(curveKey(c)).apply()
        Log.i(TAG, "learning reset (${if (all) "all" else curveKey(c)})")
        worker.post { retarget(LIGHTS_RAMP_MS) }
    }

    /** The screen set from the curve again at once (AUTO touched, a profile or a look changed). */
    fun refresh(c: Context) {
        ctx = c.applicationContext
        userUntil = 0L
        worker.post { retarget(LIGHTS_RAMP_MS) }
    }

    // ── The light now ──

    /** The car's clock is believable (set from the GPS): before that, the lights alone. */
    internal fun clockOk(now: Long) = now > 1_700_000_000_000L

    /** The light (lux) at [now], at [pos] (null: unknown), the lights [lights], the sky [sky] (radiation, cloud). */
    internal fun lightNow(now: Long, pos: Pair<Double, Double>?, lights: Boolean?, sky: Pair<Double?, Double?>?): Double {
        if (!clockOk(now) || pos == null) return if (lights == true) LightEstimator.LIGHTS_LUX else 10_000.0
        val elevation = LightEstimator.sunElevation(pos.first, pos.second, now)
        return LightEstimator.estimate(elevation, sky?.first, sky?.second, lights)
    }

    /** The brightness (0 to 255) the curve gives for [lux]. */
    internal fun target(curve: BrightnessCurve, lux: Double): Int = Math.round(curve.valueAt(LightEstimator.level(lux)) * 255).toInt().coerceIn(0, 255)

    private fun readLights(): Boolean? = try {
        val v = app.mg4dash.car.CarProps.readAny(SIDE_LIGHTS)
        if (v.isEmpty()) null else v != "0" && v != "false"
    } catch (_: Exception) { null }

    // ── The loop ──

    @Volatile private var started = false
    private var lights: Boolean? = null
    private var lightsRead: Boolean? = null
    private var lightsCount = 0
    /** The value last written by this mode (0 to 255), to tell our own writes from the driver's. */
    @Volatile var written = -1
        private set
    /** Until then the driver is moving the slider: no change of ours. */
    @Volatile private var userUntil = 0L

    fun start(context: Context) {
        ctx = context.applicationContext
        if (started) return
        started = true
        SkyForecast.load(context.filesDir)
        worker.post(object : Runnable {
            override fun run() { tick(); worker.postDelayed(this, TICK_MS) }
        })
        worker.post(object : Runnable {
            override fun run() { lightsTick(); worker.postDelayed(this, LIGHTS_MS) }
        })
    }

    private fun tick() = retarget(RAMP_MS)

    private fun lightsTick() {
        val c = ctx ?: return
        if (!enabled(c)) return
        val l = readLights()
        if (l == lightsRead) lightsCount++ else { lightsRead = l; lightsCount = 1 }
        if (lightsCount == 2 && l != lights) {
            val first = lights == null
            lights = l
            if (!first) Log.i(TAG, "lights ${if (l == true) "on" else "off"}: the brightness follows")
            retarget(LIGHTS_RAMP_MS)
        }
    }

    /** The target computed again and reached over [rampMs]. */
    private fun retarget(rampMs: Long) {
        val c = ctx ?: return
        if (!enabled(c) || SystemClock.uptimeMillis() < userUntil) return
        val now = System.currentTimeMillis()
        val lux = lightNow(now, app.mg4dash.system.SunTimes.position(c), lights ?: readLights(), SkyForecast.at(now))
        val to = target(curve(c), lux)
        val from = current(c)
        if (Math.abs(to - from) < MIN_CHANGE * 255 && written >= 0) return
        ramp(c, from, to, rampMs)
    }

    private fun current(c: Context): Int = try { Settings.System.getInt(c.contentResolver, "screen_brightness") } catch (_: Exception) { 128 }

    private var rampToken = Any()

    /** From [from] to [to] over [ms], in [STEP_MS] steps: no jump, no flicker. */
    internal fun ramp(c: Context, from: Int, to: Int, ms: Long) {
        val token = Any().also { rampToken = it }
        val steps = (ms / STEP_MS).toInt().coerceAtLeast(1)
        try { if (Settings.System.getInt(c.contentResolver, "screen_brightness_mode", 0) != 0) Settings.System.putInt(c.contentResolver, "screen_brightness_mode", 0) } catch (_: Exception) {}
        for (k in 1..steps) worker.postDelayed({
            if (rampToken !== token || SystemClock.uptimeMillis() < userUntil) return@postDelayed
            val v = from + Math.round((to - from) * k / steps.toFloat())
            write(c, v)
        }, k * STEP_MS)
    }

    private fun write(c: Context, v: Int) {
        written = v
        try { Settings.System.putInt(c.contentResolver, "screen_brightness", v) } catch (e: Exception) { Log.w(TAG, "brightness: ${e.message}") }
    }

    // ── The driver's corrections ──

    private val learn = Runnable { learnNow() }
    @Volatile private var pending = -1

    /** The slider moved to [value] (0 to 255), [final] once let go: learnt when left alone [STABLE_MS]. */
    fun userSet(c: Context, value: Int, final: Boolean) {
        ctx = c.applicationContext
        if (!enabled(c)) return
        rampToken = Any()   // any change of ours stops
        userUntil = SystemClock.uptimeMillis() + STABLE_MS + 500
        worker.removeCallbacks(learn)
        pending = value
        if (final) worker.postDelayed(learn, STABLE_MS)
    }

    private fun learnNow() {
        val c = ctx ?: return
        val v = pending.takeIf { it >= 0 } ?: return
        pending = -1
        val now = System.currentTimeMillis()
        val lux = lightNow(now, app.mg4dash.system.SunTimes.position(c), lights ?: readLights(), SkyForecast.at(now))
        val cur = curve(c)
        // Only a correction: a value the curve already gives is not a point
        if (Math.abs(target(cur, lux) - v) < MIN_CHANGE * 255) { written = v; return }
        val next = cur.with(LightEstimator.level(lux), lights == true, v / 255.0, now)
        Prefs(c).raw.edit().putString(curveKey(c), next.encode()).apply()
        written = v
        Log.i(TAG, "learnt: ${Math.round(lux)} lux${if (lights == true) ", lights on" else ""} → ${Math.round(v * 100 / 255f)} % (${next.points.size} points)")
    }
}
