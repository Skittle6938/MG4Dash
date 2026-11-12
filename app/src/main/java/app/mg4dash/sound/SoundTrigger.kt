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
import java.io.RandomAccessFile

/** Plays startup and preview sounds on a worker thread without blocking the UI. */
object SoundTrigger {
    private const val TAG = "SoundTrigger"
    private const val SAFETY_MARGIN_MS = 3_000L
    /** Audio focus kept after the startup sound when a pause is wanted, at most. */
    private const val HOLD_FOCUS_MS = 3_000L
    /** Volume at 0 during the startup pause, at most. */
    const val SILENCE_MAX_MS = 2_000L

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

    /** A startup sound is to play at this start (on, with a file): the sources wait for its end (#36). */
    @Volatile var bootSoundExpected = false; private set
    /** End of the startup sound (uptime), 0 before it starts; set to its real end when it stops. */
    @Volatile var bootSoundUntil = 0L; private set
    private var bootPlaying = false

    /**
     * A pause is wanted at this start (#36): the audio focus is kept up to 3 s after the startup sound, so that the
     * radio does not resume by itself meanwhile; MediaHub gives it back ([releaseBootFocus]) once its pause is sent.
     */
    @Volatile var holdFocusAfterBoot = false

    /** The startup sound is set (on, with a file): known before it is triggered, as at [onBootOrAppStart]. */
    fun bootSoundConfigured(context: Context) =
        SoundTriggerSettings.isBootTriggerEnabled(context) && !SoundTriggerSettings.getSelectedSoundPath(context).isNullOrEmpty()

    /** The audio focus kept after the startup sound, given back now. */
    fun releaseBootFocus() { threadHandler.post { if (!isPlaying) releaseAudioFocus() } }

    /** Media volume before [silenceForPause], restored by [endSilence]; -1 when not silenced. */
    private var silenceBefore = -1
    private var silenceRoute: CarMediaVolume.Route? = null
    private var silenceEnd: Runnable? = null

    /**
     * A pause wanted at car start (#36): the car's media volume at 0 from the source switch until the source is really
     * paused (the SAIC radio starts about 1 s after the switch and was heard for a moment before the pause took),
     * [maxMs] at most. The volume is restored by [endSilence], at the latest after [maxMs]; a restore record is kept,
     * so a crash meanwhile is undone at the next start ([restoreInterruptedCarVolume]).
     */
    fun silenceForPause(context: Context, maxMs: Long = SILENCE_MAX_MS) {
        val ctx = context.applicationContext.also { appCtx = it }
        threadHandler.post {
            if (silenceBefore >= 0 || isPlaying) return@post
            if (SoundTriggerSettings.getPendingVolumeRestore(ctx) != null) { Log.w(TAG, "silence: a volume restore is pending, volume left"); return@post }
            val route = CarMediaVolume.route(ctx) ?: run { Log.w(TAG, "silence: no volume route"); return@post }
            val before = CarMediaVolume.read(route)
            if (before <= 0) return@post
            if (!SoundTriggerSettings.savePendingVolumeRestore(ctx, route.channel.name, route.max, route.group, before, 0)) return@post
            if (!CarMediaVolume.write(route, 0)) Log.w(TAG, "silence: volume 0 not verified")
            silenceRoute = route; silenceBefore = before
            Log.i(TAG, "silence until the pause: media volume $before → 0")
            silenceEnd = Runnable { endSilenceNow("${maxMs / 1000} s at most") }.also { threadHandler.postDelayed(it, maxMs) }
        }
    }

    /** The source is paused (or the sequence is over): the volume of [silenceForPause] back. */
    fun endSilence(why: String) { threadHandler.post { endSilenceNow(why) } }

