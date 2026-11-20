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
 * The alerts. Over the high threshold inside a camera zone, a red flashing banner over every app (Android Auto too,
 * option on by default). Four kinds of sound, each with its own sound, volume and repeat (Marc, 17/11): a camera zone
 * coming (once), a little over the limit, well over it, well over it inside a camera zone.
 */
@SuppressLint("StaticFieldLeak")
object RadarAlert {
    private const val TAG = "Radar"
    /** TYPE_BOOT_PROGRESS, as the shortcuts menu: above the system bars and the other apps. */
    private const val TYPE = 2021
    private const val FLASH_MS = 450L
    /** Repeats are checked this often. */
    private const val TICK_MS = 1_000L

    /** Sounds offered: none, a chime, a beep, a loud beep. */
    enum class Sound(val key: String) { NONE("none"), CHIME("chime"), BEEP("beep"), LOUD("loud") }

    /** Kinds of alert, with their defaults: sound, volume (%), repeat (s, 0 = never). */
    enum class Kind(val key: String, val sound: Sound, val volume: Int, val repeat: Int) {
        /** A camera zone coming: once, on entering it. */
        APPROACH("approach", Sound.CHIME, 60, 0),
        LIGHT("light", Sound.NONE, 30, 0),
        STRONG("strong", Sound.BEEP, 70, 10),
        /** Well over the limit inside a camera zone, with the red banner. */
        ZONE("zone", Sound.LOUD, 100, 5),
    }

    /** Repeats offered (s): never, 5 s, 10 s, 1 min, 5 min. */
    val REPEATS = listOf(0, 5, 10, 60, 300)

    /** The over-the-limit alert now (the camera zone coming is apart), null when none. */
    internal fun kindOf(level: Level, critical: Boolean): Kind? = when {
        critical -> Kind.ZONE
        level == Level.STRONG -> Kind.STRONG
        level == Level.LIGHT -> Kind.LIGHT
        else -> null
    }

    /**
     * When to sound an alert that lasts: at once when it starts (or changes), then every its repeat while it lasts;
     * never again with no repeat. Driven every [TICK_MS].
     */
    internal class Repeater {
        private var kind: Kind? = null
        private var lastAt = 0L

        /** The kind to sound now, null for none. */
        fun tick(k: Kind?, now: Long, repeatOf: (Kind) -> Int): Kind? {
            if (k != kind) { kind = k; lastAt = now; return k }
            if (k == null) return null
            val every = repeatOf(k)
            if (every > 0 && now - lastAt >= every * 1000L) { lastAt = now; return k }
            return null
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var ctx: Context? = null
    private var banner: View? = null
    private var bannerText: TextView? = null
    private var bannerSub: TextView? = null
    private var critical = false
    private var flashOn = false
    private var current: Kind? = null
    private var zoneCam: Long? = null
    private val repeater = Repeater()

    fun start(context: Context) {
        if (job != null) return
        ctx = context.applicationContext
        job = scope.launch { RadarRepo.state.collect { on(it) } }
        main.post(tick)
    }

    fun stop() {
        job?.cancel(); job = null
        main.removeCallbacks(tick)
        critical = false; current = null; zoneCam = null
        hideBanner()
    }

    /** Plays the sound of [kind] as set (the settings' ▶ too); nothing with no sound. */
    fun play(c: Context, kind: Kind) {
        val p = Prefs(c)
        val res = when (p.radarSoundOf(kind)) {
            Sound.NONE -> return
            Sound.CHIME -> app.mg4dash.R.raw.fx_radar_chime
            Sound.BEEP -> app.mg4dash.R.raw.fx_radar_light
            Sound.LOUD -> app.mg4dash.R.raw.fx_radar_strong
        }
        FxSound.alertRaw(c, res, p.radarVolume(kind))
    }

    private fun on(s: RadarRepo.State) {
        val c = ctx ?: return
        val p = Prefs(c)
        if (s.critical) {
            if (!critical) Log.i(TAG, "strong alert in a zone: ${s.speedKmh} for ${s.zone?.limit}")
            critical = true
            if (p.radarBanner) showBanner(app.mg4dash.ui.BigDisplay.wrap(c), s) else hideBanner()
        } else {
            critical = false
            hideBanner()
        }
        // A camera zone entered: its chime, once
        val cam = s.zone?.cam
        if (cam != null && cam != zoneCam) play(c, Kind.APPROACH)
        zoneCam = cam
        current = kindOf(s.level, s.critical)
        sound()
    }

    private fun sound() {
        val c = ctx ?: return
        val p = Prefs(c)
        repeater.tick(current, android.os.SystemClock.elapsedRealtime()) { p.radarRepeat(it) }?.let { play(c, it) }
    }

    private val tick = object : Runnable {
        override fun run() { sound(); main.postDelayed(this, TICK_MS) }
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
