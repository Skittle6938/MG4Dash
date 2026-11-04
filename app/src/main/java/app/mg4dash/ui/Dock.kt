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
 * Bottom dock common to all screens: clock + Home, Vehicle, Climate, Energy,
 * Android Auto, Settings. Move from one page to another without going back through home.
 * Long press on Home: list of apps; on Settings: launcher settings.
 */
object Dock {
    enum class Page { HOME, VEHICLE, CLIMATE, ENERGY, PHONE, SETTINGS, APPS }

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
        // The dock sets its own appearance: identical on home and on all pages.
        dock.orientation = LinearLayout.HORIZONTAL
        dock.setBackgroundResource(R.drawable.bg_panel)
        dock.setPadding(kit.dp(8), kit.dp(6), kit.dp(8), kit.dp(6))
        dock.elevation = kit.dp(6).toFloat()

        val clockCell = kit.column().apply { gravity = Gravity.CENTER; setPadding(kit.dp(12), 0, kit.dp(20), 0) }
        // Left slot: clock or Wi-Fi shortcut, chosen by long press
        val wifi = app.mg4dash.system.Prefs(activity).dockSlot == 1
        clockCell.setOnLongClickListener {
            android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.dock_slot_pick)
                .setItems(arrayOf(activity.getString(R.string.dock_slot_clock), activity.getString(R.string.dock_slot_wifi))) { _, w ->
                    app.mg4dash.system.Prefs(activity).dockSlot = w
                    build(activity, dock, current, simple)
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

        // Simple home: only Home, Phone, Android Auto and Settings, larger.
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
            // Android Auto button: replaceable by another app (long press)
            val custom = if (item.label == R.string.dock_aa) customAa(activity) else null
            val iconView = ImageView(activity)
            cell.addView(iconView.apply {
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
            if (item.label == R.string.dock_vehicle) watchEsc(activity, cell, iconView, selected)
            when (item.label) {
                R.string.dock_home -> cell.setOnLongClickListener { if (current != Page.APPS) activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                R.string.dock_aa -> aaGestures(activity, cell, { open(activity, item, current) }) { build(activity, dock, current, simple) }
                else -> pageGestures(activity, cell, iconView, item, current, selected)
            }
            dock.addView(cell, LinearLayout.LayoutParams(0, -1, 1f).apply { if (index >= 0) leftMargin = kit.dp(6) })
        }
    }

    private fun pkgIcon(activity: Activity, pkg: String): android.graphics.drawable.Drawable? =
        try { activity.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }

    /** Vertical bar on the left chosen in the settings (all screens). */
    fun vertical(context: android.content.Context): Boolean = Prefs(context).verticalDock

    /**
     * Right-hand drive: vertical bar on the right, at full height (the fog light button
     * stays on the left, handled by the car). Enabled when the reading of CAL_DRIVE_HDL_TYPE has been
     * confirmed on a right-hand drive car; "force_rhd" is used for screenshots.
     */
    private const val RHD_ENABLED = true
    fun rightSide(context: android.content.Context): Boolean {
        val sp = Prefs(context).raw
        if (sp.getBoolean("force_rhd", false)) return true
        when (Prefs(context).barSide) { 1 -> return false; 2 -> return true }   // side forced in the settings (#75)
        if (!RHD_ENABLED) return false
        // At startup, the car does not answer yet: the bar went left on a right-hand drive
        // (#75). The last side read is kept and used as long as reading is not possible.
        val rhd = app.mg4dash.car.CarProps.rightHandDrive()
        if (rhd != null) { if (sp.getBoolean("rhd_cached", false) != rhd || !sp.contains("rhd_cached")) sp.edit().putBoolean("rhd_cached", rhd).apply(); return rhd }
        return sp.getBoolean("rhd_cached", false)
    }

    /**
     * Places a screen's dock: at the bottom (horizontal dock) or, if the option is chosen, as a vertical
     * bar on the left. [root] is the screen's column, [dock] its last child.
     * When vertical, all other children move to a column to the right of the bar.
     */
    fun mount(activity: Activity, root: LinearLayout, dock: LinearLayout, current: Page, simple: Boolean = false) {
        if (!vertical(activity)) { build(activity, dock, current, simple); return }
        val kit = Kit(activity)
        val children = (0 until root.childCount).map { root.getChildAt(it) }.filter { it !== dock }
        root.removeAllViews()
        root.orientation = LinearLayout.HORIZONTAL
        root.clipToPadding = false   // the bar overlaps the screen margin a little (#38)
        val bar = LinearLayout(activity)
        val right = rightSide(activity)
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        children.forEach { content.addView(it) }   // each child keeps its parameters (weight, margins)
        // 72 dp, overlapping the screen margin by 4 dp: centred under MG's fog light
        // button (x 20 → 76 dp, y ≈ 14 → 70 dp), with 8 dp on the left, right and above (#38)
        val barDp = if (Prefs(activity).wideBar) BAR_DP * 2 else BAR_DP   // "wide bar" setting (#67)
        if (!right) root.addView(bar, LinearLayout.LayoutParams(kit.dp(barDp), LinearLayout.LayoutParams.MATCH_PARENT).apply { leftMargin = -kit.dp(4); topMargin = -kit.dp(6) })
        root.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
            if (right) rightMargin = kit.dp(12) else leftMargin = kit.dp(12)
        })
        if (right) root.addView(bar, LinearLayout.LayoutParams(kit.dp(barDp), LinearLayout.LayoutParams.MATCH_PARENT).apply { rightMargin = -kit.dp(4) })
        buildVertical(activity, bar, current)
    }

