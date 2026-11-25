package app.mg4dash.memo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.cards.CardPrefs
import app.mg4dash.car.ReadyState
import app.mg4dash.system.Log
import app.mg4dash.ui.HomeSlots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.log10

/**
 * The voice memo recorder (24/11): started from the card, it records in a foreground service, so it goes on whatever
 * screen is shown, until Stop, the car switched off (READY off, screen off, shutdown) or the storage nearly full.
 * The sound goes to an ADTS file as it comes (nothing lost on a power cut), made an .m4a on Stop.
 *
 * Music while recording: cut (transient focus), lowered (ducked) or left. Calls: the recording pauses during a call
 * and goes on after, unless "record during calls" is on; then it keeps going as long as the microphone gives sound.
 */
object MemoRecorder {
    private const val TAG = "Memo"
    /** Under this much free space the recording stops (bytes). */
    const val MIN_FREE = 150L * 1024 * 1024

    /** A recording going on: [startedAt] (elapsedRealtime), the time spent paused, paused now (a call) or not. */
    data class Rec(val startedAt: Long, val pausedMs: Long, val pausedSince: Long?) {
        fun elapsed(now: Long = SystemClock.elapsedRealtime()) = now - startedAt - pausedMs - (pausedSince?.let { now - it } ?: 0L)
    }

    private val _rec = MutableStateFlow<Rec?>(null)
    val rec: StateFlow<Rec?> = _rec

    /** The microphone's last levels (0..100), newest last, for the card's bars. */
    private val _levels = MutableStateFlow<List<Int>>(emptyList())
    val levels: StateFlow<List<Int>> = _levels

    /**
     * What the card says once: a memo kept, or kept when the car switched off, with its length; or why a recording
     * could not start (microphone taken by a call, not allowed, refused).
     */
    data class Note(val kind: Kind, val ms: Long, val at: Long) { enum class Kind { SAVED, CAR_OFF, MIC_BUSY, NO_MIC, FAILED } }
    private val _note = MutableStateFlow<Note?>(null)
    val note: StateFlow<Note?> = _note

    /** The memos changed (a new one, renamed, deleted, moved): the card and the window read them again. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed
    fun touched() { _changed.value++ }

    /** The card is on screen: no floating REC pill then. */
    @Volatile var cardVisible = false
        set(v) { field = v; MemoPill.update() }

    val recording: Boolean get() = _rec.value != null

    // ── Options (card_memo_*) ──
    enum class Music { CUT, DUCK, NONE }
    fun music(c: Context) = Music.entries.getOrElse(prefs(c).int("music", 0)) { Music.CUT }
    fun calls(c: Context) = prefs(c).bool("calls", false)
    fun high(c: Context) = prefs(c).bool("high", false)
    fun prefs(c: Context) = CardPrefs(c, HomeSlots.Card.MEMO)

    /** The request code of the microphone permission asked from the card. */
    const val PERMISSION_REQUEST = 4107

    fun allowed(c: Context) = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** [c] is the screen the card is on: the microphone permission is asked there the first time. */
    fun start(c: Context) {
        if (recording) return
        val ctx = c.applicationContext
        if (inCall(ctx) && !calls(ctx)) { say(Note.Kind.MIC_BUSY); return }
        if (!allowed(ctx)) {
            val a = activity(c)
            if (a == null) { say(Note.Kind.NO_MIC); return }
            Log.i(TAG, "asking for the microphone")
            a.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            return
        }
        try { ctx.startForegroundService(Intent(ctx, MemoService::class.java).setAction(MemoService.START)) }
        catch (e: Exception) { Log.w(TAG, "service refused: ${e.message}"); say(Note.Kind.FAILED) }
    }

    /** The driver's answer to the microphone permission: recording starts when allowed, the card says so otherwise. */
    fun permissionResult(c: Context, granted: Boolean) {
        Log.i(TAG, "microphone ${if (granted) "allowed" else "refused"}")
        if (granted) start(c) else say(Note.Kind.NO_MIC)
    }

    private fun activity(c: Context): android.app.Activity? {
        var x: Context? = c
        while (x is android.content.ContextWrapper) { if (x is android.app.Activity) return x; x = x.baseContext }
        return null
    }

    private fun say(kind: Note.Kind) { _note.value = Note(kind, 0, SystemClock.elapsedRealtime()) }

    fun stop(c: Context) {
        if (!recording) return
        c.applicationContext.startService(Intent(c.applicationContext, MemoService::class.java).setAction(MemoService.STOP))
    }

    fun toggle(c: Context) = if (recording) stop(c) else start(c)

    /** A call in progress or ringing (the Bluetooth phone's calls go through Android's telecom). */
    fun inCall(c: Context): Boolean = app.mg4dash.ui.Fullscreen.inCall(c)