    private fun endSilenceNow(why: String) {
        val route = silenceRoute ?: return
        silenceEnd?.let { threadHandler.removeCallbacks(it) }; silenceEnd = null
        val before = silenceBefore
        silenceRoute = null; silenceBefore = -1
        val current = CarMediaVolume.read(route)
        when {
            current == 0 -> if (CarMediaVolume.write(route, before)) {
                appCtx?.let { SoundTriggerSettings.clearPendingVolumeRestore(it) }
                Log.i(TAG, "silence over ($why): media volume 0 → $before")
            } else Log.w(TAG, "silence over ($why): volume not restored, kept for the next start")
            current > 0 -> { appCtx?.let { SoundTriggerSettings.clearPendingVolumeRestore(it) }; Log.i(TAG, "silence over ($why): volume changed meanwhile ($current), left") }
            else -> Log.w(TAG, "silence over ($why): volume unreadable, restore kept for the next start")
        }
    }

    /** The startup sound is to play or playing at [now] (uptime). */
    fun bootSoundBusy(now: Long = android.os.SystemClock.uptimeMillis()) = bootSoundExpected && (bootSoundUntil == 0L || now < bootSoundUntil)

    /** The startup sound played or is playing, and ended less than [ms] ago. */
    fun bootSoundRecent(ms: Long, now: Long = android.os.SystemClock.uptimeMillis()) = bootSoundExpected && (bootSoundUntil == 0L || now < bootSoundUntil + ms)

    /** Trigger startup playback when the main interface opens. */
    fun onBootOrAppStart(context: Context) {
        val ctx = context.applicationContext.also { appCtx = it }
        if (bootTriggered) return
        bootTriggered = true
        bootSoundExpected = SoundTriggerSettings.isBootTriggerEnabled(ctx) && !SoundTriggerSettings.getSelectedSoundPath(ctx).isNullOrEmpty()

        threadHandler.post {
            restoreInterruptedCarVolume(ctx)
            if (!SoundTriggerSettings.isBootTriggerEnabled(ctx)) {
                return@post
            }
            val soundPath = SoundTriggerSettings.getSelectedSoundPath(ctx)
            if (soundPath.isNullOrEmpty()) {
                Log.i(TAG, "Boot trigger enabled but no audio file selected")
                return@post
            }
            Log.i(TAG, "Triggering instant start sound: $soundPath")
            playSoundFile(ctx, File(soundPath), isTest = false) { if (bootSoundUntil == 0L) bootSoundUntil = android.os.SystemClock.uptimeMillis() }
        }
    }

    /** Play an audio file for startup playback or preview. */
    fun playSoundFile(context: Context, file: File, isTest: Boolean = false, onComplete: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        threadHandler.post {
            stopPlayback("new playback request")

            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "Audio file not accessible: ${file.absolutePath}")
                onComplete?.let { Handler(context.mainLooper).post(it) }
                return@post
            }