    /**
     * Vertical bar on the left (option; issue #6): icons only, under MG's fog light
     * button which overlays the top left and so seems to be part of it.
     * Order: Settings, Android Auto / CarPlay (grey), Phone, Energy, Vehicle, Home.
     * Settings: touch = launcher settings, long press = car settings,
     * double toucher = Wi-Fi.
     */
    private const val BAR_DP = 72

    /**
     * Discreet Wi-Fi indicator next to the Vehicle icon (#67): shown when the head unit is linked to an
     * access point (phone hotspot…), reread every 5 s while the bar is shown.
     */
    private fun wifiBadge(activity: Activity, cell: android.widget.FrameLayout, iconDp: Int) {
        val d = activity.resources.displayMetrics.density
        val badge = ImageView(activity).apply {
            setImageResource(R.drawable.ic_wifi); setColorFilter(Themes.color(activity, R.color.accent_action)); visibility = android.view.View.GONE
        }
        val s = (iconDp * 0.5f * d).toInt()
        cell.addView(badge, android.widget.FrameLayout.LayoutParams(s, s, android.view.Gravity.CENTER).apply {
            // Offset from the slot's centre: 0.75 × icon overflowed the slot on the right and cropped the Wi-Fi (#38)
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
        // Place of the fog light button (overlaid by MG, it cannot be drawn by us);
        // on the right (right-hand drive), it stays on the left: the bar takes the full height
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
            // Android Auto and CarPlay: a single neutral pictogram; another app keeps its icon (grey)
            val projection = item.label == R.string.dock_aa && (custom == null || custom.packageName == Apps.CARPLAY.packageName ||
                custom.packageName.startsWith("com.allgo.app.androidauto"))
            val appIcon = if (projection) null else custom?.let { Shortcuts.appIcon(activity, it) }
            if (projection) {
                icon.setImageResource(R.drawable.ic_projection)
                icon.setColorFilter(color(if (selected) R.color.accent_action else R.color.text_sub))
            } else if (appIcon != null) {
                // App icon in greyscale, to match the bar's other icons
                icon.setImageDrawable(appIcon.mutate().apply {
                    colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) })
                })
            } else {
                icon.setImageResource(item.icon)
                icon.setColorFilter(color(if (selected) R.color.accent_action else R.color.text_sub))
            }
            // Vehicle and Home are flatter drawings than the others (and Android Auto taller): resized for
            // an equal visual gap between icons, and recentred (car drawn from y=5 to 21, house from 3 to 20
            // in their 24 frame) so the drawing, not only its frame, is in the centre.
            val size = when { item.label == R.string.dock_vehicle -> 38; item.label == R.string.dock_home -> 37; projection -> 32; else -> 34 }
                .let { if (Prefs(activity).wideBar) it * 3 / 2 else it }
            icon.translationY = when (item.label) {
                R.string.dock_vehicle -> -kit.dp(size) / 24f
                R.string.dock_home -> kit.dp(size) / 48f
                else -> 0f
            }
            if (item.label == R.string.dock_aa) markOff(activity, icon)
            cell.addView(icon, android.widget.FrameLayout.LayoutParams(kit.dp(size), kit.dp(size), android.view.Gravity.CENTER))
            // Replaced by the weather's connection icons, absent from simple home (#86)
            val prefs = app.mg4dash.system.Prefs(activity)
            if (item.label == R.string.dock_vehicle && (!prefs.connectionIcons || prefs.homeStyle == 1)) wifiBadge(activity, cell, size)
            if (item.label == R.string.dock_vehicle) watchEsc(activity, cell, icon, selected)
            if (!pageGestures(activity, cell, icon, item, current, selected)) {
                cell.setOnClickListener { open(activity, item, current) }
                if (item.label == R.string.dock_home) cell.setOnLongClickListener { if (current != Page.APPS) activity.startActivity(Intent(activity, AppsActivity::class.java)); true }
                // Long press: choice of app; double touch: cuts or restores the phone connection
                if (item.label == R.string.dock_aa) aaGestures(activity, cell, { open(activity, item, current) }) { buildVertical(activity, bar, current) }
            }
            bar.addView(cell, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = kit.dp(4) })
        }
    }

    /** Vehicle icon: red, skidding, while ESC is off. */
    private fun tintVehicle(activity: Activity, icon: ImageView, selected: Boolean) {
        fun color(r: Int) = app.mg4dash.ui.Themes.color(activity, r)
        val escOff = app.mg4dash.car.CarBridge.escActive() == false
        icon.setColorFilter(color(when { escOff -> R.color.accent_danger; selected -> R.color.accent_action; else -> R.color.text_sub }))
        val res = if (escOff) R.drawable.ic_car_drift else R.drawable.ic_car
        if (icon.tag != res) { icon.setImageResource(res); icon.tag = res }
    }

    /** ESC state reread regularly: it can also change from the MG menu. */
    private fun watchEsc(activity: Activity, cell: android.view.View, icon: ImageView, selected: Boolean) {
        tintVehicle(activity, icon, selected)
        val refresh = object : Runnable {
            override fun run() { if (cell.isAttachedToWindow) { tintVehicle(activity, icon, selected); cell.postDelayed(this, 3000) } }
        }
        cell.postDelayed(refresh, 3000)
    }

    /**
     * Gestures of the Settings, Energy and Vehicle buttons, the same on both bars (#79).
     * Returns false if this button has none: it then keeps its simple touch.
     */
    private fun pageGestures(activity: Activity, cell: android.view.View, icon: ImageView, item: Item, current: Page, selected: Boolean): Boolean {
        val listener: android.view.GestureDetector.SimpleOnGestureListener = when (item.label) {
            // Simple touch (after the double touch delay) / double touch / long press
            R.string.dock_settings -> object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(activity, item, current); return true }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean { openWifi(activity); return true }
                override fun onLongPress(e: android.view.MotionEvent) { Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings)) }
                override fun onDown(e: android.view.MotionEvent) = true
            }
            // Double touch: screen off; touch: Energy page
            R.string.dock_energy -> object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean { open(activity, item, current); return true }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    // Same command as the stock launcher; a touch on the black screen turns it back on
                    activity.startActivity(Intent(activity, ScreenOffActivity::class.java)); return true
                }
                // Long press: switch the car off (#12), without confirmation, but never while driving
                override fun onLongPress(e: android.view.MotionEvent) {
                    if (!app.mg4dash.car.CarBridge.powerOffCar())
                        app.mg4dash.ui.Toasts.show(activity, R.string.power_off_driving)
                }
                override fun onDown(e: android.view.MotionEvent) = true
            }
            // Touch: Vehicle page; double touch: doors locked / unlocked (useful from
            // the back, issue #12); long press: ESC (no confirmation, the car signals it itself)
            R.string.dock_vehicle -> object : android.view.GestureDetector.SimpleOnGestureListener() {
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
                    cell.postDelayed({ tintVehicle(activity, icon, selected) }, 800)
                }
                override fun onDown(e: android.view.MotionEvent) = true
            }
            else -> return false
        }
        val gd = android.view.GestureDetector(activity, listener)
        cell.setOnTouchListener { v, ev -> v.isPressed = ev.actionMasked == android.view.MotionEvent.ACTION_DOWN; gd.onTouchEvent(ev) }
        return true
    }

    /** Android Wi-Fi settings, otherwise the car's settings. */
    fun openWifi(activity: Activity) {
        try {
            activity.startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings))
        }
    }

    /**
     * Android Auto button: touch = open, long press = choose Android Auto, CarPlay or another app,
     * double touch = cut or restore the phone connection.
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

    /** Like the stock launcher: the button opens Android Auto, CarPlay or any app. */
    private fun pickAaTarget(activity: Activity, onDone: () -> Unit) {
        val choices = arrayOf(activity.getString(R.string.dock_aa_stock), activity.getString(R.string.dock_cp_stock), activity.getString(R.string.dock_other_app))
        // App change: the one that was cut is first re-enabled
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

    /** Double touch: cuts or restores the phone connection (the button's app is disabled). */
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

    /** Connection cut: red icon, crossed out. */
    private fun markOff(activity: Activity, icon: ImageView) {
        if (!app.mg4dash.system.ConnectionToggle.isOff(activity)) return
        val red = ContextCompat.getColor(activity, R.color.accent_danger)
        icon.setColorFilter(red)
        icon.foreground = StrikeDrawable(red, activity.resources.displayMetrics.density * 3f)
    }

    /** Diagonal line over an icon. */
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
        // DiAuto (com.drivehub.diauto.mg4) once had another name (app.mg4aa): the button follows whichever
        // of the two is installed if the other was uninstalled
        val twin = mapOf("com.drivehub.diauto.mg4" to "app.mg4aa", "app.mg4aa" to "com.drivehub.diauto.mg4")[cn.packageName]
        if (twin != null && activity.packageManager.getLaunchIntentForPackage(cn.packageName) == null) {
            activity.packageManager.getLaunchIntentForPackage(twin)?.component?.let { p.dockAaTarget = it.flattenToString(); return it }
        }
        return cn
    }

    /** Opens the app of the Android Auto button (also the two finger pull from the top of the screen). */
    fun openProjection(activity: Activity) {
        val custom = customAa(activity)
        if (custom != null && custom != Apps.CARPLAY) { Shortcuts.launch(activity, custom); return }
        // Stock Android Auto / CarPlay: with no phone connected, the screen stays black (#41).
        // Open the projection of the phone in session, or warn there is none.
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
    }

    private fun open(activity: Activity, item: Item, current: Page) {
        // Full screen map (issue #4): any bar button brings it back to its widget size
        (activity as? app.mg4dash.MainActivity)?.exitMapFullscreen()
        when (item.label) {
            R.string.dock_aa -> {
                // Connection cut by a long press: remind it instead of opening an empty screen
                if (app.mg4dash.system.ConnectionToggle.isOff(activity)) {
                    Toasts.show(activity, activity.getString(R.string.dock_conn_off, connectionName(activity)), long = true); return
                }
                openProjection(activity)
                return
            }
            // Touch: MG4Dash settings; long press: car settings
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
            Page.APPS -> return
        }
        // Home (singleTask) closes the pages above it; between two pages,
        // replace the current page so as not to stack the history.
        activity.startActivity(Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        if (current != Page.HOME) activity.finish()
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(0, 0)
    }
}
