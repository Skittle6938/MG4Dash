package app.mg4dash.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import app.mg4dash.car.CarMediaVolume
import app.mg4dash.system.Log
import java.io.File

/**
 * Gestore dell'esecuzione immediata dell'audio al trigger (es. Boot / Avvio app).
 * Esegue in un thread dedicato per non bloccare l'UI o il caricamento di MainActivity.
 */
object SoundTrigger {
    private const val TAG = "SoundTrigger"
    private const val SAFETY_MARGIN_MS = 3_000L

    private val threadHandler by lazy {
        val ht = HandlerThread("mg4dash-sound").apply { start() }
        Handler(ht.looper)
    }

    private var mediaPlayer: MediaPlayer? = null
    private var audioFocus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var timeoutRunnable: Runnable? = null
    private var volumeRoute: CarMediaVolume.Route? = null
    private var volumeBefore = -1
    private var volumeWritten = -1
    private var isPlaying = false
    private var bootTriggered = false
    @Volatile private var appCtx: Context? = null

    /**
     * Trigger istantaneo all'apertura dell'interfaccia principale (MainActivity / LauncherApp).
     */
    fun onBootOrAppStart(context: Context) {
        val ctx = context.applicationContext.also { appCtx = it }
        if (bootTriggered) return
        bootTriggered = true

        threadHandler.post {
            if (!SoundTriggerSettings.isBootTriggerEnabled(ctx)) {
                return@post
            }
            val soundPath = SoundTriggerSettings.getSelectedSoundPath(ctx)
            if (soundPath.isNullOrEmpty()) {
                Log.i(TAG, "Boot trigger enabled but no audio file selected")
                return@post
            }
            Log.i(TAG, "Triggering instant start sound: $soundPath")
            playSoundFile(ctx, File(soundPath), isTest = false)
        }
    }

    /**
     * Riproduce un file audio (può essere usato sia per i trigger che per l'anteprima/test).
     */
    fun playSoundFile(context: Context, file: File, isTest: Boolean = false, onComplete: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        threadHandler.post {
            stopPlayback("new playback request")

            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "Audio file not accessible: ${file.absolutePath}")
                onComplete?.let { Handler(context.mainLooper).post(it) }
                return@post
            }

            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading audio file: ${e.message}")
                onComplete?.let { Handler(context.mainLooper).post(it) }
                return@post
            }

            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                player.setDataSource(MemoryAudioSource(bytes))
                player.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "MediaPlayer prepare error: ${e.message}")
                runCatching { player.release() }
                onComplete?.let { Handler(context.mainLooper).post(it) }
                return@post
            }

            mediaPlayer = player
            val duration = player.duration.coerceAtLeast(500).toLong()

            acquireWakeLock(appCtx, duration)
            requestAudioFocus(appCtx)
            applyTargetVolume(appCtx)

            player.setOnCompletionListener {
                threadHandler.post {
                    stopPlayback("completed")
                    onComplete?.let { Handler(context.mainLooper).post(it) }
                }
            }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error ($what, $extra)")
                threadHandler.post {
                    stopPlayback("error")
                    onComplete?.let { Handler(context.mainLooper).post(it) }
                }
                true
            }

            try {
                player.start()
                isPlaying = true
                timeoutRunnable = Runnable {
                    stopPlayback("timeout")
                    onComplete?.let { Handler(context.mainLooper).post(it) }
                }.also {
                    threadHandler.postDelayed(it, duration + SAFETY_MARGIN_MS)
                }
                Log.i(TAG, "Started playing sound (${duration}ms, test=$isTest)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start player: ${e.message}")
                stopPlayback("start failed")
                onComplete?.let { Handler(context.mainLooper).post(it) }
            }
        }
    }

    /**
     * Interrompe la riproduzione in corso.
     */
    fun stop(context: Context? = null) {
        threadHandler.post { stopPlayback("user stopped") }
    }

    private fun stopPlayback(reason: String) {
        timeoutRunnable?.let { threadHandler.removeCallbacks(it) }
        timeoutRunnable = null

        val wasPlaying = isPlaying
        isPlaying = false

        mediaPlayer?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        mediaPlayer = null

        restoreCarVolume()
        releaseAudioFocus()
        releaseWakeLock()

        if (wasPlaying) {
            Log.i(TAG, "Playback ended: $reason")
        }
    }

    private fun applyTargetVolume(context: Context) {
        // Laisse au service audio SAIC le temps de se connecter au démarrage, comme MG4Control.
        val route = CarMediaVolume.route(context, waitForSaicMs = 2_000)
        if (route == null) {
            Log.w(TAG, "Car media volume route unavailable; leaving vehicle volume unchanged")
            return
        }
        val before = CarMediaVolume.read(route)
        if (before < 0) {
            Log.w(TAG, "Could not read car media volume on ${route.channel}")
            return
        }
        val requested = SoundTriggerSettings.getVolumeLevel(context)
        val target = CarMediaVolume.nativeLevel(requested, route)
        if (target == before) {
            Log.i(TAG, "Car media volume already at $target/${route.max}")
            return
        }
        if (CarMediaVolume.write(route, target)) {
            volumeRoute = route
            volumeBefore = before
            volumeWritten = target
            Log.i(TAG, "Car media volume temporarily set $before → $target/${route.max}")
        } else {
            Log.w(TAG, "Failed to set car media volume to $target/${route.max}")
        }
    }

    private fun restoreCarVolume() {
        val route = volumeRoute ?: return
        val current = CarMediaVolume.read(route)
        if (current == volumeWritten && volumeBefore >= 0) {
            CarMediaVolume.write(route, volumeBefore)
            Log.i(TAG, "Restored car media volume $volumeWritten → $volumeBefore")
        } else {
            Log.i(TAG, "Car media volume changed during sound ($current); leaving it unchanged")
        }
        volumeRoute = null
        volumeBefore = -1
        volumeWritten = -1
    }

    private fun requestAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        try {
            am.requestAudioFocus(req)
            audioFocus = req
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus request failed: ${e.message}")
        }
    }

    private fun releaseAudioFocus() {
        val req = audioFocus ?: return
        audioFocus = null
        try {
            val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.abandonAudioFocusRequest(req)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus release failed: ${e.message}")
        }
    }

    private fun acquireWakeLock(context: Context, durationMs: Long) {
        releaseWakeLock()
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mg4dash:sound_trigger").apply {
            setReferenceCounted(false)
            acquire(durationMs + SAFETY_MARGIN_MS * 2)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private class MemoryAudioSource(private val bytes: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= bytes.size) return -1
            val n = minOf(size.toLong(), bytes.size - position).toInt()
            System.arraycopy(bytes, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = bytes.size.toLong()

        override fun close() {}
    }
}
