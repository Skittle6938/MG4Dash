package app.mg4dash.ui

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Apps
import app.mg4dash.system.StatusMonitor

/**
 * Home connection icons (#86), like the stock banner: Bluetooth, Wi-Fi, hotspot, mobile
 * network and USB. A touch opens the detail, with the link to each setting.
 */
class ConnectionRow(private val activity: Activity) : LinearLayout(activity) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun color(res: Int) = Themes.color(activity, res)

    // Phone first, then the head unit's own connections (#86). With its network shown, a frame
    // tells its bars from the car's.
    private val phoneGroup = LinearLayout(activity).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        .also { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, dp(ICON_DP + 4)).apply { leftMargin = dp(GAP_DP) }) }
    private val phoneBattery = BatteryIcon(activity).also { phoneGroup.addView(it, LayoutParams(dp(30), dp(15))) }
    private val phoneSignal = SignalBars(activity).also { phoneGroup.addView(it, LayoutParams(dp(ICON_DP), dp(ICON_DP - 6)).apply { leftMargin = dp(8) }) }
    private val bt = icon(R.drawable.ic_bluetooth, 16)
    private val wifi = WifiArcs(activity).also { addView(it, LayoutParams(dp(28), dp(ICON_DP - 2)).apply { leftMargin = dp(GAP_DP) }) }
    private val hotspot = HotspotIcon(activity).also { addView(it, LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { leftMargin = dp(GAP_DP) }) }
    private val signal = SignalBars(activity).also { addView(it, LayoutParams(dp(ICON_DP + 4), dp(ICON_DP - 3)).apply { leftMargin = dp(GAP_DP) }) }
    private val usbA = UsbPort(activity, "A").also { addView(it, LayoutParams(dp(30), dp(ICON_DP)).apply { leftMargin = dp(GAP_DP) }) }
    private val usbC = UsbPort(activity, "C").also { addView(it, LayoutParams(dp(30), dp(ICON_DP)).apply { leftMargin = dp(GAP_DP) }) }
    private var last: StatusMonitor.Connections? = null
    private var panelClosedAt = 0L
    private var panel: PopupWindow? = null

    private val refresh = object : Runnable {
        override fun run() {
            worker.post {
                val c = try { StatusMonitor.connections(activity) } catch (_: Exception) { null }
                post { if (isAttachedToWindow && c != null) show(c) }
            }
            postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        minimumWidth = dp(60); minimumHeight = dp(ICON_DP)
        setOnLongClickListener { openChooser(); true }
        // The touch that closes the panel from outside also reaches this row: it must not reopen it (#18).
        // Either may come first, as the panel is another window.
        setOnClickListener {
            val open = panel?.takeIf { it.isShowing }
            when {
                open != null -> open.dismiss()
                android.os.SystemClock.uptimeMillis() - panelClosedAt > REOPEN_GUARD_MS -> last?.let { openPanel(it) }
            }
        }
    }

    /** [w]: narrow drawings get a narrower box, so the gaps look equal to the eye. */
    private fun icon(res: Int, w: Int = ICON_DP) = ImageView(activity).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_CROP
        putTint(color(R.color.text_disabled))
        addView(this, LayoutParams(dp(w), dp(ICON_DP)).apply { leftMargin = dp(GAP_DP) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (app.mg4dash.BuildConfig.MOCK) show(StatusMonitor.Connections(true, true, "Phone", true, true, "Home", false, 3, 4, 1, 2, app.mg4dash.system.BtAudio.PhoneIndicators(4, 2, "Operator"), usbC = true))
        else post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun show(c: StatusMonitor.Connections) {
        last = c
        fun tint(v: ImageView, on: Boolean) = v.putTint(color(if (on) R.color.accent_action else R.color.text_disabled))
        tint(bt, c.btConnected)
        usbA.on = c.usbA; usbC.on = c.usbC
        // On with no device: lit less than with one, as the stock icon only shows then (#86)
        hotspot.tint = color(when { !c.hotspotOn -> R.color.text_disabled; c.hotspotClients > 0 -> R.color.accent_action; else -> R.color.text_sub })
        hotspot.clients = c.hotspotClients
        wifi.level = if (c.wifiConnected) c.wifiLevel else -1
        phoneBattery.level = c.phone?.battery ?: 0
        phoneSignal.level = phoneBars(c.phone?.signal)
        applyChoice()
        signal.level = c.signalLevel ?: 0
        signal.label = c.signalMode.takeIf { it >= 2 }?.let { "${it}G" }
    }

    /** Phone signal, from 0 to 5 over Bluetooth, on the same 4 bars as the car's network. */
    private fun phoneBars(signal: Int?): Int = ((signal ?: 0) * StatusMonitor.SIGNAL_MAX + PHONE_LEVELS - 1) / PHONE_LEVELS

    /** Shows the icons chosen by a long press (#86); those with no data stay hidden. */
    private fun applyChoice() {
        val c = last
        val hidden = app.mg4dash.system.Prefs(activity).hiddenConnectionIcons
        fun set(v: View, key: String, hasData: Boolean = true) { v.visibility = if (key in hidden || !hasData) View.GONE else View.VISIBLE }
        set(phoneBattery, KEY_PHONE_BATTERY, c?.phone?.battery != null)
        set(phoneSignal, KEY_PHONE_NET, c?.phone?.signal != null)
        set(bt, KEY_BT); set(wifi, KEY_WIFI); set(hotspot, KEY_HOTSPOT)
        // USB ports: off until asked for (#86), not in the list of hidden icons
        val shownPorts = app.mg4dash.system.Prefs(activity).shownUsbPorts
        usbA.visibility = if (KEY_USB_A in shownPorts) View.VISIBLE else View.GONE
        usbC.visibility = if (KEY_USB_C in shownPorts) View.VISIBLE else View.GONE
        set(signal, KEY_CAR_NET, c?.signalLevel != null)
        val framed = phoneSignal.visibility == View.VISIBLE
        val anyPhone = framed || phoneBattery.visibility == View.VISIBLE
        phoneGroup.visibility = if (anyPhone) View.VISIBLE else View.GONE
        phoneGroup.background = if (framed) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(9).toFloat(); setStroke((1.5f * d).toInt().coerceAtLeast(2), color(R.color.panel_border))
        } else null
        phoneGroup.setPadding(if (framed) dp(8) else 0, 0, if (framed) dp(8) else 0, 0)
        (phoneSignal.layoutParams as LayoutParams).leftMargin = if (phoneBattery.visibility == View.VISIBLE) dp(8) else 0
    }

    /** Long press: one yes / no button per icon. The panel keeps showing everything. */
    private fun openChooser() {
        panel?.dismiss()
        val prefs = app.mg4dash.system.Prefs(activity)
        val box = panelBox()
        val popup = panelWindow(box)
        box.addView(TextView(activity).apply {
            setText(R.string.conn_icons_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(color(R.color.text_value))
            setPadding(dp(8), 0, 0, dp(6))
        })
        listOf(KEY_PHONE_BATTERY to R.string.conn_icon_battery, KEY_PHONE_NET to R.string.conn_icon_net, KEY_BT to R.string.conn_bt, KEY_WIFI to R.string.conn_wifi,
            KEY_HOTSPOT to R.string.conn_hotspot, KEY_CAR_NET to R.string.conn_icon_car_net, KEY_USB_A to R.string.conn_usb_a, KEY_USB_C to R.string.conn_usb_c).forEach { (key, name) ->
            box.addView(LinearLayout(activity).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    setText(name)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setTextColor(color(R.color.text_value))
                }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
                addView(TextView(activity).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    setBackgroundResource(R.drawable.bg_chip)
                    setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.chip_text))
                    fun paint() {
                        val on = if (key in USB_KEYS) key in prefs.shownUsbPorts else key !in prefs.hiddenConnectionIcons
                        setText(if (on) R.string.yes else R.string.no)
                        isSelected = on
                    }
                    paint()
                    setOnClickListener {
                        if (key in USB_KEYS) prefs.shownUsbPorts = prefs.shownUsbPorts.toMutableSet().apply { if (!add(key)) remove(key) }
                        else prefs.hiddenConnectionIcons = prefs.hiddenConnectionIcons.toMutableSet().apply { if (!add(key)) remove(key) }
                        paint(); applyChoice()
                    }
                }, LayoutParams(dp(100), dp(50)))
            }, LayoutParams(-1, dp(58)))
        }
        box.addView(TextView(activity).apply {
            setText(R.string.conn_icons_note)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.text_sub))
            setPadding(dp(8), dp(6), 0, 0)
        })
        place(popup, box)
    }

    private fun panelBox() = LinearLayout(activity).apply {
        orientation = VERTICAL
        // Solid background: the map showed under the panel, too busy for a translucent background
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(color(R.color.panel) or 0xFF000000.toInt())
            cornerRadius = resources.getDimension(R.dimen.radius_panel)
            setStroke(1, color(R.color.panel_border))
        }
        setPadding(dp(16), dp(14), dp(16), dp(16))
        elevation = 12 * d
    }

    private fun panelWindow(box: LinearLayout) = PopupWindow(box, dp(520), LayoutParams.WRAP_CONTENT, false).apply {
        isOutsideTouchable = true
        setOnDismissListener { panelClosedAt = android.os.SystemClock.uptimeMillis(); Fullscreen.apply(activity) }
    }

    private fun place(popup: PopupWindow, box: LinearLayout) {
        panel = popup
        // Never over the icons: below, above, or beside them if it is too tall (Android would slide it
        // over them, and a second tap on the icons then landed on a line of the panel)
        box.measure(MeasureSpec.makeMeasureSpec(popup.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val loc = IntArray(2).also { getLocationOnScreen(it) }
        if (loc[1] + height + dp(4) + box.measuredHeight <= resources.displayMetrics.heightPixels) popup.showAsDropDown(this, width - popup.width, dp(4))
        else if (loc[1] - box.measuredHeight - dp(4) >= 0) popup.showAtLocation(this, Gravity.TOP or Gravity.START, loc[0] + width - popup.width, loc[1] - box.measuredHeight - dp(4))
        else popup.showAtLocation(this, Gravity.TOP or Gravity.START, (loc[0] - popup.width - dp(8)).coerceAtLeast(0),
            (resources.displayMetrics.heightPixels - box.measuredHeight - dp(8)).coerceAtLeast(0))
        Themes.track(popup)
    }

    private fun openPanel(c: StatusMonitor.Connections) {
        val box = panelBox()
        val popup = panelWindow(box)
        box.addView(TextView(activity).apply {
            setText(R.string.conn_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(color(R.color.text_value))
            setPadding(dp(4), 0, 0, dp(6))
        })
        val off = activity.getString(R.string.conn_off)
        val none = activity.getString(R.string.conn_not_connected)
        fun row(iconRes: Int, on: Boolean, name: Int, state: String, action: String?) {
            box.addView(panelRow(iconRes, on, name, state, action) { popup.dismiss() }, LayoutParams(-1, dp(60)).apply { topMargin = dp(4) })
        }
        row(R.drawable.ic_bluetooth, c.btConnected, R.string.conn_bt, if (!c.btOn) off else c.btName ?: none, BLUETOOTH_SETTINGS)
        row(R.drawable.ic_wifi, c.wifiConnected, R.string.conn_wifi, if (!c.wifiOn) off else c.wifiName ?: none, WIFI_SETTINGS)
        // No stock setting opens the hotspot page directly: Android's own page (#86), Wi-Fi settings if it does not open
        row(R.drawable.ic_conn_hotspot, c.hotspotOn, R.string.conn_hotspot, activity.getString(if (c.hotspotOn) R.string.conn_on else R.string.conn_off) +
            (if (c.hotspotOn && c.hotspotClients > 0) " · " + activity.getString(R.string.conn_ap_clients, c.hotspotClients) else ""), TETHER_SETTINGS)
        c.signalLevel?.let { level ->
            val text = if (level == 0) activity.getString(R.string.conn_no_network)
                else "${if (c.signalMode >= 2) "${c.signalMode}G · " else ""}$level/${StatusMonitor.SIGNAL_MAX}"
            box.addView(panelRow(null, level > 0, R.string.conn_signal, text, null) { }.also { r ->
                r.addView(SignalBars(activity).apply { this.level = level }, 0, LayoutParams(dp(28), dp(24)))
            }, LayoutParams(-1, dp(60)).apply { topMargin = dp(4) })
        }
        c.phone?.let { p ->
            box.addView(panelRow(null, true, R.string.conn_phone, p.operator ?: "", PHONE_PAGE) { popup.dismiss() }.also { r ->
                p.battery?.let { b -> r.addView(BatteryIcon(activity).apply { level = b }, 0, LayoutParams(dp(28), dp(14))) }
                p.signal?.let { s -> r.addView(SignalBars(activity).apply { level = phoneBars(s) }, r.childCount - 1, LayoutParams(dp(28), dp(24)).apply { leftMargin = dp(12) }) }
            }, LayoutParams(-1, dp(60)).apply { topMargin = dp(4) })
        }
        row(R.drawable.ic_conn_usb, c.usbA, R.string.conn_usb_a, activity.getString(if (c.usbA) R.string.conn_usb_mounted else R.string.conn_not_connected), null)
        row(R.drawable.ic_conn_usb, c.usbC, R.string.conn_usb_c, activity.getString(if (c.usbC) R.string.conn_usb_mounted else R.string.conn_not_connected), null)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        box.measure(MeasureSpec.makeMeasureSpec(popup.width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        popup.height = box.measuredHeight + dp(6)
        place(popup, box)
    }

    private fun panelRow(iconRes: Int?, on: Boolean, name: Int, state: String, action: String?, close: () -> Unit) = LinearLayout(activity).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        if (iconRes != null) addView(ImageView(activity).apply {
            setImageResource(iconRes)
            setColorFilter(color(if (on) R.color.accent_action else R.color.text_disabled))
        }, LayoutParams(dp(28), dp(28)))
        addView(TextView(activity).apply {
            setText(name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(color(R.color.text_value))
        }, LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        addView(TextView(activity).apply {
            text = state
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color(R.color.text_sub))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END
        }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        if (action != null) {
            addView(TextView(activity).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(color(R.color.text_sub))
            }, LayoutParams(-2, -2).apply { leftMargin = dp(12) })
            setBackgroundResource(R.drawable.bg_chip)
            setOnClickListener { close(); openSettings(action) }
        }
    }

    /** Android's Wi-Fi hotspot page, with its switch (#18); otherwise the page before it. */
    private fun openHotspot() {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        try {
            activity.startActivity(Intent().setClassName("com.android.settings", "com.android.settings.SubSettings")
                .putExtra(":settings:show_fragment", "com.android.settings.wifi.tether.WifiTetherSettings").addFlags(flags))
        } catch (_: Exception) {
            activity.startActivity(Intent().setClassName("com.android.settings", TETHER_SETTINGS).addFlags(flags))
        }
    }

    private fun openSettings(action: String) {
        if (action == PHONE_PAGE) { activity.startActivity(Intent(activity, app.mg4dash.pages.PhoneActivity::class.java)); return }
        try {
            if (action == TETHER_SETTINGS) { openHotspot(); return }
            activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            if (action == TETHER_SETTINGS) openSettings(WIFI_SETTINGS)
            else Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings))
        }
    }

    /** Phone battery (#86): five segments, red on the last one. */
    private class BatteryIcon(context: android.content.Context) : View(context) {
        var level = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        private val on = Themes.color(context, R.color.accent_action)
        private val low = Themes.color(context, R.color.accent_heat)
        private val empty = Themes.color(context, R.color.accent_danger)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            // The phone only sends 0 to 5: 0 (under about 10 %) in red, 1 (under about 30 %) in orange
            val col = when { level <= 0 -> empty; level == 1 -> low; else -> on }
            val tip = width * 0.08f
            val body = width - tip
            val stroke = height * 0.12f
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = col
            canvas.drawRoundRect(stroke / 2, stroke / 2, body - stroke / 2, height - stroke / 2, stroke, stroke, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(body, height * 0.3f, width.toFloat(), height * 0.7f, paint)
            val inset = stroke * 1.6f
            // Continuous level: separate segments are unreadable at this size
            paint.color = off
            canvas.drawRect(inset, inset, body - inset, height - inset, paint)
            paint.color = col
            canvas.drawRect(inset, inset, inset + (body - 2 * inset) * level / PHONE_LEVELS, height - inset, paint)
        }
    }

    /** Wi-Fi strength (#86): a dot and three arcs, lit according to the level; -1 = not connected, all grey. */
    private class WifiArcs(context: android.content.Context) : View(context) {
        var level = -1
            set(v) { if (field != v) { field = v; invalidate() } }
        private val on = Themes.color(context, R.color.accent_action)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        /** Pieces of ic_wifi, the panel's icon (#18): tip, then the two arcs, lit according to the level. */
        private val parts = listOf(
            "M9,17l3,3 3,-3c-1.65,-1.66 -4.34,-1.66 -6,0z",
            "M5,13l2,2c2.76,-2.76 7.24,-2.76 10,0l2,-2C15.14,9.14 8.87,9.14 5,13z",
            "M1,9l2,2c4.97,-4.97 13.03,-4.97 18,0l2,-2C16.93,2.93 7.08,2.93 1,9z",
        ).map { androidx.core.graphics.PathParser.createPathFromPathData(it) }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val s = minOf(width, height) / 24f
            canvas.translate((width - 24 * s) / 2f, (height - 24 * s) / 2f)
            canvas.scale(s, s)
            parts.forEachIndexed { i, part ->
                paint.color = if (level >= i) on else off
                canvas.drawPath(part, paint)
            }
        }
    }

    /** Hotspot (#86): from 2 devices, the symbol moves down and their number sits above it, in the same width. */
    private class HotspotIcon(context: android.content.Context) : View(context) {
        var tint = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        var clients = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        private val symbol = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_conn_hotspot)!!.mutate()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val several = clients > 1
            val down = if (several) (height * 0.2f).toInt() else 0
            symbol.setTint(tint)
            symbol.setBounds(0, down, height, height + down)
            symbol.draw(canvas)
            if (several) {
                paint.color = tint; paint.textSize = height * 0.46f
                canvas.drawText(clients.toString(), width / 2f, height * 0.34f, paint)
            }
        }
    }

    /** One USB port (#86): the USB symbol and its letter, lit when a stick is mounted on it. */
    private class UsbPort(context: android.content.Context, private val letter: String) : View(context) {
        var on = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val lit = Themes.color(context, R.color.accent_action)
        private val off = Themes.color(context, R.color.text_disabled)
        private val symbol = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_conn_usb)!!.mutate()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true; textAlign = android.graphics.Paint.Align.RIGHT }

        override fun onDraw(canvas: android.graphics.Canvas) {
            val col = if (on) lit else off
            symbol.setTint(col)
            symbol.setBounds(0, 0, height, height)
            symbol.draw(canvas)
            paint.color = col; paint.textSize = height * 0.5f
            canvas.drawText(letter, width.toFloat(), height * 0.9f, paint)
        }
    }

    /** Mobile network bars, as on a phone: lit according to the level, the others greyed. */
    private class SignalBars(context: android.content.Context, private val n: Int = StatusMonitor.SIGNAL_MAX) : View(context) {
        var level = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        /** Network type above the short bars, like the stock icon ("4G"). */
        var label: String? = null
            set(v) { if (field != v) { field = v; invalidate() } }
        private val on = Themes.color(context, R.color.accent_action)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            // Above the two short bars, without touching the second one (its top is at half height)
            label?.let {
                paint.color = on; paint.textSize = height * 0.5f; paint.isFakeBoldText = true
                canvas.drawText(it, 0f, height * 0.42f, paint)
            }
            val gap = width * 0.08f
            val w = (width - gap * (n - 1)) / n
            for (i in 0 until n) {
                paint.color = if (i < level) on else off
                val x = i * (w + gap)
                canvas.drawRect(x, height * (n - 1 - i) / n.toFloat(), x + w, height.toFloat(), paint)
            }
        }
    }

    private companion object {
        const val REFRESH_MS = 5_000L
        const val REOPEN_GUARD_MS = 400L
        const val ICON_DP = 26
        const val PHONE_LEVELS = 5
        const val KEY_PHONE_BATTERY = "phone_battery"
        const val KEY_PHONE_NET = "phone_net"
        const val KEY_BT = "bt"
        const val KEY_WIFI = "wifi"
        const val KEY_HOTSPOT = "hotspot"
        const val KEY_CAR_NET = "car_net"
        const val KEY_USB_A = "usb_a"
        const val KEY_USB_C = "usb_c"
        val USB_KEYS = setOf(KEY_USB_A, KEY_USB_C)
        const val PHONE_PAGE = "phone_page"
        const val GAP_DP = 12
        const val BLUETOOTH_SETTINGS = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
        const val WIFI_SETTINGS = android.provider.Settings.ACTION_WIFI_SETTINGS
        const val TETHER_SETTINGS = "com.android.settings.TetherSettings"
        /** Reads off the main thread: car service and storage. */
        val worker by lazy { Handler(HandlerThread("connections").apply { start() }.looper) }
    }
}
