package app.mg4dash.ui

import app.mg4dash.system.Maintenance
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.HomeSwitcher
import app.mg4dash.system.Log

/** Maintenance mode gesture: three fingers from the top of the screen, and the touch handed back to the car. */
internal object ValetGesture {
    /** A finger alone and still this long at the very top: it is the car's (its pull-down, its bar). */
    const val WAIT_MS = 150L
    /** Slide of the three fingers that brings the banner, in dp. */
    private const val PULL_DP = 40

    fun isThreeFingerPull(dy: Float, pointers: Int, density: Float): Boolean = pointers >= 3 && dy >= PULL_DP * density

    fun event(downTime: Long, time: Long, action: Int, px: Float, py: Float, deviceId: Int, source: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = px; y = py; pressure = 1f; size = 1f })
        return MotionEvent.obtain(downTime, time, action, 1, props, coords, 0, 0, 1f, 1f, deviceId, 0,
            if (source == 0) InputDevice.SOURCE_TOUCHSCREEN else source, 0)
    }

    /** Through the input manager (system uid: INJECT_EVENTS), asynchronously. */
    fun inject(context: Context, ev: MotionEvent) {
        try {
            val im = context.getSystemService(Context.INPUT_SERVICE)
            im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType).invoke(im, ev, 0)
        } catch (e: Throwable) { Log.w("Valet", "touch given back to the car: ${e.cause?.message ?: e.message}") }
        finally { ev.recycle() }
    }
}

/**
 * Maintenance mode (Marc, 14/11, Discussion #111: "how do I get back to MG4Dash?"): MG4Dash is not in the SAIC app
 * list. A three finger slide from the top of the screen brings this small banner, over the SAIC home or any app:
 * Later, or MG4Dash back as the home screen in one tap. A touch beside it closes it.
 */
@SuppressLint("StaticFieldLeak")
internal object ValetBanner {
    private const val TAG = "Valet"
    /** TYPE_BOOT_PROGRESS, as the shortcuts menu: above the system bars. */
    private const val TYPE = 2021
    private val main = Handler(Looper.getMainLooper())
    private var shown: View? = null

    fun show(context: Context) = main.post {
        if (shown != null) return@post
        val app = context.applicationContext
        val c = Themes.themed(app)
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun col(res: Int) = Themes.color(c, res)
        val accent = col(R.color.accent_action)

        val root = FrameLayout(c).apply { setBackgroundColor(0x73000000) }
        val banner = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(36), 0, dp(36), 0)
            background = GradientDrawable().apply {
                setColor(0xF712161F.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(30).toFloat(), dp(30).toFloat(), dp(30).toFloat(), dp(30).toFloat())
                setStroke(1, col(R.color.panel_border))
            }
            isClickable = true
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(c).apply {
            text = c.getString(R.string.valet_banner_title); setTextColor(col(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1
        })
        texts.addView(TextView(c).apply {
            text = c.getString(R.string.valet_banner_sub); setTextColor(col(R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        banner.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))

        fun button(label: Int, filled: Boolean, onClick: () -> Unit) = TextView(c).apply {
            text = c.getString(label); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) 0xFFFFFFFF.toInt() else col(R.color.text_value))
            setPadding(dp(28), 0, dp(28), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                if (filled) setColor(accent) else { setColor(col(R.color.button_idle)); setStroke(1, col(R.color.panel_border)) }
            }
            setOnClickListener { onClick() }
        }
        banner.addView(button(R.string.upd_later, false) { close() }, LinearLayout.LayoutParams(-2, dp(68)).apply { leftMargin = dp(24) })
        banner.addView(button(R.string.valet_banner_go, true) {
            close()
            // Back as the home, and restarted in full (only its gesture ran in maintenance mode)
            ShortcutsPanel.GlobalStrip.update(app)
            val ok = Maintenance.backToDash(app)
            Log.i(TAG, "MG4Dash back as home from the banner → $ok")
        }, LinearLayout.LayoutParams(-2, dp(68)).apply { leftMargin = dp(20) })

        val width = minOf(dp(1140), c.resources.displayMetrics.widthPixels - dp(32))
        root.addView(banner, FrameLayout.LayoutParams(width, dp(150), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        // Beside the banner: closes it
        root.setOnClickListener { close() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; windowAnimations = 0; title = "MG4Dash maintenance banner" }
        try {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, lp)
            shown = root
            banner.translationY = -dp(160).toFloat(); root.alpha = 0f
            root.animate().alpha(1f).setDuration(180).start()
            banner.animate().translationY(0f).setDuration(240).start()
            Log.i(TAG, "maintenance banner shown")
        } catch (e: Throwable) { Log.w(TAG, "maintenance banner: ${e.message}") }
    }

    fun close() {
        val v = shown ?: return
        shown = null
        try { (v.context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Throwable) {}
    }
}
