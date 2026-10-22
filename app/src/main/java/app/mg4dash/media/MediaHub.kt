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
            Log.w(TAG, "MEDIA_CONTENT_CONTROL refusé (uid système requis) : ${e.message}")
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
        Log.i(TAG, "Android Auto joue un média : la source Android Auto est confirmée")
        aaUnconfirmed = false; sourceBeforeAa = null; pinnedPkg = null
        return sourcePkg
    }

    /** Affiche la source donnée sans ouvrir son appli. */
    fun select(pkg: String) {
        if (BuildConfig.MOCK) return
        pinnedPkg = pkg
        main.post { reevaluate() }
    }

    /** Android Auto connecté : son lecteur existe et a un état ou un titre. */
    fun androidAutoAvailable(): Boolean {
        val c = browserControllers[AA_MBS] ?: return false
        val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
        return st != PlaybackState.STATE_NONE || c.metadata != null || sourcePkg == AA_MBS
    }

    fun selectAndroidAuto() = select(AA_MBS)

    /** Lecteur commandé par MG4Dash (Bluetooth, USB, DiAuto…), hors radio et Android Auto / CarPlay. */
    fun ownPlayerShown(): Boolean = !radioShown && current?.let { !isAndroidAuto(it.packageName) } == true

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
                "com.android.bluetooth" -> try {
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.getProfileConnectionState(11) == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                } catch (_: Exception) { false }
                "com.saicmotor.media" -> try {
                    (ctx.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager).storageVolumes
                        .any { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
                } catch (_: Exception) { false }
                AA_MBS -> androidAutoAvailable()
                else -> true
            }
        }
    }

    /** Source affichée, Android Auto ramené à son lecteur. */
    fun currentSource(): String? = if (radioShown) RADIO else current?.packageName?.let { if (isAndroidAuto(it)) AA_MBS else it }

    fun cycleSource(dir: Int) {
        if (BuildConfig.MOCK) return
        val available = availableSources()
        if (available.isEmpty()) return
        val cur = currentSource()
        val i = available.indexOf(cur)
        val next = available.getOrNull(Math.floorMod((if (i < 0) 0 else i) + dir, available.size)) ?: return
        Log.i(TAG, "balayage : source $cur → $next (disponibles : $available)")
        switchTo(next)
    }

    /** Bascule la voiture sur cette source et lance la lecture (balayage ou menu des sources). */
    fun switchTo(next: String) {
        if (BuildConfig.MOCK) return
        Log.i(TAG, "source choisie : $next")
        pinnedPkg = next
        main.post { reevaluate() }
        if (next == AA_MBS) {
            browserControllers[AA_MBS]?.let { aaControl(it, AA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY) }
            return
        }
        activateSource(next)
        main.postDelayed({
            browserControllers[next]?.takeIf { !isPlaying(it) }?.transportControls?.play()
        }, 1500)
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
            Log.i(TAG, "source audio au démarrage : $last → $sourcePkg")
        } catch (e: Throwable) { Log.w(TAG, "getLastSource indisponible : ${e.message}") }
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
                        val src = when {
                            pkg == "com.saicmotor.media" && type == 5 -> "com.android.bluetooth"
                            pkg == "com.allgo.app.androidauto" -> AA_MBS.also { carPlay = false }
                            pkg == "com.allgo.carplay.service" -> AA_MBS.also { carPlay = true }
                            pkg == SaicRadio.HMI_PKG || pkg == "com.saicmotor.service.radio" -> RADIO
                            else -> pkg
                        }
                        Log.i(TAG, "source audio : $pkg ($type) → $src")
                        main.post {
                            if (src == AA_MBS) {
                                // Le choix manuel reste tant qu'Android Auto ne joue pas (guidage seul)
                                if (sourcePkg != AA_MBS) { aaUnconfirmed = true; sourceBeforeAa = sourcePkg }
                            } else { aaUnconfirmed = false; sourceBeforeAa = null; pinnedPkg = null }
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
            Log.i(TAG, "suivi de la source audio actif")
        } catch (e: Throwable) { Log.w(TAG, "registerAudioSourceChange indisponible : ${e.message}") }
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
        Log.i(TAG, "reconnexion au lecteur $pkg ($why)")
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
                Log.i(TAG, "navigateur média connecté : ${component.packageName}")
                reevaluate()
            }
            override fun onConnectionSuspended() {
                browserControllers.remove(component.packageName)
                main.postDelayed({ connectBrowser(component) }, 5_000)
            }
            override fun onConnectionFailed() {
                Log.w(TAG, "navigateur média indisponible : ${component.flattenToShortString()}")
                main.postDelayed({ connectBrowser(component) }, 30_000)
            }
        }, null)
        browsers[component.packageName] = browser
        try { browser.connect() } catch (e: Exception) { Log.w(TAG, "connexion ${component.packageName} : ${e.message}") }
    }

    /**
     * Choix du lecteur à afficher, sans clignoter :
     * 0) celui de la source audio active de la voiture ; 1) une session en lecture ; 2) la session déjà affichée tant qu'elle existe ;
     * 3) la radio, puis le Bluetooth, puis l'USB (même s'ils sont en pause).
     */
    private fun reevaluate() {
        val active = try { manager?.getActiveSessions(null).orEmpty() } catch (_: SecurityException) { emptyList() }
        val candidates = active + browserControllers.values
        lastCandidates = candidates
        candidates.forEach { c ->
            if (watched.add(c.sessionToken)) c.registerCallback(callback, main)
        }
        val playing = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val source = effectiveSource(candidates)
        // Radio de l'ancien SDK : aucun contrôleur, on l'affiche d'après la source de la voiture, le
        // choix sur la carte, ou parce qu'elle joue alors qu'aucun autre lecteur ne joue.
        val showRadio = legacyRadio && SaicRadio.ready && when (pinnedPkg) {
            RADIO -> true
            null -> source == RADIO || (source == null && playing == null && SaicRadio.last?.playing == true)
            else -> false
        }
        if (showRadio != radioShown) Log.i(TAG, "lecteur affiché : ${if (showRadio) "radio (service SAIC)" else "session média"}")
        radioShown = showRadio
        if (showRadio) { current = null; publish(); return }
        // Un lecteur peut avoir deux contrôleurs (session active, navigateur média) : on préfère celui
        // qui a un titre, puis celui qui a un état, puis le navigateur.
        fun bestFor(pkg: String): MediaController? {
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
            Log.i(TAG, "lecteur affiché : ${chosen?.packageName ?: "aucun"}")
        }
        publish()
    }

    @Volatile private var lastCandidates: List<MediaController> = emptyList()

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
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
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
        if (bmp == null && artUri == null && c.packageName == "com.saicmotor.radio" && title.isNotBlank() && key != lastArtKey) {
            lastArtKey = key
            io.launch { cachedDabArt(title)?.let { a -> if (_now.value.title == title && _now.value.packageName == "com.saicmotor.radio") _now.value = _now.value.copy(art = a) } }
        } else if (bmp == null && artUri != null && key != lastArtKey) {
            lastArtKey = key
            io.launch { loadArt(artUri)?.let { a -> if (_now.value.title == title) _now.value = _now.value.copy(art = a) } }
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
        Log.w(TAG, "image DAB en cache : ${e.message}"); null
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
        Log.w(TAG, "pochette illisible : ${e.message}"); null
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
    private fun radioCommand(action: () -> Boolean) {
        io.launch {
            val ok = action()
            kotlinx.coroutines.delay(400); SaicRadio.refresh()
            main.post { reevaluate() }
            if (!ok) Log.w(TAG, "commande radio refusée")
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
        // Android Auto (RemoteUI) ne réagit qu'aux touches média, pas aux commandes play()/pause()
        if (isAndroidAuto(c.packageName)) { aaControl(c, if (playing) AA_PAUSE else AA_PLAY, if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY); return }
        if (playing) c.transportControls.pause() else c.transportControls.play()
        force(c.packageName, !playing)
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
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) { aaService = service; Log.i(TAG, "service Android Auto connecté") }
        override fun onServiceDisconnected(name: ComponentName?) { aaService = null }
    }

    private fun bindAndroidAuto() {
        val ctx = appContext ?: return
        try {
            val ok = ctx.bindService(Intent().setComponent(ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.AndroidAutoMainService")),
                aaConnection, android.content.Context.BIND_AUTO_CREATE)
            Log.i(TAG, "liaison au service Android Auto : $ok")
        } catch (e: Exception) { Log.w(TAG, "service Android Auto : ${e.message}") }
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
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) { cpService = service; Log.i(TAG, "service CarPlay connecté") }
        override fun onServiceDisconnected(name: ComponentName?) { cpService = null }
    }

    private fun bindCarPlay() {
        val ctx = appContext ?: return
        try {
            val ok = ctx.bindService(Intent().setComponent(ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayService")),
                cpConnection, android.content.Context.BIND_AUTO_CREATE)
            Log.i(TAG, "liaison au service CarPlay : $ok")
        } catch (e: Exception) { Log.w(TAG, "service CarPlay : ${e.message}") }
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
        } catch (e: Exception) { Log.w(TAG, "CarPlay transaction $tx : ${e.message}"); false }
        finally { data.recycle(); reply.recycle() }
    }

    /** Touche média vers CarPlay ; à défaut de service, touche média du système. */
    private fun systemMediaKey(code: Int) {
        if (cpCall(4, code, 2)) return
        val am = appContext?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val t = android.os.SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "CarPlay : touche média système $code")
    }

    /** Toucher sur la carte média en CarPlay : CarPlay au premier plan, sur l'écran « À l'écoute ». */
    fun openCarPlayNowPlaying(): Boolean {
        if (!carPlay || radioShown) return false
        val ctx = appContext ?: return false
        app.mg4dash.system.Apps.open(ctx, app.mg4dash.system.Apps.CARPLAY, "CarPlay")
        main.postDelayed({ cpCall(13, 4) }, 600)
        return true
    }

    /** Commande envoyée au service Android Auto ; à défaut, touche média vers sa session. */
    private fun aaControl(c: MediaController, code: Int, fallbackKey: Int) {
        if (carPlay) {
            // Retour sur CarPlay depuis une autre source : on rouvre CarPlay, qui reprend la main
            if (code == AA_PLAY && sourcePkg != AA_MBS) {
                appContext?.let { app.mg4dash.system.Apps.open(it, app.mg4dash.system.Apps.CARPLAY, "CarPlay") }
                main.postDelayed({ systemMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 1500)
                return
            }
            systemMediaKey(fallbackKey); return
        }
        val b = aaService
        if (b == null) { bindAndroidAuto(); mediaKey(c, fallbackKey); return }
        val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
        try {
            data.writeInterfaceToken(AA_DESCRIPTOR)
            data.writeInt(code)
            b.transact(AA_TX_MEDIA_CONTROL, data, reply, 0)
            reply.readException()
            Log.i(TAG, "commande Android Auto $code → ${reply.readInt()}")
        } catch (e: Exception) {
            Log.w(TAG, "commande Android Auto $code : ${e.message}")
            mediaKey(c, fallbackKey)
        } finally { data.recycle(); reply.recycle() }
    }

    /** Appui + relâchement d'une touche média, envoyés à la session du lecteur. */
    private fun mediaKey(c: MediaController, code: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        val ok = c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)) and
            c.dispatchMediaButtonEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        Log.i(TAG, "touche média $code → ${c.packageName} : $ok")
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
            Log.i(TAG, "ancien SDK : radio en pause avant la bascule vers $pkg")
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
            Log.i(TAG, "bascule de la source audio : $cmd → ${target.flattenToShortString()}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "bascule de la source audio impossible : ${e.message}"); false
        }
    }
}
