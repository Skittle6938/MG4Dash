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
    private val phoneSignal = SignalBars(activity, PHONE_LEVELS).also { phoneGroup.addView(it, LayoutParams(dp(ICON_DP), dp(ICON_DP - 6)).apply { leftMargin = dp(8) }) }
    private val bt = icon(R.drawable.ic_bluetooth, 16)
    private val wifi = WifiArcs(activity).also { addView(it, LayoutParams(dp(28), dp(ICON_DP - 2)).apply { leftMargin = dp(GAP_DP) }) }
    private val hotspot = icon(R.drawable.ic_conn_hotspot)
    private val signal = SignalBars(activity).also { addView(it, LayoutParams(dp(ICON_DP + 4), dp(ICON_DP - 3)).apply { leftMargin = dp(GAP_DP) }) }
    private val usb = icon(R.drawable.ic_conn_usb, 18)
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
        if (app.mg4dash.BuildConfig.MOCK) show(StatusMonitor.Connections(true, true, "Phone", true, true, "Home", false, 3, 4, 1, 2, app.mg4dash.system.BtAudio.PhoneIndicators(4, 2, "Operator")))
        else post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun show(c: StatusMonitor.Connections) {
        last = c
        fun tint(v: ImageView, on: Boolean) = v.putTint(color(if (on) R.color.accent_action else R.color.text_disabled))
        tint(bt, c.btConnected); tint(hotspot, c.hotspotOn); tint(usb, c.usbCount > 0)
        wifi.level = if (c.wifiConnected) c.wifiLevel else -1
        phoneBattery.level = c.phone?.battery ?: 0
        phoneSignal.level = c.phone?.signal ?: 0
        applyChoice()
        signal.level = c.signalLevel ?: 0
        signal.label = c.signalMode.takeIf { it >= 2 }?.let { "${it}G" }
    }

    /** Shows the icons chosen by a long press (#86); those with no data stay hidden. */
    private fun applyChoice() {
        val c = last
        val hidden = app.mg4dash.system.Prefs(activity).hiddenConnectionIcons
        fun set(v: View, key: String, hasData: Boolean = true) { v.visibility = if (key in hidden || !hasData) View.GONE else View.VISIBLE }
        set(phoneBattery, KEY_PHONE_BATTERY, c?.phone?.battery != null)
        set(phoneSignal, KEY_PHONE_NET, c?.phone?.signal != null)
        set(bt, KEY_BT); set(wifi, KEY_WIFI); set(hotspot, KEY_HOTSPOT); set(usb, KEY_USB)
        set(signal, KEY_CAR_NET, c?.signalLevel != null)
        val framed = phoneSignal.visibility == View.VISIBLE
        val anyPhone = framed || phoneBattery.visibility == View.VISIBLE
        phoneGroup.visibility = if (anyPhone) View.VISIBLE else View.GONE
        phoneGroup.background = if (framed) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(9).toFloat(); setStroke(dp(1), color(R.color.panel_border))
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
            KEY_HOTSPOT to R.string.conn_hotspot, KEY_CAR_NET to R.string.conn_icon_car_net, KEY_USB to R.string.conn_usb).forEach { (key, name) ->
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
                        val on = key !in prefs.hiddenConnectionIcons
                        setText(if (on) R.string.yes else R.string.no)
                        isSelected = on
                    }
                    paint()
                    setOnClickListener {
                        prefs.hiddenConnectionIcons = prefs.hiddenConnectionIcons.toMutableSet().apply { if (!add(key)) remove(key) }
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
        row(R.drawable.ic_conn_hotspot, c.hotspotOn, R.string.conn_hotspot, activity.getString(if (c.hotspotOn) R.string.conn_on else R.string.conn_off), TETHER_SETTINGS)
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
                p.signal?.let { s -> r.addView(SignalBars(activity, PHONE_LEVELS).apply { level = s }, r.childCount - 1, LayoutParams(dp(28), dp(24)).apply { leftMargin = dp(12) }) }
            }, LayoutParams(-1, dp(60)).apply { topMargin = dp(4) })
        }
        row(R.drawable.ic_conn_usb, c.usbCount > 0, R.string.conn_usb, if (c.usbCount > 0) activity.getString(R.string.conn_usb_count, c.usbCount) else none, null)
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

    private fun openSettings(action: String) {
        if (action == PHONE_PAGE) { activity.startActivity(Intent(activity, app.mg4dash.pages.PhoneActivity::class.java)); return }
        try {
            val intent = if (action == TETHER_SETTINGS) Intent().setClassName("com.android.settings", TETHER_SETTINGS) else Intent(action)
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        private val low = Themes.color(context, R.color.accent_danger)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val col = if (level <= 1) low else on
            val tip = width * 0.08f
            val body = width - tip
            val stroke = height * 0.12f
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = stroke; paint.color = col
            canvas.drawRoundRect(stroke / 2, stroke / 2, body - stroke / 2, height - stroke / 2, stroke, stroke, paint)
            paint.style = android.graphics.Paint.Style.FILL
            canvas.drawRect(body, height * 0.3f, width.toFloat(), height * 0.7f, paint)
            val inset = stroke * 1.6f
            val seg = (body - 2 * inset) / PHONE_LEVELS
            for (i in 0 until PHONE_LEVELS) {
                paint.color = if (i < level) col else off
                canvas.drawRect(inset + i * seg + seg * 0.1f, inset, inset + (i + 1) * seg - seg * 0.1f, height - inset, paint)
            }
        }
    }

    /** Wi-Fi strength (#86): a dot and three arcs, lit according to the level; -1 = not connected, all grey. */
    private class WifiArcs(context: android.content.Context) : View(context) {
        var level = -1
            set(v) { if (field != v) { field = v; invalidate() } }
        private val on = Themes.color(context, R.color.accent_action)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
        private val box = android.graphics.RectF()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val cx = width / 2f
            val cy = height * 0.84f
            val step = height * 0.25f
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if (level >= 0) on else off
            canvas.drawCircle(cx, cy, height * 0.08f, paint)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = height * 0.11f
            for (i in 1..3) {
                val r = step * i
                paint.color = if (level >= i) on else off
                box.set(cx - r, cy - r, cx + r, cy + r)
                canvas.drawArc(box, 225f, 90f, false, paint)
            }
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
            label?.let {
                paint.color = on; paint.textSize = height * 0.5f; paint.isFakeBoldText = true
                canvas.drawText(it, 0f, height * 0.4f, paint)
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
        const val KEY_USB = "usb"
        const val PHONE_PAGE = "phone_page"
        const val GAP_DP = 12
        const val BLUETOOTH_SETTINGS = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
        const val WIFI_SETTINGS = android.provider.Settings.ACTION_WIFI_SETTINGS
        const val TETHER_SETTINGS = "com.android.settings.TetherSettings"
        /** Reads off the main thread: car service and storage. */
        val worker by lazy { Handler(HandlerThread("connections").apply { start() }.looper) }
    }
}
