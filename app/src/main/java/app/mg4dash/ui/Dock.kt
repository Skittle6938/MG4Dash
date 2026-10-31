package app.mg4dash.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.MainActivity
import app.mg4dash.R
import app.mg4dash.apps.AppsActivity
import app.mg4dash.pages.ClimateActivity
import app.mg4dash.pages.EnergyActivity
import app.mg4dash.pages.VehicleActivity
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.shortcuts.Shortcuts
import app.mg4dash.system.Apps
import app.mg4dash.system.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dock inférieur commun à tous les écrans : horloge + Accueil, Véhicule, Clim, Énergie,
 * Android Auto, Paramètres. On passe d'une page à l'autre sans repasser par l'accueil.
 * Appui long sur Accueil : liste des applis ; sur Paramètres : réglages du launcher.
 */
object Dock {
    enum class Page { HOME, VEHICLE, CLIMATE, ENERGY, PHONE, SETTINGS }

    private data class Item(val icon: Int, val label: Int, val page: Page?, val accent: Boolean = false)

    private val ITEMS = listOf(
        Item(R.drawable.ic_home, R.string.dock_home, Page.HOME),
        Item(R.drawable.ic_car, R.string.dock_vehicle, Page.VEHICLE),
        Item(R.drawable.ic_snow, R.string.dock_climate, Page.CLIMATE),
        Item(R.drawable.ic_bolt, R.string.dock_energy, Page.ENERGY),
        Item(R.drawable.ic_phone, R.string.dock_phone, Page.PHONE),
        Item(R.drawable.ic_navigation, R.string.dock_aa, null, accent = true),
        Item(R.drawable.ic_settings, R.string.dock_settings, Page.SETTINGS),
    )

    fun build(activity: Activity, dock: LinearLayout, current: Page, simple: Boolean = false) {
        val kit = Kit(activity)
        fun color(r: Int) = app.mg4dash.ui.Themes.color(activity, r)
        dock.removeAllViews()
        // Le dock fixe lui-même son apparence : identique sur l'accueil et sur toutes les pages.
        dock.orientation = LinearLayout.HORIZONTAL
        dock.setBackgroundResource(R.drawable.bg_panel)
        dock.setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(6))
        dock.elevation = kit.dp(6).toFloat()

