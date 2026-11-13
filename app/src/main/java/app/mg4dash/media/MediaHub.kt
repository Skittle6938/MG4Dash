package app.mg4dash.media

import com.saicmotor.common.CommandParcelable as SaicCommand
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URLDecoder

/**
 * Current player, whatever the source (SAIC DAB/FM radio, Bluetooth, USB, Android Auto…).
 * Relies on MediaSessionManager.getActiveSessions(), reserved to the system uid
 * (MEDIA_CONTENT_CONTROL): this is what the stock launcher does.
 */
object MediaHub {
    private const val TAG = "Media"

    data class NowPlaying(
        val available: Boolean = false,
        val packageName: String? = null,
        val source: String = "",
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        /** FM or AM radio: [artist] holds the frequency, if the title is the station name. */
        val analog: Boolean = false,
        /** RDS text of the FM station, sent by Radio Dash. */
        val radioText: String = "",
        /** Android Auto connected (its player has something to show): the AA chip is offered. */
        val androidAuto: Boolean = false,
    )

    private val _now = MutableStateFlow(NowPlaying())
    val now: StateFlow<NowPlaying> = _now

    private val main = Handler(Looper.getMainLooper())
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    private var manager: MediaSessionManager? = null
    private var current: MediaController? = null
    private var lastArtKey: String? = null
    /** Attempts to read the current track's cover (0: nothing pending). */
    @Volatile private var artTries = 0
    /** Cover address last tried, and attempt running or scheduled (only one at a time). */
    @Volatile private var lastArtUri: String? = null
    @Volatile private var artPending = false

    fun start(context: Context) {
        appContext = context.applicationContext
        // A wheel mute left by a crash (#98): undone once the car service is there
        if (!BuildConfig.MOCK) context.applicationContext.let { c -> main.postDelayed({ Thread { app.mg4dash.car.CarMediaMute.restoreAtStart(c) }.start() }, 8_000) }
        if (BuildConfig.MOCK) {
            _now.value = NowPlaying(true, "com.saicmotor.radio", "RADIO DAB", "Radio Example", "Morning show", "DAB+ · 11B", mockLogo(), true, androidAuto = true)
            return
        }
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        manager = msm
        try {
            msm.addOnActiveSessionsChangedListener({ reevaluate() }, null, main)
        } catch (e: SecurityException) {
            Log.w(TAG, "MEDIA_CONTENT_CONTROL denied (system uid required): ${e.message}")
        }
        // Found on the car: SAIC sessions (radio, Bluetooth) stay "inactive"
        // even while playing, so they are missing from getActiveSessions(). Like the stock launcher,
        // connect directly to their MediaBrowserService to get their controller.
        BROWSERS.filterNot { serviceMedia && it.packageName == USB }.forEach { connectBrowser(it) }
        listenRdsText(context)
        if (serviceMedia) {
            SaicMedia.connect(context)
            // No callback read on the USB side: reread the track every 2 s, like the old SDK radio.
            main.postDelayed(object : Runnable {
                override fun run() {
                    io.launch {
                        val before = SaicMedia.last; SaicMedia.refresh()
                        if (SaicMedia.last != before) main.post {
                            // A stick plugged in under CarPlay (#106): selectProjectionSource pauses the USB player first
                            if (usbTakesTheBar(before?.playing, SaicMedia.last?.playing, carPlay, pinnedPkg, sourcePkg)) selectProjectionSource()
                            reevaluate()
                        }
                    }
                    main.postDelayed(this, 2_000)
                }
            }, 1_000)
        }
        if (legacyRadio) {
            SaicRadio.connect(context)
            // No callback on the radio side: reread the station every 2 s.
            main.postDelayed(object : Runnable {
                override fun run() {
                    io.launch { val before = SaicRadio.last; SaicRadio.refresh(); if (SaicRadio.last != before) main.post { reevaluate() } }
                    main.postDelayed(this, 2_000)
                }
            }, 1_000)
        }
        bindAndroidAuto()
        app.mg4dash.car.ReadyState.init(context)
        startSequence()
        watchProjection(context.applicationContext)
        watchAudioSource(context)
        reevaluate()
        // Safety net: regular reread of the player (title, cover, state), in case a
        // radio callback gets lost (radio service restarted, session replaced).
        main.postDelayed(object : Runnable {
            override fun run() { checkStaleBrowsers(); reevaluate(); main.postDelayed(this, 5_000) }
        }, 5_000)
    }

    /**
     * Active audio source according to the car (SAIC AudioManager API, like the stock launcher):
     * it decides the player shown, the radio staying "playing" even when muted.
     */
    @Volatile private var sourcePkg: String? = null
        set(v) { if (v != field && field != USB) sourceBeforeUsb = field; field = v }
    /** The car's source before it went to USB: shown instead while the startup sound plays with no stick (#36). */
    @Volatile private var sourceBeforeUsb: String? = null

    /** Source chosen by hand on the card (RADIO / USB-BT chips), until the car changes source. */
    @Volatile private var pinnedPkg: String? = null

    /**
     * The car also announces Android Auto as the source when only GPS guidance speaks over
     * the radio (found on 2026-10-18): the previous source is kept as long as the Android Auto
     * player is not really playing.
     */
    @Volatile private var aaUnconfirmed = false
    @Volatile private var sourceBeforeAa: String? = null