    /**
     * At MG4Dash's start: a recording cut short (power cut, MG4Dash killed) is made an .m4a and kept, said on the card.
     * Off the main thread.
     */
    fun recover(c: Context) {
        if (recording) return
        val ctx = c.applicationContext
        Thread {
            var last: Long? = null
            MemoFiles.dir(ctx).listFiles { f -> f.extension == MemoFiles.RECORDING_EXT }.orEmpty().forEach { f ->
                val ms = MemoFiles.lengthMs(MemoFiles.frames(f.readBytes()))
                val out = File(f.parentFile, f.nameWithoutExtension + "." + MemoFiles.EXT)
                if (ms > 0 && MemoFiles.toM4a(f, out)) { MemoFiles.saveInfo(ctx, out, ms, emptyList()); last = ms }
                if (out.exists() || ms == 0L) f.delete()
                Log.i(TAG, "recovered ${f.name}: $ms ms")
            }
            last?.let { _note.value = Note(Note.Kind.CAR_OFF, it, SystemClock.elapsedRealtime()); touched() }
        }.start()
    }

    // ── Used by the service ──
    internal fun begun() { _rec.value = Rec(SystemClock.elapsedRealtime(), 0, null); _levels.value = emptyList(); MemoPill.update() }
    internal fun level(v: Int) { _levels.value = (_levels.value + v).takeLast(24) }
    internal fun pause(on: Boolean) {
        val r = _rec.value ?: return
        val now = SystemClock.elapsedRealtime()
        _rec.value = if (on && r.pausedSince == null) r.copy(pausedSince = now)
            else if (!on && r.pausedSince != null) r.copy(pausedMs = r.pausedMs + (now - r.pausedSince), pausedSince = null) else r
    }
    internal fun ended(note: Note?) { _rec.value = null; note?.let { _note.value = it }; touched(); MemoPill.update() }
    internal fun failed(kind: Note.Kind) { _rec.value = null; say(kind); MemoPill.update() }

    /** The microphone's amplitude (0..32767) as a level 0..100 (−50 dB to 0 dB). */
    fun toLevel(amp: Int): Int = if (amp <= 0) 0 else ((20 * log10(amp / 32767.0) + 50) * 2).toInt().coerceIn(0, 100)
}

/** The recording itself, in the foreground (its notification), whatever MG4Dash shows. */
class MemoService : Service() {
    companion object {
        const val START = "app.mg4dash.memo.START"
        const val STOP = "app.mg4dash.memo.STOP"
        private const val CHANNEL = "memo"
        private const val TAG = "Memo"
        /** A call's microphone giving only silence this long: the recording pauses (ms). */
        private const val SILENT_CALL_MS = 4_000L
    }

    private var recorder: AdtsRecorder? = null
    private var file: File? = null
    private val all = ArrayList<Int>()
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var pausedForCall = false
    private var silentSince = 0L
    private var lastLevelAt = 0L
    private var tick = 0
    @Volatile private var ending = false
    /** READY seen since the recording began: only then does losing it stop the memo. */
    @Volatile private var readySeen = false