            if (file.length() > SoundImporter.MAX_FILE_SIZE_BYTES) {
                Log.w(TAG, "Audio file exceeds the ${SoundImporter.MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB limit")
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
                player.setDataSource(FileAudioSource(file))
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
                if (!isTest && bootSoundExpected && bootSoundUntil == 0L) { bootPlaying = true; bootSoundUntil = android.os.SystemClock.uptimeMillis() + duration }
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

    /** Stop the current playback. */
    fun stop(context: Context? = null) {
        threadHandler.post { stopPlayback("user stopped") }
    }

    private fun stopPlayback(reason: String) {
        timeoutRunnable?.let { threadHandler.removeCallbacks(it) }
        timeoutRunnable = null

        val wasPlaying = isPlaying
        isPlaying = false
        val wasBoot = bootPlaying
        if (bootPlaying) { bootPlaying = false; bootSoundUntil = minOf(bootSoundUntil, android.os.SystemClock.uptimeMillis()) }

        mediaPlayer?.let { p ->
            runCatching { if (p.isPlaying) p.stop() }
            runCatching { p.release() }
        }
        mediaPlayer = null

        restoreCarVolume()
        // A pause wanted at startup (#36): the focus is kept a moment, the radio would resume as soon as it is free
        if (wasBoot && holdFocusAfterBoot) {
            holdFocusAfterBoot = false
            Log.i(TAG, "audio focus kept after the startup sound (pause wanted)")
            threadHandler.postDelayed({ if (!isPlaying) releaseAudioFocus() }, HOLD_FOCUS_MS)
        } else releaseAudioFocus()
        releaseWakeLock()

        if (wasPlaying) {
            Log.i(TAG, "Playback ended: $reason")
        }
    }

    private fun applyTargetVolume(context: Context) {
        if (SoundTriggerSettings.getPendingVolumeRestore(context) != null) {
            Log.w(TAG, "A previous car volume restore is still pending; leaving volume unchanged")
            return
        }

        // Give the SAIC audio service time to connect during startup, as MG4Control does.
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
        if (!SoundTriggerSettings.savePendingVolumeRestore(
                context, route.channel.name, route.max, route.group, before, target
            )) {
            Log.w(TAG, "Could not persist the original car media volume; leaving it unchanged")
            return
        }
        volumeRoute = route
        volumeBefore = before
        volumeWritten = target
        if (CarMediaVolume.write(route, target)) {
            Log.i(TAG, "Car media volume temporarily set $before → $target/${route.max}")
        } else {
            Log.w(TAG, "Could not verify car media volume $target/${route.max}; keeping the restore record")
        }
    }

    private fun restoreInterruptedCarVolume(context: Context) {
        val pending = SoundTriggerSettings.getPendingVolumeRestore(context) ?: return
        val route = CarMediaVolume.route(context, waitForSaicMs = 2_000) ?: run {
            Log.w(TAG, "Car volume route unavailable; keeping the pending restore for the next start")
            return
        }
        if (route.channel.name != pending.channel || route.max != pending.max || route.group != pending.group) {
            Log.w(TAG, "Car volume route changed; keeping the pending restore for the next start")
            return
        }

        val current = CarMediaVolume.read(route)
        when {
            current == pending.written -> {
                if (CarMediaVolume.write(route, pending.before)) {
                    SoundTriggerSettings.clearPendingVolumeRestore(context)
                    Log.i(TAG, "Restored car media volume after interrupted playback")
                } else {
                    Log.w(TAG, "Could not restore car media volume; keeping the pending restore")
                }
            }
            current >= 0 -> {
                SoundTriggerSettings.clearPendingVolumeRestore(context)
                Log.i(TAG, "Car media volume changed since playback; leaving it unchanged")
            }
            else -> Log.w(TAG, "Could not read car media volume; keeping the pending restore")
        }
    }

    private fun restoreCarVolume() {
        val route = volumeRoute ?: return
        val current = CarMediaVolume.read(route)
        if (current == volumeWritten && volumeBefore >= 0) {
            if (CarMediaVolume.write(route, volumeBefore)) {
                appCtx?.let { SoundTriggerSettings.clearPendingVolumeRestore(it) }
                Log.i(TAG, "Restored car media volume $volumeWritten → $volumeBefore")
            } else {
                Log.w(TAG, "Could not restore car media volume; keeping the pending restore")
            }
        } else if (current >= 0) {
            appCtx?.let { SoundTriggerSettings.clearPendingVolumeRestore(it) }
            Log.i(TAG, "Car media volume changed during sound ($current); leaving it unchanged")
        } else {
            Log.w(TAG, "Could not read car media volume; keeping the pending restore")
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

    private class FileAudioSource(file: File) : MediaDataSource() {
        private val input = RandomAccessFile(file, "r")

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int = synchronized(input) {
            val length = input.length()
            if (position >= length) return@synchronized -1
            input.seek(position)
            input.read(buffer, offset, minOf(size.toLong(), length - position).toInt())
        }

        override fun getSize(): Long = input.length()

        override fun close() {
            input.close()
        }
    }

}
