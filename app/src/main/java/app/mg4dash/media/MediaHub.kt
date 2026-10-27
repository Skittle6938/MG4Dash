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
 * Lecteur courant, quelle que soit la source (radio DAB/FM SAIC, Bluetooth, USB, Android Auto…).
 * Repose sur MediaSessionManager.getActiveSessions(), réservé au uid système
 * (MEDIA_CONTENT_CONTROL) : c'est ce que fait le launcher d'origine.
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
        /** Android Auto connecté (son lecteur a quelque chose à afficher) : la puce AA est proposée. */
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
    /** Tentatives de lecture de la pochette du morceau en cours (0 : rien en attente). */
    @Volatile private var artTries = 0
    /** Adresse de la pochette essayée en dernier, et essai en cours ou programmé (un seul à la fois). */
    @Volatile private var lastArtUri: String? = null
    @Volatile private var artPending = false

    fun start(context: Context) {
        appContext = context.applicationContext
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
        // Constaté sur la voiture : les sessions SAIC (radio, Bluetooth) restent « inactives »
        // même en lecture, donc absentes de getActiveSessions(). Comme le launcher d'origine,
        // on se connecte directement à leurs MediaBrowserService pour obtenir leur contrôleur.
        BROWSERS.forEach { connectBrowser(it) }
        if (legacyRadio) {
            SaicRadio.connect(context)
            // Pas de rappel côté radio : relecture de la station toutes les 2 s.
            main.postDelayed(object : Runnable {
                override fun run() {
                    io.launch { val before = SaicRadio.last; SaicRadio.refresh(); if (SaicRadio.last != before) main.post { reevaluate() } }
                    main.postDelayed(this, 2_000)
                }
            }, 1_000)
        }
        bindAndroidAuto()
        watchProjection(context.applicationContext)
        watchAudioSource(context)
        reevaluate()
        // Filet de sécurité : relecture régulière du lecteur (titre, pochette, état), au cas où un
        // rappel de la radio se perd (service radio redémarré, session remplacée).
        main.postDelayed(object : Runnable {
            override fun run() { checkStaleBrowsers(); reevaluate(); main.postDelayed(this, 5_000) }
        }, 5_000)
    }

    /**
     * Source audio active selon la voiture (API SAIC de AudioManager, comme le launcher d'origine) :
     * c'est elle qui décide du lecteur affiché, la radio restant « en lecture » même muette.
     */
    @Volatile private var sourcePkg: String? = null

    /** Source choisie à la main sur la carte (puces RADIO / USB-BT), jusqu'à ce que la voiture change de source. */
    @Volatile private var pinnedPkg: String? = null

    /**
     * La voiture annonce aussi Android Auto comme source quand seul le guidage GPS parle par-dessus
     * la radio (constaté le 2026-10-18) : on garde la source d'avant tant que le lecteur Android Auto
     * ne joue pas vraiment.
     */
    @Volatile private var aaUnconfirmed = false
    @Volatile private var sourceBeforeAa: String? = null

    /** Source réelle : celle de la voiture, sauf une bascule Android Auto due au seul guidage. */
    private fun effectiveSource(candidates: List<MediaController> = lastCandidates): String? {
        if (sourcePkg != AA_MBS || !aaUnconfirmed) return sourcePkg
        val aaPlaying = candidates.any { isAndroidAuto(it.packageName) && it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (!aaPlaying) return sourceBeforeAa
        Log.i(TAG, "Android Auto is playing media: Android Auto source confirmed")
        aaUnconfirmed = false; sourceBeforeAa = null; pinnedPkg = null
        return sourcePkg
    }

    /** Affiche la source donnée sans ouvrir son appli. */
    fun select(pkg: String) {
        if (BuildConfig.MOCK) return
        remember(pkg); autoTarget = null
        pinnedPkg = pkg
        main.post { reevaluate() }
    }

    /** Android Auto connecté : son lecteur existe et a un état ou un titre. */
    /**
     * Téléphone en projection selon AllGo (relu toutes les 5 s) : false = aucun ; null = inconnu.
     * Débranché, le lecteur RemoteUI gardait son dernier titre : la source CarPlay / Android Auto restait
     * proposée et menait à un écran noir (#36).
     */
    @Volatile private var projectionLive: Boolean? = null
    /** Téléphone connecté en audio Bluetooth (A2DP) ; false : son lecteur n'est plus proposé (#36). */
    @Volatile private var btLive: Boolean? = null

    private fun btConnected(): Boolean? = try {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
    } catch (_: Exception) { null }

    // ── Source automatique (réglage « Source au démarrage ») ──
    private fun prefs() = appContext?.let { app.mg4dash.system.Prefs(it) }

    /** Choix de l'utilisateur : en tête de l'historique (la radio reste le dernier recours). */
    private fun remember(pkg: String) {
        val p = prefs() ?: return
        val h = listOf(pkg) + p.sourceHistory.filter { it != pkg }
        if (h != p.sourceHistory) { p.sourceHistory = h.take(6); Log.i(TAG, "source history: ${h.take(6)}") }
    }

    @Volatile private var autoSwitchAt = 0L
    private val startedAt = android.os.SystemClock.uptimeMillis()
    private var startDone = false
    /** Depuis quand chaque source est disponible / absente (évite de suivre un Bluetooth qui clignote). */
    private val seenSince = mutableMapOf<String, Long>()
    private val goneSince = mutableMapOf<String, Long>()

    /**
     * Toutes les secondes. Dans tous les modes, une source de la voiture disparue depuis 1 s est remplacée
     * (automatique : par la première de l'historique ; sinon par la radio ou la source fixe). Automatique :
     * la première source de l'historique disponible depuis 5 s devient la source si l'actuelle est moins bien
     * placée. Source fixe : une seule bascule au démarrage. CarPlay n'est jamais une cible : AllGo ne lui
     * rend pas l'audio sur commande.
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
        trackPlayback(p, now)
        // Type 70 (Android Auto) tenu par une autre appli (DiAuto) alors qu'AllGo n'a aucun téléphone : la
        // source réelle est le lecteur de cette appli, et non un Android Auto « disparu » à remplacer
        if (sourcePkg == AA_MBS && projectionLive == false && otherAppShown()) sourcePkg = current?.packageName
        val cur = sourcePkg ?: return
        if (now - autoSwitchAt < 10_000 || now - startedAt < 5_000) return
        // Source de la voiture disparue (téléphone débranché…) depuis 1 s : quel que soit le réglage, on
        // bascule, sinon la voiture restait sur une source muette et les boutons du volant n'agissaient
        // plus (#36). 1 s suffit (demandé par Marc) : la disparition vient de l'état de connexion, pas d'un clignotement.
        val curGone = goneSince[cur]?.let { now - it >= 1_000 } == true
        val order = fallbackOrder(mode, p)
        if (curGone) { Log.i(TAG, "$cur gone: mode $mode, order $order, available $avail"); order.firstOrNull { it != cur && it in avail && !(it == AA_MBS && carPlay) }?.let { autoSwitch(it, "$cur gone") }; return }
        if (mode == 1) return
        if (mode >= 2) {
            if (startDone) return
            val want = order.first()
            if (now - startedAt > 30_000 || want == cur) { startDone = true; return }
            if (stable(want)) { startDone = true; autoSwitch(want, "startup source") }
            return
        }
        // Une appli a pris la source (Android Auto, CarPlay, DiAuto qui lance sa musique…) : c'est voulu, on
        // ne la lui reprend pas pour revenir à la radio (DiAuto coupé net au démarrage de sa musique)
        if (cur !in listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media")) return
        val target = order.firstOrNull { stable(it) && !(it == BT && projecting) } ?: return
        if (target == cur) return
        // Source actuelle jamais choisie (historique vide, première utilisation) : on ne la quitte que si elle disparaît
        val curRank = order.indexOf(cur).let { if (it < 0) -1 else it }
        if (order.indexOf(target) < curRank) autoSwitch(target, "$target back")
    }

    /** Sources de remplacement selon le réglage : automatique = historique des choix, puis la radio. */
    private fun fallbackOrder(mode: Int, p: app.mg4dash.system.Prefs) = when (mode) {
        0 -> (p.sourceHistory + RADIO).distinct()
        1 -> listOf(RADIO)
        else -> listOf(listOf(RADIO, "com.android.bluetooth", "com.saicmotor.media", AA_MBS)[mode - 2], RADIO).distinct()
    }

    private const val BT = "com.android.bluetooth"
    @Volatile private var resumeBtUntil = 0L

    /** Lecture en cours sur la carte (avant de couper la connexion du téléphone). */
    fun playingNow(): Boolean = current?.let { isPlaying(it) } == true

    /**
     * Connexion du téléphone coupée pendant la lecture : la source de remplacement est souvent le Bluetooth,
     * dont la musique ne revient que quelques secondes plus tard ; « lecture » y est renvoyé à son retour.
     */
    fun resumeOnBluetooth() { resumeBtUntil = android.os.SystemClock.uptimeMillis() + 15_000 }

    /** DiAuto ou une appli équivalente (Headunit Revived) en session : son écran de projection est ouvert. */
    @Volatile private var appProjecting = false

    private fun appProjection(ctx: Context): Boolean = try {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(30).any { t ->
            listOfNotNull(t.baseActivity, t.topActivity).any { it.className.endsWith(".AapProjectionActivity") }
        }
    } catch (_: Exception) { false }

    /**
     * Source Bluetooth proposée dès qu'un téléphone est connecté en Bluetooth. Pendant Android Auto,
     * CarPlay ou DiAuto, selon le réglage (activé par défaut) : DiAuto peut faire passer le son par le
     * Bluetooth. Pendant ces sessions, on ne bascule jamais dessus tout seul (voir [projecting]).
     */
    private fun btSource(): Boolean = app.mg4dash.system.BtAudio.phoneConnected() &&
        (!projecting || prefs()?.btDuringProjection != false)

    /** Android Auto / CarPlay (AllGo), DiAuto ou équivalent en session. */
    private val projecting get() = projectionLive == true || appProjecting

    /**
     * Connexion d'une appli coupée par le bouton Android Auto (DiAuto…) : si c'était la source écoutée, on
     * passe à la source de remplacement du réglage, comme pour une source de la voiture qui disparaît.
     */
    fun connectionCut(pkg: String) = main.post {
        val p = prefs() ?: return@post
        if (sourcePkg != pkg && current?.packageName != pkg) return@post
        val avail = availableSources()
        fallbackOrder(p.startSource, p).firstOrNull { it != pkg && it in avail && !(it == AA_MBS && carPlay) }
            ?.let { autoSwitch(it, "$pkg cut") }
    }

    // ── Lecture au démarrage (réglage, remplace la reprise automatique de Radio Dash) ──
    /** Démarrage de la voiture : MG4Dash lancé dans les 5 minutes qui suivent l'allumage de l'autoradio. */
    private val carStart = android.os.SystemClock.elapsedRealtime() < 5 * 60_000
    private var playbackApplied = false
    private var pausedSince = 0L

    /**
     * Retient si la musique joue (une pause n'est retenue qu'après 30 s : à l'arrêt, la voiture met tout en
     * pause juste avant de s'éteindre, ce qui ne doit pas compter), puis applique le réglage une fois, au
     * démarrage, après la source de démarrage (8 s), sur la source affichée.
     */
    private fun trackPlayback(p: app.mg4dash.system.Prefs, now: Long) {
        val playing = _now.value.playing && _now.value.available
        if (playbackApplied || !carStart) {
            if (playing) { pausedSince = 0L; if (!p.lastPlaying) p.lastPlaying = true }
            else if (_now.value.available) {
                if (pausedSince == 0L) pausedSince = now
                else if (now - pausedSince >= 30_000 && p.lastPlaying) { p.lastPlaying = false; Log.i(TAG, "playback state remembered: paused") }
            }
        }
        if (playbackApplied) return
        if (!carStart || now - startedAt > 40_000) { playbackApplied = true; return }
        if (now - startedAt < 8_000 || now - autoSwitchAt < 4_000 || !_now.value.available) return
        playbackApplied = true
        // DiAuto (Android Auto sans fil) : c'est le téléphone qui décide de lancer la musique (réglage
        // « lancer la musique automatiquement » de son appli Android Auto), MG4Dash n'y touche pas
        if (handlesWheelKeys(_now.value.packageName) || handlesWheelKeys(sourcePkg) ||
            (sourcePkg == AA_MBS && projectionLive != true && lastCandidates.any { handlesWheelKeys(it.packageName) })) {
            Log.i(TAG, "startup playback: left to the phone (DiAuto)"); return
        }
        val want = when (p.startPlayback) { 1 -> false; 2 -> true; else -> p.lastPlaying }
        Log.i(TAG, "startup playback: ${if (want) "play" else "pause"} (setting ${p.startPlayback}, was playing ${p.lastPlaying})")
        if (want != playing) playPauseRaw()
    }

    private var autoTarget: String? = null
    private var autoTries = 0

    private fun autoSwitch(pkg: String, why: String) {
        val now = android.os.SystemClock.uptimeMillis()
        // La voiture refuse la bascule (source muette…) : 3 essais au plus, puis on attend 2 min
        if (pkg == autoTarget && autoTries >= 3 && now - autoSwitchAt < 120_000) return
        if (pkg != autoTarget || now - autoSwitchAt >= 120_000) { autoTarget = pkg; autoTries = 0 }
        autoTries++
        autoSwitchAt = now
        Log.i(TAG, "automatic source: $pkg ($why)")
        pinnedPkg = pkg
        main.post { reevaluate() }
        if (pkg == AA_MBS) { browserControllers[AA_MBS]?.let { aaControl(it, AA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY) }; return }
        activateSource(pkg)
        main.postDelayed({ browserControllers[pkg]?.takeIf { !isPlaying(it) }?.transportControls?.play() }, 1500)
    }

    /** Lecteur à écarter : projection ou Bluetooth sans téléphone connecté. */
    private fun gone(pkg: String?): Boolean =
        (projectionLive == false && isAndroidAuto(pkg)) || (btLive == false && pkg == "com.android.bluetooth")

    private fun watchProjection(ctx: Context) {
        io.launch {
            while (true) {
                val s = app.mg4dash.system.Projection.session(ctx).let {
                    // Connexion coupée par le bouton : AllGo garde la session mais l'appli est désactivée
                    if (it is app.mg4dash.system.Projection.Session.Read && it.target != null &&
                        app.mg4dash.system.ConnectionToggle.isOff(ctx, it.target.packageName)) app.mg4dash.system.Projection.Session.Read(null) else it
                }
                val live = (s as? app.mg4dash.system.Projection.Session.Read)?.let { it.target != null }
                // Fin de la projection : le téléphone ne rebranche pas la musique Bluetooth de lui-même
                if (projectionLive == true && live == false) io.launch { kotlinx.coroutines.delay(3_000); app.mg4dash.system.BtAudio.connectMusic(ctx) }
                if (s is app.mg4dash.system.Projection.Session.Read && s.target != null) carPlay = s.target == app.mg4dash.system.Apps.CARPLAY
                if (live != projectionLive) {
                    Log.i(TAG, "projection: ${if (live == null) "unknown" else if (live) "phone connected" else "no phone"}")
                    projectionLive = live
                    main.post { reevaluate() }
                }
                // Bluetooth coupé pendant la lecture : la carte restait sur le Bluetooth jusqu'au balayage (#36)
                val proj = appProjection(ctx)
                if (proj != appProjecting) { Log.i(TAG, "Android Auto app (DiAuto…): ${if (proj) "phone connected" else "no phone"}"); appProjecting = proj; main.post { reevaluate() } }
                val bt = btConnected()
                if (bt != btLive) {
                    Log.i(TAG, "bluetooth audio: ${if (bt == null) "unknown" else if (bt) "phone connected" else "no phone"}")
                    btLive = bt
                    main.post { reevaluate() }
                    // Musique coupée avec la connexion du téléphone : elle reprend sur le Bluetooth revenu
                    if (bt == true && android.os.SystemClock.uptimeMillis() < resumeBtUntil) {
                        resumeBtUntil = 0L
                        main.postDelayed({
                            if (sourcePkg == BT || pinnedPkg == BT)
                                browserControllers[BT]?.takeIf { !isPlaying(it) }?.let { Log.i(TAG, "bluetooth back: play"); it.transportControls.play() }
                        }, 1_500)
                    }
                }
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

    /** Lecteur commandé par MG4Dash (Bluetooth, USB, DiAuto…), hors radio et Android Auto / CarPlay. */
    fun ownPlayerShown(): Boolean = !radioShown && current?.let { !isAndroidAuto(it.packageName) } == true

    /**
     * Lecteur d'une autre appli affiché (DiAuto…) : la voiture n'a pas de source pour elle, et les
     * boutons du volant partent vers Android Auto d'AllGo ; MG4Dash les lui renvoie.
     */
    fun otherAppShown(): Boolean = !radioShown && current?.packageName?.let {
        it != RADIO && it != "com.android.bluetooth" && it != "com.saicmotor.media" && !isAndroidAuto(it) && !it.startsWith("com.allgo.")
    } == true

    /**
     * Appli affichée qui lit elle-même le bouton lecture / pause du volant (DiAuto MG4+ et son ancien
     * nom) : MG4Dash ne le lui renvoie pas, sinon il se mettrait en pause puis relancerait aussitôt.
     */
    fun shownHandlesWheelKeys(): Boolean = handlesWheelKeys(current?.packageName)

    private fun handlesWheelKeys(pkg: String?) = pkg == "com.drivehub.diauto.mg4" || pkg == "app.mg4aa"

    // Boutons du volant sur le lecteur d'une autre appli : le launcher d'origine ne connaît pas ces applis
    // et agit sur la dernière source voiture (souvent la radio), qui reprend le son : gauche / droite lui
    // font changer de station, haut / bas (volume) la relancent si elle est en pause. On note l'appli à
    // l'appui pour la remettre aussitôt (voir watchAudioSource).
    @Volatile private var wheelFrom: String? = null
    @Volatile private var wheelAt = 0L
    /** true / false : suivant / précédent (station à rétablir) ; null : volume (rien à rétablir). */
    @Volatile private var wheelForward: Boolean? = null

    fun wheelPressed(forward: Boolean?) {
        if (!otherAppShown()) return
        wheelFrom = current?.packageName
        wheelAt = android.os.SystemClock.uptimeMillis()
        wheelForward = forward
        // La carte reste sur l'appli pendant le bref passage par la radio
        pinnedPkg = wheelFrom
    }

    /**
     * Bouton relâché : suivant / précédent à l'appli notée à l'appui (la radio a pu être affichée entre
     * les deux). DiAuto ne lit que lecture / pause du volant, pas gauche / droite. false = pas d'autre appli.
     */
    fun wheelReleased(forward: Boolean): Boolean {
        val pkg = wheelFrom?.takeIf { android.os.SystemClock.uptimeMillis() - wheelAt < 2_000 }
            ?: current?.packageName?.takeIf { otherAppShown() } ?: return false
        val c = browserControllers[pkg] ?: lastCandidates.firstOrNull { it.packageName == pkg } ?: return true
        if (forward) c.transportControls.skipToNext() else c.transportControls.skipToPrevious()
        return true
    }

    fun isAndroidAuto(pkg: String?) = pkg == AA_MBS || pkg?.startsWith("com.allgo.app.androidauto") == true

    /** Puce USB / BT : bascule entre le Bluetooth et l'USB, en partant de ce qui est affiché. */
    /**
     * Balayage vertical sur la carte musique (issue #6) : source suivante (+1) ou précédente (−1)
     * parmi celles disponibles (radio, Bluetooth connecté, clé USB, Android Auto connecté),
     * puis bascule de la source de la voiture et lecture.
     */
    /** Sources disponibles maintenant, dans l'ordre : radio, Bluetooth connecté, clé USB, Android Auto connecté. */
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
     * Autres applis qui ont un lecteur actif (DiAuto, Spotify installé sur l'autoradio…) : proposées au
     * balayage et dans le menu des sources (#36 / DiAuto : impossible de revenir dessus après la radio).
     */
    private fun otherPlayers(): List<String> = lastCandidates.filter { c ->
        val pkg = c.packageName
        pkg != appContext?.packageName && !pkg.startsWith("com.saicmotor.") && pkg != "com.android.bluetooth" &&
            !pkg.startsWith("com.allgo.") && !gone(pkg) &&
            ((c.playbackState?.state ?: PlaybackState.STATE_NONE) != PlaybackState.STATE_NONE || c.metadata != null)
    }.map { it.packageName }.distinct()

    /** Source affichée, Android Auto ramené à son lecteur. */
    fun currentSource(): String? = if (radioShown) RADIO else current?.packageName?.let { if (isAndroidAuto(it)) AA_MBS else it }

    fun cycleSource(dir: Int) {
        if (BuildConfig.MOCK) return
        val available = availableSources()
        if (available.isEmpty()) return
        val cur = currentSource()
        val i = available.indexOf(cur)
        val next = available.getOrNull(Math.floorMod((if (i < 0) 0 else i) + dir, available.size)) ?: return
        Log.i(TAG, "swipe: source $cur → $next (available: $available)")
        switchTo(next)
    }

    /** Bascule la voiture sur cette source et lance la lecture (balayage ou menu des sources). */
    fun switchTo(next: String) {
        if (BuildConfig.MOCK) return
        Log.i(TAG, "source picked: $next")
        wheelFrom = null // choix de l'utilisateur : pas de retour automatique à l'appli
        // On quitte le lecteur d'une autre appli (DiAuto, SimpMusic…) : la voiture n'a pas de source à lui
        // retirer et certaines applis continuent malgré la perte du focus, d'où deux musiques en même temps
        current?.takeIf { otherAppShown() && it.packageName != next }?.let {
            Log.i(TAG, "pausing ${it.packageName} (leaving it)")
            it.transportControls.pause()
        }
        remember(next); autoTarget = null
        pinnedPkg = next
        main.post { reevaluate() }
        if (next == AA_MBS) {
            if (carPlay) { selectProjectionSource(); return }
            // CarPlay pas encore vu depuis le démarrage : on demande à AllGo quel téléphone est en projection
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
        // Autre appli (DiAuto…) : pas de commande de source voiture, on lance directement son lecteur
        val carSource = activateSource(next)
        // La radio ne perd pas toujours le son au profit de l'appli (DiAuto ne le demande pas) : pause
        if (!carSource && (radioShown || sourcePkg == RADIO)) pauseRadio()
        main.postDelayed({
            // Autre appli : « lecture » envoyé même si elle se croit déjà en lecture. DiAuto reste en lecture
            // côté téléphone quand la radio lui prend la source ; c'est ce « lecture » qui la lui fait reprendre
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
                        // DiAuto se présente à la voiture comme l'Android Auto d'AllGo (le launcher d'origine ne lui
                        // envoie alors plus les boutons du volant) : sans téléphone sur AllGo, c'est le lecteur DiAuto
                        val diAuto = if (pkg == "com.allgo.app.androidauto" && projectionLive != true)
                            lastCandidates.firstOrNull { handlesWheelKeys(it.packageName) }?.packageName else null
                        val src = when {
                            diAuto != null -> diAuto
                            pkg == "com.saicmotor.media" && type == 5 -> "com.android.bluetooth"
                            pkg == "com.allgo.app.androidauto" -> AA_MBS.also { carPlay = false }
                            pkg == "com.allgo.carplay.service" -> AA_MBS.also { carPlay = true }
                            pkg == SaicRadio.HMI_PKG || pkg == "com.saicmotor.service.radio" -> RADIO
                            else -> pkg
                        }
                        Log.i(TAG, "audio source: $pkg ($type) → $src")
                        // Radio reprise par le launcher d'origine juste après gauche / droite du volant : retour à
                        // l'appli. Traité ici, sans attendre le fil principal (souvent occupé à ce moment-là)
                        val back = wheelFrom?.takeIf { src == RADIO && android.os.SystemClock.uptimeMillis() - wheelAt < 2_000 }
                        if (back != null) {
                            wheelFrom = null
                            Log.i(TAG, "steering wheel key sent to the radio by the stock launcher: back to $back")
                            sourcePkg = src
                            val fwd = wheelForward
                            if (fwd != null) undoRadioSkip(fwd) { switchTo(back) } else main.post { switchTo(back) }
                            return@newProxyInstance null
                        }
                        main.post {
                            if (src == AA_MBS) {
                                // Le choix manuel reste tant qu'Android Auto ne joue pas (guidage seul)
                                if (sourcePkg != AA_MBS) { aaUnconfirmed = true; sourceBeforeAa = sourcePkg }
                            } else { aaUnconfirmed = false; sourceBeforeAa = null; pinnedPkg = null }
                            // Changement fait sur la voiture (bouton source, appli d'origine) : c'est aussi un choix
                            // de l'utilisateur, sauf s'il suit une bascule automatique ou la perte de l'ancienne source
                            val prev = sourcePkg
                            // Seulement les sources de la voiture : une appli qui prend la main un instant (annonce de
                            // guidage DiAuto…) n'est pas un choix de source
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

    /** Logo de station fictif pour les captures d'écran (comme une image diffusée en DAB+). */
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
    private val browserControllers = mutableMapOf<String, MediaController>()
    private val browsers = mutableMapOf<String, MediaBrowser>()
    private val watched = mutableSetOf<MediaSession.Token>()
    private val emptySince = mutableMapOf<String, Long>()

    /**
     * Lien périmé avec un lecteur : la radio (ou le Bluetooth) a recréé sa session, par exemple après
     * un redémarrage de son service. L'ancien contrôleur ne reçoit plus rien (ni titre ni pochette)
     * alors que la source affichée reste « Radio ». On se reconnecte à son MediaBrowserService.
     */
    private fun reconnectBrowser(pkg: String, why: String) {
        val component = BROWSERS.firstOrNull { it.packageName == pkg } ?: return
        Log.i(TAG, "reconnecting to player $pkg ($why)")
        browserControllers.remove(pkg)
        emptySince.remove(pkg)
        browsers.remove(pkg)?.let { try { it.disconnect() } catch (_: Exception) { } }
        connectBrowser(component)
    }

    /** Vérification périodique : un contrôleur de navigateur resté sans état ni titre plus de 20 s est relancé. */
    private fun checkStaleBrowsers() {
        val now = System.currentTimeMillis()
        // Radio seulement : se déconnecter du lecteur Bluetooth de la voiture pouvait arrêter la musique
        // (issue #10, coupure à la première notification du téléphone).
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
     * Choix du lecteur à afficher, sans clignoter :
     * 0) celui de la source audio active de la voiture ; 1) une session en lecture ; 2) la session déjà affichée tant qu'elle existe ;
     * 3) la radio, puis le Bluetooth, puis l'USB (même s'ils sont en pause).
     */
    private fun reevaluate() {
        val active = try { manager?.getActiveSessions(null).orEmpty() } catch (_: SecurityException) { emptyList() }
        // Plus de téléphone en projection : le lecteur CarPlay / Android Auto n'est plus proposé (#36)
        val candidates = (active + browserControllers.values).filter { !gone(it.packageName) }
        lastCandidates = candidates
        candidates.forEach { c ->
            if (watched.add(c.sessionToken)) c.registerCallback(callback, main)
        }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        pauseOthersOnStart(candidates)
        val source = effectiveSource(candidates)
        // Radio de l'ancien SDK : aucun contrôleur, on l'affiche d'après la source de la voiture, le
        // choix sur la carte, ou parce qu'elle joue alors qu'aucun autre lecteur ne joue.
        val showRadio = legacyRadio && SaicRadio.ready && when (pinnedPkg) {
            RADIO -> true
            null -> source == RADIO || (source == null && playing == null && SaicRadio.last?.playing == true)
            else -> false
        }
        if (showRadio != radioShown) Log.i(TAG, "player shown: ${if (showRadio) "radio (SAIC service)" else "media session"}")
        radioShown = showRadio
        if (showRadio) { current = null; publish(); return }
        // Un lecteur peut avoir deux contrôleurs (session active, navigateur média) : on préfère celui
        // qui a un titre, puis celui qui a un état, puis le navigateur.
        fun bestFor(pkg: String): MediaController? {
            if (gone(pkg)) return null
            val all = candidates.filter { it.packageName == pkg }
            return all.firstOrNull { it.metadata != null } ?: all.firstOrNull { it.playbackState != null }
                ?: browserControllers[pkg] ?: all.firstOrNull()
        }
        val pinned = pinnedPkg?.let { bestFor(it) }
        val fromSource = source?.let { bestFor(it) }
        val chosen = pinned
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

    /** Lecteurs d'applis qui jouaient au dernier passage. */
    private val appsPlaying = mutableSetOf<String>()

    /**
     * Une appli se met à jouer (lancée depuis son écran, ou choisie comme source) : les autres applis qui
     * jouaient encore sont mises en pause. La voiture n'a pas de source pour elles et certaines ignorent la
     * perte du focus audio, d'où DiAuto et SimpMusic qui jouaient ensemble.
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
     * Un même lecteur peut avoir deux contrôleurs (session active et navigateur média) : sur la
     * voiture, l'état Bluetooth ne suivait que sur l'un des deux, et le bouton lecture/pause du
     * widget restait figé. On prend l'état le plus récemment mis à jour.
     */
    private fun isPlaying(c: MediaController): Boolean {
        // L'état le plus récent parmi les contrôleurs de ce lecteur fait foi.
        val st = (lastCandidates.filter { it.packageName == c.packageName } + c)
            .mapNotNull { it.playbackState }
            .maxByOrNull { it.lastPositionUpdateTime }
        return st?.state == PlaybackState.STATE_PLAYING
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = reevaluate()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() {
            // Le rappel ne dit pas quel lecteur : on relance les navigateurs dont la session a disparu.
            browserControllers.toMap().filterKeys { it == RADIO }.forEach { (pkg, c) ->
                val dead = try { c.playbackState == null && c.metadata == null } catch (_: Exception) { true }
                if (dead) main.postDelayed({ reconnectBrowser(pkg, "session détruite") }, 1_000)
            }
            current = null
            watched.clear()
            reevaluate()
        }
    }

    private fun publish() {
        if (radioShown) { publishRadio(); return }
        val c = current
        if (c == null) { _now.value = NowPlaying(); return }
        val md = c.metadata
        val st = c.playbackState
        val rawTitle = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val rawArtist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        // Radio FM / AM : la voiture met la fréquence en titre (« FM 98.20 ») et le nom RDS en artiste ;
        // le nom passe en titre (en grand) et la fréquence en dessous, plus petite
        val analog = c.packageName == RADIO && ANALOG.matches(rawTitle.trim())
        val swap = analog && rawArtist.isNotBlank()
        val title = if (swap) rawArtist.trim() else rawTitle
        val artist = if (swap) rawTitle.trim() else rawArtist
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val np = NowPlaying(
            available = true,
            packageName = c.packageName,
            source = sourceLabel(c.packageName),
            title = title,
            artist = artist,
            album = album,
            art = bmp ?: _now.value.art.takeIf { lastArtKey == "$title|$artist" },
            playing = shownPlaying(c),
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = st?.position ?: 0,
            androidAuto = androidAutoAvailable(),
        )
        _now.value = np
        val artUri = md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        val key = "$title|$artist"
        // Radio DAB sans image : la radio efface la pochette à chaque changement de station et ne
        // l'envoie qu'à l'arrivée du slideshow. Comme Radio Dash, on prend l'image en cache de la station.
        if (bmp == null && artUri == null && c.packageName == "com.saicmotor.radio" && !analog && title.isNotBlank() && key != lastArtKey) {
            lastArtKey = key
            io.launch { cachedDabArt(title)?.let { a -> if (_now.value.title == title && _now.value.packageName == "com.saicmotor.radio") _now.value = _now.value.copy(art = a) } }
        } else if (bmp == null && artUri != null && (key != lastArtKey || artUri != lastArtUri || (np.art == null && !artPending))) {
            // Bluetooth : la pochette n'est pas toujours lisible tout de suite (liaison image du téléphone
            // établie après le titre, ou adresse qui change en cours de morceau) et on ne réessayait qu'au
            // morceau suivant. On réessaie tant que le morceau dure : toutes les 3 s, puis toutes les 15 s.
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
     * Image en cache de la station DAB en cours (fichiers/dab_<serviceId>.jpg du patch DAB+), si le
     * nom de la station correspond au titre affiché (évite l'image d'une station DAB précédente en FM).
     * La radio tourne en uid système comme MG4Dash : ses fichiers sont lisibles.
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
        }
    } catch (e: Exception) {
        Log.w(TAG, "album art unreadable: ${e.message}"); null
    }

    /** Radio de l'ancien SDK (SWI68…) : service SAIC au lieu d'un MediaBrowserService. */
    private val legacyRadio: Boolean get() = app.mg4dash.system.Firmware.family == app.mg4dash.system.Firmware.Family.LEGACY
    @Volatile private var radioShown = false

    private fun publishRadio() {
        val st = SaicRadio.last
        val freq = st?.let { SaicRadio.frequencyLabel(it) }.orEmpty()
        _now.value = NowPlaying(
            available = true,
            packageName = RADIO,
            source = st?.let { SaicRadio.bandLabel(it.band) } ?: "RADIO",
            title = st?.name?.takeIf { it.isNotBlank() } ?: freq,
            artist = if (st?.name.isNullOrBlank()) "" else freq,
            playing = st?.playing == true,
            androidAuto = androidAutoAvailable(),
        )
    }

    /** Commande radio hors du fil principal, puis relecture de la station. */
    /**
     * La radio a changé de station sur un bouton du volant destiné à une autre appli : commande inverse
     * par sa session (le même chemin que le launcher d'origine), puis retour à l'appli une fois la
     * radio retombée sur sa station ([then] la met en pause en quittant la radio).
     */
    private fun undoRadioSkip(forward: Boolean, then: () -> Unit) {
        val rc = browserControllers[RADIO] ?: lastCandidates.firstOrNull { it.packageName == RADIO }
        Log.i(TAG, "radio station change undone (${if (forward) "previous" else "next"})")
        if (rc != null) { if (forward) rc.transportControls.skipToPrevious() else rc.transportControls.skipToNext() }
        else io.launch { if (forward) SaicRadio.previous() else SaicRadio.next() }
        main.postDelayed(then, 200)
    }

    /** Pause de la radio : par sa session, sinon par son service. */
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
     * Lecture / pause demandée : affichée tout de suite. Le Bluetooth ne met à jour son état
     * qu'avec plusieurs secondes de retard (#36) ; l'état réel reprend la main dès qu'il concorde,
     * ou au bout de 4 s.
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

    fun playPause() {
        if (BuildConfig.MOCK) { _now.value = _now.value.copy(playing = !_now.value.playing); return }
        // Choix explicite de l'utilisateur : retenu tel quel pour la lecture au démarrage
        prefs()?.lastPlaying = !_now.value.playing; pausedSince = 0L
        playPauseRaw()
    }

    private fun playPauseRaw() {
        if (radioShown) { val playing = SaicRadio.last?.playing == true; radioCommand { if (playing) SaicRadio.pause() else SaicRadio.play() }; return }
        val c = current ?: return
        val playing = isPlaying(c)
        // La voiture est sur une autre source (ex. FM) : un « lecture » envoyé au Bluetooth ou à
        // l'USB est ignoré. On demande d'abord à la voiture de changer de source (comme le launcher
        // d'origine), puis on lance la lecture si elle n'a pas démarré toute seule.
        if (!playing && !isAndroidAuto(c.packageName) && effectiveSource().let { it != null && it != c.packageName } &&
            activateSource(c.packageName)) {
            main.postDelayed({
                current?.takeIf { it.packageName == c.packageName && it.playbackState?.state != PlaybackState.STATE_PLAYING }
                    ?.transportControls?.play()
            }, 1500)
            return
        }
        // CarPlay après la radio : l'iPhone n'a plus l'audio principal, la touche lecture restait sans
        // effet tant qu'on ne relançait pas la musique sur le téléphone (#36). On repasse d'abord sur
        // la source CarPlay comme le launcher d'origine, qui rend l'audio au téléphone.
        if (carPlay && !playing && isAndroidAuto(c.packageName) && effectiveSource().let { it != null && it != c.packageName }) {
            selectProjectionSource(); return
        }
        // Android Auto (RemoteUI) ne réagit qu'aux touches média, pas aux commandes play()/pause()
        if (isAndroidAuto(c.packageName)) { aaControl(c, if (playing) AA_PAUSE else AA_PLAY, if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY); return }
        if (playing) c.transportControls.pause() else c.transportControls.play()
        force(c.packageName, !playing)
        // Après un redémarrage, la voiture annonce le Bluetooth (ou l'USB) comme source mais ne l'a pas
        // réactivée : le « lecture » reste sans effet tant qu'on ne change pas de source (#36). Si rien
        // ne joue au bout de 2 s, on redemande la source à la voiture, puis la lecture.
        if (!playing && c.packageName != RADIO) main.postDelayed({
            val now = current?.takeIf { it.packageName == c.packageName } ?: return@postDelayed
            if (isPlaying(now) || !activateSource(c.packageName)) return@postDelayed
            Log.i(TAG, "play had no effect on ${c.packageName}: source requested again")
            main.postDelayed({ current?.takeIf { it.packageName == c.packageName && !isPlaying(it) }?.transportControls?.play() }, 1500)
        }, 2000)
    }

    fun next() {
        if (radioShown) { radioCommand { SaicRadio.next() }; return }
        val c = current ?: return
        if (isAndroidAuto(c.packageName)) aaControl(c, AA_NEXT, KeyEvent.KEYCODE_MEDIA_NEXT) else c.transportControls.skipToNext()
    }

    fun previous() {
        if (radioShown) { radioCommand { SaicRadio.previous() }; return }
        val c = current ?: return
        if (isAndroidAuto(c.packageName)) aaControl(c, AA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PREVIOUS) else c.transportControls.skipToPrevious()
    }

    // ── Android Auto : commandes de lecture par le service allgo (comme le fait RemoteUI SAIC) ──
    // IAndroidAutoMainService.sendMediaPlayControl(int) : 1 lecture, 2 pause, 3 suivant, 4 précédent
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
     * CarPlay passe par le même lecteur RemoteUI qu'Android Auto, mais pas par le service Android
     * Auto : ses commandes tactiles restaient sans effet (#36, à tester). Les touches média du
     * système, celles du volant, vont à la session active.
     */
    @Volatile private var carPlay = false

    /**
     * Service CarPlay d'allgo (ICarPlayService, relevé dans AllgoCarplay_EH32) :
     * dispatchKeyEvent(code, 2 = appui + relâchement) sur 85 / 87 / 88 / 126 / 127 (transaction 4),
     * launchApp(4 = « À l'écoute ») (transaction 13). Les touches média du système ne lui parvenaient pas.
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
     * Passage sur la source CarPlay comme le launcher d'origine (onSwitchCarPlayAASourceOnly) :
     * touche 1 au service RemoteUI d'AllGo, qui rend l'audio principal à l'iPhone et relance la
     * lecture (#36). À défaut de service, la touche lecture / pause de CarPlay comme avant.
     */
    private fun selectProjectionSource() {
        val ctx = appContext ?: return
        // Touche lecture de la projection, comme le launcher d'origine. Après la radio, CarPlay ne reprend
        // pas l'audio tout seul, ni ici ni avec le launcher d'origine (#36) : limite d'AllGo.
        Thread {
            if (!app.mg4dash.system.Projection.mediaKey(ctx, app.mg4dash.system.Projection.KEY_SELECT_SOURCE))
                systemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }.start()
        // Journal de #36 : la voiture ne rend l'audio à CarPlay que quand CarPlay s'ouvre à l'écran ;
        // on note si la touche a suffi (pas d'ouverture forcée : refusée, l'écran CarPlay clignotait)
        main.postDelayed({
            Log.i(TAG, "CarPlay audio after source key: ${if (sourcePkg == AA_MBS) "taken" else "still on $sourcePkg"}")
        }, 2000)
    }

    /** Touche média vers CarPlay ; à défaut de service, touche média du système. */
    private fun systemMediaKey(code: Int) {
        if (cpCall(4, code, 2)) return
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "CarPlay: system media key $code")
    }

    /** Toucher sur la carte média en CarPlay : CarPlay au premier plan, sur l'écran « À l'écoute ». */
    fun openCarPlayNowPlaying(): Boolean {
        // Seulement quand la carte montre CarPlay : sur la radio ou le Bluetooth, le dernier CarPlay
        // connecté était ouvert, et menait à un écran noir une fois l'iPhone débranché (#36)
        if (!carPlay || radioShown || projectionLive == false || current?.let { isAndroidAuto(it.packageName) } != true) return false
        val ctx = appContext ?: return false
        app.mg4dash.system.Apps.open(ctx, app.mg4dash.system.Apps.CARPLAY, "CarPlay")
        main.postDelayed({ cpCall(13, 4) }, 600)
        return true
    }

    /** Commande envoyée au service Android Auto ; à défaut, touche média vers sa session. */
    private fun aaControl(c: MediaController, code: Int, fallbackKey: Int) {
        if (carPlay) {
            // CarPlay ne dit pas s'il joue : lecture et pause passent par la touche bascule (85), que
            // son service accepte comme le volant (#36). On n'ouvre plus CarPlay à la bascule de source.
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

    /** Appui + relâchement d'une touche média, envoyés à la session du lecteur. */
    private fun mediaKey(c: MediaController, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        val ok = c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)) and
            c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "media key $code → ${c.packageName}: $ok")
    }

    /** Composant à ouvrir quand on touche la carte média. */
    fun ownerComponent(): ComponentName? =
        if (radioShown) ownerOf(RADIO) else current?.packageName?.let { pkg -> ownerOf(pkg) }

    /** Appli d'origine d'une source ; le Bluetooth (sans appli à lui) est lu par l'appli Média SAIC. */
    private fun ownerOf(pkg: String): ComponentName? = when (pkg) {
        AA_MBS -> if (carPlay) app.mg4dash.system.Apps.CARPLAY else app.mg4dash.system.Apps.ANDROID_AUTO
        "com.android.bluetooth" -> app.mg4dash.system.Apps.MEDIA
        RADIO -> appContext?.packageManager?.let { pm -> (pm.getLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(SaicRadio.HMI_PKG))?.component }
        else -> appContext?.packageManager?.getLaunchIntentForPackage(pkg)?.component
    }

    /**
     * Bascule la source audio de la voiture comme le launcher d'origine (ModeManager.switchSource,
     * relevé sur SWI69) : commande 0x8001 « changer de source » envoyée au service de l'appli
     * média ou radio. Types du launcher : USB = 0, Bluetooth = 2 ; la radio reprend sa bande.
     * L'appli média lance elle-même la lecture de la source choisie.
     */
    private fun activateSource(pkg: String): Boolean {
        val ctx = appContext ?: return false
        if (pkg == RADIO && legacyRadio) { radioCommand { SaicRadio.play() }; return true }
        // SWI68 (issue #19) : la commande 0x8001 ne change pas la source et la radio continuait
        // par-dessus le Bluetooth ; on met d'abord en pause la radio de l'ancien SDK.
        if (legacyRadio && SaicRadio.ready && SaicRadio.last?.playing == true) {
            Log.i(TAG, "old SDK: radio paused before switching to $pkg")
            radioCommand { SaicRadio.pause() }
        }
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
