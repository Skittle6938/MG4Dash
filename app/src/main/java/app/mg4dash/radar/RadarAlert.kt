package app.mg4dash.radar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.ui.FxSound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The alerts: over the high threshold inside a camera zone, a red flashing banner over every app (Android Auto too,
 * option on by default) and a beep every 2 s while it lasts, even with the sound set to none (the only forced
 * sound). Elsewhere a beep on entering a level, as the sound setting says.
 */
@SuppressLint("StaticFieldLeak")
object RadarAlert {
    private const val TAG = "Radar"
    /** TYPE_BOOT_PROGRESS, as the shortcuts menu: above the system bars and the other apps. */
    private const val TYPE = 2021
    const val BEEP_MS = 2_000L
    private const val FLASH_MS = 450L

    /** Sound setting: none, the strong alerts only, both levels. */
    const val SOUND_NONE = 0
    const val SOUND_STRONG = 1
    const val SOUND_BOTH = 2

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var ctx: Context? = null
    private var banner: View? = null
    private var bannerText: TextView? = null
    private var bannerSub: TextView? = null
    private var lastLevel = Level.NONE
    private var critical = false
    private var flashOn = false

    /** A sound for a level reached ([critical] is beeped apart, every [BEEP_MS]). */
    internal fun beepFor(level: Level, previous: Level, setting: Int): Boolean? = when {
        level == previous || level == Level.NONE -> null
        level == Level.STRONG && setting >= SOUND_STRONG -> true
        level == Level.LIGHT && setting == SOUND_BOTH && previous == Level.NONE -> false
        else -> null
    }

    fun start(context: Context) {
        if (job != null) return
        ctx = context.applicationContext
        job = scope.launch { RadarRepo.state.collect { on(it) } }
    }

    fun stop() {
        job?.cancel(); job = null
        setCritical(false)
        hideBanner()
        lastLevel = Level.NONE
    }

    private fun on(s: RadarRepo.State) {
        val c = ctx ?: return
        val p = Prefs(c)
        if (s.critical) {
            if (!critical) Log.i(TAG, "strong alert in a zone: ${s.speedKmh} for ${s.zone?.limit}")
            setCritical(true)
            if (p.radarBanner) showBanner(c, s) else hideBanner()
        } else {
            setCritical(false)
            hideBanner()
            beepFor(s.level, lastLevel, p.radarSound)?.let { FxSound.alert(c, it) }
        }
        lastLevel = s.level
    }

    private val beep = object : Runnable {
        override fun run() {
            ctx?.let { FxSound.alert(it, true) }
            main.postDelayed(this, BEEP_MS)
        }
    }

    private fun setCritical(on: Boolean) {
        if (on == critical) return
        critical = on
        main.removeCallbacks(beep)
        if (on) main.post(beep)
    }

    private val flash = object : Runnable {
        override fun run() {
            val v = banner ?: return
            flashOn = !flashOn
            (v.background as? GradientDrawable)?.setColor(if (flashOn) 0xF2E00000.toInt() else 0xF2700000.toInt())
            main.postDelayed(this, FLASH_MS)
        }
    }

    private fun showBanner(c: Context, s: RadarRepo.State) {
        val miles = Units.miles(c)
        val unit = Units.speedUnit(c)
        val limit = s.zone?.limit?.let { "${Speeds.toUnit(it.toFloat(), miles)}" } ?: "—"
        val speed = s.speedKmh?.let { "${Speeds.toUnit(it, miles)} $unit" } ?: ""
        val head = c.getString(R.string.rad_banner, RadarCard.kindName(c, s.zone?.kind), limit)
        val sub = c.getString(R.string.rad_banner_sub, speed, RadarCard.distText(c, s.zone?.toEnd ?: 0.0))
        if (banner != null) { bannerText?.text = head; bannerSub?.text = sub; return }
        val d = c.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val v = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(40), dp(14), dp(40), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0xF2E00000.toInt()); setStroke(dp(3), 0xFFFFFFFF.toInt()) }
        }
        bannerText = TextView(c).apply {
            text = head; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f)
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD); gravity = Gravity.CENTER; maxLines = 1
        }.also { v.addView(it) }
        bannerSub = TextView(c).apply {
            text = sub; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); gravity = Gravity.CENTER; maxLines = 1
        }.also { v.addView(it) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(70); windowAnimations = 0; title = "MG4Dash speed camera alert" }
        try {
            (c.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            banner = v
            main.postDelayed(flash, FLASH_MS)
        } catch (e: Throwable) { Log.w(TAG, "alert banner: ${e.message}") }
    }

    private fun hideBanner() {
        val v = banner ?: return
        banner = null; bannerText = null; bannerSub = null
        main.removeCallbacks(flash)
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeViewImmediate(v) } catch (_: Throwable) {}
    }
}