        val clockCell = kit.column().apply { gravity = Gravity.CENTER; setPadding(kit.dp(12), 0, kit.dp(20), 0) }
        // Case de gauche : horloge ou raccourci Wi-Fi, au choix par appui long
        val wifi = app.mg4dash.system.Prefs(activity).dockSlot == 1
        clockCell.setOnLongClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.dock_slot_pick)
                .setItems(arrayOf(activity.getString(R.string.dock_slot_clock), activity.getString(R.string.dock_slot_wifi))) { _, w ->
                    app.mg4dash.system.Prefs(activity).dockSlot = w
                    build(activity, dock, current, simple)
                    (activity as? app.mg4dash.MainActivity)?.onDockSlotChanged()
                }
                .setNegativeButton(R.string.cancel, null)
                .show().also { app.mg4dash.ui.Dialogs.fix(it) }
            true
        }
        if (wifi) {
            clockCell.setBackgroundResource(R.drawable.bg_dock_item)
            clockCell.setOnClickListener { openWifi(activity) }
            clockCell.addView(android.widget.ImageView(activity).apply {
                setImageResource(R.drawable.ic_wifi); setColorFilter(color(R.color.accent_action))
            }, LinearLayout.LayoutParams(kit.dp(30), kit.dp(30)))
            clockCell.minimumWidth = kit.dp(96)
            clockCell.addView(TextView(activity).apply {
                setText(R.string.dock_slot_wifi_short); setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); maxLines = 1
            })
            dock.addView(clockCell, LinearLayout.LayoutParams(-2, -1))
        }
        val clock = TextView(activity).apply {
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); includeFontPadding = false
        }
        val date = TextView(activity).apply { setTextColor(color(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        if (!wifi) { clockCell.addView(clock); clockCell.addView(date); dock.addView(clockCell, LinearLayout.LayoutParams(-2, -1)) }
        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                clock.text = app.mg4dash.system.Clock.hm(activity, now)
                date.text = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).trimEnd('.')
                clock.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
        tick.run()

        // Accueil épuré : seulement Accueil, Téléphone, Android Auto et Paramètres, en plus grand.
        val shown = if (simple) ITEMS.filter {
            it.label == R.string.dock_home || it.label == R.string.dock_phone || it.label == R.string.dock_aa || it.label == R.string.dock_settings
        } else ITEMS
        shown.forEachIndexed { index, item ->
            val selected = item.page == current
            val cell = kit.row().apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_dock_item)
                isClickable = true; isFocusable = true
                isSelected = selected
                setOnClickListener { open(activity, item, current) }
            }
            // Bouton Android Auto : remplaçable par une autre appli (appui long)
            val custom = if (item.label == R.string.dock_aa) customAa(activity) else null
            cell.addView(ImageView(activity).apply {
                val appIcon = custom?.let { Shortcuts.appIcon(activity, it) ?: if (it.packageName == Apps.CARPLAY.packageName) pkgIcon(activity, it.packageName) else null }
                if (appIcon != null) setImageDrawable(appIcon) else {
                    setImageResource(item.icon)
                    setColorFilter(color(if (selected || item.accent) R.color.accent_action else R.color.text_sub))
                }
                if (item.label == R.string.dock_aa) markOff(activity, this)
            }, LinearLayout.LayoutParams(kit.dp(if (simple) 40 else 30), kit.dp(if (simple) 40 else 30)))
            cell.addView(TextView(activity).apply {
                if (custom == Apps.CARPLAY) setText(R.string.dock_cp)
                else if (custom != null) text = Shortcuts.appLabel(activity, custom) else setText(item.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (simple) 26f else 19f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(if (selected || item.accent) R.color.text_value else R.color.text_sub))
                setPadding(kit.dp(12), 0, 0, 0)
            })
            when (item.label) {
                R.string.dock_home -> cell.setOnLongClickListener { activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                R.string.dock_aa -> aaGestures(activity, cell, { open(activity, item, current) }) { build(activity, dock, current, simple) }
                R.string.dock_settings -> cell.setOnLongClickListener { Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings)); true }
            }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index >= 0) leftMargin = kit.dp(6) })
        }
    }

    private fun pkgIcon(activity: Activity, pkg: String): android.graphics.drawable.Drawable? =
        try { activity.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }

    /** Barre verticale à gauche choisie dans les réglages (tous les écrans). */
    fun vertical(context: android.content.Context): Boolean = Prefs(context).verticalDock

    /**
     * Conduite à droite : barre verticale à droite, sur toute la hauteur (le bouton antibrouillards
     * reste à gauche, géré par la voiture). Activé quand la lecture de CAL_DRIVE_HDL_TYPE aura été
     * confirmée sur une voiture à droite ; « force_rhd » sert aux captures.
     */
    private const val RHD_ENABLED = true
    fun rightSide(context: android.content.Context): Boolean =
        Prefs(context).raw.getBoolean("force_rhd", false) || (RHD_ENABLED && app.mg4dash.car.CarProps.rightHandDrive() == true)

    /**
     * Pose le dock d'un écran : en bas (dock horizontal) ou, si l'option est choisie, en barre
     * verticale à gauche. [root] est la colonne de l'écran, [dock] son dernier enfant.
     * En vertical, tous les autres enfants passent dans une colonne à droite de la barre.
     */
    fun mount(activity: Activity, root: LinearLayout, dock: LinearLayout, current: Page, simple: Boolean = false) {
        if (!vertical(activity)) { build(activity, dock, current, simple); return }
        val kit = Kit(activity)
        val children = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== dock }
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        root.clipToPadding = false   // la barre déborde un peu sur la marge de l'écran (#38)
        val bar = LinearLayout(activity)
        val right = rightSide(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        children.forEach { content.addView(it) }   // chaque enfant garde ses paramètres (poids, marges)
        // 72 dp, débordant de 4 dp sur la marge de l'écran : centrée sous le bouton antibrouillards
        // de MG (x 20 → 76 dp, y ≈ 14 → 70 dp), avec 8 dp à gauche, à droite et au-dessus (#38)
        val barDp = if (Prefs(activity).wideBar) BAR_DP * 2 else BAR_DP   // réglage « barre large » (#67)
        if (!right) root.addView(bar, LinearLayout.LayoutParams(kit.dp(barDp), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = -kit.dp(4); topMargin = -kit.dp(6) })
        root.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            if (right) rightMargin = kit.dp(12) else leftMargin = kit.dp(12)
        })
        if (right) root.addView(bar, LinearLayout.LayoutParams(kit.dp(barDp), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = -kit.dp(4) })
        buildVertical(activity, bar, current)
    }

    /**
     * Barre verticale à gauche (option ; issue #6) : icônes seules, sous le bouton
     * antibrouillards de MG qui se superpose en haut à gauche et semble ainsi en faire partie.
     * Ordre : Réglages, Android Auto / CarPlay (en gris), Téléphone, Énergie, Véhicule, Accueil.
     * Réglages : toucher = réglages du launcher, appui long = réglages de la voiture,
     * double toucher = Wi-Fi.
     */
    private const val BAR_DP = 72

    /**
     * Indicateur Wi-Fi discret à côté de l'icône Véhicule (#67) : affiché quand l'autoradio est relié à un
     * point d'accès (partage de connexion du téléphone…), relu toutes les 5 s tant que la barre est affichée.
     */
    private fun wifiBadge(activity: Activity, cell: android.widget.FrameLayout, iconDp: Int) {
        val d = activity.resources.displayMetrics.density
        val badge = ImageView(activity).apply {
            setImageResource(R.drawable.ic_wifi); setColorFilter(Themes.color(activity, R.color.accent_action)); visibility = android.view.View.GONE
        }
        val s = (iconDp * 0.5f * d).toInt()
        cell.addView(badge, android.widget.FrameLayout.LayoutParams(s, s, android.view.Gravity.CENTER).apply {
            // Décalage depuis le centre de la case : 0,75 × icône débordait de la case à droite et rognait le Wi-Fi (#38)
            leftMargin = (iconDp * 0.55f * d).toInt(); topMargin = -(iconDp * 0.5f * d).toInt()
        })
        val tick = object : Runnable {
            override fun run() {
                if (!badge.isAttachedToWindow) return
                val on = try { app.mg4dash.system.StatusMonitor.read(activity).wifiConnected } catch (_: Exception) { false }
                badge.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
                badge.postDelayed(this, 5_000)
            }
        }
        badge.addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { badge.removeCallbacks(tick); badge.post(tick) }
            override fun onViewDetachedFromWindow(v: android.view.View) { badge.removeCallbacks(tick) }
        })
    }

    fun buildVertical(activity: Activity, bar: LinearLayout, current: Page) {
        val kit = Kit(activity)
        fun color(r: Int) = app.mg4dash.ui.Themes.color(activity, r)
        bar.removeAllViews()
        bar.orientation = LinearLayout.VERTICAL
        bar.setBackgroundResource(R.drawable.bg_panel)
        bar.elevation = kit.dp(6).toFloat()
        bar.setPadding(kit.dp(4), kit.dp(4), kit.dp(4), kit.dp(6))
        // Place du bouton antibrouillards (superposé par MG, on ne peut pas le dessiner nous-mêmes) ;
        // à droite (conduite à droite), il reste à gauche : la barre prend toute la hauteur
        if (!rightSide(activity)) bar.addView(android.view.View(activity), LinearLayout.LayoutParams(-1, kit.dp(68)))
        val order = listOf(
            ITEMS.first { it.label == R.string.dock_settings },
            ITEMS.first { it.label == R.string.dock_aa },
            ITEMS.first { it.label == R.string.dock_phone },
            ITEMS.first { it.label == R.string.dock_energy },
            ITEMS.first { it.label == R.string.dock_vehicle },
            ITEMS.first { it.label == R.string.dock_home },
        )
        order.forEach { item ->
            val selected = item.page == current
            val cell = android.widget.FrameLayout(activity).apply {
                setBackgroundResource(R.drawable.bg_dock_item); isSelected = selected
                isClickable = true; isFocusable = true
            }
            val icon = ImageView(activity)
            val custom = if (item.label == R.string.dock_aa) customAa(activity) else null
            // Android Auto et CarPlay : un seul pictogramme neutre ; une autre appli garde son icône (en gris)
            val projection = item.label == R.string.dock_aa && (custom == null || custom.packageName == Apps.CARPLAY.packageName ||
                custom.packageName.startsWith("com.allgo.app.androidauto"))
            val appIcon = if (projection) null else custom?.let { Shortcuts.appIcon(activity, it) }
            if (projection) {
                icon.setImageResource(R.drawable.ic_projection)
                icon.setColorFilter(color(if (selected) R.color.accent_action else R.color.text_sub))
            } else if (appIcon != null) {
                // Icône d'appli en niveaux de gris, pour s'accorder aux autres icônes de la barre
                icon.setImageDrawable(appIcon.mutate().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                })
            } else {
                icon.setImageResource(item.icon)
                icon.setColorFilter(color(if (selected) R.color.accent_action else R.color.text_sub))
            }
            // Véhicule et Accueil sont des dessins plus plats que les autres (et Android Auto plus haut) : retaillés pour
            // un vide visuel égal entre les icônes, et recentrés (voiture tracée de y=5 à 21, maison de 3 à 20
            // dans leur cadre de 24) pour que le dessin, et pas seulement son cadre, soit au centre.
            val size = when { item.label == R.string.dock_vehicle -> 38; item.label == R.string.dock_home -> 37; projection -> 32; else -> 34 }
                .let { if (Prefs(activity).wideBar) it * 3 / 2 else it }
            icon.translationY = when (item.label) {
                R.string.dock_vehicle -> -kit.dp(size) / 24f
                R.string.dock_home -> kit.dp(size) / 48f
                else -> 0f
            }
            if (item.label == R.string.dock_aa) markOff(activity, icon)
            cell.addView(icon, android.widget.FrameLayout.LayoutParams(kit.dp(size), kit.dp(size), android.view.Gravity.CENTER))
            if (item.label == R.string.dock_vehicle) wifiBadge(activity, cell, size)
            when (item.label) {
                R.string.dock_settings -> {
                    // Toucher simple (après le délai du double toucher) / double toucher / appui long
                    val gd = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(activity, item, current); return true }
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean { openWifi(activity); return true }
                        override fun onLongPress(e: android.view.MotionEvent) { Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings)) }
                        override fun onDown(e: android.view.MotionEvent) = true
                    })
                    cell.setOnTouchListener { v, ev -> v.isPressed = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN; gd.onTouchEvent(ev) }
                }
                R.string.dock_energy -> {
                    // Double toucher : écran éteint ; toucher : page Énergie
                    val gd = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(activity, item, current); return true }
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                            // Même commande que le launcher d'origine ; un toucher sur l'écran noir le rallume
                            activity.startActivity(Intent(activity, ScreenOffActivity::class.java)); return true
                        }
                        // Appui long : éteindre la voiture (#12), sans confirmation, mais jamais en roulant
                        override fun onLongPress(e: android.view.MotionEvent) {
                            if (!app.mg4dash.car.CarBridge.powerOffCar())
                                app.mg4dash.ui.Toasts.show(activity, R.string.power_off_driving)
                        }
                        override fun onDown(e: android.view.MotionEvent) = true
                    })
                    cell.setOnTouchListener { v, ev -> v.isPressed = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN; gd.onTouchEvent(ev) }
                }
                R.string.dock_vehicle -> {
                    // Appui long : ESC coupé / réactivé ; icône rouge tant qu'il est coupé
                    fun tint() {
                        val escOff = app.mg4dash.car.CarBridge.escActive() == false
                        icon.setColorFilter(color(when { escOff -> R.color.accent_danger; selected -> R.color.accent_action; else -> R.color.text_sub }))
                        // ESC coupé : la voiture passe en dérive, en rouge
                        val res = if (escOff) R.drawable.ic_car_drift else R.drawable.ic_car
                        if (icon.tag != res) { icon.setImageResource(res); icon.tag = res }
                    }
                    tint()
                    // Toucher : page Véhicule ; double toucher : portes verrouillées / déverrouillées (utile depuis
                    // l'arrière, issue #12) ; appui long : ESC (pas de confirmation, la voiture le signale elle-même)
                    val gd = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(activity, item, current); return true }
                        override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                            val nowLocked = app.mg4dash.car.CarBridge.toggleDoors()
                            app.mg4dash.ui.Toasts.show(activity, when (nowLocked) {
                                true -> R.string.doors_locked; false -> R.string.doors_unlocked; null -> R.string.doors_unknown
                            })
                            return true
                        }
                        override fun onLongPress(e: android.view.MotionEvent) {
                            app.mg4dash.car.CarBridge.toggleEsc()
                            cell.postDelayed({ tint() }, 800)
                        }
                        override fun onDown(e: android.view.MotionEvent) = true
                    })
                    cell.setOnTouchListener { v, ev -> v.isPressed = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN; gd.onTouchEvent(ev) }
                    // État relu régulièrement : l'ESC peut aussi changer depuis le menu MG
                    val refresh = object : Runnable {
                        override fun run() { if (cell.isAttachedToWindow) { tint(); cell.postDelayed(this, 3000) } }
                    }
                    cell.postDelayed(refresh, 3000)
                }
                else -> {
                    cell.setOnClickListener { open(activity, item, current) }
                    if (item.label == R.string.dock_home) cell.setOnLongClickListener { activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                    // Appui long : choix de l'appli ; double toucher : coupe ou rétablit la connexion du téléphone
                    if (item.label == R.string.dock_aa) aaGestures(activity, cell, { open(activity, item, current) }) { buildVertical(activity, bar, current) }
                }
            }
            bar.addView(cell, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = kit.dp(4) })
        }
    }

    /** Réglages Wi-Fi d'Android, sinon les réglages de la voiture. */
    fun openWifi(activity: Activity) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings))
        }
    }

    /**
     * Bouton Android Auto : toucher = ouvrir, appui long = choisir Android Auto, CarPlay ou une autre appli,
     * double toucher = couper ou rétablir la connexion du téléphone.
     */
    private fun aaGestures(activity: Activity, cell: android.view.View, open: () -> Unit, rebuild: () -> Unit) {
        val gd = android.view.GestureDetector(activity, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(); return true }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean { toggleConnection(activity, rebuild); return true }
            override fun onLongPress(e: android.view.MotionEvent) { pickAaTarget(activity, rebuild) }
            override fun onDown(e: android.view.MotionEvent) = true
        })
        cell.setOnClickListener(null)
        cell.setOnTouchListener { v, ev -> v.isPressed = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN; gd.onTouchEvent(ev) }
    }

    /** Comme le launcher d'origine : le bouton ouvre Android Auto, CarPlay ou n'importe quelle appli. */
    private fun pickAaTarget(activity: Activity, onDone: () -> Unit) {
        val choices = arrayOf(activity.getString(R.string.dock_aa_stock), activity.getString(R.string.dock_cp_stock), activity.getString(R.string.dock_other_app))
        // Changement d'appli : celle qui avait été coupée est d'abord réactivée
        fun choose(target: String?) { app.mg4dash.system.ConnectionToggle.restore(activity); Prefs(activity).dockAaTarget = target; onDone() }
        android.app.AlertDialog.Builder(activity)
            .setTitle(R.string.dock_aa_pick)
            .setItems(choices) { _, w ->
                when (w) {
                    0 -> choose(null)
                    1 -> choose(Apps.CARPLAY.flattenToString())
                    else -> Shortcuts.pickApp(activity, activity.getString(R.string.dock_aa_pick)) { cn -> if (cn != null) choose(cn.flattenToString()) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener { Fullscreen.apply(activity) }
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    /** Double toucher : coupe ou rétablit la connexion du téléphone (l'appli du bouton est désactivée). */
    private fun toggleConnection(activity: Activity, rebuild: () -> Unit) {
        val name = connectionName(activity)
        app.mg4dash.system.ConnectionToggle.toggle(activity) { state ->
            when (state) {
                true -> Toasts.show(activity, activity.getString(R.string.dock_conn_disabled, name), long = true)
                false -> Toasts.show(activity, activity.getString(R.string.dock_conn_enabled, name))
                null -> Toasts.show(activity, activity.getString(R.string.st_failed))
            }
            if (!activity.isDestroyed) rebuild()
        }
    }

    private fun connectionName(activity: Activity): String = customAa(activity)?.let {
        if (it.packageName == Apps.CARPLAY.packageName) activity.getString(R.string.dock_cp) else Shortcuts.appLabel(activity, it)
    } ?: activity.getString(R.string.dock_aa)

    /** Connexion coupée : icône en rouge, barrée. */
    private fun markOff(activity: Activity, icon: ImageView) {
        if (!app.mg4dash.system.ConnectionToggle.isOff(activity)) return
        val red = ContextCompat.getColor(activity, R.color.accent_danger)
        icon.setColorFilter(red)
        icon.foreground = StrikeDrawable(red, activity.resources.displayMetrics.density * 3f)
    }

    /** Trait en diagonale par-dessus une icône. */
    private class StrikeDrawable(color: Int, width: Float) : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; strokeWidth = width; strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; val i = b.width() * 0.08f
            c.drawLine(b.left + i, b.top + i, b.right - i, b.bottom - i, p)
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Suppress("OverridingDeprecatedMember")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun customAa(activity: Activity): android.content.ComponentName? {
        val p = Prefs(activity)
        val cn = p.dockAaTarget?.let { android.content.ComponentName.unflattenFromString(it) } ?: return null
        // DiAuto (com.drivehub.diauto.mg4) a un temps porté un autre nom (app.mg4aa) : le bouton suit celui des
        // deux qui est installé si l'autre a été désinstallé
        val twin = mapOf("com.drivehub.diauto.mg4" to "app.mg4aa", "app.mg4aa" to "com.drivehub.diauto.mg4")[cn.packageName]
        if (twin != null && activity.packageManager.getLaunchIntentForPackage(cn.packageName) == null) {
            activity.packageManager.getLaunchIntentForPackage(twin)?.component?.let { p.dockAaTarget = it.flattenToString(); return it }
        }
        return cn
    }

    private fun open(activity: Activity, item: Item, current: Page) {
        // Carte en plein écran (issue #4) : tout bouton de la barre la ramène à sa taille de widget
        (activity as? app.mg4dash.MainActivity)?.exitMapFullscreen()
        when (item.label) {
            R.string.dock_aa -> {
                // Connexion coupée par un appui long : on le rappelle au lieu d'ouvrir un écran vide
                if (app.mg4dash.system.ConnectionToggle.isOff(activity)) {
                    Toasts.show(activity, activity.getString(R.string.dock_conn_off, connectionName(activity)), long = true); return
                }
                val custom = customAa(activity)
                if (custom != null && custom != Apps.CARPLAY) { Shortcuts.launch(activity, custom); return }
                // Android Auto / CarPlay d'origine : sans téléphone connecté, l'écran reste noir (#41).
                // On ouvre la projection du téléphone en session, ou on prévient qu'il n'y en a pas.
                val fallback = custom ?: Apps.ANDROID_AUTO
                Thread {
                    val s = app.mg4dash.system.Projection.session(activity)
                    activity.runOnUiThread {
                        when {
                            s is app.mg4dash.system.Projection.Session.Read && s.target == null ->
                                app.mg4dash.ui.Toasts.show(activity, R.string.projection_no_phone)
                            else -> {
                                val target = (s as? app.mg4dash.system.Projection.Session.Read)?.target ?: fallback
                                Apps.open(activity, target, activity.getString(if (target == Apps.CARPLAY) R.string.dock_cp else R.string.dock_aa))
                            }
                        }
                    }
                }.start()
                return
            }
            // Toucher : réglages MG4Dash ; appui long : réglages de la voiture
            R.string.dock_settings -> {
                if (current == Page.SETTINGS) return
                activity.startActivity(Intent(activity, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
                if (current != Page.HOME) activity.finish()
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
                return
            }
        }
        val page = item.page ?: return
        if (page == current) return
        val target = when (page) {
            Page.HOME -> MainActivity::class.java
            Page.VEHICLE -> VehicleActivity::class.java
            Page.CLIMATE -> ClimateActivity::class.java
            Page.ENERGY -> EnergyActivity::class.java
            Page.PHONE -> app.mg4dash.pages.PhoneActivity::class.java
            Page.SETTINGS -> SettingsActivity::class.java
        }
        // L'accueil (singleTask) referme les pages au-dessus de lui ; entre deux pages, on
        // remplace la page courante pour ne pas empiler l'historique.
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        if (current != Page.HOME) activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