    /** Real source: the car's, except an Android Auto switch due to guidance alone. */
    private fun effectiveSource(candidates: List<MediaController> = lastCandidates): String? {
        // The startup sound played as media makes the car announce its USB player even with no stick (#36)
        if (sourcePkg == USB && usbHeldBySound(app.mg4dash.sound.SoundTrigger.bootSoundRecent(2_000), USB in availableSources())) return sourceBeforeUsb
        if (sourcePkg != AA_MBS || !aaUnconfirmed) return sourcePkg
        val aaPlaying = candidates.any { isAndroidAuto(it.packageName) && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (!aaPlaying) return sourceBeforeAa
        Log.i(TAG, "Android Auto is playing media: Android Auto source confirmed")
        aaUnconfirmed = false; sourceBeforeAa = null; pinnedPkg = null
        return sourcePkg
    }

    /** Shows the given source without opening its app. */
    fun select(pkg: String) {
        if (BuildConfig.MOCK) return
        userAction("source $pkg picked")
        remember(pkg); autoTarget = null
        pinnedPkg = pkg
        main.post { reevaluate() }
    }

    /** Android Auto connected: its player exists and has a state or a title. */
    /**
     * Phone projecting according to AllGo (reread every 5 s): false = none; null = unknown.
     * Once unplugged, the RemoteUI player kept its last title: the CarPlay / Android Auto source stayed
     * offered and led to a black screen (#36).
     */
    @Volatile private var projectionLive: Boolean? = null
    /** Phone connected over Bluetooth audio (A2DP); false: its player is no longer offered (#36). */
    @Volatile private var btLive: Boolean? = null

    private fun btConnected(): Boolean? = try {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { null }

    // ── Automatic source ("Source at startup" setting) ──
    private fun prefs() = appContext?.let { app.mg4dash.system.Prefs(it) }

    /** User's choice: at the top of the history (the radio stays the last resort). */
    private fun remember(pkg: String) {
        val p = prefs() ?: return
        val h = listOf(pkg) + p.sourceHistory.filter { it != pkg }
        if (h != p.sourceHistory) { p.sourceHistory = h.take(6); Log.i(TAG, "source history: ${h.take(6)}") }
    }

    @Volatile private var autoSwitchAt = 0L
    private val startedAt = android.os.SystemClock.uptimeMillis()
    /** Since when each source is available / missing (avoids following a flickering Bluetooth). */
    private val seenSince = mutableMapOf<String, Long>()
    private val goneSince = mutableMapOf<String, Long>()

    /**
     * Every second. In all modes, a car source gone for 1 s is replaced
     * (automatic: by the first in the history; otherwise by the radio or the fixed source). Automatic:
     * the first history source available for 5 s becomes the source if the current one is ranked
     * lower. Fixed source: a single switch at startup. CarPlay is never a target: AllGo does not
     * give it back the audio on command.
     */
    private fun autoSource() {
        val p = prefs() ?: return
        if (BuildConfig.MOCK) return
        val mode = p.startSource
        val now = android.os.SystemClock.uptimeMillis()
        val avail = availableSources()
        for (pkg in listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media", AA_MBS)) {
            if (pkg in avail) { seenSince.getOrPut(pkg) { now }; goneSince.remove(pkg) }
            else { goneSince.getOrPut(pkg) { now }; seenSince.remove(pkg) }
        }
        fun stable(pkg: String) = (seenSince[pkg]?.let { now - it >= 5_000 } == true) && !(pkg == AA_MBS && carPlay)
        recordPlayback(p)
        // Last time each car source was playing: used to restart after an announcement only what was playing
        for (pkg in CAR_SOURCES) if (browserControllers[pkg]?.let { isPlaying(it) } == true) playingAt[pkg] = now
        restoreAfterPrompt(now)
        // Type 70 (Android Auto) held by another app (DiAuto) while AllGo has no phone: the
        // real source is that app's player, not a "gone" Android Auto to replace. DiAuto
        // counts even off the card: the switch restarted paused music on Bluetooth (#78)
        if (sourcePkg == AA_MBS && projectionLive == false) {
            (current?.packageName?.takeIf { otherAppShown() } ?: lastCandidates.firstOrNull { handlesWheelKeys(it.packageName) }?.packageName)
                ?.let { sourcePkg = it }
        }
        // The startup sequence (#36) chooses the source and the state: nothing else switches meanwhile
        seqStep()
        if (seq?.done == false) return
        val cur = sourcePkg ?: return
        if (now - autoSwitchAt < 10_000 || now - startedAt < 5_000) return
        // Car source gone (phone unplugged…) for 1 s: whatever the setting, switch,
        // otherwise the car stayed on a silent source and the steering wheel buttons no longer
        // acted (#36). 1 s is enough (owner's request): the loss comes from the connection state, not from flickering.
        val curGone = goneSince[cur]?.let { now - it >= 1_000 } == true
        val order = fallbackOrder(mode, p)
        // CarPlay or Android Auto took the phone's audio (#99): its Bluetooth source going away is expected, and
        // picking the radio then started it over CarPlay's music
        val toProjection = curGone && cur == BT && (projecting || carPlay && AA_MBS in avail)
        if (toProjection && !goneToProjectionLogged) { goneToProjectionLogged = true; Log.i(TAG, "$cur gone: the phone's projection has the audio, no automatic source") }
        if (!curGone) goneToProjectionLogged = false
        if (toProjection) return
        if (curGone && !toProjection) { Log.i(TAG, "$cur gone: mode $mode, order $order, available $avail"); replacement(order, cur, avail, carPlay)?.let { autoSwitch(it, "$cur gone", play = playingState()) }; return }
        // As the car, or a fixed source: chosen once at startup (the sequence), then only replaced when lost
        if (mode >= 1) return
        // An app took the source (Android Auto, CarPlay, DiAuto starting its music…): that is intended,
        // it is not taken back to return to the radio (DiAuto cut off as its music started)
        if (cur !in listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media")) return
        // The driver's choice under way (the car not switched yet): it is not a "better source back" to switch to once more (#36)
        if (now - userPickAt < USER_PICK_MS) return
        // A better-ranked source back (the phone over Bluetooth…): it takes over in the current state, paused stays paused
        betterSource(order, cur) { stable(it) && !(it == BT && projecting) }?.let { autoSwitch(it, "$it back", play = playingState()) }
    }

    /** The source replacing [cur], lost: the first of [order] there, never CarPlay. */
    internal fun replacement(order: List<String>, cur: String, avail: List<String>, carPlay: Boolean): String? =
        order.firstOrNull { it != cur && it in avail && !(it == AA_MBS && carPlay) }

    /**
     * Automatic: a source ranked better than [cur] in [order] and [ready] (there for 5 s) takes over. A current
     * source never chosen (empty history, first use) is only left if it disappears.
     */
    internal fun betterSource(order: List<String>, cur: String, ready: (String) -> Boolean): String? {
        val target = order.firstOrNull(ready) ?: return null
        if (target == cur) return null
        val curRank = order.indexOf(cur)
        return target.takeIf { curRank >= 0 && order.indexOf(it) < curRank }
    }

    private val CAR_SOURCES = listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media")
    /** Car source before an app (DiAuto) took it for an announcement, and since when. */
    @Volatile private var promptFrom: String? = null
    @Volatile private var promptAt = 0L
    /** The previous source was playing when the announcement interrupted it: otherwise the source is returned without "play". */
    @Volatile private var promptWasPlaying = false
    private val playingAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var promptQuiet = 0
    private var promptSounding = 0

    /**
     * DiAuto guidance announcement in hybrid mode (music over Bluetooth): the car moves to DiAuto's
     * source and stays there after the announcement; the screen returns to Bluetooth but the music does not resume
     * (seen on 27/10). Once DiAuto has been silent for 2 s, the car gets its previous source back, like a
     * manual round trip, then "play". DiAuto sound for more than 25 s in a row is music
     * (music through Android Auto mode): then nothing is touched.
     */
    private fun restoreAfterPrompt(now: Long) {
        val back = promptFrom ?: return
        val app = sourcePkg
        if (app == null || !handlesWheelKeys(app) || now - promptAt > 10 * 60_000) { promptFrom = null; return }
        // Only in hybrid mode: otherwise DiAuto takes the source for its own music (log of 27/10:
        // wrong return to Bluetooth while DiAuto music was starting)
        if (!musicOverBluetooth(app)) { promptFrom = null; return }
        if (now - promptAt < 2_000) return
        if (appSounding(app)) {
            promptQuiet = 0
            if (++promptSounding > 25) { Log.i(TAG, "$app playing for a while: music, source kept"); promptFrom = null }
            return
        }
        promptSounding = 0
        if (++promptQuiet < 2) return
        promptFrom = null
        Log.i(TAG, "announcement from $app over: car source back to $back (${if (promptWasPlaying) "was playing" else "was paused, no play"})")
        // Music paused before the announcement: the source returns, but not playback (DiAuto #3: Bluetooth
        // restarted by itself after a pause, at each guidance announcement)
        autoSwitch(back, "announcement over", play = promptWasPlaying)
    }

    /**
     * DiAuto set to hybrid mode (music over Bluetooth, Android Auto for guidance only): read from
     * its settings, accessible because DiAuto runs under the same system user as MG4Dash.
     */
    private fun musicOverBluetooth(pkg: String): Boolean = try {
        java.io.File("/data/user/0/$pkg/shared_prefs/settings.xml").readText()
            .contains("name=\"music-via-bluetooth-v5\" value=\"true\"")
    } catch (_: Throwable) { false }

    /**
     * The app has a started audio player (announcement or music), from the player state seen by the
     * system. DiAuto runs as a system uid like other apps: compare the process, not the uid.
     * Unreadable: considered as playing (the source is never returned blindly).
     */
    private fun appSounding(pkg: String): Boolean = try {
        val ctx = appContext ?: throw IllegalStateException("no context")
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val pids = am.runningAppProcesses.orEmpty().filter { it.processName == pkg || it.processName.startsWith("$pkg:") }.map { it.pid }.toSet()
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        pids.isNotEmpty() && audio.activePlaybackConfigurations.any { cfg ->
            val pid = cfg.javaClass.getMethod("getClientPid").invoke(cfg) as Int
            val state = cfg.javaClass.getMethod("getPlayerState").invoke(cfg) as Int
            pid in pids && state == 2   // PLAYER_STATE_STARTED
        }
    } catch (e: Throwable) { Log.w(TAG, "$pkg players unreadable: ${(e.cause ?: e).message}"); true }

    /** Replacement sources according to the setting: automatic = history of choices, then the radio. */
    private fun fallbackOrder(mode: Int, p: app.mg4dash.system.Prefs) = when (mode) {
        0 -> (p.sourceHistory + RADIO).distinct()
        1 -> listOf(RADIO)
        else -> listOf(listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media", AA_MBS)[mode - 2], RADIO).distinct()
    }

    private const val BT = "com.android.bluetooth"
    @Volatile private var resumeBtUntil = 0L

    /** Playing on the card (before cutting the phone's connection). */
    fun playingNow(): Boolean = current?.let { isPlaying(it) } == true

    /**
     * Phone connection cut while playing: the replacement source is often Bluetooth,
     * whose music only returns a few seconds later; "play" is sent to it when it returns.
     */
    fun resumeOnBluetooth() { resumeBtUntil = android.os.SystemClock.uptimeMillis() + 15_000 }

    /** DiAuto or an equivalent app (Headunit Revived) in session: its projection screen is open. */
    @Volatile private var appProjecting = false

    /** Projection screen of DiAuto / Headunit Revived, and of MG4Link from its next version (renamed screen). */
    internal fun isProjectionScreen(className: String) =
        className.endsWith(".AapProjectionActivity") || className == "com.allgo.app.androidauto.ProjectionActivity"

    private fun appProjection(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(30).any { t ->
            listOfNotNull(t.baseActivity, t.topActivity).any { isProjectionScreen(it.className) }
        }
    } catch (_: Exception) { false }

    /**
     * Bluetooth source offered as soon as a phone is connected over Bluetooth. During Android Auto,
     * CarPlay or DiAuto, depending on the setting (on by default): DiAuto can route sound through
     * Bluetooth. During these sessions, it never switches to it by itself (see [projecting]).
     */
    private fun btSource(): Boolean = app.mg4dash.system.BtAudio.phoneConnected() && (!projecting || diAutoOverBluetooth())

    private var diAutoBtReadAt = 0L
    private var diAutoBt = false

    /**
     * During projection, Bluetooth is only a source if DiAuto sends music to it: with stock Android
     * Auto, CarPlay or DiAuto over Wi-Fi, the projection has the sound. Reread every 10 s at most (file).
     */
    private fun diAutoOverBluetooth(): Boolean {
        if (!appProjecting) return false
        val now = android.os.SystemClock.uptimeMillis()
        if (diAutoBtReadAt == 0L || now - diAutoBtReadAt > 10_000) {
            diAutoBtReadAt = now
            diAutoBt = listOf("com.drivehub.diauto.mg4", "app.mg4aa", "com.allgo.app.androidauto").any { musicOverBluetooth(it) }
        }
        return diAutoBt
    }

    /** Android Auto / CarPlay (AllGo), DiAuto or equivalent in session. */
    private val projecting get() = projectionLive == true || appProjecting

    /** A phone is connected through Android Auto, CarPlay or DiAuto. */
    val phoneProjecting get() = projecting

    /**
     * App connection cut by the Android Auto button (DiAuto…): if it was the source being listened to,
     * move to the setting's replacement source, as for a car source that disappears.
     */
    fun connectionCut(pkg: String) = main.post {
        userAction("Android Auto button")
        val p = prefs() ?: return@post
        if (sourcePkg != pkg && current?.packageName != pkg) return@post
        val avail = availableSources()
        fallbackOrder(p.startSource, p).firstOrNull { it != pkg && it in avail && !(it == AA_MBS && carPlay) }
            ?.let { autoSwitch(it, "$pkg cut", play = playingState()) }
    }

    // ── Startup sequence (#36): source and playback at car start ──
    /** Car start: MG4Dash launched within 5 minutes of the head unit turning on (a restart while driving: nothing). */
    private val carStart = isCarStart(android.os.SystemClock.elapsedRealtime())

    /** The head unit turned on less than 5 minutes ago: a car start, not MG4Dash restarted while driving. */
    internal fun isCarStart(elapsedMs: Long) = elapsedMs < 5 * 60_000
    private var seq: StartupSequence? = null
    private var stepping = false
    /** MG4Dash's startup sound is set for this start (read when the sequence starts). */
    private var soundConfigured = false
    /** The volume held at 0 during the startup pause (#36), until [PauseSilence.over]. */
    private var silence: PauseSilence? = null
    /**
     * The driver's last action (uptime) seen by MG4Dash: its media buttons and swipes, the source menu and chips,
     * the wheel's buttons (StarKey), the Android Auto button. It ends the startup sequence at once.
     */
    @Volatile private var userAt: Long? = null

    /** Something the driver did: the startup sequence stops and keeps the driver's choice. */
    private fun userAction(why: String) {
        if (seq?.done != false) return
        userAt = android.os.SystemClock.uptimeMillis()
        Log.i(TAG, "startup sequence: driver's action ($why)")
    }

    private fun startSequence() {
        val p = prefs() ?: return
        if (!carStart || BuildConfig.MOCK) { Log.i(TAG, "no startup sequence (${if (BuildConfig.MOCK) "mock" else "MG4Dash restarted while the car was on"})"); return }
        val want = startWanted(p)
        val mode = when (p.startSource) {
            0 -> StartupSequence.Mode.Auto(fallbackOrder(0, p))
            1 -> StartupSequence.Mode.Car
            else -> StartupSequence.Mode.Fixed(fallbackOrder(p.startSource, p).first())
        }
        seq = StartupSequence(mode, want, RADIO)
        // Read here: MG4Dash starts before its home screen triggers the sound, which would not be "expected" yet
        soundConfigured = appContext?.let { app.mg4dash.sound.SoundTrigger.bootSoundConfigured(it) } == true
        app.mg4dash.sound.SoundTrigger.holdFocusAfterBoot = soundConfigured && !want
        Log.i(TAG, "startup sequence: source $mode, ${if (want) "play" else "pause"} (setting ${p.startPlayback}, " +
            "last ${p.lastPlaying}, before ${p.playingBefore}, frozen at READY off ${p.stopFrozen})")
    }

    /** Playing wanted at car start: the setting, or as when the car stopped ([StartupSequence.lastState]). */
    private fun startWanted(p: app.mg4dash.system.Prefs) = when (p.startPlayback) {
        1 -> false
        2 -> true
        else -> StartupSequence.lastState(p.stopFrozen.takeIf { it >= 0 }?.let { it == 1 }, p.lastPlaying, p.lastPlayingAt, p.playingBefore, p.aliveAt)
    }

    /** The source shown really plays (its player's state, not the one shown for a moment after a command). */
    private fun realPlaying(): Boolean = when {
        usbShown -> SaicMedia.last?.playing == true
        radioShown -> SaicRadio.last?.playing == true
        else -> current?.let { isPlaying(it) } == true
    }

    /** The playback state kept when the source changes by itself (a better source back, a source lost). */
    private fun playingState(): Boolean = _now.value.available && realPlaying()

    /** The phone decides the playback (DiAuto, wireless Android Auto): MG4Dash chooses the source only. */
    private fun phoneDecides(pkg: String?): Boolean = isAndroidAuto(pkg) || handlesWheelKeys(pkg)

    /** One step of the startup sequence, at each reread of the players and every second. */
    private fun seqStep() {
        val s = seq ?: return
        if (s.done || stepping) return
        stepping = true
        try {
            val up = android.os.SystemClock.uptimeMillis()
            val snd = app.mg4dash.sound.SoundTrigger
            val shown = _now.value.packageName
            val input = StartupSequence.Input(
                now = up - startedAt,
                soundExpected = soundConfigured || snd.bootSoundExpected,
                // Set but not triggered yet (home not shown yet): it is to come
                soundBusy = if (soundConfigured && !snd.bootSoundExpected) true else snd.bootSoundBusy(up),
                soundEndAt = snd.bootSoundUntil.takeIf { snd.bootSoundExpected && it != 0L && up >= it }?.let { it - startedAt },
                available = availableSources().filterNot { it == AA_MBS && carPlay },
                carSource = effectiveSource(),
                playing = _now.value.available && realPlaying() && !phoneDecides(shown) && !phoneDecides(sourcePkg),
                userAt = userAt?.let { it - startedAt },
                phoneDecides = { phoneDecides(it) || handlesWheelKeys(sourcePkg) },
            )
            for (a in s.step(input)) when (a) {
                is StartupSequence.Action.Switch -> {
                    // A pause wanted: the volume at 0 until the source is really paused (#36, the radio heard a moment)
                    if (!s.wantPlay && !phoneDecides(a.pkg) && a.pkg in CAR_SOURCES) appContext?.let {
                        app.mg4dash.sound.SoundTrigger.silenceForPause(it)
                        silence = PauseSilence(up, app.mg4dash.sound.SoundTrigger.SILENCE_MAX_MS)
                    }
                    seqSwitch(a.pkg)
                }
                StartupSequence.Action.Play -> setPlaying(true, "startup")
                StartupSequence.Action.Pause -> {
                    setPlaying(false, "startup", s.target)
                    // The pause sent: the audio focus kept after the sound goes back a moment later
                    if (soundConfigured) main.postDelayed({ app.mg4dash.sound.SoundTrigger.releaseBootFocus() }, 1_000)
                }
            }
            silence?.let { w ->
                val playingNow = _now.value.available && realPlaying()
                if (s.done || w.over(android.os.SystemClock.uptimeMillis(), playingNow)) {
                    app.mg4dash.sound.SoundTrigger.endSilence(if (s.done) "sequence over" else if (playingNow) "time" else "paused")
                    silence = null
                }
            }
            if (s.done) Log.i(TAG, "startup sequence over: ${s.endedBy} (source ${s.target})")
        } finally { stepping = false }
    }

    /**
     * Startup source, without playing: the car's source-change command for a car source, sent even when the car
     * already announces it, as the stock launcher does when a source is picked: after a start the car announces its
     * last source without making it the active one, and the wheel's buttons did nothing until a touch on play (#36).
     * Android Auto (CarPlay is never chosen): shown only, the phone plays. Another app (DiAuto): shown, the phone plays.
     */
    private fun seqSwitch(pkg: String) {
        Log.i(TAG, "startup source: $pkg (car on ${effectiveSource()})")
        pinnedPkg = pkg; autoTarget = null
        autoSwitchAt = android.os.SystemClock.uptimeMillis()
        main.post { reevaluate() }
        if (pkg in CAR_SOURCES) activateSource(pkg)
    }

    /** Explicit play or pause on the source shown (never a toggle: a toggle on a late state did the opposite, #36). */
    private fun setPlaying(want: Boolean, why: String, pkg: String? = null) {
        // Sent with the switch: to the source switched to, not the one still shown
        val c0 = pkg?.let { p -> browserControllers[p] ?: lastCandidates.firstOrNull { it.packageName == p } }
        Log.i(TAG, "$why: ${if (want) "play" else "pause"} on ${c0?.packageName ?: _now.value.packageName}")
        if (c0 == null && usbShown) { usbCommand { if (want) SaicMedia.usbPlay() else SaicMedia.usbPause() }; return }
        if (c0 == null && radioShown) { radioCommand { if (want) SaicRadio.play() else SaicRadio.pause() }; return }
        if (pkg == RADIO && legacyRadio) { radioCommand { if (want) SaicRadio.play() else SaicRadio.pause() }; return }
        val c = c0 ?: current ?: return
        if (isAndroidAuto(c.packageName)) { aaControl(c, if (want) AA_PLAY else AA_PAUSE, if (want) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE); return }
        if (want) c.transportControls.play() else c.transportControls.pause()
        force(c.packageName, want)
    }

    // ── The state at the last stop ("As at switch-off") ──
    private var lastReady: Boolean? = null
    private var aliveWrittenAt = 0L

    /** Every playback change recorded at once, with its time (the old "a pause counts after 30 s" rule is gone). */
    private fun recordPlayback(p: app.mg4dash.system.Prefs) {
        if (!_now.value.available) return
        val playing = realPlaying()
        if (playing == p.lastPlaying) return
        p.playingBefore = p.lastPlaying
        p.lastPlaying = playing
        p.lastPlayingAt = System.currentTimeMillis()
    }

    /**
     * Every second (off the main thread): READY read as MG4Control does ([app.mg4dash.car.ReadyState]). The car
     * leaving READY in P freezes the playback state of that moment: the head unit's own pause when it switches off
     * comes later and does not count. Back to READY: cleared. Without READY, the "alive" heartbeat every 5 s tells
     * that pause apart at the next start ([StartupSequence.lastState]).
     */
    private fun watchStop(p: app.mg4dash.system.Prefs) {
        val now = System.currentTimeMillis()
        if (now - aliveWrittenAt >= 5_000) { aliveWrittenAt = now; p.aliveAt = now }
        val r = try { app.mg4dash.car.ReadyState.ready() } catch (_: Exception) { null } ?: return
        if (r && p.stopFrozen != -1) p.stopFrozen = -1
        if (lastReady == true && !r && app.mg4dash.car.ReadyState.inPark() != false) {
            p.stopFrozen = if (p.lastPlaying) 1 else 0
            Log.i(TAG, "car out of READY in P: playback state kept as ${if (p.lastPlaying) "playing" else "paused"}")
        }
        lastReady = r
    }

    /** USB announced while the startup sound plays (or just after) with no stick mounted: not shown (#36). */
    internal fun usbHeldBySound(soundRecent: Boolean, usbMounted: Boolean) = soundRecent && !usbMounted
    private var goneToProjectionLogged = false
    /** The driver's last source choice (swipe, menu) and when; given back once if the radio retakes the source (#36). */
    @Volatile private var userPick: String? = null
    @Volatile private var userPickAt = 0L
    @Volatile private var userPickRetried = false
    private const val USER_PICK_MS = 3_000L

    /** The car's source back to the radio [sinceMs] after the driver chose another car source: the radio's late start. */
    internal fun radioTakesBack(src: String?, pick: String?, sinceMs: Long, retried: Boolean) =
        src == RADIO && pick != null && pick != RADIO && pick in CAR_SOURCES && sinceMs in 0 until USER_PICK_MS && !retried
    private var autoTarget: String? = null
    private var autoTries = 0

    /** Source switched by MG4Dash (not at startup), then [play] or pause explicitly: the current state is kept. */
    private fun autoSwitch(pkg: String, why: String, play: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        // The car refuses the switch (silent source…): 3 tries at most, then wait 2 min
        if (pkg == autoTarget && autoTries >= 3 && now - autoSwitchAt < 120_000) return
        if (pkg != autoTarget || now - autoSwitchAt >= 120_000) { autoTarget = pkg; autoTries = 0 }
        autoTries++
        autoSwitchAt = now
        Log.i(TAG, "automatic source: $pkg ($why)")
        pinnedPkg = pkg
        main.post { reevaluate() }
        // Android Auto: its play key is the only way to give it the sound; paused, it is only shown
        if (pkg == AA_MBS) { if (play) browserControllers[AA_MBS]?.let { aaControl(it, AA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY) }; return }
        activateSource(pkg)
        // The car may start the new source by itself: the state kept, explicitly (paused stays paused, #36)
        main.postDelayed({
            browserControllers[pkg]?.let { c ->
                if (play && !isPlaying(c)) c.transportControls.play()
                else if (!play && isPlaying(c)) { Log.i(TAG, "$pkg started by itself: paused, as before"); c.transportControls.pause() }
            }
        }, 1500)
    }

    /** Player to discard: projection or Bluetooth with no phone connected. */
    private fun gone(pkg: String?): Boolean =
        (projectionLive == false && isAndroidAuto(pkg)) || (btLive == false && pkg == "com.android.bluetooth")

    private fun watchProjection(ctx: Context) {
        io.launch {
            while (true) {
                val s = app.mg4dash.system.Projection.session(ctx).let {
                    // Connection cut by the button: AllGo keeps the session but the app is disabled
                    if (it is app.mg4dash.system.Projection.Session.Read && it.target != null &&
                        app.mg4dash.system.ConnectionToggle.isOff(ctx, it.target.packageName)) app.mg4dash.system.Projection.Session.Read(null) else it
                }
                val live = (s as? app.mg4dash.system.Projection.Session.Read)?.let { it.target != null }
                // End of projection: the phone does not reconnect Bluetooth music by itself
                if (projectionLive == true && live == false) io.launch { kotlinx.coroutines.delay(3_000); app.mg4dash.system.BtAudio.connectMusic(ctx) }
                if (s is app.mg4dash.system.Projection.Session.Read && s.target != null) carPlay = s.target == app.mg4dash.system.Apps.CARPLAY
                if (live != projectionLive) {
                    Log.i(TAG, "projection: ${if (live == null) "unknown" else if (live) "phone connected" else "no phone"}")
                    projectionLive = live
                    main.post { reevaluate() }
                }
                // Bluetooth cut while playing: the card stayed on Bluetooth until a swipe (#36)
                val proj = appProjection(ctx)
                if (proj != appProjecting) { Log.i(TAG, "Android Auto app (DiAuto…): ${if (proj) "phone connected" else "no phone"}"); appProjecting = proj; main.post { reevaluate() } }
                val bt = btConnected()
                if (bt != btLive) {
                    Log.i(TAG, "bluetooth audio: ${if (bt == null) "unknown" else if (bt) "phone connected" else "no phone"}")
                    btLive = bt
                    main.post { reevaluate() }
                    // Music cut with the phone connection: it resumes on the returned Bluetooth
                    if (bt == true && android.os.SystemClock.uptimeMillis() < resumeBtUntil) {
                        resumeBtUntil = 0L
                        main.postDelayed({
                            if (sourcePkg == BT || pinnedPkg == BT)
                                browserControllers[BT]?.takeIf { !isPlaying(it) }?.let { Log.i(TAG, "bluetooth back: play"); it.transportControls.play() }
                        }, 1_500)
                    }
                }
                prefs()?.let { watchStop(it) }
                main.post { autoSource() }
                kotlinx.coroutines.delay(1_000)
            }
        }
    }

    fun androidAutoAvailable(): Boolean {
        if (projectionLive == false) return false
        val c = browserControllers[AA_MBS] ?: return false
        val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
        return st != PlaybackState.STATE_NONE || c.metadata != null || sourcePkg == AA_MBS
    }

    fun selectAndroidAuto() = select(AA_MBS)

    /**
     * Player of another app shown (DiAuto…): the car has no source for it, and the
     * steering wheel buttons go to AllGo's Android Auto; MG4Dash passes them on to it.
     */
    fun otherAppShown(): Boolean = !radioShown && current?.packageName?.let {
        it != RADIO && it != "com.android.bluetooth" && it != "com.saicmotor.media" && !isAndroidAuto(it) && !it.startsWith("com.allgo.")
    } == true

    /**
     * Shown app that reads the wheel's play / pause button itself (DiAuto MG4+ and its old
     * name): MG4Dash does not pass it on, otherwise it would pause and restart at once.
     */
    private fun handlesWheelKeys(pkg: String?) = pkg == "com.drivehub.diauto.mg4" || pkg == "app.mg4aa"

    // Wheel buttons on another app's player: the stock launcher does not know these apps
    // and acts on the last car source (often the radio), which takes the sound back: left / right make it
    // change station, up / down (volume) restart it if paused. The app is noted at the
    // press to put it back at once (see watchAudioSource).
    @Volatile private var wheelFrom: String? = null
    @Volatile private var wheelAt = 0L
    /** true / false: next / previous (station to restore); null: volume (nothing to restore). */
    @Volatile private var wheelForward: Boolean? = null
    /** The wheel's play / pause (#36): the app given back its source is toggled, not just resumed. */
    @Volatile private var wheelPlayPause = false

    /** App given back its source after a wheel button, and when: a second press soon after, while the radio still had it, went to the radio (#98). */
    @Volatile private var wheelBack: String? = null
    /** The app was playing at the press: given back its source, it plays again (#98). */
    @Volatile private var wheelPlaying = false
    @Volatile private var wheelBackAt = 0L
    private fun recentBack() = wheelBack?.takeIf { android.os.SystemClock.uptimeMillis() - wheelBackAt < 3_000 }

    // Wheel decisions (#36, #98), apart for the tests (WheelKeysTest)

    /** A wheel pause sticks this long against the player's own "playing" (late state of the web app's page). */
    internal const val WHEEL_PAUSE_STICKS_MS = 5_000L

    /**
     * The app playing at a wheel key, from what its player [says]: at play / pause, an app [pausedByWheel] less than
     * [WHEEL_PAUSE_STICKS_MS] ago counts as paused (otherwise the next press paused it again: two presses to resume).
     */
    internal fun wheelPlayingAtPress(says: Boolean, playPause: Boolean, pausedByWheel: Boolean, sincePauseMs: Long) =
        says && !(playPause && pausedByWheel && sincePauseMs <= WHEEL_PAUSE_STICKS_MS)

    /** Given back its source after the radio's passage, the app plays: play / pause toggles it, other keys keep its state. */
    internal fun wheelReplay(playPause: Boolean, wasPlaying: Boolean) = if (playPause) !wasPlaying else wasPlaying

    /** The source switch back to the app sends "play", except for a wheel pause (it restarted the app a second later). */
    internal fun wheelSwitchPlays(playPause: Boolean, replay: Boolean) = !(playPause && !replay)

    /** The car's source back to the radio while an app was just given it back by the wheel ([recentBack]): the card stays on it. */
    internal fun wheelCardKeptOn(src: String?, recentBack: String?) = recentBack?.takeIf { src == RADIO }

    /** During a wheel silence, a new car source [src]: the delay before the sound comes back, null while the radio has it. */
    internal fun wheelSilenceEndsIn(src: String?): Long? = if (src == RADIO) null else WHEEL_SILENCE_TAIL_MS

    fun wheelPressed(forward: Boolean?, playPause: Boolean = false) {
        if (forward != null) userAction("wheel ${if (forward) "next" else "previous"}")
        // A press during the silence: the volume back before the car applies this key's step, which would be lost
        else endWheelSilence(if (playPause) "play / pause key" else "volume key")
        val app = current?.packageName?.takeIf { otherAppShown() } ?: recentBack() ?: return
        // DiAuto reads play / pause itself: passed on, it would pause and restart at once
        if (playPause && handlesWheelKeys(app)) return
        wheelPlayPause = playPause
        wheelFrom = app
        if (current?.packageName == app) wheelPlaying = current?.let { isPlaying(it) } == true
        // Paused by the wheel and not played since: its player may still say "playing" for a moment,
        // which made the next press pause it again instead of playing (#36, two presses to resume)
        // A "playing" seen long after that pause is real (played again from the page itself)
        val sincePause = android.os.SystemClock.uptimeMillis() - wheelPausedAt
        if (wheelPlaying && sincePause > WHEEL_PAUSE_STICKS_MS) wheelPausedPkg = null
        wheelPlaying = wheelPlayingAtPress(wheelPlaying, playPause, wheelPausedPkg == app, sincePause)
        wheelAt = android.os.SystemClock.uptimeMillis()
        wheelForward = forward
        // The card stays on the app during the brief passage through the radio
        pinnedPkg = wheelFrom
        if (playPause) {
            // The radio does not always take the source at this key (paused by us, it may only toggle):
            // nothing to give back then, so the app is toggled directly
            val at = wheelAt; val wasPlaying = wheelPlaying
            main.postDelayed({
                if (wheelFrom != app || wheelAt != at) return@postDelayed
                wheelFrom = null
                Log.i(TAG, "wheel play / pause: the radio did not take the source, $app toggled directly")
                if (wasPlaying) pauseApp(app) else playApp(app)
            }, WHEEL_PLAY_PAUSE_FALLBACK_MS)
        }
    }

    private const val WHEEL_PLAY_PAUSE_FALLBACK_MS = 800L
    /** App paused by the wheel's play / pause, until it plays again (#36). */
    @Volatile private var wheelPausedPkg: String? = null
    @Volatile private var wheelPausedAt = 0L

    private fun playApp(pkg: String) {
        wheelPausedPkg = null
        val c = (lastCandidates.filter { it.packageName == pkg } + listOfNotNull(browserControllers[pkg])).firstOrNull() ?: return
        Log.i(TAG, "$pkg: wheel play / pause, play")
        c.transportControls.play()
    }

    /**
     * Button released: next / previous to the app noted at the press (the radio may have been shown in
     * between). DiAuto only reads the wheel's play / pause, not left / right. false = no other app.
     */
    fun wheelReleased(forward: Boolean): Boolean {
        val pkg = wheelFrom?.takeIf { android.os.SystemClock.uptimeMillis() - wheelAt < 2_000 }
            ?: current?.packageName?.takeIf { otherAppShown() } ?: recentBack() ?: return false
        val c = browserControllers[pkg] ?: lastCandidates.firstOrNull { it.packageName == pkg } ?: return true
        if (forward) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        return true
    }

    fun isAndroidAuto(pkg: String?) = pkg == AA_MBS || pkg?.startsWith("com.allgo.app.androidauto") == true

    /** USB / BT chip: switches between Bluetooth and USB, starting from what is shown. */
    /**
     * Vertical swipe on the music card (issue #6): next (+1) or previous (−1) source
     * among those available (radio, connected Bluetooth, USB stick, connected Android Auto),
     * then switch of the car's source and playback.
     */
    /** Sources available now, in order: radio, connected Bluetooth, USB stick, connected Android Auto. */
    fun availableSources(): List<String> {
        if (BuildConfig.MOCK) return listOf("com.saicmotor.radio", "com.android.bluetooth", AA_MBS)
        val ctx = appContext ?: return emptyList()
        val order = listOf("com.saicmotor.radio", "com.android.bluetooth", "com.saicmotor.media", AA_MBS)
        return order.filter { pkg ->
            when (pkg) {
                "com.android.bluetooth" -> btSource()
                "com.saicmotor.media" -> try {
                    (ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager).storageVolumes
                        .any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
                } catch (_: Exception) { false }
                AA_MBS -> androidAutoAvailable()
                else -> true
            }
        } + otherPlayers()
    }

    /**
     * Other apps with an active player (DiAuto, Spotify installed on the head unit…): offered by
     * swipe and in the source menu (#36 / DiAuto: impossible to go back to them after the radio).
     */
    private fun otherPlayers(): List<String> = lastCandidates.filter { c ->
        val pkg = c.packageName
        pkg != appContext?.packageName && !pkg.startsWith("com.saicmotor.") && pkg != "com.android.bluetooth" &&
            !pkg.startsWith("com.allgo.") && !gone(pkg) &&
            ((c.playbackState?.state ?: PlaybackState.STATE_NONE) != PlaybackState.STATE_NONE || c.metadata != null)
    }.map { it.packageName }.distinct()

    /** Shown source, Android Auto mapped to its player, the radio to its band if DAB and FM are separate. */
    fun currentSource(): String? {
        val pkg = if (radioShown) RADIO else if (usbShown) USB else current?.packageName?.let { if (isAndroidAuto(it)) AA_MBS else it }
        return if (pkg == RADIO && radioBandsSplit()) bandTarget ?: currentBandSource() ?: RADIO_DAB else pkg
    }

    // ── Radio Dash: DAB and FM, two home sources (swipe and menu) of one app ──
    const val RADIO_DAB = "com.saicmotor.radio#dab"
    const val RADIO_FM = "com.saicmotor.radio#fm"
    private const val ACTION_NEXT_BAND = "__ACTION_NEXT_SOURCE__"
    /** Radio Dash only, announced by this flag in its session extras: goes straight to the band in the "band" extra. */
    private const val ACTION_SET_BAND = "__ACTION_SET_BAND__"
    private const val FLAG_SET_BAND = "radiodash.set_band"
    private const val BAND_RETRY_MS = 800L
    /** Radio Dash's requests, every [BAND_RETRY_MS]: about 6 s, the radio starting after another source can take that long. */
    private const val DIRECT_BAND_TRIES = 8
    private val radioDashInstalled by lazy { appContext?.let { app.mg4dash.system.RadioDashUpdater.installed(it) } == true }
    /** Wanted band, until the radio gets there. */
    @Volatile private var bandTarget: String? = null

    private fun radioBandsSplit() = radioDashInstalled && prefs()?.radioDashBands == true

    /**
     * Old firmware with Radio Dash shown: its launcher only knows its own radio and does not pass it
     * the wheel buttons, MG4Dash does it. On SWI69, the stock launcher already does.
     */
    fun radioDashNeedsWheelKeys(): Boolean =
        app.mg4dash.system.Firmware.family == app.mg4dash.system.Firmware.Family.LEGACY && radioDashInstalled && current?.packageName == RADIO

    /** Sources offered by swipe and menu: those available, the radio split into DAB and FM. */
    fun menuSources(): List<String> {
        val split = radioBandsSplit()
        return availableSources().flatMap { if (it == RADIO && split) listOf(RADIO_DAB, RADIO_FM) else listOf(it) }
    }

    /** Band from the radio's metadata: author "DAB", or frequency as title for FM / AM. */
    fun radioBandOf(author: String?, title: String?): String? = when {
        author == "DAB" -> RADIO_DAB
        title?.trim()?.startsWith("FM") == true -> RADIO_FM
        title?.trim()?.startsWith("AM") == true -> "AM"
        else -> null
    }

    private fun currentBandSource(): String? {
        val md = browserControllers[RADIO]?.metadata ?: return null
        return radioBandOf(md.getString(MediaMetadata.METADATA_KEY_AUTHOR), md.getString(MediaMetadata.METADATA_KEY_TITLE))
    }

    /**
     * The stock radio can only move to the next band (command of its session): advance to the wanted
     * band, rereading each time. It also counts AM where that tab exists, hence 3 tries.
     * Radio Dash can be asked for the band directly, which cannot overshoot it (#80): asked again for longer,
     * as the radio starting from another source could put its last band back over the first requests.
     * Each try is in the log, with the command sent, to tell the two cases apart.
     */
    private fun reachBand(target: String, done: Int = 0, delayMs: Long = BAND_RETRY_MS) {
        main.postDelayed({
            if (bandTarget != target) return@postDelayed
            val c = browserControllers[RADIO]
            val band = currentBandSource()
            val direct = c?.extras?.getBoolean(FLAG_SET_BAND) == true
            val how = if (direct) "set band" else "next band"
            if (band == target || c == null || done >= (if (direct) DIRECT_BAND_TRIES else 3)) {
                Log.i(TAG, "radio band: $band, wanted $target" + (if (c == null) " (no radio session)" else " ($done × $how)"))
                bandTarget = null
                if (c != null && !isPlaying(c)) c.transportControls.play()
                publish()
            } else {
                if (done == 0) Log.i(TAG, "radio session extras: ${c.extras?.keySet()}")
                Log.i(TAG, "radio band: $band, wanted $target, try ${done + 1}: $how")
                if (direct) {
                    c.transportControls.sendCustomAction(ACTION_SET_BAND, android.os.Bundle().apply {
                        putString("band", if (target == RADIO_DAB) "DAB" else "FM")
                    })
                } else c.transportControls.sendCustomAction(ACTION_NEXT_BAND, null)
                reachBand(target, done + 1)
            }
        }, delayMs)
    }

    private const val SWIPE_CHAIN_MS = 8_000L
    private var swipeTarget: String? = null
    private var swipeAt = 0L

    fun cycleSource(dir: Int) {
        if (BuildConfig.MOCK) return
        val available = menuSources()
        if (available.isEmpty()) return
        // Quick successive swipes: start from the targeted source, not the shown one. Android Auto playing takes
        // the display back when targeting Bluetooth, and each swipe fell back on Bluetooth without going further
        val now = android.os.SystemClock.uptimeMillis()
        val cur = swipeTarget?.takeIf { now - swipeAt < SWIPE_CHAIN_MS && it in available } ?: currentSource()
        val i = available.indexOf(cur)
        val next = available.getOrNull(Math.floorMod((if (i < 0) 0 else i) + dir, available.size)) ?: return
        swipeTarget = next; swipeAt = now
        Log.i(TAG, "swipe: source $cur → $next (available: $available)")
        switchTo(next)
    }

    /**
     * Switches the car to this source and starts playback (swipe or source menu). [play] false: the source only,
     * the app's player left paused (the wheel's play / pause, #36).
     */
    fun switchTo(next: String, play: Boolean = true) {
        if (BuildConfig.MOCK) return
        userAction("source $next")
        if (next == RADIO_DAB || next == RADIO_FM) {
            bandTarget = next
            // Indicator right away, without waiting for the radio to confirm the band
            publish()
            // Already on the radio: no source change to the car, go directly to the band
            if (current?.packageName == RADIO) reachBand(next, 0, 0) else { switchTo(RADIO); reachBand(next) }
            return
        }
        Log.i(TAG, "source picked: $next")
        userPick = next; userPickAt = android.os.SystemClock.uptimeMillis(); userPickRetried = false
        wheelFrom = null; wheelBack = null // user's choice: no automatic return to the app
        promptFrom = null
        // Leaving another app's player (DiAuto, SimpMusic…): the car has no source to take from it
        // and some apps carry on despite the loss of focus, hence two musics at once
        current?.takeIf { otherAppShown() && it.packageName != next }?.let {
            Log.i(TAG, "pausing ${it.packageName} (leaving it)")
            it.transportControls.pause()
        }
        remember(next); autoTarget = null
        pinnedPkg = next
        main.post { reevaluate() }
        if (next == AA_MBS) {
            if (carPlay) { selectProjectionSource(); return }
            // CarPlay not seen yet since startup: ask AllGo which phone is projecting
            val ctx = appContext
            io.launch {
                val s = ctx?.let { app.mg4dash.system.Projection.session(it) }
                main.post {
                    if (s is app.mg4dash.system.Projection.Session.Read && s.target == app.mg4dash.system.Apps.CARPLAY) {
                        carPlay = true; selectProjectionSource()
                    } else browserControllers[AA_MBS]?.let { aaControl(it, AA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY) }
                }
            }
            return
        }
        if (next == "com.android.bluetooth") appContext?.let { app.mg4dash.system.BtAudio.connectMusic(it) }
        // Other app (DiAuto…): no car source command, start its player directly
        val carSource = activateSource(next)
        // The radio does not always lose the sound to the app (DiAuto does not ask for it): pause
        if (!carSource && (radioShown || sourcePkg == RADIO)) pauseRadio()
        if (play) main.postDelayed({
            // Other app: "play" sent even if it thinks it is already playing. DiAuto stays playing
            // on the phone side when the radio takes the source from it; this "play" makes it resume
            (browserControllers[next] ?: lastCandidates.firstOrNull { it.packageName == next })
                ?.takeIf { !carSource || !isPlaying(it) }?.transportControls?.play()
        }, if (carSource) 1500 else 0)
    }

    fun selectUsbBt() {
        val bt = "com.android.bluetooth"; val usb = "com.saicmotor.media"
        select(if (current?.packageName == bt) usb else bt)
    }
    private const val AA_MBS = "com.allgo.remoteui.mediabrowserservice"

    private fun sourceFromCarType(t: Int): String? = when (t) {
        1, 2, 3 -> "com.saicmotor.radio"
        5 -> "com.android.bluetooth"
        6, 7, 14 -> "com.saicmotor.media"
        70 -> AA_MBS
        else -> null
    }

    private fun watchAudioSource(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        try {
            val last = am.javaClass.getMethod("getLastSource").invoke(am) as Int
            sourcePkg = sourceFromCarType(last)
            Log.i(TAG, "audio source at startup: $last → $sourcePkg")
        } catch (e: Throwable) { Log.w(TAG, "getLastSource unavailable: ${e.message}") }
        try {
            val reg = am.javaClass.methods.first { it.name == "registerAudioSourceChange" }
            val cbType = reg.parameterTypes[0]
            val cb = java.lang.reflect.Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { proxy, m, args ->
                when (m.name) {
                    "onSourceChanged" -> {
                        val attrs = args?.getOrNull(0)
                        val pkg = args?.getOrNull(1) as? String
                        val type = try {
                            (attrs?.javaClass?.getMethod("getBundle")?.invoke(attrs) as? android.os.Bundle)?.getInt("key_car_source_type", -1) ?: -1
                        } catch (_: Throwable) { -1 }
                        // Passenger sound of an app with no car source (type -1): DiAuto guidance announcement mixed
                        // over the radio (DiAuto 2.0.0-beta19). This is not a source change: the media
                        // card flickered to DiAuto at each announcement
                        // Same for AllGo without a phone: DiAuto closed the announcement under its name, and the following
                        // switch, Android Auto being absent, restarted paused Bluetooth (#78)
                        if (type == -1 && pkg != null && (!pkg.startsWith("com.saicmotor.") && !pkg.startsWith("com.allgo.") ||
                                pkg == "com.allgo.app.androidauto" && projectionLive != true)) {
                            Log.i(TAG, "audio source: $pkg (-1) ignored: passing sound, the car source does not change")
                            return@newProxyInstance null
                        }
                        // DiAuto presents itself to the car as AllGo's Android Auto (the stock launcher then no longer
                        // sends it the wheel buttons): with no phone on AllGo, this is the DiAuto player
                        val diAuto = if (pkg == "com.allgo.app.androidauto" && projectionLive != true)
                            lastCandidates.firstOrNull { handlesWheelKeys(it.packageName) }?.packageName else null
                        val src = when {
                            diAuto != null -> diAuto
                            pkg == "com.saicmotor.media" && type == 5 -> "com.android.bluetooth"
                            // Old SDK (#80): the same service holds the source for Bluetooth (5) and USB (6)
                            pkg == "com.saicmotor.service.media" && type == 5 -> "com.android.bluetooth"
                            pkg == "com.saicmotor.service.media" && type == 6 -> USB
                            pkg == "com.allgo.app.androidauto" -> AA_MBS.also { carPlay = false }
                            pkg == "com.allgo.carplay.service" -> AA_MBS.also { carPlay = true }
                            pkg == SaicRadio.HMI_PKG || pkg == "com.saicmotor.service.radio" -> RADIO
                            else -> pkg
                        }
                        Log.i(TAG, "audio source: $pkg ($type) → $src")
                        // Wheel silence: over once the app has had the sound back for a moment; the radio taking it again
                        // (its station change undone, ~1 s after next / previous) keeps it on
                        if (wheelSilence) {
                            main.removeCallbacks(silenceEnd)
                            wheelSilenceEndsIn(src)?.let { main.postDelayed(silenceEnd, it) }
                        }
                        // Radio taken back by the stock launcher right after wheel left / right: back to
                        // the app. Handled here, without waiting for the main thread (often busy at that moment)
                        val back = wheelFrom?.takeIf { src == RADIO && android.os.SystemClock.uptimeMillis() - wheelAt < 2_000 }
                        if (back != null) {
                            wheelFrom = null
                            wheelBack = back; wheelBackAt = android.os.SystemClock.uptimeMillis()
                            Log.i(TAG, "steering wheel key sent to the radio by the stock launcher: back to $back")
                            sourcePkg = src
                            val fwd = wheelForward
                            val toggle = wheelPlayPause
                            // Play / pause (#36): the app plays if it was paused, stays paused if it played
                            val replay = wheelReplay(toggle, wheelPlaying)
                            // switchTo forgets the wheel's app (a user's choice): kept here, for a second press and the card
                            // Every wheel key (volume, next / previous, play / pause): silent at once, before the radio is heard (#98, #36)
                            startWheelSilence()
                            val giveBack = {
                                // Play / pause on an app that played: its source back without the "play" switchTo sends,
                                // which restarted it a second later (#36)
                                switchTo(back, play = wheelSwitchPlays(toggle, replay)); wheelBack = back; wheelBackAt = android.os.SystemClock.uptimeMillis()
                                if (replay) { if (toggle) wheelPausedPkg = null; resumeApp(back) }
                                else {
                                    if (toggle) pauseApp(back)
                                    main.postDelayed({ endWheelSilence("radio paused") }, WHEEL_SILENCE_NO_RESUME_MS)
                                }
                            }
                            if (fwd != null) undoRadioSkip(fwd) { giveBack() } else main.post { giveBack() }
                            return@newProxyInstance null
                        }
                        main.post {
                            // The SAIC radio starts ~1 s after its command: left within that second, its late start took
                            // the source back from the driver's choice (#36). Paused, and the choice sent once more
                            val pick = userPick
                            if (radioTakesBack(src, pick, android.os.SystemClock.uptimeMillis() - userPickAt, userPickRetried)) {
                                userPickRetried = true
                                Log.i(TAG, "the radio took the source back from $pick: paused, $pick again")
                                pauseRadio()
                                pinnedPkg = pick
                                // The same choice again (switch and playback), once: not retried a second time
                                main.postDelayed({ switchTo(pick!!); userPickRetried = true }, 300)
                                reevaluate()
                                return@post
                            }
                            if (src == AA_MBS) {
                                // The manual choice stays as long as Android Auto is not playing (guidance only)
                                if (sourcePkg != AA_MBS) { aaUnconfirmed = true; sourceBeforeAa = sourcePkg }
                            } else {
                                aaUnconfirmed = false; sourceBeforeAa = null
                                // The radio's late start (~0.1 s) right after a wheel key gave the source back to an app: with the
                                // app paused by the wheel, nothing takes the source back from it and the card went to the DAB, the
                                // next press then going to the radio (#36). The card stays on the app and the radio is paused again
                                val kept = wheelCardKeptOn(src, recentBack())
                                if (kept != null) {
                                    Log.i(TAG, "the radio took the source right after the wheel gave it back to $kept: card kept on $kept, radio paused")
                                    pauseRadio()
                                }
                                pinnedPkg = kept
                            }
                            // Change made on the car (source button, stock app): this is also a user's
                            // choice, unless it follows an automatic switch or the loss of the old source
                            val prev = sourcePkg
                            // DiAuto takes the source for an announcement (hybrid mode: music over Bluetooth): remember
                            // the car's previous source to give it back once the announcement is over
                            if (src != null && handlesWheelKeys(src) && prev in CAR_SOURCES) {
                                promptFrom = prev; promptAt = android.os.SystemClock.uptimeMillis(); promptQuiet = 0; promptSounding = 0
                                promptWasPlaying = prev != null && promptAt - (playingAt[prev] ?: 0L) < 3_000
                                Log.i(TAG, "$src took the car source from $prev (music over Bluetooth: ${musicOverBluetooth(src)}, was playing: $promptWasPlaying)")
                            } else if (src in CAR_SOURCES) promptFrom = null
                            // Only car sources: an app taking over for a moment (DiAuto guidance
                            // announcement…) is not a source choice
                            if (src != null && src in listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media") && src != prev && android.os.SystemClock.uptimeMillis() - autoSwitchAt > 8_000 &&
                                (prev == null || prev in availableSources())) { remember(src); autoTarget = null }
                            sourcePkg = src; reevaluate()
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "MG4DashSourceCallback"
                    else -> null
                }
            }
            val extra = reg.parameterTypes.drop(1).map { null }
            reg.invoke(am, cb, *extra.toTypedArray())
            Log.i(TAG, "audio source tracking on")
        } catch (e: Throwable) { Log.w(TAG, "registerAudioSourceChange unavailable: ${e.message}") }
    }

    /** Dummy station logo for screenshots (like a picture broadcast over DAB+). */
    private fun mockLogo(): Bitmap {
        val size = 320
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            0xFFFF5F6D.toInt(), 0xFF6A11CB.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        p.color = 0x33FFFFFF
        c.drawCircle(size * 0.82f, size * 0.18f, size * 0.38f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
        p.textSize = size * 0.30f
        c.drawText("RADIO", size / 2f, size * 0.52f, p)
        p.textSize = size * 0.20f
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        c.drawText("DAB+  FM", size / 2f, size * 0.76f, p)
        return bmp
    }

    private val BROWSERS = listOf(
        ComponentName("com.saicmotor.radio", "com.saicmotor.radio.service.RadioMBService"),
        ComponentName("com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService"),
        ComponentName("com.saicmotor.media", "com.saicmotor.media.mbs.Usb1MediaBrowserService"),
        ComponentName("com.allgo.remoteui.mediabrowserservice", "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService"),
    )
    private const val RADIO = "com.saicmotor.radio"
    private val ANALOG = Regex("^(FM|AM) [0-9.,]+$")

    /** FM frequency in kHz of the radio's title ("FM 98.20", "FM 98,2"), -1 for AM or another title. */
    internal fun analogKhz(title: String): Int {
        val t = title.trim()
        if (!ANALOG.matches(t) || !t.startsWith("FM")) return -1
        val mhz = t.substring(3).replace(',', '.').toDoubleOrNull() ?: return -1
        return Math.round(mhz * 1000).toInt()
    }
    private val browserControllers = mutableMapOf<String, MediaController>()
    private val browsers = mutableMapOf<String, MediaBrowser>()
    private val watched = mutableSetOf<MediaSession.Token>()
    private val emptySince = mutableMapOf<String, Long>()

    /**
     * Stale link with a player: the radio (or Bluetooth) recreated its session, for example after
     * its service restarted. The old controller receives nothing more (no title or cover)
     * while the shown source stays "Radio". Reconnect to its MediaBrowserService.
     */
    private fun reconnectBrowser(pkg: String, why: String) {
        val component = BROWSERS.firstOrNull { it.packageName == pkg } ?: return
        Log.i(TAG, "reconnecting to player $pkg ($why)")
        browserControllers.remove(pkg)
        emptySince.remove(pkg)
        browsers.remove(pkg)?.let { try { it.disconnect() } catch (_: Exception) { } }
        connectBrowser(component)
    }

    /** Periodic check: a browser controller left with no state or title for more than 20 s is restarted. */
    private fun checkStaleBrowsers() {
        val now = System.currentTimeMillis()
        // Radio only: disconnecting from the car's Bluetooth player could stop the music
        // (issue #10, cut at the phone's first notification).
        browserControllers.toMap().filterKeys { it == RADIO }.forEach { (pkg, c) ->
            val empty = try { c.metadata == null && c.playbackState == null } catch (_: Exception) { true }
            if (!empty) { emptySince.remove(pkg); return@forEach }
            val since = emptySince.getOrPut(pkg) { now }
            if (now - since > 20_000 && (pkg == sourcePkg || pkg == pinnedPkg || pkg == current?.packageName)) reconnectBrowser(pkg, "aucune donnée depuis 20 s")
        }
    }

    private fun connectBrowser(component: ComponentName) {
        val ctx = appContext ?: return
        var browser: MediaBrowser? = null
        browser = MediaBrowser(ctx, component, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val b = browser ?: return
                browserControllers[component.packageName] = MediaController(ctx, b.sessionToken)
                Log.i(TAG, "media browser connected: ${component.packageName}")
                reevaluate()
            }
            override fun onConnectionSuspended() {
                browserControllers.remove(component.packageName)
                main.postDelayed({ connectBrowser(component) }, 5_000)
            }
            override fun onConnectionFailed() {
                Log.w(TAG, "media browser unavailable: ${component.flattenToShortString()}")
                main.postDelayed({ connectBrowser(component) }, 30_000)
            }
        }, null)
        browsers[component.packageName] = browser
        try { browser.connect() } catch (e: Exception) { Log.w(TAG, "connecting ${component.packageName}: ${e.message}") }
    }

    /**
     * Choosing the player to show, without flickering:
     * 0) the one of the car's active audio source; 1) a playing session; 2) the session already shown as long as it exists;
     * 3) the radio, then Bluetooth, then USB (even if paused).
     */
    private fun reevaluate() {
        // A player changed (state, source): the startup sequence looks at once, e.g. to pause a source that starts
        if (seq?.done == false) main.post { prefs()?.let { recordPlayback(it) }; seqStep() }
        val active = try { manager?.getActiveSessions(null).orEmpty() } catch (_: SecurityException) { emptyList() }
        // No more phone projecting: the CarPlay / Android Auto player is no longer offered (#36)
        val candidates = (active + browserControllers.values).filter { !gone(it.packageName) }
        lastCandidates = candidates
        candidates.forEach { c ->
            if (watched.add(c.sessionToken)) c.registerCallback(callback, main)
        }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        pauseOthersOnStart(candidates)
        val source = effectiveSource(candidates)
        // Wheel button on an app's player (#98): the stock launcher sends it to the radio for a moment, the
        // card stays on the app meanwhile (1 s)
        val nowUp = android.os.SystemClock.uptimeMillis()
        val held = (wheelFrom?.takeIf { nowUp - wheelAt < 1_000 } ?: wheelBack?.takeIf { nowUp - wheelBackAt < 1_000 })
            ?.takeIf { pkg -> candidates.any { it.packageName == pkg } || browserControllers[pkg] != null }
        // Old SDK radio: no controller, it is shown from the car's source, the
        // choice on the card, or because it plays while no other player does.
        val showRadio = held == null && legacyRadio && SaicRadio.ready && when (pinnedPkg) {
            RADIO -> true
            null -> source == RADIO || (source == null && playing == null && SaicRadio.last?.playing == true)
            else -> false
        }
        if (showRadio != radioShown) Log.i(TAG, "player shown: ${if (showRadio) "radio (SAIC service)" else "media session"}")
        radioShown = showRadio
        // Old SDK USB (#80): no controller either, shown from the same signals as the radio
        val showUsb = held == null && !showRadio && serviceMedia && SaicMedia.ready && USB in availableSources() && when (pinnedPkg) {
            USB -> true
            null -> source == USB || (source == null && playing == null && SaicMedia.last?.playing == true)
            else -> false
        }
        if (showUsb != usbShown) Log.i(TAG, "player shown: ${if (showUsb) "USB (SAIC service)" else "not USB"}")
        usbShown = showUsb
        if (showRadio || showUsb) { current = null; publish(); return }
        // A player can have two controllers (active session, media browser): prefer the one
        // with a title, then the one with a state, then the browser.
        fun bestFor(pkg: String): MediaController? {
            if (gone(pkg)) return null
            val all = candidates.filter { it.packageName == pkg }
            return all.firstOrNull { it.metadata != null } ?: all.firstOrNull { it.playbackState != null }
                ?: browserControllers[pkg] ?: all.firstOrNull()
        }
        val pinned = pinnedPkg?.let { bestFor(it) }
        val fromSource = source?.let { bestFor(it) }
        val chosen = held?.let { bestFor(it) }
            ?: pinned
            ?: fromSource
            ?: playing
            ?: current?.takeIf { cur -> candidates.any { it.sessionToken == cur.sessionToken } }
            ?: active.firstOrNull()
            ?: BROWSERS.firstNotNullOfOrNull { browserControllers[it.packageName] }
        if (chosen?.sessionToken != current?.sessionToken) {
            current = chosen
            Log.i(TAG, "player shown: ${chosen?.packageName ?: "none"}")
        }
        publish()
    }

    @Volatile private var lastCandidates: List<MediaController> = emptyList()

    /** App players that were playing at the last pass. */
    private val appsPlaying = mutableSetOf<String>()

    /**
     * An app starts playing (launched from its screen, or chosen as source): the other apps still
     * playing are paused. The car has no source for them and some ignore the
     * loss of audio focus, hence DiAuto and SimpMusic playing together.
     */
    private fun pauseOthersOnStart(candidates: List<MediaController>) {
        fun isApp(pkg: String) = pkg != RADIO && pkg != "com.android.bluetooth" && pkg != "com.saicmotor.media" &&
            !isAndroidAuto(pkg) && !pkg.startsWith("com.allgo.") && pkg != appContext?.packageName
        val now = candidates.filter { isApp(it.packageName) && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val nowPkgs = now.map { it.packageName }.toSet()
        val started = nowPkgs - appsPlaying
        if (started.size == 1 && nowPkgs.size > 1) {
            val keep = started.first()
            now.filter { it.packageName != keep }.distinctBy { it.packageName }.forEach {
                Log.i(TAG, "pausing ${it.packageName} ($keep started)")
                it.transportControls.pause()
            }
        }
        appsPlaying.clear(); appsPlaying.addAll(nowPkgs)
    }

    /**
     * One player can have two controllers (active session and media browser): on the
     * car, the Bluetooth state only followed on one of them, and the widget's play/pause
     * button stayed frozen. Take the most recently updated state.
     */
    private fun isPlaying(c: MediaController): Boolean {
        // The most recent state among this player's controllers prevails.
        val st = (lastCandidates.filter { it.packageName == c.packageName } + c)
            .mapNotNull { it.playbackState }
            .maxByOrNull { it.lastPositionUpdateTime }
        return st?.state == PlaybackState.STATE_PLAYING
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            // The callback does not say which player: restart browsers whose session disappeared.
            browserControllers.toMap().filterKeys { it == RADIO }.forEach { (pkg, c) ->
                val dead = try { c.playbackState == null && c.metadata == null } catch (_: Exception) { true }
                if (dead) main.postDelayed({ reconnectBrowser(pkg, "session détruite") }, 1_000)
            }
            current = null
            watched.clear()
            reevaluate()
        }
    }

    @Volatile private var rdsText = ""
    private var rdsStation = ""

    /** RDS text of the FM station, sent by Radio Dash: only source, its media session does not contain it. */
    private fun listenRdsText(context: Context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                rdsText = i.getStringExtra("text").orEmpty()
                main.post { publish() }
            }
        }
        try { context.applicationContext.registerReceiver(receiver, android.content.IntentFilter("app.mg4dash.RDS_TEXT")) }
        catch (e: Exception) { Log.w(TAG, "RDS text: ${e.message}") }
        // RDS name (PS) of the FM station, with its frequency (kHz), sent by MG4Radio: the radio's media session only
        // has the name known when tuning, nearly always empty since the PS comes a few seconds later
        val names = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val khz = i.getIntExtra("frequency", -1)
                val name = i.getStringExtra("name").orEmpty().trim()
                if (khz <= 0 || name.length < 2) return
                synchronized(rdsNames) { rdsNames.remove(khz); rdsNames[khz] = name; while (rdsNames.size > 60) rdsNames.remove(rdsNames.keys.first()) }
                main.post { publish() }
            }
        }
        try { context.applicationContext.registerReceiver(names, android.content.IntentFilter("app.mg4dash.RDS_NAME")) }
        catch (e: Exception) { Log.w(TAG, "RDS name: ${e.message}") }
    }

    /** RDS names heard, by FM frequency (kHz): the last one first shown when coming back to a station. */
    private val rdsNames = LinkedHashMap<Int, String>()

    private fun rdsNameFor(khz: Int): String = if (khz <= 0) "" else synchronized(rdsNames) { rdsNames[khz].orEmpty() }

    private fun publish() {
        if (radioShown) { publishRadio(); return }
        if (usbShown) { publishUsb(); return }
        val c = current
        if (c == null) { _now.value = NowPlaying(); return }
        val md = c.metadata
        val st = c.playbackState
        val rawTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val rawArtist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        // FM / AM radio: the car puts the frequency as title ("FM 98.20") and the RDS name as artist;
        // the name moves to title and the frequency below; with no name, the frequency stays as title. The RDS text
        // sent by Radio Dash (the session only has frequency and name) is cleared on station change
        val analog = c.packageName == RADIO && ANALOG.matches(rawTitle.trim())
        if (rdsStation != rawTitle) { rdsStation = rawTitle; rdsText = "" }
        // Name not in the session (the radio only puts the one known when tuning): the one MG4Radio sent for that frequency
        val name = if (!analog) "" else rawArtist.trim().ifBlank { rdsNameFor(analogKhz(rawTitle)) }
        val swap = name.isNotBlank()
        val title = if (swap) name else rawTitle
        val artist = if (swap) rawTitle.trim() else rawArtist
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp = (md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.takeUnless { blank(it) }
        val np = NowPlaying(
            available = true,
            packageName = c.packageName,
            source = if (c.packageName == RADIO && radioBandsSplit()) sourceLabel(bandTarget ?: currentBandSource() ?: RADIO) else sourceLabel(c.packageName),
            title = title,
            artist = artist,
            album = album,
            art = bmp ?: _now.value.art.takeIf { lastArtKey == "$title|$artist" },
            playing = shownPlaying(c),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = st?.position ?: 0,
            analog = analog,
            radioText = if (analog) rdsText else "",
            androidAuto = androidAutoAvailable(),
        )
        _now.value = np
        // CarPlay (#94): what its session really gives, to tell a car limit from a reading problem
        if (carPlay && isAndroidAuto(c.packageName)) {
            val seen = "state ${st?.state}, title ${title.isNotBlank()}, cover ${bmp != null}"
            if (seen != carPlayLogged) { carPlayLogged = seen; Log.i(TAG, "CarPlay: $seen") }
        }
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "$title|$artist"
        // DAB radio without a picture: the radio clears the cover at each station change and only
        // sends it when the slideshow arrives. Like Radio Dash, take the station's cached image.
        if (bmp == null && artUri == null && c.packageName == "com.saicmotor.radio" && !analog && title.isNotBlank() && key != lastArtKey) {
            lastArtKey = key
            io.launch { cachedDabArt(title)?.let { a -> if (_now.value.title == title && _now.value.packageName == "com.saicmotor.radio") _now.value = _now.value.copy(art = a) } }
        } else if (bmp == null && artUri != null && (key != lastArtKey || artUri != lastArtUri || (np.art == null && !artPending))) {
            // Bluetooth: the cover is not always readable at once (phone's image link
            // established after the title, or address changing during the track) and it was only retried at
            // the next track. Retry as long as the track lasts: every 3 s, then every 15 s.
            if (key != lastArtKey || artUri != lastArtUri) artTries = 0
            lastArtKey = key; lastArtUri = artUri; artTries++; artPending = true
            val tries = artTries
            io.launch {
                val a = loadArt(artUri)
                if (a != null) {
                    artTries = 0; artPending = false
                    if (_now.value.title == title) _now.value = _now.value.copy(art = a)
                } else {
                    if (tries == 1 || tries % 10 == 0) Log.i(TAG, "album art not ready (try $tries), retrying")
                    main.postDelayed({ artPending = false; if (lastArtKey == key && _now.value.art == null) publish() }, if (tries < 10) 3_000 else 15_000)
                }
            }
        } else if (bmp != null) {
            lastArtKey = key
        }
    }

    /**
     * Cached image of the current DAB station (files/dab_<serviceId>.jpg of the DAB+ patch), if the
     * station name matches the displayed title (avoids a previous DAB station's image on FM).
     * The radio runs as a system uid like MG4Dash: its files are readable.
     */
    private fun cachedDabArt(title: String): Bitmap? = try {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            "/data/data/com.saicmotor.radio/databases/RadioDatabase", null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        val (id, name) = db.use { d ->
            d.rawQuery("SELECT service_id, service_name FROM dab_current_station_table LIMIT 1", null).use { cur ->
                if (cur.moveToFirst()) cur.getLong(0) to (cur.getString(1) ?: "") else null
            }
        } ?: return null
        fun norm(t: String) = t.uppercase().replace("DAB+", "").replace(Regex("[^A-Z0-9]"), "")
        val a = norm(name); val b = norm(title)
        if (a.isEmpty() || !(b.startsWith(a) || a.startsWith(b))) null
        else java.io.File("/data/data/com.saicmotor.radio/files/dab_$id.jpg").takeIf { it.canRead() }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    } catch (e: Exception) {
        Log.w(TAG, "cached DAB image: ${e.message}"); null
    }

    /** Pochette Bluetooth AVRCP : /storage/emulated/0/bluetooth/<MAC>/AVRCP_BIP_IMG_*.JPEG */
    private fun loadArt(uriString: String): Bitmap? = try {
        val ctx = appContext ?: return null
        val decoded = URLDecoder.decode(uriString, "UTF-8")
        val uri = Uri.parse(decoded)
        when (uri.scheme) {
            "content", "android.resource" -> ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            else -> File(uri.path ?: decoded).takeIf { it.canRead() }?.let { BitmapFactory.decodeFile(it.path) }
        }?.takeUnless { blank(it) }
    } catch (e: Exception) {
        Log.w(TAG, "album art unreadable: ${e.message}"); null
    }

    /**
     * Blank image (a single colour): over Bluetooth, the phone sometimes sends a blank thumbnail before the
     * real cover (#36). It is treated as "no cover yet": keep the previous one of the same
     * track or retry, instead of showing an empty square.
     */
    private fun blank(b: Bitmap): Boolean = try {
        val w = b.width; val h = b.height
        if (w < 4 || h < 4) true else {
            val c0 = b.getPixel(w / 2, h / 2)
            val same = listOf(w / 4 to h / 4, 3 * w / 4 to h / 4, w / 4 to 3 * h / 4, 3 * w / 4 to 3 * h / 4, w / 2 to h / 4, w / 2 to 3 * h / 4)
                .all { (x, y) -> close(b.getPixel(x, y), c0) }
            same
        }
    } catch (_: Exception) { false }

    private fun close(a: Int, b: Int): Boolean =
        kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) < 8 &&
            kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) < 8 &&
            kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)) < 8

    /**
     * Stock radio of old firmwares, driven through its service. Radio Dash installed in its place (#80) is
     * another app, with a standard playback service: it then goes through the SWI69 path.
     */
    private val legacyRadio: Boolean
        get() {
            if (app.mg4dash.system.Firmware.family != app.mg4dash.system.Firmware.Family.LEGACY) return false
            // Read again every minute: Radio Dash may be installed while MG4Dash runs (#99)
            val now = android.os.SystemClock.uptimeMillis()
            if (now - radioDashCheckedAt > 60_000L || radioDashCheckedAt == 0L) {
                radioDashCheckedAt = now
                radioDash = appContext?.let { app.mg4dash.system.RadioDashUpdater.installed(it) } == true
            }
            return !radioDash
        }
    @Volatile private var radioDash = false
    @Volatile private var radioDashCheckedAt = 0L
    @Volatile private var radioShown = false
    @Volatile private var usbShown = false
    private const val USB = "com.saicmotor.media"
    /** Old SDK without com.saicmotor.media: Bluetooth and USB go through its service (#80). */
    private val serviceMedia: Boolean by lazy { appContext?.let { SaicMedia.applies(it) } == true }

    /**
     * Like the old SDK launcher (MusicViewModel.checkResource): pause the old source, then "play" on the new
     * one, with no source command. If USB has nothing to play yet, its list is loaded by the stock music app.
     */
    private fun activateServiceMedia(ctx: Context, pkg: String): Boolean {
        if (!legacyRadio && (radioShown || sourcePkg == RADIO || current?.packageName == RADIO)) pauseRadio()
        val fromBluetooth = current?.packageName == "com.android.bluetooth"
        Log.i(TAG, "audio source switch through the media service: $pkg")
        if (pkg == USB) usbCommand {
            if (fromBluetooth) SaicMedia.btPause()
            SaicMedia.usbPlay().also { ok ->
                if (!ok) main.post {
                    Log.i(TAG, "USB not ready: opening the stock music app")
                    SaicMedia.openMusicApp(ctx, usb = true)
                }
            }
        } else io.launch { if (!SaicMedia.btPlay()) Log.w(TAG, "Bluetooth play refused") }
        return true
    }

    private fun publishRadio() {
        val st = SaicRadio.last
        val freq = st?.let { SaicRadio.frequencyLabel(it) }.orEmpty()
        val name = st?.name?.takeIf { it.isNotBlank() } ?: st?.takeIf { it.band == SaicRadio.BAND_FM }?.let { rdsNameFor(it.frequencyKhz) }.orEmpty()
        _now.value = NowPlaying(
            available = true,
            packageName = RADIO,
            source = st?.let { SaicRadio.bandLabel(it.band) } ?: "RADIO",
            title = name.ifBlank { freq },
            artist = if (name.isBlank()) "" else freq,
            playing = st?.playing == true,
            androidAuto = androidAutoAvailable(),
        )
    }

    /** USB of old SDK firmwares (#80). */
    private fun publishUsb() {
        val st = SaicMedia.last
        _now.value = NowPlaying(
            available = true,
            packageName = USB,
            source = "USB",
            title = st?.track?.title?.takeIf { it.isNotBlank() } ?: "USB",
            artist = st?.track?.artist.orEmpty(),
            album = st?.track?.album.orEmpty(),
            playing = st?.playing == true,
            androidAuto = androidAutoAvailable(),
        )
    }

    /** USB command off the main thread, then reread the track. */
    private fun usbCommand(action: () -> Boolean) {
        io.launch {
            if (!action()) Log.w(TAG, "USB command refused")
            kotlinx.coroutines.delay(400); SaicMedia.refresh()
            main.post { reevaluate() }
        }
    }

    /** Radio command off the main thread, then reread the station. */
    /**
     * The radio changed station on a wheel button meant for another app: reverse command
     * through its session (the same path as the stock launcher), then back to the app once the
     * radio is back on its station ([then] pauses it when leaving the radio).
     */
    private fun undoRadioSkip(forward: Boolean, then: () -> Unit) {
        val rc = browserControllers[RADIO] ?: lastCandidates.firstOrNull { it.packageName == RADIO }
        Log.i(TAG, "radio station change undone (${if (forward) "previous" else "next"})")
        if (rc != null) { if (forward) rc.transportControls.skipToPrevious() else rc.transportControls.skipToNext() }
        else io.launch { if (forward) SaicRadio.previous() else SaicRadio.next() }
        main.postDelayed(then, 200)
    }

    /**
     * The app the radio took the sound from (a wheel button through the stock launcher) plays again: some
     * take their audio focus back themselves, others (the web app's page) stay paused and the radio
     * kept the sound, from the second press on (#98).
     */
    private fun resumeApp(pkg: String) {
        // Once, 0.5 s after the last press (#98): a burst of volume presses gives a single cut, not one per press
        main.removeCallbacks(resumeRun)
        resumePkg = pkg
        main.postDelayed(resumeRun, RESUME_AFTER_MS)
    }

    private const val RESUME_AFTER_MS = 500L

    /** The wheel's play / pause on an app that was playing (#36): paused, the radio's passage having maybe not done it. */
    private fun pauseApp(pkg: String) {
        main.removeCallbacks(resumeRun)
        resumePkg = null
        val c = (lastCandidates.filter { it.packageName == pkg } + listOfNotNull(browserControllers[pkg])).firstOrNull() ?: return
        Log.i(TAG, "$pkg: wheel play / pause, paused")
        wheelPausedPkg = pkg; wheelPausedAt = android.os.SystemClock.uptimeMillis()
        c.transportControls.pause()
    }
    @Volatile private var resumePkg: String? = null
    private val resumeRun = Runnable {
        val pkg = resumePkg ?: return@Runnable endWheelSilence("no app to resume")
        val c = (lastCandidates.filter { it.packageName == pkg } + listOfNotNull(browserControllers[pkg])).firstOrNull()
            ?: return@Runnable endWheelSilence("app gone")
        if (!isPlaying(c)) {
            Log.i(TAG, "$pkg paused by the radio's passage: play")
            c.transportControls.play()
        }
        // The volume back once the app has the sound again (see watchAudioSource), at once if it already has it
        wheelSilenceEndsIn(sourcePkg)?.let { main.removeCallbacks(silenceEnd); main.postDelayed(silenceEnd, it) }
    }

    /**
     * A volume key on the wheel while another app has the sound: the stock launcher restarts the radio, heard
     * about 0.1 s until our pause takes (#98). The media group stays muted from the radio's restart until the app
     * plays again. Its level is never written (a write to 0 then back lost the presses made meanwhile).
     */
    @Volatile private var wheelSilence = false
    // next / previous: the app had the sound back up to 1.9 s after the key (YouTube in the web app)
    internal const val WHEEL_SILENCE_MAX_MS = 3_000L
    internal const val WHEEL_SILENCE_TAIL_MS = 150L
    /** Without a resume (the app was paused), after the radio's pause has taken. */
    private const val WHEEL_SILENCE_NO_RESUME_MS = 400L
    private val wheelSilenceMax = Runnable { endWheelSilence("${WHEEL_SILENCE_MAX_MS / 1000} s at most") }
    private val silenceEnd = Runnable { endWheelSilence("the app has the sound") }

    private fun startWheelSilence() {
        val ctx = appContext ?: return
        if (wheelSilence) return
        wheelSilence = app.mg4dash.car.CarMediaMute.mute(ctx)
        if (wheelSilence) { main.removeCallbacks(wheelSilenceMax); main.postDelayed(wheelSilenceMax, WHEEL_SILENCE_MAX_MS) }
    }

    private fun endWheelSilence(why: String) {
        if (!wheelSilence) return
        wheelSilence = false
        main.removeCallbacks(wheelSilenceMax); main.removeCallbacks(silenceEnd)
        appContext?.let { app.mg4dash.car.CarMediaMute.unmute(it, why) }
    }

    /** Pauses the radio: through its session, otherwise through its service. */
    private fun pauseRadio() {
        val rc = browserControllers[RADIO] ?: lastCandidates.firstOrNull { it.packageName == RADIO }
        Log.i(TAG, "pausing the radio")
        if (rc != null) rc.transportControls.pause() else radioCommand { SaicRadio.pause() }
    }

    private fun radioCommand(action: () -> Boolean) {
        io.launch {
            val ok = action()
            kotlinx.coroutines.delay(400); SaicRadio.refresh()
            main.post { reevaluate() }
            if (!ok) Log.w(TAG, "radio command refused")
        }
    }

    fun sourceLabel(pkg: String?): String = when {
        pkg == null -> ""
        pkg == "com.android.bluetooth" -> "BLUETOOTH"
        pkg == RADIO_DAB -> "RADIO DAB"
        pkg == RADIO_FM -> "RADIO FM"
        pkg == "com.saicmotor.radio" -> "RADIO"
        pkg == "com.saicmotor.media" -> "USB"
        pkg == AA_MBS && carPlay -> "CARPLAY"
        pkg.startsWith("com.allgo.app.androidauto") || pkg == AA_MBS -> "ANDROID AUTO"
        pkg.startsWith("com.allgo.carplay") -> "CARPLAY"
        else -> appContext?.let { ctx ->
            try {
                val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
                ctx.packageManager.getApplicationLabel(ai).toString().uppercase()
            } catch (_: Exception) { pkg.substringAfterLast('.').uppercase() }
        } ?: pkg
    }

    /**
     * Play / pause requested: shown at once. Bluetooth only updates its state
     * several seconds late (#36); the real state takes over as soon as it matches,
     * or after 4 s.
     */
    private var forcedPlay: Pair<String, Boolean>? = null
    private var forcedUntil = 0L

    private fun force(pkg: String, playing: Boolean) {
        forcedPlay = pkg to playing
        forcedUntil = android.os.SystemClock.uptimeMillis() + 4000
        publish()
        main.postDelayed({ publish() }, 4100)
    }

    private fun shownPlaying(c: MediaController): Boolean {
        val real = isPlaying(c)
        val f = forcedPlay ?: return real
        if (f.first != c.packageName || android.os.SystemClock.uptimeMillis() >= forcedUntil || f.second == real) { forcedPlay = null; return real }
        return f.second
    }

    /** Play / pause of the card or the wheel (the driver's: toggles what the driver sees). */
    fun playPause() {
        if (BuildConfig.MOCK) { _now.value = _now.value.copy(playing = !_now.value.playing); return }
        userAction("play / pause")
        playPauseRaw()
    }

    private fun playPauseRaw() {
        if (usbShown) { val playing = SaicMedia.last?.playing == true; usbCommand { if (playing) SaicMedia.usbPause() else SaicMedia.usbPlay() }; return }
        if (radioShown) { val playing = SaicRadio.last?.playing == true; radioCommand { if (playing) SaicRadio.pause() else SaicRadio.play() }; return }
        val c = current ?: return
        val playing = isPlaying(c)
        // The car is on another source (e.g. FM): a "play" sent to Bluetooth or
        // USB is ignored. First ask the car to change source (like the stock
        // launcher), then start playback if it did not start by itself.
        if (!playing && !isAndroidAuto(c.packageName) && effectiveSource().let { it != null && it != c.packageName } &&
            activateSource(c.packageName)) {
            main.postDelayed({
                current?.takeIf { it.packageName == c.packageName && it.playbackState?.state != PlaybackState.STATE_PLAYING }
                    ?.transportControls?.play()
            }, 1500)
            return
        }
        // CarPlay after the radio: the iPhone no longer has main audio, the play key had no
        // effect until music was restarted on the phone (#36). First go back to
        // the CarPlay source like the stock launcher, which gives audio back to the phone.
        if (carPlay && !playing && isAndroidAuto(c.packageName) && effectiveSource().let { it != null && it != c.packageName }) {
            selectProjectionSource(); return
        }
        // Android Auto (RemoteUI) only reacts to media keys, not play()/pause() commands
        if (isAndroidAuto(c.packageName)) { aaControl(c, if (playing) AA_PAUSE else AA_PLAY, if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY); return }
        if (playing) c.transportControls.pause() else c.transportControls.play()
        force(c.packageName, !playing)
        // After a restart, the car announces Bluetooth (or USB) as the source but has not
        // reactivated it: "play" has no effect until the source changes (#36). If nothing
        // plays after 2 s, ask the car for the source again, then playback.
        if (!playing && c.packageName != RADIO) retryPlayIfSilent(c.packageName)
    }

    private fun retryPlayIfSilent(pkg: String) {
        main.postDelayed({
            val now = current?.takeIf { it.packageName == pkg } ?: return@postDelayed
            if (isPlaying(now) || !activateSource(pkg)) return@postDelayed
            Log.i(TAG, "play had no effect on $pkg: source requested again")
            main.postDelayed({ current?.takeIf { it.packageName == pkg && !isPlaying(it) }?.transportControls?.play() }, 1500)
        }, 2000)
    }

    /**
     * Wheel play button (#78): the stock launcher sends "play", which has no effect after a restart until
     * the source changes, like the on-screen button above. Only while the source has not played since
     * MG4Dash started: later, the late Bluetooth state (#36) would turn a pause into a restart.
     */
    fun wheelPlayPressed() {
        if (BuildConfig.MOCK) return
        userAction("wheel play")
        val c = current ?: return
        if (c.packageName == RADIO || c.packageName !in CAR_SOURCES) return
        if (playingAt.containsKey(c.packageName) || isPlaying(c)) return
        retryPlayIfSilent(c.packageName)
    }

    fun next() {
        userAction("next")
        if (radioShown) { radioCommand { SaicRadio.next() }; return }
        if (usbShown) { usbCommand { SaicMedia.usbNext() }; return }
        val c = current ?: return
        if (isAndroidAuto(c.packageName)) aaControl(c, AA_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) else c.transportControls.skipToNext()
    }

    fun previous() {
        userAction("previous")
        if (radioShown) { radioCommand { SaicRadio.previous() }; return }
        if (usbShown) { usbCommand { SaicMedia.usbPrevious() }; return }
        val c = current ?: return
        if (isAndroidAuto(c.packageName)) aaControl(c, AA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS) else c.transportControls.skipToPrevious()
    }

    // ── Android Auto: playback commands through the allgo service (as SAIC RemoteUI does) ──
    // IAndroidAutoMainService.sendMediaPlayControl(int): 1 play, 2 pause, 3 next, 4 previous
    private const val AA_PLAY = 1
    private const val AA_PAUSE = 2
    private const val AA_NEXT = 3
    private const val AA_PREVIOUS = 4
    private const val AA_DESCRIPTOR = "com.allgo.app.androidauto.IAndroidAutoMainService"
    private const val AA_TX_MEDIA_CONTROL = 25   // IBinder.FIRST_CALL_TRANSACTION + 24
    @Volatile private var aaService: android.os.IBinder? = null
    private val aaConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) { aaService = service; Log.i(TAG, "Android Auto service connected") }
        override fun onServiceDisconnected(name: ComponentName?) { aaService = null }
    }

    private fun bindAndroidAuto() {
        val ctx = appContext ?: return
        try {
            val ok = ctx.bindService(Intent().setComponent(ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.AndroidAutoMainService")),
                aaConnection, android.content.Context.BIND_AUTO_CREATE)
            Log.i(TAG, "binding Android Auto service: $ok")
        } catch (e: Exception) { Log.w(TAG, "Android Auto service: ${e.message}") }
    }

    /**
     * CarPlay goes through the same RemoteUI player as Android Auto, but not through the Android
     * Auto service: its touch commands had no effect (#36, to test). System media
     * keys, those of the wheel, go to the active session.
     */
    @Volatile private var carPlay = false
    private var carPlayLogged: String? = null

    /**
     * allgo's CarPlay service (ICarPlayService, found in AllgoCarplay_EH32):
     * dispatchKeyEvent(code, 2 = press + release) on 85 / 87 / 88 / 126 / 127 (transaction 4),
     * launchApp(4 = "Now Playing") (transaction 13). System media keys did not reach it.
     */
    private const val CP_DESCRIPTOR = "com.allgo.carplay.service.ICarPlayService"
    @Volatile private var cpService: android.os.IBinder? = null
    private val cpConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) { cpService = service; Log.i(TAG, "CarPlay service connected") }
        override fun onServiceDisconnected(name: ComponentName?) { cpService = null }
    }

    private fun bindCarPlay() {
        val ctx = appContext ?: return
        try {
            val ok = ctx.bindService(Intent().setComponent(ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayService")),
                cpConnection, android.content.Context.BIND_AUTO_CREATE)
            Log.i(TAG, "binding CarPlay service: $ok")
        } catch (e: Exception) { Log.w(TAG, "CarPlay service: ${e.message}") }
    }

    private fun cpCall(tx: Int, vararg args: Int): Boolean {
        val b = cpService ?: run { bindCarPlay(); return false }
        val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
        return try {
            data.writeInterfaceToken(CP_DESCRIPTOR)
            args.forEach { data.writeInt(it) }
            b.transact(tx, data, reply, 0)
            reply.readException()
            Log.i(TAG, "CarPlay transaction $tx ${args.joinToString()} → ${reply.readInt()}")
            true
        } catch (e: Exception) { Log.w(TAG, "CarPlay transaction $tx: ${e.message}"); false }
        finally { data.recycle(); reply.recycle() }
    }

    /**
     * Moves to the CarPlay source like the stock launcher (onSwitchCarPlayAASourceOnly): key 1 to AllGo's RemoteUI
     * service, which gives main audio back to the iPhone. The car then changes its source, which the SAIC media
     * service sees: its top bar shows CarPlay's song (#106). Without the service, CarPlay's play / pause key as before.
     *
     * The #99 rescue (SWI165: the radio kept the sound): only if, 2 s later, the radio really still plays. Then the
     * radio is paused and CarPlay told to play, as the play button of the SAIC Bluetooth page does. Never on doubt:
     * that path is not seen by the SAIC media service (its bar stayed on the radio, #106), and its play key could
     * pause a CarPlay already playing.
     */
    private fun selectProjectionSource(attempt: Int = 0) {
        val ctx = appContext ?: return
        val rescue = attempt > 0
        if (rescue) { Log.i(TAG, "CarPlay : secours #99 (la radio joue encore)"); pauseRadio() }
        val fromRadio = sourcePkg == RADIO || radioShown
        // The car's USB player playing (a stick plugged in): it holds the SAIC top bar, CarPlay's key alone did not
        // take it back (#106). Paused first, unless USB is the driver's source
        val usbFirst = serviceMedia && SaicMedia.last?.playing == true && pinnedPkg != USB && sourcePkg != USB
        Thread {
            if (usbFirst) { Log.i(TAG, "CarPlay : lecteur USB mis en pause"); SaicMedia.usbPause(); Thread.sleep(300) }
            if (rescue) Thread.sleep(300)
            if (!app.mg4dash.system.Projection.mediaKey(ctx, app.mg4dash.system.Projection.KEY_SELECT_SOURCE))
                systemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            if (rescue) systemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }.start()
        main.postDelayed({
            io.launch {
                // What the SAIC media service (its top bar) has as the current source, not the one MG4Dash follows
                val saic = if (serviceMedia) SaicMedia.currentSource() else null
                main.post {
                    val taken = carPlayTaken(saic, sourcePkg)
                    if (taken) Log.i(TAG, "CarPlay : source prise" + (saic?.let { " (SAIC $it)" } ?: "") + if (rescue) " (secours #99)" else "")
                    else Log.i(TAG, "CarPlay audio after source key: still on ${saic?.let { "SAIC $it" } ?: sourcePkg}" + if (rescue) " (after the rescue)" else "")
                    if (handoverRetry(taken, fromRadio, radioPlaying(), attempt) && pinnedPkg == AA_MBS) selectProjectionSource(attempt + 1)
                }
            }
        }, 2000)
    }

    /**
     * CarPlay has the car's source: the SAIC media service's current source is CarPlay's (50, 52), when it answers
     * (old SDK firmwares, SWI165…); otherwise the source MG4Dash follows.
     */
    internal fun carPlayTaken(saicSource: Int?, followed: String?) =
        if (saicSource != null) saicSource in CARPLAY_SOURCES else followed == AA_MBS

    /** SAIC source types of CarPlay (its audio, and its Siri / navigation prompts). */
    private val CARPLAY_SOURCES = setOf(50, 52)

    /**
     * #106: the car's USB player starts by itself (a stick plugged in) while CarPlay is the source: it takes the SAIC
     * top bar from CarPlay. Never when USB is the driver's source. True: pause it and give the source back to CarPlay.
     */
    internal fun usbTakesTheBar(wasPlaying: Boolean?, playing: Boolean?, carPlay: Boolean, pinned: String?, source: String?) =
        playing == true && wasPlaying != true && carPlay && pinned != USB && source != USB && (pinned == AA_MBS || source == AA_MBS)

    /** The radio really plays (its player's state); false when unknown. */
    private fun radioPlaying(): Boolean =
        if (legacyRadio) SaicRadio.last?.playing == true
        else (browserControllers[RADIO] ?: lastCandidates.firstOrNull { it.packageName == RADIO })?.let { isPlaying(it) } == true

    /** The #99 rescue, once: CarPlay has not the sound, it came from the radio and the radio really still plays. */
    internal fun handoverRetry(taken: Boolean, fromRadio: Boolean, radioPlaying: Boolean, attempt: Int) = !taken && fromRadio && radioPlaying && attempt == 0

    /** Media key to CarPlay; without the service, system media key. */
    private fun systemMediaKey(code: Int) {
        if (cpCall(4, code, 2)) return
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "CarPlay: system media key $code")
    }

    /** Touch on the media card in CarPlay: CarPlay in front, on the "Now Playing" screen. */
    fun openCarPlayNowPlaying(): Boolean {
        // Only when the card shows CarPlay: on the radio or Bluetooth, the last connected CarPlay
        // was opened, and led to a black screen once the iPhone was unplugged (#36)
        if (!carPlay || radioShown || projectionLive == false || current?.let { isAndroidAuto(it.packageName) } != true) return false
        val ctx = appContext ?: return false
        app.mg4dash.system.Apps.open(ctx, app.mg4dash.system.Apps.CARPLAY, "CarPlay")
        main.postDelayed({ cpCall(13, 4) }, 600)
        return true
    }

    /** Command sent to the Android Auto service; otherwise, media key to its session. */
    private fun aaControl(c: MediaController, code: Int, fallbackKey: Int) {
        if (carPlay) {
            // CarPlay does not say if it is playing: play and pause go through the toggle key (85), which
            // its service accepts like the wheel (#36). CarPlay is no longer opened on source switch.
            val key = if (code == AA_PLAY || code == AA_PAUSE) KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE else fallbackKey
            systemMediaKey(key); return
        }
        val b = aaService
        if (b == null) { bindAndroidAuto(); mediaKey(c, fallbackKey); return }
        val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(AA_DESCRIPTOR)
            data.writeInt(code)
            b.transact(AA_TX_MEDIA_CONTROL, data, reply, 0)
            reply.readException()
            Log.i(TAG, "Android Auto command $code → ${reply.readInt()}")
        } catch (e: Exception) {
            Log.w(TAG, "Android Auto command $code: ${e.message}")
            mediaKey(c, fallbackKey)
        } finally { data.recycle(); reply.recycle() }
    }

    /** Press + release of a media key, sent to the player's session. */
    private fun mediaKey(c: MediaController, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        val ok = c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)) and
            c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "media key $code → ${c.packageName}: $ok")
    }

    /** Old SDK Bluetooth or USB shown: opens the stock music app on its tab (#80); false in any other case. */
    fun openServiceMediaApp(): Boolean {
        val ctx = appContext ?: return false
        if (!serviceMedia || radioShown) return false
        val usb = usbShown
        if (!usb && current?.packageName != "com.android.bluetooth") return false
        return SaicMedia.openMusicApp(ctx, usb)
    }

    /** Component to open when touching the media card. */
    fun ownerComponent(): ComponentName? =
        if (radioShown) ownerOf(RADIO) else if (usbShown) ownerOf(USB) else current?.packageName?.let { pkg -> ownerOf(pkg) }

    /** Stock app of a source; Bluetooth (with no app of its own) is played by the SAIC Media app. */
    private fun ownerOf(pkg: String): ComponentName? = when {
        serviceMedia && (pkg == "com.android.bluetooth" || pkg == USB) -> appContext?.packageManager?.getLaunchIntentForPackage(SaicMedia.HMI_PKG)?.component
        else -> ownerOnOtherFirmwares(pkg)
    }

    private fun ownerOnOtherFirmwares(pkg: String): ComponentName? = when (pkg) {
        AA_MBS -> if (carPlay) app.mg4dash.system.Apps.CARPLAY else app.mg4dash.system.Apps.ANDROID_AUTO
        "com.android.bluetooth" -> app.mg4dash.system.Apps.MEDIA
        RADIO -> appContext?.packageManager?.let { pm -> (pm.getLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(SaicRadio.HMI_PKG))?.component }
        else -> appContext?.packageManager?.getLaunchIntentForPackage(pkg)?.component
    }

    /**
     * Switches the car's audio source like the stock launcher (ModeManager.switchSource,
     * found on SWI69): command 0x8001 "change source" sent to the service of the media
     * or radio app. Launcher types: USB = 0, Bluetooth = 2; the radio resumes its band.
     * The media app starts playback of the chosen source itself.
     */
    private fun activateSource(pkg: String): Boolean {
        val ctx = appContext ?: return false
        if (serviceMedia && pkg != USB && SaicMedia.last?.playing == true) {
            Log.i(TAG, "USB paused before switching to $pkg")
            usbCommand { SaicMedia.usbPause() }
        }
        if (pkg == RADIO && legacyRadio) { radioCommand { SaicRadio.play() }; return true }
        // SWI68 (issue #19): command 0x8001 does not change the source and the radio continued
        // over Bluetooth; first pause the old SDK radio.
        if (legacyRadio && SaicRadio.ready && SaicRadio.last?.playing == true) {
            Log.i(TAG, "old SDK: radio paused before switching to $pkg")
            radioCommand { SaicRadio.pause() }
        }
        if (serviceMedia && (pkg == "com.android.bluetooth" || pkg == USB)) return activateServiceMedia(ctx, pkg)
        val (target, type) = when (pkg) {
            "com.android.bluetooth" -> ComponentName("com.saicmotor.media", "com.saicmotor.media.service.MediaService") to 2
            "com.saicmotor.media" -> ComponentName("com.saicmotor.media", "com.saicmotor.media.service.MediaService") to 0
            "com.saicmotor.radio" -> ComponentName("com.saicmotor.radio", "com.saicmotor.radio.service.RadioService") to -2
            else -> return false
        }
        val cmd = SaicCommand()
            .setEvent(0x8001).setTargetType(type).setTargetPackage("com.saicmotor.launcher")
        return try {
            ctx.startService(android.content.Intent().setComponent(target).setPackage(target.packageName).putExtra("command", cmd))
            Log.i(TAG, "audio source switch: $cmd → ${target.flattenToShortString()}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "audio source switch failed: ${e.message}"); false
        }
    }
}
