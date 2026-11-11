package app.mg4dash.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import app.mg4dash.R

/**
 * Soft "whoosh" of the jump to light speed: a short sound (1.7 s, quiet) mixed over the music,
 * without taking the audio focus, so the radio or the phone go on as they were.
 */
object HyperSound {
    private const val VOLUME = 0.5f
    private var pool: SoundPool? = null
    private var id = 0
    private var ready = false
    /** Asked before the sound was loaded: played at the end of the load if still recent. */
    private var wantedAt = 0L

    fun play(context: Context) {
        runCatching {
            val p = pool ?: SoundPool.Builder().setMaxStreams(1).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ).build().also { sp ->
                pool = sp
                sp.setOnLoadCompleteListener { s, sample, status ->
                    if (status != 0) return@setOnLoadCompleteListener
                    ready = true
                    if (SystemClock.uptimeMillis() - wantedAt < 400) s.play(sample, VOLUME, VOLUME, 1, 0, 1f)
                }
                id = sp.load(context.applicationContext, R.raw.fx_hyperspace, 1)
            }
            if (ready) p.play(id, VOLUME, VOLUME, 1, 0, 1f) else wantedAt = SystemClock.uptimeMillis()
        }
    }
}
