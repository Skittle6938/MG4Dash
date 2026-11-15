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
 * default; the three files are levelled to the same loudness (about -12 dB RMS, soft-limited) so 100 % is loud.
 * The car only lets an app be heard while it holds the audio focus (15/11: played without it, nothing came out
 * over the radio): a short transient focus is taken for each sound, as for the start sound, then given back, so the
 * radio gets its focus back and plays on. The request and the release must go through the same AudioManager (the
 * application's): Android matches them by AudioManager instance, and a release from another one was ignored, so
 * the radio stayed paused (15/11).
 */
object FxSound {
    /** Default volume of the animation sounds, in % (low: they play over the music). */
    const val DEFAULT_VOLUME = 20

    /** SoundPool volume (0..1) for a setting in % (0..100). */
    internal fun gain(percent: Int): Float = (percent.coerceIn(0, 100) / 100f)

    /** Sound tried in the settings for the chosen animation; random: each style in turn, one per trial. */
    internal fun trialStyle(setting: Int, turn: Int): Int = when (setting) {
        PowerFx.HYPERSPACE, PowerFx.LIGHTNING, PowerFx.FLAMES -> setting
        PowerFx.NONE -> PowerFx.NONE
        else -> listOf(PowerFx.LIGHTNING, PowerFx.FLAMES, PowerFx.HYPERSPACE)[Math.floorMod(turn, 3)]
    }

    /** Length of each sound (ms), the time the audio focus is held. */
    private fun lengthOf(res: Int): Long = when (res) {
        R.raw.fx_crackle -> 1000L
        R.raw.fx_fire -> 2600L
        else -> 1700L
    }

    private var focus: android.media.AudioFocusRequest? = null
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val release = Runnable { giveBack() }

    /** The audio focus for [ms]: the car lets the sound be heard (a later sound extends it). */
    private fun holdFocus(context: Context, ms: Long) {
        val appCtx = context.applicationContext
        val am = appCtx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        if (focus == null) {
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setOnAudioFocusChangeListener { }
                .build()
            runCatching { am.requestAudioFocus(req); focus = req }
                .onFailure { app.mg4dash.system.Log.w("FxSound", "audio focus: ${it.message}") }
            appContext = appCtx
        }
        main.removeCallbacks(release)
        main.postDelayed(release, ms + 300)
    }

    private var appContext: Context? = null

    private fun giveBack() {
        val req = focus ?: return
        focus = null
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        runCatching { am.abandonAudioFocusRequest(req) }
    }

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

    /** Plays the sound of [style] at the volume of the settings (or [percent] for the settings' trial). */
    fun play(context: Context, style: Int, percent: Int = Prefs(context).fxVolume) {
        val res = soundOf(style) ?: return
        val v = gain(percent)
        app.mg4dash.system.Log.i("FxSound", "style $style at $percent % (gain $v)")
        if (v <= 0f) return
        runCatching {
            val p = pool ?: SoundPool.Builder().setMaxStreams(2).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ).build().also { sp ->
                pool = sp
                sp.setOnLoadCompleteListener { s, sample, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    ready += sample
                    wanted.remove(sample)?.let { (at, vol) -> if (SystemClock.uptimeMillis() - at < 400) s.play(sample, vol, vol, 1, 0, 1f) }
                }
            }
            val id = ids.getOrPut(res) { p.load(context.applicationContext, res, 1) }
            holdFocus(context, lengthOf(res))
            if (id in ready) p.play(id, v, v, 1, 0, 1f) else wanted[id] = SystemClock.uptimeMillis() to v
        }
    }
}
