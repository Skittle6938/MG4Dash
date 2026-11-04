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
import app.mg4dash.ui.put
import app.mg4dash.ui.putImage
import app.mg4dash.ui.putTint
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
        // Heated seats read after the screen was built (app restart): rebuild
        // to show their buttons, instead of waiting for a return to the home screen (#19).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CarBridge.state.collect { if (!builtHeating && CarBridge.heatingShown(this@MainActivity)) recreate() }
            }
        }
        if (prefs.homeStyle == 1) {
            // Simple home: same HOME activity, other content
            simpleMode = true
            builtVertical = Dock.vertical(this)
            builtHideTitles = prefs.hideTitles
            builtHeating = CarBridge.heatingShown(this)
            builtConnections = prefs.connectionIcons
            val sh = SimpleHome(this)
            simple = sh
            setContentView(sh.root)
            app.mg4dash.ui.FadeOutline.applyTo(sh.root)
            sh.start()
            warmUpStockLauncherIfNeeded()
            if (savedInstanceState == null) {
                app.mg4dash.sound.SoundTrigger.onBootOrAppStart(this)
            }
            return
        }
        registerReceiver(callChanged, android.content.IntentFilter("android.bluetooth.headsetclient.profile.action.AG_CALL_CHANGED"))
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        app.mg4dash.ui.FadeOutline.applyTo(b.root)
        b.fanGauge.segments = VehicleState.FAN_MAX
        b.kersGauge.segments = 4
        b.kersGauge.onColor = color(R.color.accent_energy)
        // Touch a segment = regeneration level; long press = one-pedal driving
        b.kersGauge.onSegmentTap = { CarBridge.setRegenLevel(it) }
        b.kersGauge.onLongTap = { CarBridge.toggleOnePedal() }
        if (prefs.homeStyle == 2) applyMapLayout()
        builtCardPower = cardPower
        if (cardPower) buildPowerEnergyCard()
        else if (!mapLayout && !simpleMode && !Dock.vertical(this)) compactEnergyCard()
        if (!mapLayout && !simpleMode && !Dock.vertical(this)) compactClimateCard(b.climateCard)
        // Vertical swipe (issue #6): music card = next / previous source;
        // bottom widget (Full layout) = next / previous page
        // Up / down: source; left / right: next / previous track, like the buttons
        app.mg4dash.ui.SwipeBox.wrap(b.mediaCard, vertical = true, horizontal = false) { MediaHub.cycleSource(it) }
            .onHorizontal = { if (it > 0) MediaHub.next() else MediaHub.previous() }
        if (!mapLayout) {
            useCompactWeather(0)
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
        setupConnections()
        wireClimate()
        wireMedia()
        mediaStyle = app.mg4dash.ui.MediaStyle.apply(this, b)
        wireCards()
        observe()
        warmUpStockLauncherIfNeeded()
        // Map (or map app) in full screen before a rebuild (day / night theme…):
        // it stays there; only Home and the enlarge / reduce button bring it back to the widget
        if (savedInstanceState?.getBoolean(KEY_MAP_FULL) == true || mapFullOnRecreate) b.mapCard.post { setMapFullscreen(true) }
        mapFullOnRecreate = false
        // Gesture tutorial: once after a first install, or when asked from the settings
        if (savedInstanceState == null && intent?.getBooleanExtra(app.mg4dash.ui.Tutorial.EXTRA_SHOW, false) == true) b.root.post { app.mg4dash.ui.Tutorial.show(this) }
        else if (savedInstanceState == null) app.mg4dash.ui.Tutorial.showIfPending(this)

        // Play the startup sound as soon as the app appears.
        if (savedInstanceState == null) {
            app.mg4dash.sound.SoundTrigger.onBootOrAppStart(this)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MAP_FULL, hiddenForMap != null)
        if (isChangingConfigurations && hiddenForMap != null) mapFullOnRecreate = true
    }

    private val restoreBars = Runnable { Fullscreen.restore(this) }
    private val callChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            window.decorView.removeCallbacks(restoreBars)
            window.decorView.postDelayed(restoreBars, 1500)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(callChanged) } catch (_: Exception) {}
        if (isChangingConfigurations && hiddenForMap != null) mapFullOnRecreate = true
        super.onDestroy()
    }

    /**
     * "Map" layout (a user's proposal, issue #6): same widgets, other place.
     * Left: weather (small) above climate (large); centre: energy (small) above
     * music (large); right: the map at full height. The rows are inverted
     * (small on top, large at the bottom) so each widget keeps its size.
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
        // Stack: energy and bottom widget together, swipe to go from one page to the other
        val box = app.mg4dash.ui.SwipeBox(this) { dir -> cancelBoost(); showStackPage(nextStack(prefs.stackPage, dir)) }
        box.addView(b.energyCard, android.widget.FrameLayout.LayoutParams(-1, -1))
        box.addView(b.secondaryCard, android.widget.FrameLayout.LayoutParams(-1, -1))
        // No page dots on the stack: they did not fall in the same place from one page to the next.
        b.secondaryDots.visibility = View.GONE
        left.addView(b.weatherCard, lp(40f, 0)); left.addView(b.climateCard, lp(60f, gap))
        center.addView(box, lp(40f, 0)); center.addView(b.mediaCard, lp(60f, gap))
        right.addView(b.mapCard, lp(100f, 0))
        // Bottom of the Climate and Media cards aligned with the vertical bar and the map (#38): the outline
        // fades downwards, their buttons mark the edge
        val dd = resources.displayMetrics.density
        // (only with the faded outline; with the full outline, the frame keeps its margin)
        if (prefs.panelFade) listOf(b.climateCard, b.mediaCard).forEach { it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, (2 * dd).toInt()) }
        // MG's fog light button overlaps the top left: the margin moves from the
        // Climate title (going down) to the Weather title (going up).
        val fogSafe = (64 * resources.displayMetrics.density).toInt()
        ((b.climateCard.getChildAt(0) as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = 0
        ((b.weatherPlace.parent as LinearLayout).getChildAt(0).layoutParams as LinearLayout.LayoutParams).leftMargin = fogSafe
        // Widget-style weather (issue #6): replaces the Weather card's content; the top left
        // stays free for the fog light button; swipe = hourly / daily forecast.
        // Map: speed / heading / altitude at the top left, in place of the "Position" title (issue #6)
        (0 until b.mapCard.childCount).map { b.mapCard.getChildAt(it) }
            .filter { it is TextView && it.text?.toString() == getString(R.string.title_position) }
            .forEach { b.mapCard.removeView(it) }   // removed: applyHomeWidget shows the map's children again
        ((b.mapSpeed.parent as View).layoutParams as android.widget.FrameLayout.LayoutParams).gravity = Gravity.TOP or Gravity.START
        useCompactWeather(if (Dock.vertical(this)) 0 else fogSafe)
        showStackPage(prefs.stackPage)
        // No titles (option): less busy; the stack dots and useful info stay
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

    /** "Power" energy card (#29): the Power page is no longer in the scrolling widget. */
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

    /** Stack page: 0 = energy, 1..3 = trip energy, power, phone. */
    private fun showStackPage(page: Int) {
        val p = if (cardPower && page == 2) 0 else page
        prefs.stackPage = p
        b.energyCard.visibility = if (p == 0) View.VISIBLE else View.INVISIBLE
        b.secondaryCard.visibility = if (p == 0) View.INVISIBLE else View.VISIBLE
        if (p > 0) { prefs.secondaryWidget = p - 1; renderSecondary(); lifecycleScope.launch { updatePower() } }
        val dots = (0..3).joinToString(" ") { if (it == p) "●" else "○" }
        stackDotViews.forEach { it.text = dots }
    }

    /** Title of the bottom widget / stack pages. */
    private fun stackTitle(tv: TextView, base: String) { tv.text = base }

    /** Auto stack: when the car has just been plugged in, go back to the battery. */
    private fun stackAuto(s: VehicleState) {
        val plugged = VehicleState.isPlugged(s.chargeStatus)
        if (mapLayout && prefs.stackAuto && plugged && lastPlugged == false) showStackPage(0)
        lastPlugged = plugged
    }

    private var weatherCompact: app.mg4dash.ui.WeatherCompact? = null

    /**
     * Full layout without titles (option): hide the card titles (the space stays, so as not to
     * shift the widgets); useful header info (city, dots, buttons) stays visible.
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

    /** Bottom bar (#88): the climate card is shorter, its buttons were cut at the bottom. */
    private fun compactClimateCard(v: View) {
        val d = resources.displayMetrics.density
        (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
            if (lp.height == (68 * d).toInt()) lp.height = (60 * d).toInt()
            if (lp.height == (48 * d).toInt()) lp.height = (42 * d).toInt()
        }
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) compactClimateCard(v.getChildAt(i))
    }

    /** Widget-style weather (issue #6), Full and Map layouts: swipe = hourly / daily forecast. */
    private fun useCompactWeather(leftInset: Int) {
        val wc = app.mg4dash.ui.WeatherCompact(this)
        wc.setLeftInset(leftInset)
        wc.tight = !Dock.vertical(this)
        b.weatherCard.removeAllViews()
        b.weatherCard.addView(wc, LinearLayout.LayoutParams(-1, -1))
        weatherCompact = wc
        app.mg4dash.ui.SwipeBox.wrap(b.weatherCard) { flipWeather(it) }
    }

    private fun flipWeather(dir: Int) {
        prefs.weatherPage = Math.floorMod(prefs.weatherPage + dir, 2)
        renderWeather(WeatherRepo.shown.value, CarBridge.state.value)
    }

    private var builtConnections = false

    /** Connection icons (#86): at the top right of the weather, to the right of the city. */
    private fun setupConnections() {
        builtConnections = prefs.connectionIcons
        if (!builtConnections) return
        val row = app.mg4dash.ui.ConnectionRow(this)
        weatherCompact?.addConnections(row)
    }

    /** Called by the dock when the clock / Wi-Fi slot changes. */
    override fun onResume() {
        super.onResume()
        CarBridge.setForeground(true)
        // MG4Dash had not started and the rescue app stepped in: say so once
        app.mg4dash.system.Rescue.notice?.let { app.mg4dash.system.Rescue.notice = null; app.mg4dash.ui.Toasts.show(this, it, long = true) }
        // Home layout changed in the settings: rebuild the screen
        if ((prefs.homeStyle == 1) != simpleMode || (prefs.homeStyle == 2) != mapLayout || Dock.vertical(this) != builtVertical || prefs.hideTitles != builtHideTitles ||
            CarBridge.heatingShown(this) != builtHeating || (!simpleMode && cardPower != builtCardPower) ||
            prefs.connectionIcons != builtConnections ||
            app.mg4dash.ui.Themes.signature(this) != themeSig) { app.mg4dash.ui.Themes.recreateSmooth(this); return }
        // Map app uninstalled in the meantime (#54): back to the OpenStreetMap map
        mapAppCn?.let { if (!mapAppInstalled(it)) applyHomeWidget() }
        app.mg4dash.ui.UpdatePrompt.maybeShow(this)
        if (simpleMode) { simple?.onResume(); Fullscreen.apply(this); return }
        mapCard.onResume()
        if (prefs.homeWidget == 1) renderShortcuts()
        Fullscreen.apply(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Car day / night change (auto mode): redraw with the other theme
        if (app.mg4dash.ui.Themes.signature(this) != themeSig) { app.mg4dash.ui.Themes.publish(this); app.mg4dash.ui.Themes.recreateSmooth(this) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Fullscreen.apply(this)
    }


    override fun onPause() {
        super.onPause()
        CarBridge.setForeground(false)
        // (the map's full screen is no longer left here: the bar does it when opening a page, and
        // a rebuild (theme) or the map app taking over must not leave it)
        if (!simpleMode) mapCard.onPause()
        handler.removeCallbacksAndMessages(null)
    }

    /** HOME pressed while already home: nothing is recreated, the map goes back to its widget size. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (!simpleMode) setMapFullscreen(false)
        if (intent?.getBooleanExtra(app.mg4dash.ui.Tutorial.EXTRA_SHOW, false) == true) b.root.post { app.mg4dash.ui.Tutorial.show(this) }
    }

    @Deprecated("Accueil : retour sans effet")
    override fun onBackPressed() { /* l'accueil ne se ferme pas */ }

    // ── Carte ────────────────────────────────────────────────────────────────

    private fun renderLocation(loc: Location?, s: VehicleState) {
        b.mapEmpty.visibility = if (effectiveWidget() == 0 && (loc == null || !mapCard.available)) View.VISIBLE else View.GONE
        if (!mapCard.available) b.mapEmpty.text = getString(R.string.map_unavailable)
        val speed = s.speedKmh?.takeIf { it >= 0f } ?: loc?.takeIf { it.hasSpeed() }?.speed?.times(3.6f)
        b.mapSpeed.put(speed?.let { app.mg4dash.system.Units.speedText(this, it) } ?: "— ${app.mg4dash.system.Units.speedUnit(this)}")
        b.mapInfo.put(listOfNotNull(
            loc?.takeIf { it.hasBearing() }?.let { getString(R.string.heading, LocationRepo.cardinal(it.bearing)) },
            loc?.takeIf { it.hasAltitude() }?.let { getString(R.string.altitude, Math.round(it.altitude).toInt()) },
        ).joinToString(" · "))
        loc?.let { mapCard.update(it) }
    }

    // ── Dock ─────────────────────────────────────────────────────────────────

    private fun buildDock() {
        // Vertical bar (option, all screens): the fog light button sits on it,
        // the top left widgets no longer need to leave room for it.
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
     * Heated seats and steering wheel (equipped versions): three buttons centred on the top line of the
     * climate widget, which no longer has a title. Seat: a touch = next level; wheel: on / off.
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
            // Seat level (1 to 3), small, at the top right
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
            // Seat: 0 to 3 heat waves drawn above the seat according to the level
            if (showLevel) (v.getChildAt(0) as? ImageView)?.putImage(when (level) { 1 -> R.drawable.ic_seat_heat_1; 2 -> R.drawable.ic_seat_heat_2; 3 -> R.drawable.ic_seat_heat_3; else -> R.drawable.ic_seat_heat_0 })
        }
        paint(heatSeatL, s.seatHeatL, true); paint(heatSeatR, s.seatHeatR, true); paint(heatWheel, s.wheelHeat, false)
    }

    private fun wireClimate() {
        buildHeating()
        b.climPower.setOnClickListener { CarBridge.togglePower() }
        // Long press: keep climate on, if enabled in the settings
        b.climPower.setOnLongClickListener { if (prefs.climateHoldEnabled) { app.mg4dash.climate.ClimateHoldPanel.show(this); true } else false }
        repeatOnHold(b.tempDown) { CarBridge.stepTemp(-1) }
        // Fan centred between the temperature − / + buttons and the air flow (#34): the
        // temperature row is taller than its buttons (large number + outside); the extra below the buttons
        // is added above the air flow for equal gaps from button to button
        (b.tempDown.parent as View).addOnLayoutChangeListener { row, _, top, _, bottom, _, _, _, _ ->
            val extra = ((bottom - top - b.tempDown.height) / 2).coerceAtLeast(0)
            val lp = b.climDirRow.layoutParams as android.widget.LinearLayout.LayoutParams
            if (lp.topMargin != extra) row.post { lp.topMargin = extra; b.climDirRow.layoutParams = lp }
        }
        repeatOnHold(b.tempUp) { CarBridge.stepTemp(1) }
        // Fan: below speed 1 the climate stops; long press − = minimum, long press + = maximum
        b.fanDown.setOnClickListener {
            val s = CarBridge.state.value
            // Climate off: − does nothing (lowering the speed turned it back on, #34)
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
        app.mg4dash.climate.AutoAirVolume.attach(this, b.chipAuto)
        app.mg4dash.climate.AcCycle.attach(b.chipAc)
        b.chipEco.setOnClickListener { CarBridge.toggleEco() }
        b.chipRecirc.setOnClickListener { CarBridge.toggleRecirc() }
        // Air direction: windscreen, face and feet combine (at least one outlet stays active)
        listOf(b.chipFace to 0, b.chipFeet to 1, b.chipScreen to 2).forEach { (chip, i) ->
            chip.setOnClickListener {
                val parts = VehicleState.flowParts(CarBridge.state.value.fanDirection)?.copyOf() ?: booleanArrayOf(false, false, false)
                parts[i] = !parts[i]
                VehicleState.flowDirection(parts)?.let { CarBridge.setFanDirection(it) }
            }
        }
        b.temp.setOnClickListener { startActivity(Intent(this, ClimateActivity::class.java)) }
        // Long press: calibrate the displayed temperature
        b.temp.setOnLongClickListener { app.mg4dash.ui.TempCalibration.show(this) { renderClimate(CarBridge.state.value) }; true }
    }

    /** Press = one step; hold = repeat every 350 ms. */
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
        b.climPower.putTint(color(if (on) R.color.accent_action else R.color.text_sub))
        b.temp.put(if (on || s.driverTemp != null) VehicleState.tempLabel(s.driverTemp) else getString(R.string.no_data))
        b.temp.setTextColor(color(if (on) R.color.text_value else R.color.text_disabled))
        b.climOutside.put(s.outsideTemp?.let { getString(R.string.cl_outside, Math.round(it)) } ?: "")
        // In AUTO mode the car reports a meaningless speed (often the maximum):
        // show "AUTO" and a greyed gauge rather than a misleading level.
        val auto = on && s.autoOn == true
        b.fanGauge.onColor = color(if (auto) R.color.text_disabled else R.color.accent_cold)
        b.fanGauge.value = if (on && !auto) (s.fanSpeed ?: 0) else 0
        spinFan(on && (auto || (s.fanSpeed ?: 0) > 0))
        // In AUTO the gauge gives way to "AUTO · flow" centred between the fan and the + (#34): "AUTO"
        // next to an empty gauge touched the + on the car
        b.fanGauge.visibility = if (auto) View.GONE else View.VISIBLE
        // AUTO: "fan AUTO · flow" grouped and centred between − and + (#34, idea from mquiambao00)
        b.fanSpaceL.visibility = if (auto) View.VISIBLE else View.GONE
        b.fanSpaceR.visibility = if (auto) View.VISIBLE else View.GONE
        (b.fanIcon.layoutParams as android.widget.LinearLayout.LayoutParams).let { lp ->
            val m = ((if (auto) 0 else 20) * resources.displayMetrics.density).toInt()
            if (lp.leftMargin != m) { lp.leftMargin = m; b.fanIcon.layoutParams = lp }
        }
        (b.fanValue.layoutParams as android.widget.LinearLayout.LayoutParams).let { lp ->
            val w = if (auto) android.widget.LinearLayout.LayoutParams.WRAP_CONTENT else (48 * resources.displayMetrics.density).toInt()
            val ml = ((if (auto) 10 else 0) * resources.displayMetrics.density).toInt()
            if (lp.width != w || lp.leftMargin != ml) { lp.width = w; lp.leftMargin = ml; b.fanValue.layoutParams = lp }
        }
        b.fanValue.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        // Automatic text size unusable with a free width: off in AUTO
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeWithDefaults(b.fanValue,
            if (auto) androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_NONE else androidx.core.widget.TextViewCompat.AUTO_SIZE_TEXT_TYPE_UNIFORM)
        b.fanValue.put(when {
            !on -> "0"   // like the empty gauge (#34)
            auto -> listOfNotNull("AUTO", s.autoAirVolume?.let { getString(listOf(R.string.cl_level_high, R.string.cl_level_medium, R.string.cl_level_low)[it.coerceIn(0, 2)]) })
                .joinToString("  ·  ")
            else -> s.fanSpeed?.toString() ?: "—"
        })
        b.fanValue.setTextColor(color(if (auto) R.color.accent_cold else if (on) R.color.text_value else R.color.text_disabled))
        b.fanValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (auto) 20f else if (!on) 16f else 20f)
        b.chipAuto.isSelected = s.autoOn == true
        // AUTO flow shows on the fan line: no more bars in the button (#34)
        if (b.chipAuto.text.toString() != "AUTO") b.chipAuto.text = "AUTO"
        b.chipAc.isSelected = s.acOn == true
        app.mg4dash.climate.AcCycle.paint(b.chipAc)
        // Recirculation: 0 = recirculated, 1 = outside, 2 = auto (the car chooses; real state read separately if it exists)
        val autoRecirc = s.recirc == 2
        val inner = if (autoRecirc) actualRecirc else s.recirc == 0
        b.chipRecirc.isSelected = autoRecirc   // highlighted only in auto mode; no blinking during the change
        b.chipRecircIcon.putImage(if (inner == true) R.drawable.ic_air_recirc else R.drawable.ic_air_outside)
        b.chipRecircAuto.visibility = if (autoRecirc) View.VISIBLE else View.GONE
        // Air direction; in AUTO mode the car chooses it
        b.chipEco.isSelected = s.ecoOn == true
        val parts = VehicleState.flowParts(s.fanDirection)
        // Climate off: the car keeps the last direction, but nothing blows (#34)
        b.chipFace.isSelected = on && parts?.get(0) == true
        b.chipFeet.isSelected = on && parts?.get(1) == true
        b.chipScreen.isSelected = on && parts?.get(2) == true
        listOf(b.tempDown, b.tempUp, b.fanDown, b.fanUp, b.chipAuto, b.chipAc, b.chipEco, b.chipRecirc,
            b.chipFace, b.chipFeet, b.chipScreen, b.climPower)
            .forEach { it.isEnabled = known }
        b.climateCard.alpha = if (known) 1f else 0.6f
    }


    /** Recirculation really active (HVAC_RECIRC_ON), read every 5 s; null if the car does not provide it. */
    @Volatile private var actualRecirc: Boolean? = null

    // ── Energy ───────────────────────────────────────────────────────────────

    /** Height of the power card's battery bar, unplugged (start marker included). */
    private var gaugeHeight = 0

    private fun renderEnergy(s: VehicleState, avg: Float?) {
        val pct = s.batteryPercent
        val batteryText = VehicleState.socLabel(s) ?: "— %"
        b.batteryGauge.currentBubble = false   // the large % is just above
        b.batteryGauge.soc = pct?.toFloat() ?: 0f
        b.batteryGauge.tripStart = app.mg4dash.energy.EnergyMonitor.tripStartSoc.value?.toFloat()
        b.batteryGauge.charging = VehicleState.isCharging(s.chargeStatus)
        blinkPlug(s.batteryPercent)
        val rangeText = s.rangeKm?.let { app.mg4dash.system.Units.distText(this, it.toFloat()) } ?: "— ${app.mg4dash.system.Units.distUnit(this)}"
        // Power card (#29): units half the size of the numbers
        b.battery.put(if (cardPower) halfUnit(batteryText) else batteryText)
        b.range.put(if (cardPower) halfUnit(rangeText) else rangeText)
        b.consumption.put(avg?.let { app.mg4dash.system.Units.consText(this, it) } ?: "—")
        b.consAvgLabel.put(getString(if (app.mg4dash.system.Units.miles(this)) R.string.cons_avg_mi else R.string.cons_avg))
        b.driveMode.put(VehicleState.driveModeLabel(s.driveMode))
        b.kersGauge.value = if (s.onePedal == 1) 4 else VehicleState.regenGauge(s.regenLevel)
        b.kersLabel.put(VehicleState.regenLabel(s.regenLevel, s.onePedal))
        // Charge state: values found in the stock launcher, not yet confirmed on the
        // vehicle; only "charging" is shown when a remaining time is provided.
        val remain = s.chargeRemainMin
        val discharge = VehicleState.isDischargeMode(s.chargeStatus)
        b.chargeState.put(when {
            !VehicleState.isPlugged(s.chargeStatus) -> ""
            VehicleState.isCharging(s.chargeStatus) && remain != null && remain in 1..2000 ->
                getString(R.string.charging_eta, remain / 60, remain % 60)
            else -> VehicleState.chargeStatusLabel(s.chargeStatus)
        })
        b.chargeState.setTextColor(color(if (discharge) R.color.accent_heat else R.color.accent_energy))
        // Button as on the stock launcher: inactive with the cable unplugged, and switches
        // automatically to "V2L" when a discharge adapter is plugged in.
        b.chargeAction.isEnabled = VehicleState.isPlugged(s.chargeStatus)
        // "Power" card: the charge / V2L line only appears with the cable plugged in (#29)
        if (cardPower) {
            val plugged = VehicleState.isPlugged(s.chargeStatus)
            (b.chargeState.parent as View).visibility = if (plugged) View.VISIBLE else View.GONE
            // Cable plugged in: the charge line takes the place, the plug under the km disappears
            b.rangePlug.visibility = View.GONE
            // ... and so does the trip start marker: the bar moves up under the numbers, the km
            // stay aligned with the % without the charge button pushing them (#29)
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
        b.chargeAction.put(when {
            discharge -> getString(if (VehicleState.isDischarging(s)) R.string.v2l_stop else R.string.v2l_start)
            s.chargeCtrl == 1 -> getString(R.string.stop_btn)
            else -> getString(R.string.charge_btn)
        })
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

    // ── Media ────────────────────────────────────────────────────────────────

    private var mediaStyle: app.mg4dash.ui.MediaStyle? = null
    private var shownArt: android.graphics.Bitmap? = null

    private fun wireMedia() {
        b.mediaPlay.setOnClickListener { MediaHub.playPause() }
        // Long press on play / pause: sound settings (Sound page)
        b.mediaPlay.setOnLongClickListener { startActivity(android.content.Intent(this, app.mg4dash.pages.SoundActivity::class.java)); true }
        b.mediaNext.setOnClickListener { MediaHub.next() }
        b.mediaPrev.setOnClickListener { MediaHub.previous() }
        // The chips switch the displayed source; the app only opens by touching the cover / info.
        // Source at the top left ("Radio ▾"): a touch opens the list of available sources
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
            b.mediaSource.put(getString(R.string.title_media) + "  ▾")
            b.mediaTitle.put(getString(R.string.no_media))
            b.mediaArtist.put(getString(R.string.media_hint))
            b.mediaAlbum.put("")
        } else {
            b.mediaSource.put(m.source.ifEmpty { getString(R.string.title_media) } + "  ▾")
            b.mediaTitle.put(m.title.ifEmpty { m.source })
            b.mediaArtist.put(m.artist)
            b.mediaAlbum.put(m.album)
        }
        if (shownArt !== m.art) { shownArt = m.art; b.mediaArt.setImageBitmap(m.art) }
        mediaStyle?.update(m.art, m.packageName == "com.saicmotor.radio")
        mediaStyle?.radioLines(m.analog, m.radioText)
        b.mediaArtPlaceholder.visibility = if (m.art == null) View.VISIBLE else View.INVISIBLE
        b.mediaPlay.putImage(if (m.playing) R.drawable.ic_pause else R.drawable.ic_play)
        // Without a picture the texts are centred: the missing bar must not keep its place
        b.mediaProgress.visibility = if (m.durationMs > 0) View.VISIBLE else if (m.art == null) View.GONE else View.INVISIBLE
        if (m.durationMs > 0) b.mediaProgress.progress = (m.positionMs * 1000 / m.durationMs).toInt()
        val controls = m.available
        b.mediaPlay.isEnabled = controls; b.mediaNext.isEnabled = controls; b.mediaPrev.isEnabled = controls
    }

    // ── Weather ──────────────────────────────────────────────────────────────

    private fun renderWeather(w: WeatherRepo.Weather?, s: VehicleState) {
        weatherCompact?.show(w, s.outsideTemp, prefs.weatherCity, prefs.weatherPage)
    }

    // ── Widget secondaire ────────────────────────────────────────────────────

    private fun wireCards() {
        b.energyCard.setOnClickListener { startActivity(Intent(this, EnergyActivity::class.java)) }
        b.chargeAction.setOnClickListener { onChargeAction() }
        b.weatherCard.setOnClickListener { WeatherRepo.refreshNow(this) }
        b.secondaryCard.setOnClickListener {
            // Map layout: a touch moves the stack forward (energy → trip → power → phone)
            cancelBoost()
            if (mapLayout) { showStackPage(nextStack(prefs.stackPage, 1)); return@setOnClickListener }
            prefs.secondaryWidget = nextSecondary(prefs.secondaryWidget, 1)
            renderSecondary()
            lifecycleScope.launch { updatePower() }
        }
        // Hidden buttons (#15): each press restarts the 6 s display
        b.mapZoomIn.setOnClickListener { extendMapButtons(); mapCard.zoom(1) }
        b.mapZoomOut.setOnClickListener { extendMapButtons(); mapCard.zoom(-1) }
        b.mapFullscreen.setOnClickListener { extendMapButtons(); setMapFullscreen(hiddenForMap == null) }
        b.mapCenter.setOnClickListener { extendMapButtons(); mapCard.recenter(LocationRepo.location.value); tintMapCenter() }
        tintMapCenter()
        mapCard.onTouch = { showMapButtons() }
        // Long press on the map (or its top right button): another app in its place (#41)
        mapCard.onLongPress = { pickMapApp() }
        b.widgetToggle.setOnLongClickListener { pickMapApp(); true }
        applyMapButtons()
        // Map → Consumption → Shortcuts → Map (issue #6: consumption in the map widget)
        b.widgetToggle.setOnClickListener {
            // Shortcuts page removable from the rotation (#38): map → consumption → map
            prefs.homeWidget = when (effectiveWidget()) { 0 -> 2; 2 -> if (prefs.shortcutsPage) 1 else 0; else -> 0 }
            applyHomeWidget()
        }
        applyHomeWidget()
    }

    // ── Map or shortcuts ─────────────────────────────────────────────────────

    private var shortcutsPage = 0

    /** Fan running: the icon turns slowly, whatever the speed (#34). */
    private var fanSpin: android.animation.ObjectAnimator? = null
    private fun spinFan(on: Boolean) {
        if (on == (fanSpin != null)) return
        if (on) fanSpin = android.animation.ObjectAnimator.ofFloat(b.fanIcon, View.ROTATION, 0f, 360f).apply {
            duration = 3000; repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator(); start()
        } else { fanSpin?.cancel(); fanSpin = null; b.fanIcon.rotation = 0f }
    }

    /** Plug icon under the range: blinks gently below 30 %, faster below 15 % (#29). */
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

    /** End of the display of the map's hidden buttons (uptime), after a touch. */
    private var mapButtonsUntil = 0L
    private val hideMapButtons = Runnable { applyMapButtons() }

    /** Press on a map button while they are shown: restart for 6 s. */
    private fun extendMapButtons() { if (android.os.SystemClock.uptimeMillis() < mapButtonsUntil) showMapButtons() }

    private fun showMapButtons() {
        if (!prefs.mapAutoHide) return
        mapButtonsUntil = android.os.SystemClock.uptimeMillis() + 6000
        applyMapButtons()
        b.mapButtons.removeCallbacks(hideMapButtons)
        b.mapButtons.postDelayed(hideMapButtons, 6100)
    }

    /**
     * Option (#15): zoom +, zoom − and full screen hidden; the heading button stays, at the top under the
     * widget switch. A touch on the map shows them for 6 s.
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

    /** "Recentre" button green when the map follows the car's heading, button colour when north is up. */
    private fun tintMapCenter() {
        b.mapCenter.setColorFilter(color(if (mapCard.headingUp) R.color.accent_energy else R.color.accent_action))
    }

    private var consumptionCard: app.mg4dash.ui.ConsumptionCard? = null
    private var lastWidgetMode = -1

    /** Without a data connection, the map (online tiles) gives way to consumption. */
    private fun offline(): Boolean {
        // Same signal as the weather widget (#15): no weather, or last request failed;
        // Android's "internet available" stayed true without real access.
        val w = WeatherRepo.weather.value
        return w == null || WeatherRepo.offline ||
            !app.mg4dash.system.StatusMonitor.hasInternet(this) && System.currentTimeMillis() - w.updatedAt > 3600_000L
    }

    /** 0 = carte, 1 = raccourcis, 2 = consommation. */
    private fun effectiveWidget(): Int = prefs.homeWidget.let { if (it == 1 && !prefs.shortcutsPage) 0 else it }.let {
        if (it == 0 && prefs.mapOfflineSwitch && offline() && !mapCard.hasCachedTiles(LocationRepo.location.value)) 2 else it
    }

    /** Views hidden by the full screen map, with their previous visibility (null: map as a widget). */
    private var hiddenForMap: List<Pair<View, Int>>? = null

    /** Called by the bar: each of its buttons brings the map back to its widget size. */
    fun exitMapFullscreen() = setMapFullscreen(false)

    /**
     * Full screen map (issue #4): the other widgets disappear, the bar (vertical or bottom)
     * stays. HOME, a bar button or the same button bring the map back to its widget size.
     */
    private fun setMapFullscreen(on: Boolean) {
        if (simpleMode || on == (hiddenForMap != null)) return
        if (on) {
            // Walk up from the map to the column row, hiding everything around it
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
        // App that sets its size at launch (wireless CarPlay MG4CPlay / DiPlay: the video stayed at
        // the map's size in full screen, #52): relaunch it at the new size
        if (mapAppCn?.let { relaunchOnResize(it.packageName) } == true) b.mapCard.post { rebuildMapApp() }
    }

    private fun relaunchOnResize(pkg: String) = pkg.startsWith("com.shihab.diplay")

    private fun rebuildMapApp() {
        mapAppView?.let { app.mg4dash.ui.EmbeddedApp.release(it); b.mapCard.removeView(it) }
        mapAppFull?.let { b.mapCard.removeView(it) }
        mapAppView = null; mapAppCn = null; mapAppFull = null
        applyMapApp(effectiveWidget() == 0)
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
        // The map and its controls disappear behind the shortcuts (and vice versa).
        for (i in 0 until b.mapCard.childCount) {
            val v = b.mapCard.getChildAt(i)
            if (v === b.widgetToggle || v === navBanner || v === cc) continue
            if (v === b.shortcutsHolder) v.visibility = if (shortcuts) View.VISIBLE else View.GONE
            else v.visibility = if (shortcuts || consumption) View.INVISIBLE else View.VISIBLE
        }
        if (mode == 0) renderLocation(LocationRepo.location.value, CarBridge.state.value)
        // The icon announces the next page; without a shortcuts page (#38), consumption goes back to the map
        b.widgetToggle.setImageResource(when (mode) { 0 -> R.drawable.ic_bolt; 2 -> if (prefs.shortcutsPage) R.drawable.ic_apps else R.drawable.ic_map; else -> R.drawable.ic_map })
        navBanner?.show(app.mg4dash.map.NavRepo.nav.value?.takeIf { mode == 0 })
        if (shortcuts) renderShortcuts()
        applyMapApp(mode == 0)
    }

    /** App embedded in the map widget (#41, trial) and its component. */
    private var mapAppView: android.view.ViewGroup? = null
    private var mapAppCn: android.content.ComponentName? = null

    private fun pickMapApp() {
        app.mg4dash.shortcuts.Shortcuts.pickApp(this, getString(R.string.map_app_pick), getString(R.string.map_app_osm)) { cn ->
            prefs.mapApp = cn?.flattenToString()
            applyHomeWidget()
        }
    }

    /** If the map's app came in front of the home screen (on the main screen), bring home back in front. */
    private fun reclaimFromApp(pkg: String) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val top = am.getRunningTasks(1).firstOrNull() ?: return
            if (top.topActivity?.packageName == pkg && top.id != taskId) {
                am.moveTaskToFront(taskId, 0)
                app.mg4dash.system.Log.i("MapApp", "$pkg came in front of home: home brought back to front")
            }
        } catch (e: Throwable) { app.mg4dash.system.Log.w("MapApp", "reclaim: ${e.message}") }
    }

    /**
     * App in the map: the switch and full screen buttons fade after 5 s so as not to
     * hide the app; a touch anywhere on the map brings them back.
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

    /**
     * Long press on an app embedded in the map: the app receives the touches and the map's long press
     * no longer got through; with an app that does not display (Camera), no way out (#66).
     * The long press is therefore detected here, before the app.
     */
    private var mapAppPressX = 0f
    private var mapAppPressY = 0f
    private val mapAppLongPress = Runnable { pickMapApp() }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (mapAppView?.visibility == View.VISIBLE) {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val r = android.graphics.Rect(); b.mapCard.getGlobalVisibleRect(r)
                    if (r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                        showMapAppButtons()
                        mapAppPressX = ev.rawX; mapAppPressY = ev.rawY
                        b.mapCard.postDelayed(mapAppLongPress, android.view.ViewConfiguration.getLongPressTimeout() + 300L)
                    }
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop * 2
                    if (kotlin.math.abs(ev.rawX - mapAppPressX) > slop || kotlin.math.abs(ev.rawY - mapAppPressY) > slop) b.mapCard.removeCallbacks(mapAppLongPress)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL,
                android.view.MotionEvent.ACTION_POINTER_DOWN -> b.mapCard.removeCallbacks(mapAppLongPress)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Chosen app that does not fit in the map (Camera…): it closes at once or never
     * starts, and the map stayed black (#66). 6 s after launch, with no task of this app, go
     * back to the OpenStreetMap map.
     */
    private fun checkMapAppAlive(cn: android.content.ComponentName) {
        b.mapCard.postDelayed({
            if (mapAppCn != cn || mapAppView?.isAttachedToWindow != true) return@postDelayed
            val alive = try {
                @Suppress("DEPRECATION")
                (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getRunningTasks(50)
                    .any { it.baseActivity?.packageName == cn.packageName || it.topActivity?.packageName == cn.packageName }
            } catch (_: Throwable) { true }
            if (!alive) {
                app.mg4dash.system.Log.i("MapApp", "${cn.packageName} not running in the map card: back to the map")
                app.mg4dash.ui.Toasts.show(this, R.string.map_app_failed, long = true)
                prefs.mapApp = null
                applyHomeWidget()
            }
        }, 6_000)
    }

    /** Full screen button of the app embedded in the map. */
    private var mapAppFull: android.widget.ImageButton? = null

    /** Map app still installed? (uninstalled, the map stayed black with no long press, #54) */
    private fun mapAppInstalled(cn: android.content.ComponentName): Boolean =
        try { packageManager.getActivityInfo(cn, 0); true } catch (_: Exception) { false }

    private fun applyMapApp(show: Boolean) {
        var cn = prefs.mapApp?.let { android.content.ComponentName.unflattenFromString(it) }
        if (cn != null && !mapAppInstalled(cn)) {
            app.mg4dash.system.Log.i("MapApp", "${cn.packageName} uninstalled: back to the map")
            app.mg4dash.ui.Toasts.show(this, R.string.map_app_unavailable, long = true)
            prefs.mapApp = null; cn = null
        }
        if (cn != mapAppCn) {
            mapAppView?.let { b.mapCard.removeView(it) }
            mapAppFull?.let { b.mapCard.removeView(it) }
            mapAppView = null; mapAppCn = null; mapAppFull = null
        }
        app.mg4dash.ui.EmbeddedApp.immersive(this, cn?.packageName)
        if (cn == null) return
        if (mapAppView == null) {
            // Brought back to the map's size only a few seconds after the actual launch
            // (at car start, the virtual display sometimes takes more than 10 s to be ready)
            var created: View? = null
            val relaunches = relaunchOnResize(cn.packageName)
            val v = app.mg4dash.ui.EmbeddedApp.create(this, cn) {
                if (!relaunches) created?.let { av -> av.postDelayed({ if (av.isAttachedToWindow) av.layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1) }, 5000) }
                checkMapAppAlive(cn)
            }
            if (v == null) {
                app.mg4dash.ui.Toasts.show(this, R.string.map_app_unavailable, long = true)
                prefs.mapApp = null; return
            }
            // Above the map and its buttons, under the switch button
            // Created first at screen width (then brought back to the map): Android sets the size of
            // the app's surface from its virtual display at creation; created at the size
            // of the map, the app then stayed cut off on the right in full screen
            val screenW = resources.displayMetrics.widthPixels
            b.mapCard.addView(v, b.mapCard.indexOfChild(b.widgetToggle), android.widget.FrameLayout.LayoutParams(if (relaunches) -1 else screenW, -1))
            created = v
            mapAppView = v; mapAppCn = cn
            // Home rebuilt (day / night theme…): the old virtual display disappears and Android
            // brings the app to the foreground, in full screen. Take back control: the new map
            // can then get it back.
            for (delay in longArrayOf(800, 1800, 3000, 4500)) v.postDelayed({ reclaimFromApp(cn.packageName) }, delay)
            // Full screen button, as for the OpenStreetMap map, under the switch button
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

    // Bottom widget: trip energy, live power, phone (touch the widget to change)
    private var builtMode = -1
    private var powerValue: TextView? = null
    private var powerLabel: TextView? = null
    private var powerAvg: TextView? = null
    private var powerBar: app.mg4dash.ui.PowerBar? = null
    private var powerFx: app.mg4dash.ui.PowerFx? = null
    private var xpowerHits = 0

    /** Removes the full power animation from the map (other widget, or map rebuilt). */
    private fun dropPowerFx() {
        powerFx?.let { fxHost?.overlay?.remove(it); it.onBadge = null; it.stop() }
        powerFx = null; fxHost = null
    }

    // Full power while the Power widget is not shown: show it for the time of the
    // animation and peak, then go back to the previous widget (Full and Map layouts).
    private var fxBoost = false
    private var fxPrevStack = -1
    private var fxHost: View? = null

    private fun startBoost() {
        if (fxBoost) return
        fxBoost = true
        app.mg4dash.system.Log.i("Power", "full power: Power widget shown during the animation")
        if (mapLayout) { fxPrevStack = prefs.stackPage; showStackPage(2) } else renderSecondary()
    }

    private fun endBoost() {
        if (!fxBoost) return
        fxBoost = false
        if (mapLayout) { if (fxPrevStack >= 0 && fxPrevStack != 2) showStackPage(fxPrevStack) } else renderSecondary()
        fxPrevStack = -1
    }

    /** The driver changes the widget: no going back. */
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

    /** Energy since start, split as on the Energy page (MG4Dash measurements). */
    private var tripShown: String? = null

    private fun renderTripEnergy() {
        stackTitle(b.secondaryTitle, getString(R.string.sw_energy))
        val t = app.mg4dash.energy.EnergyMonitor.sinceStart.value
        val ok = t.outKwh > 0f
        val g = b.secondaryGrid
        val key = "$ok ${t.driveKwh} ${t.climKwh} ${t.heatKwh} ${t.accKwh} ${t.outKwh}"
        if (builtMode == 0 && key == tripShown && g.childCount > 0) return
        tripShown = key
        g.removeAllViews()
        g.orientation = LinearLayout.VERTICAL
        // Readable at a glance: a big coloured bar and the percentages; the detail is on the Energy page.
        val d = resources.displayMetrics.density
        val parts = listOfNotNull(
            Triple(R.string.sw_drive, t.driveKwh, color(R.color.accent_energy)),
            Triple(R.string.sw_clim, t.climKwh, color(R.color.accent_cold)),
            Triple(R.string.sw_batt, t.heatKwh, color(R.color.accent_battery)).takeIf { t.heatKwh >= app.mg4dash.energy.EnergyMonitor.HEAT_SHOWN_KWH },
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
        // Same presentation as trip energy: big bar, then the large numbers
        powerBar = app.mg4dash.ui.PowerBar(this).apply {
            // Scale by model (motor power: 51 kWh 170 hp / 125 kW, 64 kWh 204 hp / 150 kW,
            // 77 kWh 245 hp / 180 kW), plus a margin for losses; it widens by itself if the
            // car draws more (XPower 435 hp / 320 kW), in 50 kW steps, and remembers it.
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
        // Detail: voltage, current (green when recovering), centred in the space left under the numbers
        powerExtra = TextView(this).apply {
            setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
        }
        g.addView(powerExtra, LinearLayout.LayoutParams(-1, 0, 1f))
        // Full power animation (settings): above the map, the peak replaces the voltage · current line
        dropPowerFx()
        powerFx = app.mg4dash.ui.PowerFx(this).also { fx ->
            fx.onBadge = { on ->
                powerExtra?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(250)?.start()
                if (!on) b.root.post { endBoost() }   // not while the animation is drawing
            }
        }
    }

    private var powerExtra: TextView? = null

    /**
     * "Power" energy card (#29, setting): no drive mode, KERS or average consumption at
     * the top; the range moves to the right, the live power bar under the battery one,
     * then kW on the left, voltage · current in the centre, kWh/100 on the right. It takes the place of
     * the scrolling widget's Power page, full power animation included.
     */
    /** Bottom bar (Full layout): the energy card is shorter, smaller numbers (#43). */
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
        // More vertical room: slightly smaller numbers, lower battery bar
        // % and km at the same size, units at half size, pin at the numbers' height (#29)
        b.battery.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        b.range.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f); b.range.typeface = b.battery.typeface
        // Numbers close to the bar (#29), solid plug with a lightning bolt, more readable marks
        (b.battery.parent as LinearLayout).gravity = Gravity.BOTTOM
        // Solid plug with a lightning bolt, reduced so km + plug match the % height (#29)
        // Long thick cable, under the full width of the km; same gap with the bar as the % (#29)
        b.rangePlug.setImageResource(R.drawable.ic_range_plug_bolt_long)
        b.rangePlug.layoutParams = (b.rangePlug.layoutParams as LinearLayout.LayoutParams).apply {
            width = (110 * d).toInt(); height = (20 * d).toInt(); topMargin = (2 * d).toInt(); bottomMargin = (9 * d).toInt()
        }
        b.batteryGauge.smallSp = 16f; b.batteryGauge.tickSp = 15f
        // No icon before the km (#29)
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
        // V · A on one line, 50 % larger than before (#29), reduced only if room is short
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
        // V / A optional (#29): without them, kW and average consumption take the space and grow
        val va = prefs.powerVa
        if (!va) { powerExtra?.visibility = View.INVISIBLE; powerValue?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f); powerAvg?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f) }
        else row.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol && r > l) v.post { fixVaSize(r - l) } }
        powerFx = app.mg4dash.ui.PowerFx(this).also { fx ->
            fx.onBadge = { on -> if (va) powerExtra?.animate()?.alpha(if (on) 0f else 1f)?.setDuration(250)?.start() }
        }
        // Bottom bar (Full layout): shorter card, everything tightened (#43)
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
     * Fixed size V / A line (#29): kW and average consumption take the width of their longest
     * values, and V / A the size that fits its longest line in what remains. It no longer
     * shrinks when the numbers change.
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

    /** kW and kWh/100 units at the size of the power bar's marks (#29). */
    private fun scaleUnit(t: CharSequence): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        val sp = powerBar?.labelSp ?: 15f
        return android.text.SpannableString(t).apply { setSpan(android.text.style.AbsoluteSizeSpan(sp.toInt(), true), i, t.length, 0) }
    }

    /** Unit (after the last space) at half size: "79.7 %", "347 km". */
    private fun halfUnit(t: CharSequence): CharSequence {
        val i = t.lastIndexOf(' ').takeIf { it > 0 } ?: return t
        return android.text.SpannableString(t).apply { setSpan(android.text.style.RelativeSizeSpan(0.5f), i, t.length, 0) }
    }

    /** Lowest voltage and highest current of the trip (#29), reset at each start. */
    private var minVolts: Float? = if (BuildConfig.MOCK) 325f else null
    private var maxAmps: Float? = if (BuildConfig.MOCK) 150.6f else null
    private var minMaxTrip = -1
    /** Highest charge current since plugging in (#29), shown instead of the highest drawn while charging. */
    private var maxChargeAmps: Float? = null
    private var wasCharging = false

    /** Battery power (voltage × current): positive = drawn, negative = recovered. */
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
        // XPower: the car does not give its version; two measurements in a row above 350 hp give it away
        // Only while driving and not charging: an aberrant reading when stopped or charging (old
        // firmwares, issue #28) must not make the car pass for an XPower
        val rolling = (CarBridge.state.value.speedKmh ?: 0f) > 5f && !VehicleState.isPlugged(CarBridge.state.value.chargeStatus)
        xpowerHits = if (rolling && !plugged && kw != null && kw >= app.mg4dash.ui.PowerBar.XPOWER_DETECT_KW && kw < 400f) xpowerHits + 1 else 0
        if (xpowerHits >= 2 && !prefs.xpower) {
            prefs.xpower = true
            app.mg4dash.system.Log.i("Power", "MG4 XPower detected (${kw} kW): max 320 kW, animation at 80 %")
            powerBar?.let { val keep = it.maxDrawKw; it.configureFor(prefs.batteryModel, true); it.maxDrawKw = maxOf(keep, it.maxDrawKw) }
        }
        if (builtMode != 1 && !cardPower) {
            val peak = app.mg4dash.ui.PowerBar.peakFor(prefs.batteryModel, prefs.xpower)
            if (prefs.powerFx == app.mg4dash.ui.PowerFx.NONE || plugged || kw == null || kw < peak * 0.8f) return
            startBoost()
            if (builtMode != 1) return
        }
        val regen = kw != null && kw < -0.05f && !plugged
        powerValue?.put(kw?.let { String.format(Locale.getDefault(), "%.1f kW", kotlin.math.abs(it)) } ?.let { if (cardPower) scaleUnit(it) else it } ?: "—")
        powerValue?.setTextColor(color(if (regen) R.color.accent_energy else R.color.text_value))
        powerLabel?.put(getString(when { plugged -> R.string.sw_charging; regen -> R.string.sw_recovering; else -> R.string.sw_drawn }))
        powerBar?.kw = if (plugged) 0f else kw ?: 0f
        val bar = powerBar; val under = powerExtra
        if (bar != null && under != null) powerFx?.let { fx ->
            // Random: new draw as long as nothing plays, kept during the animation
            if (!fx.isActive) fx.style = app.mg4dash.ui.PowerFx.pick(prefs.powerFx)
            // Peak badge: only if the driver shows the Power widget themselves
            fx.badgeAllowed = cardPower || (if (mapLayout) (if (fxPrevStack >= 0) fxPrevStack else prefs.stackPage) == 2 else prefs.secondaryWidget == 1)
            // Across the screen: the rocket arrives from the left edge, lightning and flames cover the home screen
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
        powerAvg?.put(avg?.let { "${app.mg4dash.system.Units.consText(this, it)} ${if (app.mg4dash.system.Units.miles(this)) "mi/kWh" else "kWh/100"}" }?.let { if (cardPower) scaleUnit(it) else it } ?: "—")
        if (cardPower) powerExtra?.let { tv ->
            val trip = app.mg4dash.energy.EnergyMonitor.tripSeq
            if (trip != minMaxTrip) { if (minMaxTrip >= 0) { minVolts = null; maxAmps = null }; minMaxTrip = trip }
            volts?.let { v -> minVolts = minOf(minVolts ?: v, v) }
            amps?.let { a -> if (a > 0f) maxAmps = maxOf(maxAmps ?: a, a) }
            if (plugged && !wasCharging) maxChargeAmps = null
            wasCharging = plugged
            amps?.let { a -> if (plugged && a < 0f) maxChargeAmps = maxOf(maxChargeAmps ?: -a, -a) }
            val shownMax = if (plugged) maxChargeAmps else maxAmps
            // One line: voltage · lowest voltage | current · highest current (lowest and highest slightly dimmed, #29)
            val dim = color(R.color.text_sub)
            fun line(now: CharSequence?, extreme: String?): CharSequence = android.text.SpannableStringBuilder().apply {
                append(now ?: "—")
                extreme?.let { val st = length; append(" $it"); setSpan(android.text.style.ForegroundColorSpan(dim), st, length, 0)
                    setSpan(android.text.style.RelativeSizeSpan(0.7f), st + it.lastIndexOf(' ') + 2, length, 0) }
            }
            // V and A units at half size, like kW and kWh/100 (#29)
            fun half(t: String): android.text.SpannableString = android.text.SpannableString(t).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.7f), t.lastIndexOf(' ') + 1, t.length, 0)
            }
            val ampsTxt = amps?.let { a ->
                half(String.format(Locale.getDefault(), "%.1f A", kotlin.math.abs(a))).apply {
                    if (a < -0.5f) setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_energy)), 0, length, 0)
                }
            }
            tv.put(android.text.SpannableStringBuilder()
                .append(line(volts?.let { half(String.format(Locale.getDefault(), "%.0f V", it)) }, minVolts?.let { String.format(Locale.getDefault(), "%.0f min", it) }))
                .append(android.text.SpannableString("   |   ").apply { setSpan(android.text.style.ForegroundColorSpan(color(R.color.text_disabled)), 0, length, 0) })
                .append(ampsTxt ?: "—").append(shownMax?.let { m ->
                    android.text.SpannableString(String.format(Locale.getDefault(), " %.1f max", m)).apply { setSpan(android.text.style.ForegroundColorSpan(dim), 0, length, 0); setSpan(android.text.style.RelativeSizeSpan(0.7f), length - 3, length, 0) }
                } ?: ""))
        } else powerExtra?.let { tv ->
            val parts = mutableListOf<CharSequence>()
            volts?.let { parts.add(String.format(Locale.getDefault(), "%.0f V", it)) }
            amps?.let { a ->
                val txt = String.format(Locale.getDefault(), "%.1f A", kotlin.math.abs(a))
                parts.add(if (a < -0.5f) android.text.SpannableString(txt).apply {
                    setSpan(android.text.style.ForegroundColorSpan(color(R.color.accent_energy)), 0, txt.length, 0)
                } else txt)
            }
            tv.put(android.text.TextUtils.join(if (cardPower) " · " else "   ·   ", parts))
        }
    }

    /** Three favourite contacts, chosen from the phone book; touch = call, long press = change. */
    private fun renderPhoneFavs() {
        stackTitle(b.secondaryTitle, getString(R.string.sw_phone))
        val g = b.secondaryGrid
        g.removeAllViews()
        // 2 or 3 shortcuts on one line, 4, 6 or 8 on two lines; icon and name reduced on two lines
        val n = app.mg4dash.shortcuts.PhoneFavorites.count(prefs)
        val cols = app.mg4dash.shortcuts.PhoneFavorites.columns(n)
        val twoRows = n > 3
        g.orientation = if (twoRows) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val d = resources.displayMetrics.density
        val refresh = { builtMode = -1; renderSecondary() }
        var line: LinearLayout? = null
        for (i in 0 until n) {
            val target = if (!twoRows) g else {
                if (i % cols == 0) line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also {
                    g.addView(it, LinearLayout.LayoutParams(-1, 0, 1f))
                }
                line!!
            }
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
            val icon = when { !twoRows && n == 2 -> 64; !twoRows -> 56; cols <= 2 -> 40; else -> 34 }
            val textSp = when { !twoRows -> 24f; cols <= 2 -> 21f; cols == 3 -> 19f; else -> 17f }
            if (twoRows) cell.setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            cell.addView(ImageView(this).apply {
                setImageResource(if (fav == null) R.drawable.ic_plus else R.drawable.ic_phone)
                setColorFilter(color(if (fav == null) R.color.text_title else R.color.accent_energy))
            }, LinearLayout.LayoutParams((icon * d).toInt(), (icon * d).toInt()))
            if (fav != null) cell.addView(TextView(this).apply {
                text = fav.name; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER
                setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ((if (twoRows) 4 else 8) * d).toInt() })
            target.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt(); topMargin = ((if (twoRows) 4 else 6) * d).toInt()
            })
        }
    }

    // ── Watching the data sources ────────────────────────────────────────────

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    var shown: VehicleState? = null
                    CarBridge.state.collect { s ->
                        // Speed and odometer keep changing while driving, and these cards do not show them
                        val quiet = s.copy(speedKmh = null, odometerKm = null)
                        if (quiet != shown) { shown = quiet; renderClimate(s); renderEnergy(s, ConsumptionEstimator.average.value); stackAuto(s) }
                    }
                }
                launch { ConsumptionEstimator.average.collect { renderEnergy(CarBridge.state.value, it) } }
                launch { MediaHub.now.collect { renderMedia(it) } }
                launch { WeatherRepo.shown.collect { renderWeather(it, CarBridge.state.value) } }
                launch { LocationRepo.location.collect { renderLocation(it, CarBridge.state.value) } }
                // DiAuto guidance: banner on the map (not on the shortcuts)
                launch { app.mg4dash.energy.ConsumptionCurve.curves.collect { consumptionCard?.takeIf { it.visibility == View.VISIBLE }?.show() } }
                launch { app.mg4dash.map.NavRepo.nav.collect { n -> navBanner?.show(n?.takeIf { effectiveWidget() == 0 }) } }
                launch { while (true) { updatePower(); delay(1_000) } }
                // Startup (#64): home is built with the last known theme; as soon as the car
                // answers, the lights state is read and the theme changed at once, without waiting for the 5 s loop
                launch {
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    while (!app.mg4dash.car.CarProps.ready && android.os.SystemClock.elapsedRealtime() - t0 < 15_000) delay(100)
                    if (!isFinishing && app.mg4dash.ui.Themes.signature(this@MainActivity) != themeSig && !app.mg4dash.ui.Themes.popupOpen(this@MainActivity)) {
                        app.mg4dash.system.Log.i("Theme", "lights read at start: theme changed right away")
                        app.mg4dash.ui.Themes.publish(this@MainActivity); app.mg4dash.ui.Themes.recreateSmooth(this@MainActivity)
                    }
                }
                launch {
                    while (true) {
                        if (isFinishing) return@launch
                        if (app.mg4dash.ui.Themes.signature(this@MainActivity) != themeSig && !app.mg4dash.ui.Themes.popupOpen(this@MainActivity)) { app.mg4dash.ui.Themes.publish(this@MainActivity); app.mg4dash.ui.Themes.recreateSmooth(this@MainActivity); return@launch }
                        renderSecondary()
                        renderWeatherFallbackOnly()
                        if (effectiveWidget() != lastWidgetMode || effectiveWidget() == 2) { lastWidgetMode = effectiveWidget(); applyHomeWidget() }
                        val recircNow = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            app.mg4dash.car.CarProps.readAny(354419976).let { v -> when (v) { "true", "1" -> true; "false", "0" -> false; else -> null } }
                        }
                        if (recircNow != actualRecirc) { actualRecirc = recircNow; renderClimate(CarBridge.state.value) }
                        delay(5_000)
                    }
                }
            }
        }
    }

    private fun renderWeatherFallbackOnly() {
        if (WeatherRepo.shown.value == null) renderWeather(null, CarBridge.state.value)
    }

    // ── Startup: let the SAIC launcher register its interface ────────────────

    /**
     * The SAIC launcher's service (camera, audio sources, SystemUI commands) only receives
     * its callback interface once its home screen is created. Once per car
     * start, it is therefore opened briefly, then we come back here.
     */
    private fun warmUpStockLauncherIfNeeded() {
        if (BuildConfig.MOCK) return
        if (!HomeSwitcher.isStockEnabled(this)) return   // SAIC home disabled (replaced by MG4Dash)
        val bootCount = try { Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Exception) { -1 }
        val sp = prefs.raw
        if (bootCount >= 0 && sp.getInt("warmup_boot", -2) == bootCount) return
        sp.edit().putInt("warmup_boot", bootCount).apply()
        Log.i("Boot", "briefly opening the SAIC launcher (boot #$bootCount)")
        try {
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(HomeSwitcher.STOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Separate Handler: the activity's one is emptied in onPause, which happens right here.
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 1500)
        } catch (e: Exception) {
            Log.w("Boot", "SAIC launcher not opened: ${e.message}")
        }
    }

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private companion object {
        const val KEY_MAP_FULL = "map_full"
        /** Map full screen to restore after a recreation (in addition to the saved state). */
        var mapFullOnRecreate = false
    }
}
