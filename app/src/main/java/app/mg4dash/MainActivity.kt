package app.mg4dash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.Location
import app.mg4dash.map.LocationRepo
import app.mg4dash.map.MapCard
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.pages.EnergyActivity
import app.mg4dash.pages.PhoneActivity
import app.mg4dash.pages.VehicleActivity
import app.mg4dash.ui.Fullscreen
import app.mg4dash.ui.Dock
import app.mg4dash.ui.SimpleHome
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.apps.AppsActivity
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import app.mg4dash.databinding.ActivityMainBinding
import app.mg4dash.energy.ConsumptionEstimator
import app.mg4dash.media.MediaHub
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Apps
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.weather.WeatherRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var mapCard: MapCard
    private var simpleMode = false
    private var mapLayout = false
    private var themeSig = 0
    private var simple: SimpleHome? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        app.mg4dash.ui.Themes.apply(this)
        themeSig = app.mg4dash.ui.Themes.signature(this)
        // Sièges chauffants lus après la construction de l'écran (redémarrage de l'appli) : on reconstruit
        // pour afficher leurs boutons, au lieu d'attendre un retour sur l'accueil (#19).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarBridge.state.collect { if (!builtHeating && CarBridge.heatingShown(this@MainActivity)) recreate() }
            }
        }
        if (prefs.homeStyle == 1) {
            // Accueil épuré : même activité HOME, autre contenu
            simpleMode = true
            builtVertical = Dock.vertical(this)
            builtHideTitles = prefs.hideTitles
            builtHeating = CarBridge.heatingShown(this)
            val sh = SimpleHome(this)
            simple = sh
            setContentView(sh.root)
            app.mg4dash.ui.FadeOutline.applyTo(sh.root)
            sh.start()
            warmUpStockLauncherIfNeeded()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        app.mg4dash.ui.FadeOutline.applyTo(b.root)
        b.fanGauge.segments = VehicleState.FAN_MAX
        b.kersGauge.segments = 4
        b.kersGauge.onColor = color(R.color.accent_energy)
        // Toucher une case = niveau de régénération ; appui long = conduite à une pédale
        b.kersGauge.onSegmentTap = { CarBridge.setRegenLevel(it) }
        b.kersGauge.onLongTap = { CarBridge.toggleOnePedal() }
        if (prefs.homeStyle == 2) applyMapLayout()
        builtCardPower = cardPower
        if (cardPower) buildPowerEnergyCard()
        else if (!mapLayout && !simpleMode && !Dock.vertical(this)) compactEnergyCard()
        // Balayage vertical (issue #6) : carte musique = source suivante / précédente ;
        // widget du bas (style Complet) = page suivante / précédente
        app.mg4dash.ui.SwipeBox.wrap(b.mediaCard, vertical = true, horizontal = false) { MediaHub.cycleSource(it) }
        if (!mapLayout) {
            // Météo (style Complet) : balayer ou toucher les points = prévisions par heure / par jour
            weatherDots = TextView(this).apply {
                setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); letterSpacing = 0.3f
                gravity = Gravity.CENTER; isClickable = true; setOnClickListener { flipWeather(1) }
            }
            (b.weatherHours.parent as LinearLayout).addView(weatherDots, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
            app.mg4dash.ui.SwipeBox.wrap(b.weatherCard) { flipWeather(it) }
            if (prefs.hideTitles) hideFullTitles()
        }
        if (!mapLayout) app.mg4dash.ui.SwipeBox.wrap(b.secondaryCard) { dir ->
            cancelBoost()
            prefs.secondaryWidget = nextSecondary(prefs.secondaryWidget, dir)
            renderSecondary(); lifecycleScope.launch { updatePower() }
        }
        mapCard = MapCard(this, b.mapHolder)
        navBanner = app.mg4dash.ui.NavBanner(this)
        val nd = resources.displayMetrics.density
        b.mapCard.addView(navBanner, android.widget.FrameLayout.LayoutParams((440 * nd).toInt(), -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (12 * nd).toInt(); topMargin = ((if (mapLayout) 76 else 12) * nd).toInt()
        })
        buildDock()
        setupWeatherClock()
        wireClimate()
        wireMedia()
        mediaStyle = app.mg4dash.ui.MediaStyle.apply(this, b, prefs.mediaStyle)
        wireCards()
        observe()
        warmUpStockLauncherIfNeeded()
        // Carte (ou appli de la carte) en plein écran avant une reconstruction (thème jour / nuit…) :
        // elle y reste ; seuls Home et le bouton agrandir / réduire la ramènent au widget
        if (savedInstanceState?.getBoolean(KEY_MAP_FULL) == true || mapFullOnRecreate) b.mapCard.post { setMapFullscreen(true) }
        mapFullOnRecreate = false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MAP_FULL, hiddenForMap != null)
        if (isChangingConfigurations && hiddenForMap != null) mapFullOnRecreate = true
    }

    override fun onDestroy() {
        if (isChangingConfigurations && hiddenForMap != null) mapFullOnRecreate = true
        super.onDestroy()
    }

    /**
     * Style « Carte » (proposition d'un utilisateur, issue #6) : mêmes widgets, autre place.
     * Gauche : météo (petite) au-dessus de la clim (grande) ; centre : énergie (petite) au-dessus
     * de la musique (grande) ; droite : la carte sur toute la hauteur. Les rangées sont inversées
     * (petite en haut, grande en bas) pour que chaque widget garde sa taille.
     */
    private fun applyMapLayout() {
        mapLayout = true
        val left = b.climateCard.parent as LinearLayout
        val center = b.mapCard.parent as LinearLayout
        val right = b.mediaCard.parent as LinearLayout
        listOf(b.climateCard, b.energyCard, b.mapCard, b.weatherCard, b.mediaCard, b.secondaryCard)
            .forEach { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        val gap = resources.getDimensionPixelSize(R.dimen.gap)
        fun lp(weight: Float, top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).apply { topMargin = top }
        left.addView(b.weatherCard, lp(40f, 0)); left.addView(b.climateCard, lp(60f, gap))
        // Pile : énergie et widget du bas réunis, on passe d'une page à l'autre en balayant
        val box = app.mg4dash.ui.SwipeBox(this) { dir -> cancelBoost(); showStackPage(nextStack(prefs.stackPage, dir)) }
        box.addView(b.energyCard, android.widget.FrameLayout.LayoutParams(-1, -1))
        box.addView(b.secondaryCard, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Pas de points de page sur la pile : ils ne tombaient pas au même endroit d'une page à l'autre.
        b.secondaryDots.visibility = View.GONE
        center.addView(box, lp(40f, 0)); center.addView(b.mediaCard, lp(60f, gap))
        right.addView(b.mapCard, lp(100f, 0))
        // Bas des cartes Clim et Média aligné sur la barre verticale et la carte (#38) : le contour
        // s'estompe vers le bas, ce sont leurs boutons qui marquent le bord
        val dd = resources.displayMetrics.density
        // (seulement avec le contour estompé ; avec le contour complet, le cadre garde sa marge)
        if (prefs.panelFade) listOf(b.climateCard, b.mediaCard).forEach { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, (2 * dd).toInt()) }
        // Le bouton antibrouillards de MG se superpose en haut à gauche : la marge passe du titre
        // Climatisation (qui descend) au titre Météo (qui monte).
        val fogSafe = (64 * resources.displayMetrics.density).toInt()
        ((b.climateCard.getChildAt(0) as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = 0
        ((b.weatherPlace.parent as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = fogSafe
        // Météo façon widget (issue #6) : remplace le contenu de la carte Météo ; le haut gauche
        // reste libre pour le bouton antibrouillards ; balayer = prévisions par heure / par jour.
        // Carte : vitesse / cap / altitude en haut à gauche, à la place du titre « Position » (issue #6)
        (0 until b.mapCard.childCount).map { b.mapCard.getChildAt(it) }
            .filter { it is TextView && it.text?.toString() == getString(R.string.title_position) }
            .forEach { b.mapCard.removeView(it) }   // retiré : applyHomeWidget réaffiche les enfants de la carte
        ((b.mapSpeed.parent as View).layoutParams as android.widget.FrameLayout.LayoutParams).gravity = Gravity.TOP or Gravity.START
        val wc = app.mg4dash.ui.WeatherCompact(this) { flipWeather(1) }
        wc.getChildAt(0).setPadding(if (Dock.vertical(this)) 0 else fogSafe, 0, 0, 0)
        b.weatherCard.removeAllViews()
        b.weatherCard.addView(wc, LinearLayout.LayoutParams(-1, -1))
        weatherCompact = wc
        app.mg4dash.ui.SwipeBox.wrap(b.weatherCard) { flipWeather(it) }
        showStackPage(prefs.stackPage)
        // Sans titres (option) : moins chargé ; les points de la pile et les infos utiles restent
        if (prefs.hideTitles) {
            b.climHeader.visibility = View.GONE
            listOf((b.energyCard.getChildAt(0) as LinearLayout).getChildAt(0),
                b.secondaryTitle).forEach { it.visibility = View.INVISIBLE }
        }
    }




    private var lastPlugged: Boolean? = null
    private var navBanner: app.mg4dash.ui.NavBanner? = null
    private var stackDotViews: List<TextView> = emptyList()

    private var builtCardPower = false

    /** Carte énergie « puissance » (#29) : la page Puissance n'est plus dans le widget défilant. */
    private val cardPower: Boolean get() = prefs.energyCardPower && !simpleMode

    private fun nextSecondary(cur: Int, dir: Int): Int {
        var n = Math.floorMod(cur.coerceIn(0, 2) + dir, 3)
        if (cardPower && n == 1) n = Math.floorMod(n + dir, 3)
        return n
    }

    private fun nextStack(cur: Int, dir: Int): Int {
        var n = Math.floorMod(cur + dir, 4)
        if (cardPower && n == 2) n = Math.floorMod(n + dir, 4)
        return n
    }

    /** Page de la pile : 0 = énergie, 1..3 = énergie du trajet, puissance, téléphone. */
    private fun showStackPage(page: Int) {
        val p = if (cardPower && page == 2) 0 else page
        prefs.stackPage = p
        b.energyCard.visibility = if (p == 0) View.VISIBLE else View.INVISIBLE
        b.secondaryCard.visibility = if (p == 0) View.INVISIBLE else View.VISIBLE
        if (p > 0) { prefs.secondaryWidget = p - 1; renderSecondary(); lifecycleScope.launch { updatePower() } }
        val dots = (0..3).joinToString(" ") { if (it == p) "●" else "○" }
        stackDotViews.forEach { it.text = dots }
    }

    /** Titre des pages du widget du bas / de la pile. */
    private fun stackTitle(tv: TextView, base: String) { tv.text = base }

    /** Pile automatique : quand la voiture vient d'être branchée, on revient sur la batterie. */
    private fun stackAuto(s: VehicleState) {
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        if (mapLayout && prefs.stackAuto && plugged && lastPlugged == false) showStackPage(0)
        lastPlugged = plugged
    }

    /** Heure et date dans l'en-tête de la météo (style Carte, ou quand le dock affiche le Wi-Fi). */
    private var weatherClock: TextView? = null

    private var weatherCompact: app.mg4dash.ui.WeatherCompact? = null
    private var weatherDots: TextView? = null

    /**
     * Style Complet sans titres (option) : on masque les titres des cartes (la place reste, pour ne pas
     * décaler les widgets) ; les infos utiles de l'en-tête (ville, points, boutons) restent visibles.
     */
    private fun hideFullTitles() {
        b.climHeader.visibility = if (Dock.vertical(this)) View.GONE else View.INVISIBLE
        listOf((b.energyCard.getChildAt(0) as LinearLayout).getChildAt(0),
            (b.weatherPlace.parent as LinearLayout).getChildAt(0),
            b.secondaryTitle).forEach { it.visibility = View.INVISIBLE }
        (0 until b.mapCard.childCount).map { b.mapCard.getChildAt(it) }
            .filter { it is TextView && it.text?.toString() == getString(R.string.title_position) }
            .forEach { b.mapCard.removeView(it) }
    }

    private fun flipWeather(dir: Int) {
        prefs.weatherPage = Math.floorMod(prefs.weatherPage + dir, 2)
        renderWeather(WeatherRepo.shown.value, CarBridge.state.value)
    }

    private fun setupWeatherClock() {
        if (weatherCompact != null) return   // le style Carte a sa propre horloge dans la météo
        val header = b.weatherPlace.parent as LinearLayout
        weatherClock?.let { header.removeView(it) }
        weatherClock = null
        if (!mapLayout && prefs.dockSlot != 1) return
        weatherClock = TextView(this).apply {
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setPadding(0, 0, (16 * resources.displayMetrics.density).toInt(), 0)
        }
        header.addView(weatherClock, header.indexOfChild(b.weatherPlace))
        tickWeatherClock()
    }

    private val weatherClockTick = Runnable { tickWeatherClock() }

    private fun tickWeatherClock() {
        val c = weatherClock ?: return
        val now = java.util.Date()
        c.text = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) + "  ·  " +
            java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).trimEnd('.')
        c.removeCallbacks(weatherClockTick)
        c.postDelayed(weatherClockTick, 60_000L - System.currentTimeMillis() % 60_000L)
    }

    /** Appelé par le dock quand la case horloge / Wi-Fi change. */
    fun onDockSlotChanged() { if (!simpleMode) setupWeatherClock() }

    override fun onResume() {
        super.onResume()
        CarBridge.setForeground(true)
        // Style d'accueil changé dans les paramètres : on reconstruit l'écran
        if ((prefs.homeStyle == 1) != simpleMode || (prefs.homeStyle == 2) != mapLayout || Dock.vertical(this) != builtVertical || prefs.hideTitles != builtHideTitles ||
            CarBridge.heatingShown(this) != builtHeating || (!simpleMode && cardPower != builtCardPower) ||
            app.mg4dash.ui.Themes.signature(this) != themeSig) { app.mg4dash.ui.Themes.recreateSmooth(this); return }
        app.mg4dash.ui.UpdatePrompt.maybeShow(this)
        if (simpleMode) { simple?.onResume(); Fullscreen.apply(this); return }
        mapCard.onResume()
        if (prefs.homeWidget == 1) renderShortcuts()
        Fullscreen.apply(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Passage jour / nuit de la voiture (mode auto) : on redessine avec l'autre thème
        if (app.mg4dash.ui.Themes.signature(this) != themeSig) { app.mg4dash.ui.Themes.publish(this); app.mg4dash.ui.Themes.recreateSmooth(this) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Fullscreen.apply(this)
    }


    override fun onPause() {
        super.onPause()
        CarBridge.setForeground(false)
        // (le plein écran de la carte n'est plus quitté ici : la barre le fait en ouvrant une page, et
        // une reconstruction (thème) ou l'appli de la carte qui prend la main ne doivent pas le quitter)
        if (!simpleMode) mapCard.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /** HOME pressé alors qu'on est déjà l'accueil : on ne recrée rien, la carte reprend sa taille de widget. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!simpleMode) setMapFullscreen(false)
    }

    @Deprecated("Accueil : retour sans effet")
    override fun onBackPressed() { /* l'accueil ne se ferme pas */ }

    // ── Carte ────────────────────────────────────────────────────────────────

    private fun renderLocation(loc: Location?, s: VehicleState) {
        b.mapEmpty.visibility = if (effectiveWidget() == 0 && (loc == null || !mapCard.available)) View.VISIBLE else View.GONE
        if (!mapCard.available) b.mapEmpty.text = getString(R.string.map_unavailable)
        val speed = s.speedKmh?.takeIf { it >= 0f } ?: loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
        b.mapSpeed.text = speed?.let { app.mg4dash.system.Units.speedText(this, it) } ?: "— ${app.mg4dash.system.Units.speedUnit(this)}"
        b.mapInfo.text = listOfNotNull(
            loc?.takeIf { it.hasBearing() }?.let { getString(R.string.heading, LocationRepo.cardinal(it.bearing)) },
            loc?.takeIf { it.hasAltitude() }?.let { getString(R.string.altitude, Math.round(it.altitude).toInt()) },
        ).joinToString(" · ")
        loc?.let { mapCard.update(it) }
    }

    // ── Dock ─────────────────────────────────────────────────────────────────

    private fun buildDock() {
        // Barre verticale (option, tous les écrans) : le bouton antibrouillards se pose sur elle,
        // les widgets du haut à gauche n'ont plus à lui laisser de place.
        if (Dock.vertical(this)) {
            ((b.climateCard.parent as View).parent as View).let { row ->
                (row.layoutParams as LinearLayout.LayoutParams).bottomMargin = 0
            }
            ((b.climateCard.getChildAt(0) as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = 0
            ((b.weatherPlace.parent as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = 0
        }
        Dock.mount(this, b.root as LinearLayout, b.dock, Dock.Page.HOME)
        builtVertical = Dock.vertical(this)
        builtHideTitles = prefs.hideTitles
        builtHeating = CarBridge.heatingShown(this)
    }

    private var builtVertical = false
    private var builtHideTitles = false
    private var builtHeating = false


    // ── Clim ─────────────────────────────────────────────────────────────────

    private var heatSeatL: android.widget.FrameLayout? = null
    private var heatSeatR: android.widget.FrameLayout? = null
    private var heatWheel: android.widget.FrameLayout? = null

    /**
     * Sièges et volant chauffants (versions équipées) : trois boutons centrés sur la ligne du haut du
     * widget clim, qui n'a plus de titre. Siège : un toucher = cran suivant ; volant : marche / arrêt.
     */
    private fun buildHeating() {
        if (!CarBridge.heatingShown(this)) return
        val header = b.climHeader
        header.visibility = View.VISIBLE
        (header.getChildAt(0)).visibility = View.GONE   // titre
        header.gravity = Gravity.CENTER
        val d = resources.displayMetrics.density
        fun chip(icon: Int, mirror: Boolean, onClick: () -> Unit) = android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_chip); isClickable = true; isFocusable = true
            addView(ImageView(this@MainActivity).apply {
                setImageResource(icon); isDuplicateParentStateEnabled = true; if (mirror) scaleX = -1f
                imageTintList = androidx.core.content.ContextCompat.getColorStateList(this@MainActivity, R.color.chip_text)
            }, android.widget.FrameLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt(), Gravity.CENTER))
            // Niveau du siège (1 à 3) en petit, en haut à droite
            addView(TextView(this@MainActivity).apply {
                tag = "level"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(color(R.color.accent_action))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = (8 * d).toInt() })
            setOnClickListener { onClick() }
        }
        heatSeatL = chip(R.drawable.ic_seat_heat_0, false) { CarBridge.stepSeatHeat(true) }
        heatWheel = chip(R.drawable.ic_wheel_heat, false) { CarBridge.toggleWheelHeat() }
        heatSeatR = chip(R.drawable.ic_seat_heat_0, true) { CarBridge.stepSeatHeat(false) }
        listOf(heatSeatL, heatWheel, heatSeatR).forEachIndexed { i, v ->
            header.addView(v, LinearLayout.LayoutParams((96 * d).toInt(), (40 * d).toInt()).apply { if (i > 0) leftMargin = (10 * d).toInt() })
        }
    }

    private fun renderHeating(s: VehicleState) {
        fun paint(v: android.widget.FrameLayout?, level: Int?, showLevel: Boolean) {
            v ?: return
            v.isSelected = (level ?: 0) > 0
            // Siège : 0 à 3 flux de chaleur dessinés au-dessus de l'assise selon le niveau
            if (showLevel) (v.getChildAt(0) as? ImageView)?.setImageResource(when (level) { 1 -> R.drawable.ic_seat_heat_1; 2 -> R.drawable.ic_seat_heat_2; 3 -> R.drawable.ic_seat_heat_3; else -> R.drawable.ic_seat_heat_0 })
        }
        paint(heatSeatL, s.seatHeatL, true); paint(heatSeatR, s.seatHeatR, true); paint(heatWheel, s.wheelHeat, false)
    }

    private fun wireClimate() {
        buildHeating()
        b.climPower.setOnClickListener { CarBridge.togglePower() }
        // Appui long : clim maintenue, si elle est activée dans les réglages
        b.climPower.setOnLongClickListener { if (prefs.climateHoldEnabled) { app.mg4dash.climate.ClimateHoldPanel.show(this); true } else false }
        repeatOnHold(b.tempDown) { CarBridge.stepTemp(-1) }
        repeatOnHold(b.tempUp) { CarBridge.stepTemp(1) }
        // Ventilation : sous la vitesse 1, la clim s'arrête ; appui long − = minimum, appui long + = maximum
        b.fanDown.setOnClickListener {
            val s = CarBridge.state.value
            // Clim arrêtée : − ne fait rien (baisser la vitesse la rallumait, #34)
            if (s.hvacOn != true) return@setOnClickListener
            if (s.autoOn != true && (s.fanSpeed ?: 0) <= VehicleState.FAN_MIN) CarBridge.togglePower()
            else CarBridge.stepFan(-1)
        }
        b.fanUp.setOnClickListener { CarBridge.stepFan(1) }
        b.fanDown.setOnLongClickListener { if (CarBridge.state.value.hvacOn == true) CarBridge.stepFan(VehicleState.FAN_MIN - (CarBridge.state.value.fanSpeed ?: VehicleState.FAN_MIN)); true }
        b.fanUp.setOnLongClickListener {
            val cur = CarBridge.state.value.fanSpeed ?: VehicleState.FAN_MIN
            CarBridge.stepFan(VehicleState.FAN_MAX - cur); true
        }
        b.chipAuto.setOnClickListener { CarBridge.toggleAuto() }
        app.mg4dash.climate.AcCycle.attach(b.chipAc)
        b.chipEco.setOnClickListener { CarBridge.toggleEco() }
        b.chipRecirc.setOnClickListener { CarBridge.toggleRecirc() }
        // Direction de l'air : pare-brise, visage et pieds se combinent (au moins une sortie reste active)
        listOf(b.chipFace to 0, b.chipFeet to 1, b.chipScreen to 2).forEach { (chip, i) ->
            chip.setOnClickListener {
                val parts = VehicleState.flowParts(CarBridge.state.value.fanDirection)?.copyOf() ?: booleanArrayOf(false, false, false)
                parts[i] = !parts[i]
                VehicleState.flowDirection(parts)?.let { CarBridge.setFanDirection(it) }
            }
        }
        b.temp.setOnClickListener { startActivity(Intent(this, ClimateActivity::class.java)) }
        // Appui long : calibration de la température affichée
        b.temp.setOnLongClickListener { app.mg4dash.ui.TempCalibration.show(this) { renderClimate(CarBridge.state.value) }; true }
    }

    /** Appui = un cran ; appui maintenu = répétition toutes les 350 ms. */
    private fun repeatOnHold(view: View, action: () -> Unit) {
        val repeat = object : Runnable {
            override fun run() { action(); handler.postDelayed(this, 350) }
        }
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); handler.postDelayed(repeat, 500) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
            }
            true
        }
    }

    private fun renderClimate(s: VehicleState) {
        renderHeating(s)
        val on = s.hvacOn == true
        val known = s.connected && s.hvacOn != null
        b.climPower.isSelected = on
        b.climPower.setColorFilter(color(if (on) R.color.accent_action else R.color.text_sub))
        b.temp.text = if (on || s.driverTemp != null) VehicleState.tempLabel(s.driverTemp) else getString(R.string.no_data)
        b.temp.setTextColor(color(if (on) R.color.text_value else R.color.text_disabled))
        b.climOutside.text = s.outsideTemp?.let { getString(R.string.cl_outside, Math.round(it)) } ?: ""
        // En mode AUTO, la voiture rapporte une vitesse peu parlante (souvent le maximum) :
        // on affiche « AUTO » et une jauge grisée plutôt qu'un niveau trompeur.
        val auto = on && s.autoOn == true
        b.fanGauge.onColor = color(if (auto) R.color.text_disabled else R.color.accent_cold)
        b.fanGauge.value = if (on && !auto) (s.fanSpeed ?: 0) else 0
        spinFan(on && (auto || (s.fanSpeed ?: 0) > 0))
        b.fanValue.text = when {
            !on -> "0"   // comme la jauge vide (#34)
            auto -> "AUTO"
            else -> s.fanSpeed?.toString() ?: "—"
        }
        b.fanValue.setTextColor(color(if (auto) R.color.accent_cold else if (on) R.color.text_value else R.color.text_disabled))
        b.fanValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (auto || !on) 16f else 20f)
        b.chipAuto.isSelected = s.autoOn == true
        b.chipAc.isSelected = s.acOn == true
        app.mg4dash.climate.AcCycle.paint(b.chipAc)
        // Recyclage : 0 = recyclé, 1 = extérieur, 2 = auto (la voiture choisit ; état réel lu à part s'il existe)
        val autoRecirc = s.recirc == 2
        val inner = if (autoRecirc) actualRecirc else s.recirc == 0
        b.chipRecirc.isSelected = autoRecirc   // surligné seulement en mode auto ; pas de clignotement pendant la bascule
        b.chipRecircIcon.setImageResource(if (inner == true) R.drawable.ic_air_recirc else R.drawable.ic_air_outside)
        b.chipRecircAuto.visibility = if (autoRecirc) View.VISIBLE else View.GONE
        // Direction de l'air ; en mode AUTO, c'est la voiture qui la choisit
        b.chipEco.isSelected = s.ecoOn == true
        val parts = VehicleState.flowParts(s.fanDirection)
        b.chipFace.isSelected = parts?.get(0) == true
        b.chipFeet.isSelected = parts?.get(1) == true
        b.chipScreen.isSelected = parts?.get(2) == true
        listOf(b.tempDown, b.tempUp, b.fanDown, b.fanUp, b.chipAuto, b.chipAc, b.chipEco, b.chipRecirc,
            b.chipFace, b.chipFeet, b.chipScreen, b.climPower)
            .forEach { it.isEnabled = known }
        b.climateCard.alpha = if (known) 1f else 0.6f
    }


    /** Recyclage réellement actif (HVAC_RECIRC_ON), lu toutes les 5 s ; null si la voiture ne le fournit pas. */
    @Volatile private var actualRecirc: Boolean? = null

    // ── Énergie ──────────────────────────────────────────────────────────────

    /** Hauteur de la barre de batterie de la carte puissance, débranché (repère de départ compris). */
    private var gaugeHeight = 0

    private fun renderEnergy(s: VehicleState, avg: Float?) {
        val pct = s.batteryPercent
        b.battery.text = VehicleState.socLabel(s) ?: "— %"
        b.batteryGauge.currentBubble = false   // le grand % est juste au-dessus
        b.batteryGauge.soc = pct?.toFloat() ?: 0f
        b.batteryGauge.tripStart = app.mg4dash.energy.EnergyMonitor.tripStartSoc.value?.toFloat()
        b.batteryGauge.charging = VehicleState.isCharging(s.chargeStatus)
        blinkPlug(s.batteryPercent)
        b.range.text = s.rangeKm?.let { app.mg4dash.system.Units.distText(this, it.toFloat()) } ?: "— ${app.mg4dash.system.Units.distUnit(this)}"
        // Carte puissance (#29) : unités deux fois plus petites que les chiffres
        if (cardPower) { b.battery.text = halfUnit(b.battery.text); b.range.text = halfUnit(b.range.text) }
        b.consumption.text = avg?.let { app.mg4dash.system.Units.consText(this, it) } ?: "—"
        b.consAvgLabel.text = getString(if (app.mg4dash.system.Units.miles(this)) R.string.cons_avg_mi else R.string.cons_avg)
        b.driveMode.text = VehicleState.driveModeLabel(s.driveMode)
        b.kersGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        b.kersLabel.text = VehicleState.regenLabel(s.regenLevel, s.onePedal)
        // État de charge : valeurs relevées dans le launcher d'origine non encore confirmées sur
        // véhicule ; on n'affiche que « en charge » quand un temps restant est fourni.
        val remain = s.chargeRemainMin
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        b.chargeState.text = when {
            !VehicleState.isPlugged(s.chargeStatus) -> ""
            VehicleState.isCharging(s.chargeStatus) && remain != null && remain in 1..2000 ->
                getString(R.string.charging_eta, remain / 60, remain % 60)
            else -> VehicleState.chargeStatusLabel(s.chargeStatus)
        }
        b.chargeState.setTextColor(color(if (discharge) R.color.accent_heat else R.color.accent_energy))
        // Bouton comme sur le launcher d'origine : inactif câble débranché, et bascule
        // automatiquement en « V2L » quand un adaptateur de décharge est branché.
        b.chargeAction.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        // Carte « puissance » : la ligne charge / V2L n'apparaît que câble branché (#29)
        if (cardPower) {
            val plugged = VehicleState.isPlugged(s.chargeStatus)
            (b.chargeState.parent as View).visibility = if (plugged) View.VISIBLE else View.GONE
            // Câble branché : la ligne de charge prend la place, la prise sous les km s'efface
            b.rangePlug.visibility = View.GONE
            // ... et le repère de départ du trajet aussi : la barre remonte sous les chiffres, les km
            // restent alignés avec le % sans que le bouton de charge les pousse (#29)
            if (plugged) b.batteryGauge.tripStart = null
            val lp = b.batteryGauge.layoutParams as LinearLayout.LayoutParams
            if (gaugeHeight == 0) gaugeHeight = lp.height
            val want = if (plugged) (26 * resources.displayMetrics.density).toInt() else gaugeHeight
            if (lp.height != want) { lp.height = want; b.batteryGauge.layoutParams = lp }
        }
        b.chargeAction.visibility = if (CarBridge.chargeControl) View.VISIBLE else View.GONE
        b.chargeAction.setBackgroundResource(if (discharge) R.drawable.bg_chip_heat else R.drawable.bg_chip)
        b.chargeAction.setTextColor(androidx.core.content.ContextCompat.getColorStateList(this, if (discharge) R.color.chip_text_heat else R.color.chip_text))
        b.chargeAction.isSelected = if (discharge) VehicleState.isDischarging(s) else s.chargeCtrl == 1
        b.chargeAction.text = when {
            discharge -> getString(if (VehicleState.isDischarging(s)) R.string.v2l_stop else R.string.v2l_start)
            s.chargeCtrl == 1 -> getString(R.string.stop_btn)
            else -> getString(R.string.charge_btn)
        }
    }

    private fun onChargeAction() {
        val s = CarBridge.state.value
        if (!VehicleState.isPlugged(s.chargeStatus)) return
        when {
            VehicleState.isDischargeMode(s.chargeStatus) -> if (VehicleState.isDischarging(s)) CarBridge.stopV2L() else CarBridge.startV2L()
            s.chargeCtrl == 1 -> CarBridge.stopCharge()
            else -> CarBridge.startCharge()
        }
    }

    // ── Média ────────────────────────────────────────────────────────────────

    private var mediaStyle: app.mg4dash.ui.MediaStyle? = null

    private fun wireMedia() {
        b.mediaPlay.setOnClickListener { MediaHub.playPause() }
        // Appui long sur lecture / pause : réglages du son (page Son)
        b.mediaPlay.setOnLongClickListener { startActivity(android.content.Intent(this, app.mg4dash.pages.SoundActivity::class.java)); true }
        b.mediaNext.setOnClickListener { MediaHub.next() }
        b.mediaPrev.setOnClickListener { MediaHub.previous() }
        // Les puces basculent la source affichée ; l'appli ne s'ouvre qu'en touchant la pochette / les infos.
        // Source en haut à gauche (« Radio ▾ ») : un toucher ouvre la liste des sources disponibles
        listOf(b.srcRadio, b.srcMedia, b.srcAa).forEach { it.visibility = View.GONE }
        app.mg4dash.ui.SourceMenu.attach(this, b.mediaSource)
        b.mediaInfo.setOnClickListener {
            if (MediaHub.openCarPlayNowPlaying()) return@setOnClickListener
            val owner = MediaHub.ownerComponent()
            if (owner != null) Apps.open(this, owner, "Media") else Apps.open(this, Apps.RADIO, "Radio")
        }
    }

    private fun renderMedia(m: MediaHub.NowPlaying) {
        if (!m.available) {
            b.mediaSource.text = getString(R.string.title_media) + "  ▾"
            b.mediaTitle.text = getString(R.string.no_media)
            b.mediaArtist.text = getString(R.string.media_hint)
            b.mediaAlbum.text = ""
        } else {
            b.mediaSource.text = m.source.ifEmpty { getString(R.string.title_media) } + "  ▾"
            b.mediaTitle.text = m.title.ifEmpty { m.source }
            b.mediaArtist.text = m.artist
            b.mediaAlbum.text = m.album
        }
        b.mediaArt.setImageBitmap(m.art)
        mediaStyle?.update(m.art)
        b.mediaArtPlaceholder.visibility = if (m.art == null) View.VISIBLE else View.INVISIBLE
        b.mediaPlay.setImageResource(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        b.mediaProgress.visibility = if (m.durationMs > 0) View.VISIBLE else View.INVISIBLE
        if (m.durationMs > 0) b.mediaProgress.progress = (m.positionMs * 1000 / m.durationMs).toInt()
        val controls = m.available
        b.mediaPlay.isEnabled = controls; b.mediaNext.isEnabled = controls; b.mediaPrev.isEnabled = controls
    }

    // ── Météo ────────────────────────────────────────────────────────────────

    private fun renderWeather(w: WeatherRepo.Weather?, s: VehicleState) {
        weatherCompact?.let { it.show(w, s.outsideTemp, prefs.weatherCity, prefs.weatherPage); return }
        if (w == null) {
            b.weatherTemp.text = s.outsideTemp?.let { "${Math.round(it)}°" } ?: "—"
            b.weatherLabel.text = getString(R.string.weather_pending)
            b.weatherDetails.text = if (s.outsideTemp != null) getString(R.string.weather_outside_vehicle) else ""
            b.weatherPlace.text = ""
            b.weatherHours.removeAllViews()
            weatherDots?.visibility = View.GONE
            return
        }
        b.weatherPlace.text = w.place
        b.weatherIcon.setImageResource(WeatherRepo.icon(w.code))
        b.weatherIcon.setColorFilter(color(WeatherRepo.iconColor(w.code)))
        b.weatherTemp.text = "${w.tempC}°"
        b.weatherLabel.text = WeatherRepo.label(w.code)
        b.weatherDetails.text = getString(R.string.weather_details, w.feelsC, Math.round(app.mg4dash.system.Units.dist(this, w.windKmh.toFloat())), w.humidity, app.mg4dash.system.Units.speedUnit(this))
        val page = prefs.weatherPage
        fillForecast(b.weatherHours, if (page == 0) w.hours.map { Triple(it.label, it.code, "${it.tempC}°") }
            else w.days.map { Triple(it.label, it.code, "${it.minC}/${it.maxC}°") })
        weatherDots?.apply { visibility = View.VISIBLE; text = (0..1).joinToString(" ") { if (it == page) "●" else "○" } }
    }

    private fun fillForecast(row: LinearLayout, items: List<Triple<String, Int, String>>) {
        row.removeAllViews()
        items.forEach { (label, code, value) ->
            // Heure, icône et température empilées : plus gros, et la hauteur du cadre est mieux utilisée
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
            }
            cell.addView(TextView(this).apply {
                text = label; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); gravity = Gravity.CENTER
            })
            cell.addView(ImageView(this).apply {
                setImageResource(WeatherRepo.icon(code)); setColorFilter(color(WeatherRepo.iconColor(code)))
            }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { topMargin = dp(2); bottomMargin = dp(2) })
            cell.addView(TextView(this).apply {
                text = value; setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f); gravity = Gravity.CENTER
            })
            row.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ── Widget secondaire ────────────────────────────────────────────────────

    private fun wireCards() {
        b.energyCard.setOnClickListener { startActivity(Intent(this, EnergyActivity::class.java)) }
        b.chargeAction.setOnClickListener { onChargeAction() }
        b.weatherCard.setOnClickListener { WeatherRepo.refreshNow(this) }
        b.secondaryCard.setOnClickListener {
            // Style Carte : le toucher fait avancer la pile (énergie → trajet → puissance → téléphone)
            cancelBoost()
            if (mapLayout) { showStackPage(nextStack(prefs.stackPage, 1)); return@setOnClickListener }
            prefs.secondaryWidget = nextSecondary(prefs.secondaryWidget, 1)
            renderSecondary()
            lifecycleScope.launch { updatePower() }
        }
        // Boutons masqués (#15) : chaque appui relance les 6 s d'affichage
        b.mapZoomIn.setOnClickListener { extendMapButtons(); mapCard.zoom(1) }
        b.mapZoomOut.setOnClickListener { extendMapButtons(); mapCard.zoom(-1) }
        b.mapFullscreen.setOnClickListener { extendMapButtons(); setMapFullscreen(hiddenForMap == null) }
        b.mapCenter.setOnClickListener { extendMapButtons(); mapCard.recenter(LocationRepo.location.value); tintMapCenter() }
        tintMapCenter()
        mapCard.onTouch = { showMapButtons() }
        // Appui long sur la carte (ou sur son bouton en haut à droite) : une autre appli à sa place (#41)
        mapCard.onLongPress = { pickMapApp() }
        b.widgetToggle.setOnLongClickListener { pickMapApp(); true }
        applyMapButtons()
        // Carte → Consommation → Raccourcis → Carte (issue #6 : la consommation dans le widget carte)
        b.widgetToggle.setOnClickListener {
            // Page raccourcis retirable de la rotation (#38) : carte → conso → carte
            prefs.homeWidget = when (effectiveWidget()) { 0 -> 2; 2 -> if (prefs.shortcutsPage) 1 else 0; else -> 0 }
            applyHomeWidget()
        }
        applyHomeWidget()
    }

    // ── Carte ou raccourcis ──────────────────────────────────────────────────

    private var shortcutsPage = 0

    /** Ventilateur en marche : l'icône tourne lentement, quelle que soit la vitesse (#34). */
    private var fanSpin: android.animation.ObjectAnimator? = null
    private fun spinFan(on: Boolean) {
        if (on == (fanSpin != null)) return
        if (on) fanSpin = android.animation.ObjectAnimator.ofFloat(b.fanIcon, View.ROTATION, 0f, 360f).apply {
            duration = 3000; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator(); start()
        } else { fanSpin?.cancel(); fanSpin = null; b.fanIcon.rotation = 0f }
    }

    /** Icône prise sous l'autonomie : clignote doucement sous 30 %, plus vite sous 15 % (#29). */
    private var plugBlink: android.animation.ObjectAnimator? = null
    private var plugBlinkMs = 0L
    private fun blinkPlug(soc: Int?) {
        val ms = when { soc == null -> 0L; soc < 15 -> 500L; soc < 30 -> 1400L; else -> 0L }
        if (ms == plugBlinkMs) return
        plugBlinkMs = ms
        val plug: View = b.rangePlug
        plugBlink?.cancel(); plugBlink = null; plug.alpha = 1f
        if (ms > 0) plugBlink = android.animation.ObjectAnimator.ofFloat(plug, View.ALPHA, 1f, 0.15f).apply {
            duration = ms; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
        }
    }

    /** Fin de l'affichage des boutons masqués de la carte (uptime), après un toucher. */
    private var mapButtonsUntil = 0L
    private val hideMapButtons = Runnable { applyMapButtons() }

    /** Appui sur un bouton de la carte pendant qu'ils sont affichés : on repart pour 6 s. */
    private fun extendMapButtons() { if (android.os.SystemClock.uptimeMillis() < mapButtonsUntil) showMapButtons() }

    private fun showMapButtons() {
        if (!prefs.mapAutoHide) return
        mapButtonsUntil = android.os.SystemClock.uptimeMillis() + 6000
        applyMapButtons()
        b.mapButtons.removeCallbacks(hideMapButtons)
        b.mapButtons.postDelayed(hideMapButtons, 6100)
    }

    /**
     * Option (#15) : zoom +, zoom − et plein écran masqués ; le bouton cap reste, en haut sous la
     * bascule de widget. Un toucher sur la carte les montre 6 s.
     */
    private fun applyMapButtons() {
        val hide = prefs.mapAutoHide && android.os.SystemClock.uptimeMillis() >= mapButtonsUntil
        listOf(b.mapZoomIn, b.mapZoomOut, b.mapFullscreen).forEach { it.visibility = if (hide) View.GONE else View.VISIBLE }
        (b.mapButtons.layoutParams as android.widget.FrameLayout.LayoutParams).let {
            it.gravity = if (hide) Gravity.TOP or Gravity.RIGHT else Gravity.RIGHT or Gravity.CENTER_VERTICAL
            it.topMargin = if (hide) dp(14 + 56) else 0
            b.mapButtons.layoutParams = it
        }
    }

    /** Bouton « recentrer » vert quand la carte suit le cap de la voiture, couleur des boutons en nord en haut. */
    private fun tintMapCenter() {
        b.mapCenter.setColorFilter(color(if (mapCard.headingUp) R.color.accent_energy else R.color.accent_action))
    }

    private var consumptionCard: app.mg4dash.ui.ConsumptionCard? = null
    private var lastWidgetMode = -1

    /** Sans connexion data, la carte (tuiles en ligne) cède la place à la consommation. */
    private fun offline(): Boolean {
        // Même signal que le widget météo (#15) : pas de météo, ou dernière requête en échec ;
        // « internet disponible » d'Android restait vrai sans accès réel.
        val w = WeatherRepo.weather.value
        return w == null || WeatherRepo.offline ||
            !app.mg4dash.system.StatusMonitor.hasInternet(this) && System.currentTimeMillis() - w.updatedAt > 3600_000L
    }

    /** 0 = carte, 1 = raccourcis, 2 = consommation. */
    private fun effectiveWidget(): Int = prefs.homeWidget.let { if (it == 1 && !prefs.shortcutsPage) 0 else it }.let {
        if (it == 0 && prefs.mapOfflineSwitch && offline() && !mapCard.hasCachedTiles(LocationRepo.location.value)) 2 else it
    }

    /** Vues masquées par la carte en plein écran, avec leur visibilité d'avant (null : carte en widget). */
    private var hiddenForMap: List<Pair<View, Int>>? = null

    /** Appelé par la barre : chacun de ses boutons ramène la carte à sa taille de widget. */
    fun exitMapFullscreen() = setMapFullscreen(false)

    /**
     * Carte en plein écran (issue #4) : les autres widgets s'effacent, la barre (verticale ou du bas)
     * reste. HOME, un bouton de la barre ou le même bouton ramènent la carte à sa taille de widget.
     */
    private fun setMapFullscreen(on: Boolean) {
        if (simpleMode || on == (hiddenForMap != null)) return
        if (on) {
            // On remonte de la carte jusqu'à la rangée des colonnes en masquant tout ce qui l'entoure
            val row = (b.climateCard.parent as View).parent as android.view.ViewGroup
            val hidden = mutableListOf<Pair<View, Int>>()
            var child: View = b.mapCard
            while (child !== row) {
                val parent = child.parent as? android.view.ViewGroup ?: break
                for (i in 0 until parent.childCount) {
                    val v = parent.getChildAt(i)
                    if (v !== child && v.visibility != View.GONE) { hidden += v to v.visibility; v.visibility = View.GONE }
                }
                child = parent
            }
            hiddenForMap = hidden
        } else {
            hiddenForMap?.forEach { (v, vis) -> v.visibility = vis }
            hiddenForMap = null
        }
        b.mapFullscreen.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        mapAppFull?.setImageResource(if (on) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
    }

    private fun applyHomeWidget() {
        val mode = effectiveWidget()
        if (mode != 0) setMapFullscreen(false)
        val consumption = mode == 2
        val cc = consumptionCard ?: app.mg4dash.ui.ConsumptionCard(this).also {
            consumptionCard = it
            b.mapCard.addView(it, b.mapCard.indexOfChild(b.widgetToggle), android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        cc.visibility = if (consumption) View.VISIBLE else View.GONE
        if (consumption) cc.show()
        val shortcuts = mode == 1
        // La carte et ses commandes disparaissent derrière les raccourcis (et inversement).
        for (i in 0 until b.mapCard.childCount) {
            val v = b.mapCard.getChildAt(i)
            if (v === b.widgetToggle || v === navBanner || v === cc) continue
            if (v === b.shortcutsHolder) v.visibility = if (shortcuts) View.VISIBLE else View.GONE
            else v.visibility = if (shortcuts || consumption) View.INVISIBLE else View.VISIBLE
        }
        if (mode == 0) renderLocation(LocationRepo.location.value, CarBridge.state.value)
        // L'icône annonce la page suivante ; sans page de raccourcis (#38), la conso ramène à la carte
        b.widgetToggle.setImageResource(when (mode) { 0 -> R.drawable.ic_bolt; 2 -> if (prefs.shortcutsPage) R.drawable.ic_apps else R.drawable.ic_map; else -> R.drawable.ic_map })
        navBanner?.show(app.mg4dash.map.NavRepo.nav.value?.takeIf { mode == 0 })
        if (shortcuts) renderShortcuts()
        applyMapApp(mode == 0)
    }

    /** Appli intégrée au widget carte (#41, essai) et son composant. */
    private var mapAppView: android.view.ViewGroup? = null
    private var mapAppCn: android.content.ComponentName? = null

    private fun pickMapApp() {
        app.mg4dash.shortcuts.Shortcuts.pickApp(this, getString(R.string.map_app_pick), getString(R.string.map_app_osm)) { cn ->
            prefs.mapApp = cn?.flattenToString()
            applyHomeWidget()
        }
    }

    /** Si l'appli de la carte est passée devant l'accueil (sur l'écran principal), on remet l'accueil devant. */
    private fun reclaimFromApp(pkg: String) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val top = am.getRunningTasks(1).firstOrNull() ?: return
            if (top.topActivity?.packageName == pkg && top.id != taskId) {
                am.moveTaskToFront(taskId, 0)
                android.util.Log.i("MG4L.AppCarte", "$pkg passée devant l'accueil : accueil remis au premier plan")
            }
        } catch (e: Throwable) { android.util.Log.w("MG4L.AppCarte", "reprise : ${e.message}") }
    }

    /**
     * Appli dans la carte : les boutons de bascule et de plein écran s'effacent après 5 s pour ne pas
     * cacher l'appli ; un toucher n'importe où sur la carte les fait revenir.
     */
    private val fadeMapAppButtons = Runnable {
        listOfNotNull(b.widgetToggle, mapAppFull).forEach { v -> v.animate().alpha(0f).setDuration(400).withEndAction { v.visibility = View.INVISIBLE }.start() }
    }

    private fun showMapAppButtons() {
        if (mapAppView?.visibility != View.VISIBLE) return
        listOfNotNull(b.widgetToggle, mapAppFull).forEach { v -> v.animate().cancel(); v.alpha = 1f; v.visibility = View.VISIBLE }
        b.widgetToggle.removeCallbacks(fadeMapAppButtons)
        b.widgetToggle.postDelayed(fadeMapAppButtons, 5000)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && mapAppView?.visibility == View.VISIBLE) {
            val r = android.graphics.Rect(); b.mapCard.getGlobalVisibleRect(r)
            if (r.contains(ev.rawX.toInt(), ev.rawY.toInt())) showMapAppButtons()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Bouton plein écran de l'appli intégrée à la carte. */
    private var mapAppFull: android.widget.ImageButton? = null

    private fun applyMapApp(show: Boolean) {
        val cn = prefs.mapApp?.let { android.content.ComponentName.unflattenFromString(it) }
        if (cn != mapAppCn) {
            mapAppView?.let { b.mapCard.removeView(it) }
            mapAppFull?.let { b.mapCard.removeView(it) }
            mapAppView = null; mapAppCn = null; mapAppFull = null
        }
        app.mg4dash.ui.EmbeddedApp.immersive(this, cn?.packageName)
        if (cn == null) return
        if (mapAppView == null) {
            // Ramenée à la taille de la carte seulement quelques secondes après le lancement effectif
            // (au démarrage de la voiture, l'écran virtuel met parfois plus de 10 s à être prêt)
            var created: View? = null
            val v = app.mg4dash.ui.EmbeddedApp.create(this, cn) {
                created?.let { av -> av.postDelayed({ if (av.isAttachedToWindow) av.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1) }, 5000) }
            }
            if (v == null) {
                app.mg4dash.ui.Toasts.show(this, R.string.map_app_unavailable, long = true)
                prefs.mapApp = null; return
            }
            // Par-dessus la carte et ses boutons, sous le bouton de bascule
            // Créée d'abord à la largeur de l'écran (puis ramenée à la carte) : Android fixe la taille de
            // la surface de l'appli d'après celle de son écran virtuel à la création ; créée à la taille
            // de la carte, l'appli restait ensuite coupée à droite en plein écran
            val screenW = resources.displayMetrics.widthPixels
            b.mapCard.addView(v, b.mapCard.indexOfChild(b.widgetToggle), android.widget.FrameLayout.LayoutParams(screenW, -1))
            created = v
            mapAppView = v; mapAppCn = cn
            // Accueil reconstruit (thème jour / nuit…) : l'ancien écran virtuel disparaît et Android
            // remet l'appli au premier plan, en plein écran. On reprend la main : la nouvelle carte
            // peut alors la récupérer.
            for (delay in longArrayOf(800, 1800, 3000, 4500)) v.postDelayed({ reclaimFromApp(cn.packageName) }, delay)
            // Bouton plein écran, comme pour la carte OpenStreetMap, sous le bouton de bascule
            val d = resources.displayMetrics.density
            val full = android.widget.ImageButton(this).apply {
                setBackgroundResource(R.drawable.bg_button); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val pad = (14 * d).toInt(); setPadding(pad, pad, pad, pad)
                setColorFilter(color(R.color.text_value)); contentDescription = getString(R.string.cd_map_fullscreen)
                setImageResource(if (hiddenForMap != null) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
                setOnClickListener { setMapFullscreen(hiddenForMap == null) }
            }
            val size = resources.getDimensionPixelSize(R.dimen.touch_min)
            b.mapCard.addView(full, android.widget.FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = (14 * d).toInt() + size + (10 * d).toInt(); rightMargin = (14 * d).toInt()
            })
            mapAppFull = full
        }
        mapAppView?.visibility = if (show) View.VISIBLE else View.GONE
        mapAppFull?.visibility = if (show) View.VISIBLE else View.GONE
        if (show) showMapAppButtons() else { b.widgetToggle.removeCallbacks(fadeMapAppButtons); b.widgetToggle.alpha = 1f; b.widgetToggle.visibility = View.VISIBLE }
    }

    private fun renderShortcuts() {
        app.mg4dash.shortcuts.Shortcuts.render(this, b.shortcutsHolder, shortcutsPage, if (mapLayout) 3 else 2) { p ->
            shortcutsPage = p; renderShortcuts()
        }
    }

    // Widget du bas : énergie du trajet, puissance en direct, téléphone (toucher le widget pour changer)
    private var builtMode = -1
    private var powerValue: TextView? = null
    private var powerLabel: TextView? = null
    private var powerAvg: TextView? = null
    private var powerBar: app.mg4dash.ui.PowerBar? = null
    private var powerFx: app.mg4dash.ui.PowerFx? = null
    private var xpowerHits = 0

    /** Retire l'animation pleine puissance de la carte (autre widget, ou carte reconstruite). */
    private fun dropPowerFx() {
        powerFx?.let { fxHost?.overlay?.remove(it); it.onBadge = null; it.stop() }
        powerFx = null; fxHost = null
    }

    // Pleine puissance alors que le widget Puissance n'est pas affiché : on le montre le temps de
    // l'animation et du pic, puis on revient au widget d'avant (styles Complet et Carte).
    private var fxBoost = false
    private var fxPrevStack = -1
    private var fxHost: View? = null

    private fun startBoost() {
        if (fxBoost) return
        fxBoost = true
        android.util.Log.i("MG4L.Power", "pleine puissance : widget Puissance affiché le temps de l'animation")
        if (mapLayout) { fxPrevStack = prefs.stackPage; showStackPage(2) } else renderSecondary()
    }

    private fun endBoost() {
        if (!fxBoost) return
        fxBoost = false
        if (mapLayout) { if (fxPrevStack >= 0 && fxPrevStack != 2) showStackPage(fxPrevStack) } else renderSecondary()
        fxPrevStack = -1
    }

    /** Le conducteur change lui-même de widget : on ne reviendra pas en arrière. */
    private fun cancelBoost() { fxBoost = false; fxPrevStack = -1 }

    private fun renderSecondary() {
        val mode = if (fxBoost && !mapLayout) 1 else prefs.secondaryWidget.coerceIn(0, 2).let { if (cardPower && it == 1) 0 else it }
        b.secondaryDots.text = (if (cardPower) listOf(0, 2) else listOf(0, 1, 2)).joinToString(" ") { if (it == mode) "●" else "○" }
        if (mode != 1 && !cardPower) dropPowerFx()
        when (mode) {
            0 -> renderTripEnergy()
            1 -> if (builtMode != 1) { buildPower(); builtMode = 1; lifecycleScope.launch { updatePower() } }
            else -> if (builtMode != 2) renderPhoneFavs()
        }
        builtMode = mode
    }

    private fun statColumn(title: String, value: String, valueSp: Float = 28f, valueColor: Int = color(R.color.text_value)) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = value; setTextColor(valueColor); setTextSize(TypedValue.COMPLEX_UNIT_SP, valueSp)
                fontFeatureSettings = "tnum"; maxLines = 1; gravity = Gravity.CENTER
            })
            addView(TextView(this@MainActivity).apply {
                text = title; setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                letterSpacing = 0.12f; maxLines = 1; gravity = Gravity.CENTER
            })
        }

    private fun kwh(v: Float) = String.format(Locale.getDefault(), "%.1f", v)

    /** Énergie depuis le démarrage, répartie comme sur la page Énergie (mesures de MG4Dash). */
    private fun renderTripEnergy() {
        stackTitle(b.secondaryTitle, getString(R.string.sw_energy))
        val t = app.mg4dash.energy.EnergyMonitor.sinceStart.value
        val ok = t.outKwh > 0f
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.VERTICAL
        // Lisible d'un coup d'œil : une grosse barre colorée et les pourcentages ; le détail est sur la page Énergie.
        val d = resources.displayMetrics.density
        val parts = listOf(
            Triple(R.string.sw_drive, t.driveKwh, color(R.color.accent_energy)),
            Triple(R.string.sw_clim, t.climKwh, color(R.color.accent_cold)),
            Triple(R.string.sw_acc, t.accKwh, color(R.color.accent_heat)),
        )
        g.addView(app.mg4dash.ui.StackedBar(this).apply {
            this.parts = if (ok) parts.map { app.mg4dash.ui.StackedBar.Part(it.second, it.third) } else emptyList()
        }, LinearLayout.LayoutParams(-1, (34 * d).toInt()).apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parts.forEach { (label, v, c) ->
            row.addView(statColumn(getString(label), if (ok) "${Math.round(v / t.outKwh * 100)} %" else "—", 38f, c),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        g.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
    }

    private fun buildPower() {
        stackTitle(b.secondaryTitle, getString(R.string.sw_power))
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.VERTICAL
        val d = resources.displayMetrics.density
        // Même présentation que l'énergie du trajet : grosse barre, puis les chiffres en grand
        powerBar = app.mg4dash.ui.PowerBar(this).apply {
            // Échelle selon le modèle (puissance moteur : 51 kWh 170 ch / 125 kW, 64 kWh 204 ch / 150 kW,
            // 77 kWh 245 ch / 180 kW), plus une marge pour les pertes ; elle s'élargit d'elle-même si la
            // voiture tire davantage (XPower 435 ch / 320 kW), par paliers de 50 kW, et s'en souvient.
            configureFor(prefs.batteryModel, prefs.xpower)
            maxDrawKw = maxOf(maxDrawKw, prefs.raw.getFloat("power_scale_kw", 0f))
            onScale = { prefs.raw.edit().putFloat("power_scale_kw", it).apply() }
        }
        g.addView(powerBar, LinearLayout.LayoutParams(-1, (40 * d).toInt()).apply { marginStart = (8 * d).toInt(); marginEnd = (8 * d).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = statColumn(getString(R.string.sw_drawn), "—", 38f)
        powerValue = left.getChildAt(0) as TextView; powerLabel = left.getChildAt(1) as TextView
        val right = statColumn(getString(R.string.sw_trip_avg), "—", 38f)
        powerAvg = right.getChildAt(0) as TextView
        row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        g.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (12 * d).toInt() })
        // Détail : tension, courant (vert en récupération), centré dans la place restante sous les chiffres
        powerExtra = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        g.addView(powerExtra, LinearLayout.LayoutParams(-1, 0, 1f))
        // Animation pleine puissance (réglages) : par-dessus la carte, le pic remplace la ligne tension · courant
        dropPowerFx()
        powerFx = app.mg4dash.ui.PowerFx(this).also { fx ->
            fx.onBadge = { on ->
                powerExtra?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(250)?.start()
                if (!on) b.root.post { endBoost() }   // pas pendant le dessin de l'animation
            }
        }
    }

    private var powerExtra: TextView? = null

    /**
     * Carte énergie « puissance » (#29, réglage) : sans mode de conduite, KERS ni conso moyenne en
     * haut ; l'autonomie passe à droite, la barre de puissance en direct sous celle de la batterie,
     * puis kW à gauche, tension · courant au centre, kWh/100 à droite. Elle prend la place de la
     * page Puissance du widget défilant, animation pleine puissance comprise.
     */
    /** Barre en bas (style Complet) : la carte énergie est moins haute, chiffres réduits (#43). */
    private fun compactEnergyCard() {
        val d = resources.displayMetrics.density
        b.battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46f)
        b.range.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        b.consumption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
        b.consAvgLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.rangePlug.layoutParams = b.rangePlug.layoutParams.apply { height = (18 * d).toInt() }
        b.batteryGauge.layoutParams = b.batteryGauge.layoutParams.apply { height = (52 * d).toInt() }
    }


    private fun buildPowerEnergyCard() {
        val d = resources.displayMetrics.density
        (b.driveMode.parent as View).visibility = View.GONE
        (b.consumption.parent as View).visibility = View.GONE
        (b.range.parent as LinearLayout).gravity = Gravity.END
        (b.chargeState.parent as View).visibility = View.GONE
        // Plus de place verticale : chiffres un peu plus petits, barre batterie plus basse
        // % et km à la même taille, unités à moitié, épingle à la hauteur des chiffres (#29)
        b.battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        b.range.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); b.range.typeface = b.battery.typeface
        // Chiffres posés près de la barre (#29), prise pleine avec éclair, repères plus lisibles
        (b.battery.parent as LinearLayout).gravity = Gravity.BOTTOM
        // Prise pleine avec éclair, réduite pour que km + prise fassent la hauteur du % (#29)
        // Câble long et épais, sous toute la largeur des km ; même écart avec la barre que le % (#29)
        b.rangePlug.setImageResource(R.drawable.ic_range_plug_bolt_long)
        b.rangePlug.layoutParams = (b.rangePlug.layoutParams as LinearLayout.LayoutParams).apply {
            width = (110 * d).toInt(); height = (20 * d).toInt(); topMargin = (2 * d).toInt(); bottomMargin = (9 * d).toInt()
        }
        b.batteryGauge.smallSp = 16f; b.batteryGauge.tickSp = 15f
        // Pas d'icône devant les km (#29)
        b.rangePlug.visibility = View.GONE
        (b.batteryGauge.layoutParams as LinearLayout.LayoutParams).apply { height = (62 * d).toInt(); bottomMargin = (-2 * d).toInt() }
        val card = b.energyCard
        val at = card.indexOfChild(b.batteryGauge) + 1
        powerBar = app.mg4dash.ui.PowerBar(this).apply {
            configureFor(prefs.batteryModel, prefs.xpower)
            maxDrawKw = maxOf(maxDrawKw, prefs.raw.getFloat("power_scale_kw", 0f))
            onScale = { prefs.raw.edit().putFloat("power_scale_kw", it).apply() }
            labelSp = 15f
        }
        card.addView(powerBar, at, LinearLayout.LayoutParams(-1, (42 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun tv(sp: Float, colorRes: Int, g: Int) = TextView(this).apply {
            setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); gravity = g; maxLines = 1; fontFeatureSettings = "tnum"
        }
        powerValue = tv(26f, R.color.text_value, Gravity.START)
        // V · A sur une ligne, 50 % plus grands qu'avant (#29), réduits seulement si la place manque
        powerExtra = tv(22f, R.color.text_value, Gravity.CENTER).apply {
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        powerAvg = tv(26f, R.color.text_value, Gravity.END)
        powerLabel = null
        row.addView(powerValue, LinearLayout.LayoutParams(-2, -2))
        row.addView(powerExtra, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(powerAvg, LinearLayout.LayoutParams(-2, -2))
        card.addView(row, at + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        // V / A en option (#29) : sans eux, kW et conso moyenne prennent la place et grossissent
        val va = prefs.powerVa
        if (!va) { powerExtra?.visibility = View.INVISIBLE; powerValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); powerAvg?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f) }
        else row.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol && r > l) v.post { fixVaSize(r - l) } }
        powerFx = app.mg4dash.ui.PowerFx(this).also { fx ->
            fx.onBadge = { on -> if (va) powerExtra?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(250)?.start() }
        }
        // Barre en bas (style Complet) : carte moins haute, tout est resserré (#43)
        if (!mapLayout && !Dock.vertical(this)) {
            b.battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            b.range.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            b.rangePlug.layoutParams = (b.rangePlug.layoutParams as LinearLayout.LayoutParams).apply { height = (16 * d).toInt(); bottomMargin = (6 * d).toInt() }
            b.batteryGauge.layoutParams = (b.batteryGauge.layoutParams as LinearLayout.LayoutParams).apply { height = (50 * d).toInt() }
            powerBar?.layoutParams = (powerBar?.layoutParams as LinearLayout.LayoutParams).apply { height = (36 * d).toInt(); topMargin = 0 }
            (row.layoutParams as LinearLayout.LayoutParams).topMargin = (2 * d).toInt()
            b.chargeAction.layoutParams = b.chargeAction.layoutParams.apply { height = (36 * d).toInt() }
            val sz = if (va) 22f else 28f
            powerValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz); powerAvg?.setTextSize(TypedValue.COMPLEX_UNIT_SP, sz)
        }
    }

    /**
     * Ligne V / A à taille fixe (#29) : kW et conso moyenne prennent la largeur de leurs valeurs les plus
     * longues, et le V / A la taille qui fait tenir sa ligne la plus longue dans ce qui reste. Elle ne
     * rétrécit plus quand les chiffres changent.
     */
    private fun fixVaSize(rowW: Int) {
        val pv = powerValue ?: return; val pa = powerAvg ?: return; val ex = powerExtra ?: return
        val unit = android.graphics.Paint(pv.paint).apply { textSize = (powerBar?.labelSp ?: 15f) * resources.displayMetrics.scaledDensity }
        val wv = (pv.paint.measureText("188.8") + unit.measureText(" kW")).toInt() + 2
        val wa = (pa.paint.measureText("88.8") + unit.measureText(" kWh/100")).toInt() + 2
        (pv.layoutParams as LinearLayout.LayoutParams).width = wv
        (pa.layoutParams as LinearLayout.LayoutParams).width = wa
        ex.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        val room = rowW - wv - wa - (12 * resources.displayMetrics.density)
        val probe = android.graphics.Paint(ex.paint).apply { textSize = 22f * resources.displayMetrics.scaledDensity }
        val worst = probe.measureText("888 V  ·  888 min   |   888.8 A  ·  888.8 max")
        val sp = (22f * minOf(1f, room / worst)).coerceAtLeast(12f)
        ex.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        pv.requestLayout()
    }

    /** Unités kW et kWh/100 à la taille des graduations de la barre de puissance (#29). */
    private fun scaleUnit(t: CharSequence): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        val sp = powerBar?.labelSp ?: 15f
        return android.text.SpannableString(t).apply { setSpan(android.text.style.AbsoluteSizeSpan(sp.toInt(), true), i, t.length, 0) }
    }

    /** Unité (après le dernier espace) à demi-taille : « 79.7 % », « 347 km ». */
    private fun halfUnit(t: CharSequence): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        return android.text.SpannableString(t).apply { setSpan(android.text.style.RelativeSizeSpan(0.5f), i, t.length, 0) }
    }

    /** Tension la plus basse et courant le plus fort du trajet (#29), remis à zéro à chaque démarrage. */
    private var minVolts: Float? = if (BuildConfig.MOCK) 325f else null
    private var maxAmps: Float? = if (BuildConfig.MOCK) 150.6f else null
    private var minMaxTrip = -1

    /** Puissance batterie (tension × courant) : positive = tirée, négative = récupérée. */
    private suspend fun updatePower() {
        if (simpleMode) return
        var volts: Float? = null; var amps: Float? = null
        val kw = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val v = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_VOL)
            val i = app.mg4dash.car.CarProps.getFloat(app.mg4dash.car.CarProps.BMS_PACK_CRNT)
            volts = v?.takeIf { it in 100f..900f }; amps = i
            if (v != null && i != null && v in 100f..900f) v * i / 1000f else null
        }
        val plugged = VehicleState.isCharging(CarBridge.state.value.chargeStatus)
        // XPower : la voiture ne donne pas sa version ; deux mesures de suite au-delà de 350 ch la trahissent
        // Seulement en roulant et hors recharge : une mesure aberrante à l'arrêt ou en charge (anciens
        // firmwares, issue #28) ne doit pas faire passer la voiture pour une XPower
        val rolling = (CarBridge.state.value.speedKmh ?: 0f) > 5f && !VehicleState.isPlugged(CarBridge.state.value.chargeStatus)
        xpowerHits = if (rolling && !plugged && kw != null && kw >= app.mg4dash.ui.PowerBar.XPOWER_DETECT_KW && kw < 400f) xpowerHits + 1 else 0
        if (xpowerHits >= 2 && !prefs.xpower) {
            prefs.xpower = true
            android.util.Log.i("MG4L.Power", "MG4 XPower reconnue (${kw} kW) : max 320 kW, animation à 80 %")
            powerBar?.let { val keep = it.maxDrawKw; it.configureFor(prefs.batteryModel, true); it.maxDrawKw = maxOf(keep, it.maxDrawKw) }
        }
        if (builtMode != 1 && !cardPower) {
            val peak = app.mg4dash.ui.PowerBar.peakFor(prefs.batteryModel, prefs.xpower)
            if (prefs.powerFx == app.mg4dash.ui.PowerFx.NONE || plugged || kw == null || kw < peak * 0.8f) return
            startBoost()
            if (builtMode != 1) return
        }
        val regen = kw != null && kw < -0.05f && !plugged
        powerValue?.text = kw?.let { String.format(Locale.getDefault(), "%.1f kW", kotlin.math.abs(it)) } ?.let { if (cardPower) scaleUnit(it) else it } ?: "—"
        powerValue?.setTextColor(color(if (regen) R.color.accent_energy else R.color.text_value))
        powerLabel?.text = getString(when { plugged -> R.string.sw_charging; regen -> R.string.sw_recovering; else -> R.string.sw_drawn })
        powerBar?.kw = if (plugged) 0f else kw ?: 0f
        val bar = powerBar; val under = powerExtra
        if (bar != null && under != null) powerFx?.let { fx ->
            // Au hasard : nouveau tirage tant que rien ne joue, gardé pendant l'animation
            if (!fx.isActive) fx.style = prefs.powerFx.let { if (it == app.mg4dash.ui.PowerFx.RANDOM) (1..3).random() else it }
            // Sur tout l'écran : la fusée arrive du bord gauche, l'éclair et les flammes couvrent l'accueil
            val wide = fx.style != app.mg4dash.ui.PowerFx.NONE
            val host: View = if (wide) window.decorView else if (cardPower) b.energyCard else b.secondaryCard
            if (fxHost !== host) {
                fxHost?.overlay?.remove(fx)
                host.overlay.add(fx); fxHost = host
                fx.corner = if (wide) 0f else resources.getDimension(R.dimen.radius_panel)
                fx.attach(host, bar, under)
                fx.shake = b.root
            }
            fx.update(if (plugged) 0f else kw ?: 0f, bar.peakKw * 0.8f, bar.peakKw)
        }
        val avg = app.mg4dash.energy.EnergyMonitor.sinceStart.value.per100
        powerAvg?.text = avg?.let { "${app.mg4dash.system.Units.consText(this, it)} ${if (app.mg4dash.system.Units.miles(this)) "mi/kWh" else "kWh/100"}" }?.let { if (cardPower) scaleUnit(it) else it } ?: "—"
        if (cardPower) powerExtra?.let { tv ->
            val trip = app.mg4dash.energy.EnergyMonitor.tripSeq
            if (trip != minMaxTrip) { if (minMaxTrip >= 0) { minVolts = null; maxAmps = null }; minMaxTrip = trip }
            volts?.let { v -> minVolts = minOf(minVolts ?: v, v) }
            amps?.let { a -> if (a > 0f) maxAmps = maxOf(maxAmps ?: a, a) }
            // Une ligne : tension · tension mini | courant · courant maxi (mini et maxi légèrement estompés, #29)
            val dim = color(R.color.text_sub)
            fun line(now: String?, extreme: String?): CharSequence = android.text.SpannableStringBuilder().apply {
                append(now ?: "—")
                extreme?.let { val st = length; append("  ·  $it"); setSpan(android.text.style.ForegroundColorSpan(dim), st, length, 0) }
            }
            val ampsTxt = amps?.let { a ->
                val txt = String.format(Locale.getDefault(), "%.1f A", kotlin.math.abs(a))
                if (a < -0.5f) android.text.SpannableString(txt).apply {
                    setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_energy)), 0, txt.length, 0)
                } else txt
            }
            tv.text = android.text.SpannableStringBuilder()
                .append(line(volts?.let { String.format(Locale.getDefault(), "%.0f V", it) }, minVolts?.let { String.format(Locale.getDefault(), "%.0f min", it) }))
                .append(android.text.SpannableString("   |   ").apply { setSpan(android.text.style.ForegroundColorSpan(color(R.color.text_disabled)), 0, length, 0) })
                .append(ampsTxt ?: "—").append(maxAmps?.let { m ->
                    android.text.SpannableString(String.format(Locale.getDefault(), "  ·  %.1f max", m)).apply { setSpan(android.text.style.ForegroundColorSpan(dim), 0, length, 0) }
                } ?: "")
        } else powerExtra?.let { tv ->
            val parts = mutableListOf<CharSequence>()
            volts?.let { parts.add(String.format(Locale.getDefault(), "%.0f V", it)) }
            amps?.let { a ->
                val txt = String.format(Locale.getDefault(), "%.1f A", kotlin.math.abs(a))
                parts.add(if (a < -0.5f) android.text.SpannableString(txt).apply {
                    setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_energy)), 0, txt.length, 0)
                } else txt)
            }
            tv.text = android.text.TextUtils.join(if (cardPower) " · " else "   ·   ", parts)
        }
    }

    /** Trois contacts favoris, choisis dans le répertoire ; toucher = appeler, appui long = changer. */
    private fun renderPhoneFavs() {
        stackTitle(b.secondaryTitle, getString(R.string.sw_phone))
        val g = b.secondaryGrid
        g.removeAllViews()
        g.orientation = LinearLayout.HORIZONTAL
        val d = resources.displayMetrics.density
        val refresh = { builtMode = -1; renderSecondary() }
        for (i in 0 until app.mg4dash.shortcuts.PhoneFavorites.COUNT) {
            val fav = app.mg4dash.shortcuts.PhoneFavorites.get(prefs, i)
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                setBackgroundResource(R.drawable.bg_dock_item)
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (fav == null) app.mg4dash.shortcuts.PhoneFavorites.edit(this@MainActivity, i, refresh)
                    else PhoneActivity.placeCall(this@MainActivity, fav.number)
                }
                setOnLongClickListener { app.mg4dash.shortcuts.PhoneFavorites.edit(this@MainActivity, i, refresh); true }
            }
            cell.addView(ImageView(this).apply {
                setImageResource(if (fav == null) R.drawable.ic_plus else R.drawable.ic_phone)
                setColorFilter(color(if (fav == null) R.color.text_title else R.color.accent_energy))
            }, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
            if (fav != null) cell.addView(TextView(this).apply {
                text = fav.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
            g.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt(); topMargin = (6 * d).toInt()
            })
        }
    }

    // ── Observation des sources de données ───────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { CarBridge.state.collect { s -> renderClimate(s); renderEnergy(s, ConsumptionEstimator.average.value); stackAuto(s) } }
                launch { ConsumptionEstimator.average.collect { renderEnergy(CarBridge.state.value, it) } }
                launch { MediaHub.now.collect { renderMedia(it) } }
                launch { WeatherRepo.shown.collect { renderWeather(it, CarBridge.state.value) } }
                launch { LocationRepo.location.collect { renderLocation(it, CarBridge.state.value) } }
                // Guidage DiAuto : bandeau sur la carte (pas sur les raccourcis)
                launch { app.mg4dash.energy.ConsumptionCurve.curves.collect { consumptionCard?.takeIf { it.visibility == View.VISIBLE }?.show() } }
                launch { app.mg4dash.map.NavRepo.nav.collect { n -> navBanner?.show(n?.takeIf { effectiveWidget() == 0 }) } }
                launch { while (true) { updatePower(); delay(1_000) } }
                launch {
                    while (true) {
                        if (app.mg4dash.ui.Themes.signature(this@MainActivity) != themeSig) { app.mg4dash.ui.Themes.publish(this@MainActivity); app.mg4dash.ui.Themes.recreateSmooth(this@MainActivity); return@launch }
                        renderSecondary()
                        renderWeatherFallbackOnly()
                        if (effectiveWidget() != lastWidgetMode || effectiveWidget() == 2) { lastWidgetMode = effectiveWidget(); applyHomeWidget() }
                        actualRecirc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.mg4dash.car.CarProps.readAny(354419976).let { v -> when (v) { "true", "1" -> true; "false", "0" -> false; else -> null } }
                        }
                        delay(5_000)
                    }
                }
            }
        }
    }

    private fun renderWeatherFallbackOnly() {
        if (WeatherRepo.shown.value == null) renderWeather(null, CarBridge.state.value)
    }

    // ── Démarrage : laisser le launcher SAIC enregistrer son interface ───────

    /**
     * Le service du launcher SAIC (caméra, sources audio, commandes de SystemUI) ne reçoit
     * son interface de rappel que lorsque son écran d'accueil est créé. Une fois par démarrage
     * de la voiture, on l'ouvre donc brièvement puis on revient ici.
     */
    private fun warmUpStockLauncherIfNeeded() {
        if (BuildConfig.MOCK || !prefs.warmUpStock) return
        if (!HomeSwitcher.isStockEnabled(this)) return   // accueil SAIC désactivé (remplacé par MG4Dash)
        val bootCount = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 }
        val sp = prefs.raw
        if (bootCount >= 0 && sp.getInt("warmup_boot", -2) == bootCount) return
        sp.edit().putInt("warmup_boot", bootCount).apply()
        Log.i("Boot", "ouverture brève du launcher SAIC (démarrage n°$bootCount)")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(HomeSwitcher.STOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Handler distinct : celui de l'activité est vidé dans onPause, qui survient justement ici.
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 1500)
        } catch (e: Exception) {
            Log.w("Boot", "launcher SAIC non ouvert : ${e.message}")
        }
    }

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private companion object {
        const val KEY_MAP_FULL = "map_full"
        /** Plein écran de la carte à rétablir après une recréation (en plus de l'état sauvegardé). */
        var mapFullOnRecreate = false
    }
}
