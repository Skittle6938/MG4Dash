package app.mg4dash.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * Sounds of the full power animations: the whoosh of the jump to light speed, the electric crackle of the
 * lightning and the fire of the flames. One volume for all of them (Settings › Look, under the animation), low by
 * default; the three files are levelled to the same loudness (about -12 dB RMS, soft-limited).
 * Mixed over whatever plays, which goes on at its own volume (15/11): played as system sounds, on the car's
 * system channel, with no audio focus. The MG4 mixes that channel with the radio or the music without lowering or
 * pausing them (heard on the car, 15/11). A media sound needs the focus to be heard over the radio, and the focus
 * paused it (media) or lowered it (navigation prompt).
 */
object FxSound {
    /** Default volume of the animation sounds, in % (low: the system channel is loud). */
    const val DEFAULT_VOLUME = 30

    /** SoundPool volume at 100 %: the system channel is loud, a quarter of full gain is plenty (15/11). */
    internal const val MAX_GAIN = 0.25f

    /** SoundPool volume (0..MAX_GAIN) for a setting in % (0..100). */
    internal fun gain(percent: Int): Float = percent.coerceIn(0, 100) / 100f * MAX_GAIN

    /** Sound tried in the settings for the chosen animation; random: each style in turn, one per trial. */
    internal fun trialStyle(setting: Int, turn: Int): Int = when (setting) {
        PowerFx.HYPERSPACE, PowerFx.LIGHTNING, PowerFx.FLAMES -> setting
        PowerFx.NONE -> PowerFx.NONE
        else -> listOf(PowerFx.LIGHTNING, PowerFx.FLAMES, PowerFx.HYPERSPACE)[Math.floorMod(turn, 3)]
    }

    /** System sounds: the car's system channel, mixed over the source without taking it. */
    internal val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    /** Sound of an animation style, null when it has none. */
    internal fun soundOf(style: Int): Int? = when (style) {
        PowerFx.HYPERSPACE -> R.raw.fx_hyperspace
        PowerFx.LIGHTNING -> R.raw.fx_crackle
        PowerFx.FLAMES -> R.raw.fx_fire
        else -> null
    }

    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()          // raw resource → sample id
    private val ready = HashSet<Int>()             // sample ids loaded
    /** Asked before its sound was loaded: played at the end of the load if still recent. */
    private val wanted = HashMap<Int, Pair<Long, Float>>()

    /** The sounds are on (Settings › Look), off by default. */
    fun enabled(context: Context): Boolean = Prefs(context).fxSound

    /** Plays the sound of [style] at the volume of the settings (or [percent] for the settings' trial), if on. */
    fun play(context: Context, style: Int, percent: Int = Prefs(context).fxVolume) {
        if (!enabled(context)) return
        val res = soundOf(style) ?: return
        val v = gain(percent)
        app.mg4dash.system.Log.i("FxSound", "style $style at $percent % (gain $v)")
        if (v <= 0f) return
        playRaw(context, res, v)
    }

    /** Gain of the speed camera alerter's beeps: the effects' volume, never under an audible minimum. */
    internal const val MIN_ALERT_GAIN = 0.12f
    /**
     * The alerts' own scale, louder than the effects' (17/11: too quiet in the car at 100 %): 100 % is SoundPool's
     * full volume, four times the effects' top, on the same channel (mixed over the source, nothing lowered).
     */
    internal const val ALERT_MAX_GAIN = 1f
    internal fun alertGain(percent: Int): Float = maxOf(percent.coerceIn(0, 100) / 100f * ALERT_MAX_GAIN, MIN_ALERT_GAIN)

    /**
     * A beep of the speed camera alerter ([strong]: the strong alert), on the same channel as the effects and at their
     * volume, but whether the effects are on or not: the alerter has its own sound setting.
     */
    fun alert(context: Context, strong: Boolean, percent: Int = Prefs(context).fxVolume) =
        playRaw(context, if (strong) R.raw.fx_radar_strong else R.raw.fx_radar_light, alertGain(percent))

    /** An alerter sound ([res]) at [percent] of the alerts' scale; 0 % is silent. */
    fun alertRaw(context: Context, res: Int, percent: Int) {
        if (percent <= 0) return
        playRaw(context, res, alertGain(percent))
    }

    private fun playRaw(context: Context, res: Int, v: Float) {
        runCatching {
            val p = pool ?: SoundPool.Builder().setMaxStreams(2).setAudioAttributes(ATTRIBUTES).build().also { sp ->
                pool = sp
                sp.setOnLoadCompleteListener { s, sample, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    ready += sample
                    wanted.remove(sample)?.let { (at, vol) -> if (SystemClock.uptimeMillis() - at < 400) s.play(sample, vol, vol, 1, 0, 1f) }
                }
            }
            val id = ids.getOrPut(res) { p.load(context.applicationContext, res, 1) }
            if (id in ready) p.play(id, v, v, 1, 0, 1f) else wanted[id] = SystemClock.uptimeMillis() to v
        }
    }
}
