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
 * Icônes de connexion de l'accueil (#86), comme le bandeau d'origine : Bluetooth, Wi-Fi, point d'accès, réseau
 * mobile et USB. Un toucher ouvre le détail, avec le lien vers chaque réglage.
 */
class ConnectionRow(private val activity: Activity) : LinearLayout(activity) {
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun color(res: Int) = Themes.color(activity, res)

    private val bt = icon(R.drawable.ic_bluetooth)
    private val wifi = icon(R.drawable.ic_wifi)
    private val hotspot = icon(R.drawable.ic_conn_hotspot)
    private val signal = SignalBars(activity).also { addView(it, LayoutParams(dp(26), dp(22)).apply { leftMargin = dp(12) }) }
    private val usb = icon(R.drawable.ic_conn_usb)
    private var last: StatusMonitor.Connections? = null

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
        setOnClickListener { last?.let { openPanel(it) } }
    }

    private fun icon(res: Int) = ImageView(activity).apply {
        setImageResource(res)
        putTint(color(R.color.text_disabled))
        addView(this, LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(12) })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (app.mg4dash.BuildConfig.MOCK) show(StatusMonitor.Connections(true, true, "Phone", true, true, "Home", false, 3, 4, 1))
        else post(refresh)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(refresh)
        super.onDetachedFromWindow()
    }

    private fun show(c: StatusMonitor.Connections) {
        last = c
        fun tint(v: ImageView, on: Boolean) = v.putTint(color(if (on) R.color.accent_action else R.color.text_disabled))
        tint(bt, c.btConnected); tint(wifi, c.wifiConnected); tint(hotspot, c.hotspotOn); tint(usb, c.usbCount > 0)
        signal.level = c.signalLevel ?: 0
        signal.visibility = if (c.signalLevel == null) View.GONE else View.VISIBLE
    }

    private fun openPanel(c: StatusMonitor.Connections) {
        val box = LinearLayout(activity).apply {
            orientation = VERTICAL
            // Fond plein : la carte se devinait sous le panneau, trop chargé pour un fond translucide
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color(R.color.panel) or 0xFF000000.toInt())
                cornerRadius = resources.getDimension(R.dimen.radius_panel)
                setStroke(1, color(R.color.panel_border))
            }
            setPadding(dp(16), dp(14), dp(16), dp(16))
            elevation = 12 * d
        }
        val popup = PopupWindow(box, dp(520), LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
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
        // La voiture n'a pas de page de réglage propre au point d'accès : il se règle avec le Wi-Fi
        row(R.drawable.ic_conn_hotspot, c.hotspotOn, R.string.conn_hotspot, activity.getString(if (c.hotspotOn) R.string.conn_on else R.string.conn_off), WIFI_SETTINGS)
        c.signalLevel?.let { level ->
            val text = if (level == 0) activity.getString(R.string.conn_no_network)
                else "${if (c.signalMode >= 2) "${c.signalMode}G · " else ""}$level/${StatusMonitor.SIGNAL_MAX}"
            box.addView(panelRow(null, level > 0, R.string.conn_signal, text, null) { }.also { r ->
                r.addView(SignalBars(activity).apply { this.level = level }, 0, LayoutParams(dp(28), dp(24)))
            }, LayoutParams(-1, dp(60)).apply { topMargin = dp(4) })
        }
        row(R.drawable.ic_conn_usb, c.usbCount > 0, R.string.conn_usb, if (c.usbCount > 0) activity.getString(R.string.conn_usb_count, c.usbCount) else none, null)
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        box.measure(MeasureSpec.makeMeasureSpec(popup.width, MeasureSpec.EXACTLY), MeasureSpec.UNSPECIFIED)
        popup.height = box.measuredHeight + dp(6)
        popup.showAsDropDown(this, width - popup.width, dp(4)); Themes.track(popup)
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
        try {
            activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Apps.open(activity, Apps.SETTINGS, activity.getString(R.string.dock_settings))
        }
    }

    /** Barres du réseau mobile, comme sur un téléphone : allumées selon le niveau, les autres grisées. */
    private class SignalBars(context: android.content.Context) : View(context) {
        var level = 0
            set(v) { if (field != v) { field = v; invalidate() } }
        private val on = Themes.color(context, R.color.accent_action)
        private val off = Themes.color(context, R.color.text_disabled)
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            val n = StatusMonitor.SIGNAL_MAX
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
        const val BLUETOOTH_SETTINGS = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
        const val WIFI_SETTINGS = android.provider.Settings.ACTION_WIFI_SETTINGS
        /** Lectures hors du fil principal : service de la voiture et stockage. */
        val worker by lazy { Handler(HandlerThread("connections").apply { start() }.looper) }
    }
}