    private val carOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Log.i(TAG, "car off: ${i.action}"); finish(carOff = true) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> if (recorder == null) begin()
            STOP -> finish(carOff = false)
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wc_memo), NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_mic).setContentTitle(getString(R.string.mm_recording))
            .setOngoing(true).build()
    }

    private fun begin() {
        startForeground(41, notification())
        val high = MemoRecorder.high(this)
        val f = File(MemoFiles.dir(this), MemoFiles.stamp(System.currentTimeMillis()) + "." + MemoFiles.RECORDING_EXT)
        // Voice: about 15 MB an hour; high: 44.1 kHz, 96 kbit/s. Recorded in this process (AdtsRecorder: the media
        // server may not write a platform app's files)
        val r = AdtsRecorder(f, if (high) 44100 else 16000, if (high) 96_000 else 32_000)
        try {
            r.start()
        } catch (e: Exception) {
            Log.w(TAG, "microphone refused: ${e.javaClass.simpleName}: ${e.message}")
            runCatching { r.release() }; f.delete()
            MemoRecorder.failed(when {
                !MemoRecorder.allowed(this) -> MemoRecorder.Note.Kind.NO_MIC
                MemoRecorder.inCall(this) -> MemoRecorder.Note.Kind.MIC_BUSY
                else -> MemoRecorder.Note.Kind.FAILED
            })
            stopForeground(true); stopSelf(); return
        }
        recorder = r; file = f; all.clear(); ending = false; readySeen = false
        Log.i(TAG, "recording ${f.name}, ${if (high) "high" else "voice"}")
        takeFocus()
        registerReceiver(carOff, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SHUTDOWN) })
        MemoRecorder.begun()
        main.post(loop)
    }

    private fun takeFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val gain = when (MemoRecorder.music(this)) {
            MemoRecorder.Music.CUT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            MemoRecorder.Music.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            MemoRecorder.Music.NONE -> return
        }
        val req = AudioFocusRequest.Builder(gain).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        focus = req
        Log.i(TAG, "music focus: ${am.requestAudioFocus(req)}")
    }

    private fun dropFocus() {
        focus?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        focus = null
    }

    private val loop = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val now = SystemClock.elapsedRealtime()
            val lvl = if (pausedForCall) 0 else MemoRecorder.toLevel(runCatching { r.maxAmplitude }.getOrDefault(0))
            if (now - lastLevelAt >= 250) {
                lastLevelAt = now
                if (!pausedForCall) { all += lvl; MemoRecorder.level(lvl) }
            }
            if (tick % 10 == 0) checks(r, lvl, now)
            tick++
            MemoPill.refresh()
            main.postDelayed(this, 100)
        }
    }

    /** Once a second: a call (pause, or silence while recording a call), the storage, the car switched off. */
    private fun checks(r: AdtsRecorder, lvl: Int, now: Long) {
        val call = MemoRecorder.inCall(this)
        val keep = MemoRecorder.calls(this)
        if (call && !keep && !pausedForCall) pauseForCall(r, true)
        else if (!call && pausedForCall) pauseForCall(r, false)
        else if (call && keep && !pausedForCall) {
            // The car's microphone taken by the phone gives silence: pause as when the option is off
            if (lvl == 0) { if (silentSince == 0L) silentSince = now else if (now - silentSince > SILENT_CALL_MS) pauseForCall(r, true) } else silentSince = 0L
        }
        if (filesDir.usableSpace < MemoRecorder.MIN_FREE) { Log.w(TAG, "storage nearly full"); finish(carOff = false); return }
        // The car switched off: READY seen during the recording, then lost (25/11: a memo started parked, not in READY,
        // stopped at once)
        if (!BuildConfig.MOCK && tick % 20 == 0) Thread {
            when (ReadyState.ready()) {
                true -> readySeen = true
                false -> if (readySeen) main.post { Log.i(TAG, "READY off"); finish(carOff = true) }
                null -> {}
            }
        }.start()
    }

    private fun pauseForCall(r: AdtsRecorder, on: Boolean) {
        runCatching { if (on) r.pause() else r.resume() }.onFailure { Log.w(TAG, "pause: ${it.message}") }
        pausedForCall = on; silentSince = 0L
        MemoRecorder.pause(on)
        Log.i(TAG, if (on) "paused for a call" else "resumed after the call")
        if (on) dropFocus() else takeFocus()
    }

    /** Stop: the recording closed, made an .m4a and kept; [carOff] says so on the card. */
    private fun finish(carOff: Boolean) {
        if (ending) return
        ending = true
        main.removeCallbacks(loop)
        runCatching { unregisterReceiver(this.carOff) }
        val r = recorder; val f = file
        recorder = null; file = null
        dropFocus()
        val ms = MemoRecorder.rec.value?.elapsed() ?: 0L
        runCatching { r?.stop() }.onFailure { Log.w(TAG, "stop: ${it.message}") }
        runCatching { r?.release() }
        val levels = MemoFiles.downsample(all.toList())
        val ctx = applicationContext
        Thread {
            var kept = 0L
            if (f != null && f.exists()) {
                val real = MemoFiles.lengthMs(MemoFiles.frames(f.readBytes())).takeIf { it > 0 } ?: ms
                val out = File(f.parentFile, f.nameWithoutExtension + "." + MemoFiles.EXT)
                if (real > 0 && MemoFiles.toM4a(f, out)) { MemoFiles.saveInfo(ctx, out, real, levels); f.delete(); kept = real }
                else if (real == 0L) f.delete()
                Log.i(TAG, "kept ${out.name}: $kept ms${if (carOff) " (car off)" else ""}")
            }
            main.post {
                MemoRecorder.ended(if (kept > 0) MemoRecorder.Note(if (carOff) MemoRecorder.Note.Kind.CAR_OFF else MemoRecorder.Note.Kind.SAVED, kept, SystemClock.elapsedRealtime()) else null)
                // Card out of sight: the toast says it
                if (kept > 0 && !MemoRecorder.cardVisible) app.mg4dash.ui.Toasts.show(ctx, ctx.getString(
                    if (carOff) R.string.mm_car_off else R.string.mm_saved, MemoFiles.clock(kept)))
                stopForeground(true); stopSelf()
            }
        }.start()
    }

    override fun onDestroy() {
        // Killed while recording: the ADTS file stays, made an .m4a at the next start
        if (recorder != null && !ending) { runCatching { recorder?.stop() }; runCatching { recorder?.release() }; MemoRecorder.ended(null) }
        main.removeCallbacks(loop)
        super.onDestroy()
    }
}
