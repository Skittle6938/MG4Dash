package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import android.util.TypedValue
import androidx.core.content.ContextCompat
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Themes: background and button colour, chosen separately (5 × 5).
 * Resources use attributes (?attr/mgPanel…); the chosen styles are overlaid here on
 * each screen's theme, before it shows. Colours "with a meaning" (battery,
 * hot, cold, alert) do not change.
 *
 * The choice is also published in Settings.Global ("mg4dash_theme" = "background,buttons") so that
 * Radio Dash, running in the SAIC radio app, follows the same theme.
 */
object Themes {
    val BACKGROUNDS = listOf(R.style.MgBg_Night, R.style.MgBg_Black, R.style.MgBg_Charcoal, R.style.MgBg_Forest, R.style.MgBg_Burgundy)
    val ACCENTS = listOf(R.style.MgAccent_Blue, R.style.MgAccent_Grey, R.style.MgAccent_Red, R.style.MgAccent_Green, R.style.MgAccent_Orange)
    val BACKGROUNDS_LIGHT = listOf(R.style.MgBgLight_Night, R.style.MgBgLight_Black, R.style.MgBgLight_Charcoal, R.style.MgBgLight_Forest, R.style.MgBgLight_Burgundy)
    val ACCENTS_LIGHT = listOf(R.style.MgAccentLight_Blue, R.style.MgAccentLight_Grey, R.style.MgAccentLight_Red, R.style.MgAccentLight_Green, R.style.MgAccentLight_Orange)

    /**
     * Light mode? Setting: 0 = auto, 1 = dark, 2 = light. In auto, follow the car's day/night
     * mode: Android's night mode (driven by the car) or PMS_SYSTEM_DAY_NIGHT_MODE (3 = night,
     * found on the car: goes to 3 when the lights come on).
     */
    fun isLight(context: Context): Boolean = when (Prefs(context).themeMode) {
        1 -> false
        2 -> true
        else -> !isNight(context)
    }

    /**
     * Stabilised day / night: the change is only kept if the lights stay in the new state
     * for at least [HOLD_MS] (a bridge or a short tunnel no longer switches the screen).
     */
    fun isNight(context: Context): Boolean {
        // At startup, as long as the car does not answer yet, keep the last known state:
        // otherwise home was built light then switched to dark a few seconds
        // later (and the map widget's app, chased off by the rebuild, went full screen)
        val sp = context.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)
        if (!app.mg4dash.car.CarProps.ready && !app.mg4dash.BuildConfig.MOCK) {
            // Android's night mode already given by the car: readable at once, without waiting (#64)
            val ui = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (ui == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true
            // Always dark until the lights are read (#64, idea from mquiambao00): a night
            // start no longer dazzles; by day, go light from the first read
            return stableNight ?: true
        }
        val raw = rawNight(context)
        // First read of the lights since startup: applied at once, without the anti-tunnel delay.
        // Otherwise a night start after a day stop stayed light for several seconds (#64).
        if (stableNight == null) {
            stableNight = raw; pendingSince = 0L
            if (sp.getBoolean("last_night", !raw) != raw) sp.edit().putBoolean("last_night", raw).apply()
            return raw
        }
        // "Immediate transition" setting: no wait, the lights state is followed at once
        if (!sp.getBoolean("theme_smooth", true)) {
            if (stableNight != raw) { stableNight = raw; sp.edit().putBoolean("last_night", raw).apply() }
            pendingSince = 0L; return raw
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val stable = stableNight ?: sp.getBoolean("last_night", raw).also { stableNight = it }
        if (raw == stable) { pendingSince = 0L; return stable }
        if (pendingSince == 0L) { pendingSince = now; return stable }
        if (now - pendingSince < HOLD_MS) return stable
        stableNight = raw; pendingSince = 0L
        sp.edit().putBoolean("last_night", raw).apply()
        return raw
    }

    private const val HOLD_MS = 5_000L
    @Volatile private var stableNight: Boolean? = null
    @Volatile private var pendingSince = 0L

    private fun rawNight(context: Context): Boolean {
        val ui = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (ui == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true
        // Option: night after sunset, even with side lights off (#64)
        if (Prefs(context).nightBySun && app.mg4dash.system.SunTimes.isNight(context)) return true
        // Found on the car: lights on → VEH_SIDE_LGHT = 3 and PMS_SYSTEM_DAY_NIGHT_MODE = 3 (area 0x1000000)
        return try {
            val side = app.mg4dash.car.CarProps.readAny(557880099)
            (side.isNotEmpty() && side != "0" && side != "false") ||
                app.mg4dash.car.CarProps.readIntArea(289442317, 0x1000000) == 3
        } catch (_: Exception) { false }
    }

    /** Colours of the selector's dots (same order as the lists). */
    val BACKGROUND_SWATCHES = intArrayOf(0xFF141B29.toInt(), 0xFF0E0E10.toInt(), 0xFF2B2F35.toInt(), 0xFF16271F.toInt(), 0xFF29161C.toInt())
    val ACCENT_SWATCHES = intArrayOf(0xFF2979FF.toInt(), 0xFF8E99A8.toInt(), 0xFFE53935.toInt(), 0xFF00C853.toInt(), 0xFFFF8F00.toInt())

    private const val GLOBAL_KEY = "mg4dash_theme"

    /** Signature of the current theme: used to know if a screen must redraw. */
    fun signature(context: Context): Int = Prefs(context).let { it.themeBackground * 10 + it.themeAccent + (if (isLight(context)) 100 else 0) + (if (it.verticalDock) 1000 else 0) + (if (it.panelFade) 0 else 10000) + (if (it.wideBar) 100000 else 0) }

    /** A context with MG4Dash's theme (background and buttons chosen), for windows outside the screens. */
    fun themed(context: Context): Context {
        val p = Prefs(context)
        val light = isLight(context)
        val bgs = if (light) BACKGROUNDS_LIGHT else BACKGROUNDS
        val accs = if (light) ACCENTS_LIGHT else ACCENTS
        return android.view.ContextThemeWrapper(context.applicationContext, R.style.Theme_MG4Launcher).apply {
            theme.applyStyle(bgs.getOrElse(p.themeBackground) { bgs[0] }, true)
            theme.applyStyle(accs.getOrElse(p.themeAccent) { accs[0] }, true)
        }
    }

    /** To call in onCreate, before setContentView; [dark] forces the dark theme. */
    fun apply(activity: Activity, dark: Boolean = false) {
        val p = Prefs(activity)
        val light = !dark && isLight(activity)
        val bgs = if (light) BACKGROUNDS_LIGHT else BACKGROUNDS
        val accs = if (light) ACCENTS_LIGHT else ACCENTS
        activity.theme.applyStyle(bgs.getOrElse(p.themeBackground) { bgs[0] }, true)
        activity.theme.applyStyle(accs.getOrElse(p.themeAccent) { accs[0] }, true)
        fitHeight(activity)
        fadeFromPrevious(activity)
    }

    private const val FADE_MS = 2_000L
    /** Window placed above everything during recreation (keeps the old image, with no black). */
    private var fadeWindow: android.widget.ImageView? = null

    /**
     * Changes theme (day / night…) with a fade. The image of the current screen is placed in a small
     * system window above everything, which survives the activity's recreation: while
     * the screen rebuilds (Android's short black moment), the old image is still seen,
     * then it fades out onto the new screen.
     */
    /** Menus and confirmations placed by the app (floating windows), followed without retaining them. */
    private val popups = java.util.WeakHashMap<android.widget.PopupWindow, Boolean>()
    fun track(p: android.widget.PopupWindow) { popups[p] = true }
    private var lastOpen = false

    /**
     * A window is open over the screen (dialog, which takes focus, or the app's menu /
     * confirmation): the automatic theme change waits for it to close,
     * otherwise the screen's recreation made it disappear.
     */
    fun popupOpen(activity: Activity): Boolean {
        if (popups.keys.any { it.isShowing }) return true
        // The app's dialogs (APPLICATION type) and attached windows (sub-windows 1000-1999)
        return try {
            val g = Class.forName("android.view.WindowManagerGlobal")
            val inst = g.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = g.getDeclaredField("mViews").apply { isAccessible = true }.get(inst) as List<android.view.View>
            @Suppress("UNCHECKED_CAST")
            val params = g.getDeclaredField("mParams").apply { isAccessible = true }.get(inst) as List<android.view.WindowManager.LayoutParams>
            val open = views.indices.any { i ->
                val t = params.getOrNull(i)?.type ?: return@any false
                views[i].isShown && (t == android.view.WindowManager.LayoutParams.TYPE_APPLICATION || t in 1000..1999)
            }
            if (open != lastOpen) { lastOpen = open; app.mg4dash.system.Log.i("Theme", if (open) "theme change on hold: popup open" else "popup closed (${views.size} windows)") }
            open
        } catch (e: Throwable) { app.mg4dash.system.Log.w("Theme", "window list: ${e.message}"); !activity.hasWindowFocus() }
    }

    fun recreateSmooth(activity: Activity) {
        if (!Prefs(activity).themeSmooth) { activity.recreate(); return }
        if (recreating) return
        recreating = true
        val v = activity.window.decorView
        if (v.width <= 0 || v.height <= 0) { recreating = false; activity.recreate(); return }
        // Copy of the pixels really shown (PixelCopy): a software draw of the screen came out
        // black on the car (hardware rendering), hence the second of black before the fade
        val shot = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(activity.window, shot, { result ->
                if (result == android.view.PixelCopy.SUCCESS) overlayThenRecreate(activity, shot)
                else { app.mg4dash.system.Log.w("Theme", "capture: $result"); recreating = false; activity.recreate() }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Throwable) { app.mg4dash.system.Log.w("Theme", "capture: ${e.message}"); recreating = false; activity.recreate() }
    }

    @Volatile private var recreating = false

    /** Old image window placed, then recreation once it is drawn. */
    private fun overlayThenRecreate(activity: Activity, shot: android.graphics.Bitmap) {
        if (activity.isFinishing || activity.isDestroyed) { recreating = false; return }
        try {
            val app = activity.applicationContext
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            fadeWindow?.let { old -> try { wm.removeView(old) } catch (_: Throwable) {} }
            val img = android.widget.ImageView(app).apply { setImageBitmap(shot); scaleType = android.widget.ImageView.ScaleType.FIT_XY }
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START; x = 0; y = 0; windowAnimations = 0 }
            wm.addView(img, lp)
            fadeWindow = img
            // Frame by frame fade (no animator: the animation scale can be 0 on the car),
            // after a short delay to let the new screen draw underneath
            val t0 = android.os.SystemClock.uptimeMillis() + 700
            img.post(object : Runnable {
                override fun run() {
                    val k = (android.os.SystemClock.uptimeMillis() - t0).toFloat() / FADE_MS
                    if (k >= 1f) { try { wm.removeView(img) } catch (_: Throwable) {}; if (fadeWindow === img) fadeWindow = null; shot.recycle(); return }
                    img.alpha = 1f - k.coerceAtLeast(0f).let { it * it * (3 - 2 * it) }
                    img.postDelayed(this, 16)
                }
            })
            // Recreation only when the old image is on screen, above everything
            img.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    img.viewTreeObserver.removeOnPreDrawListener(this)
                    img.postDelayed({ recreating = false; if (!activity.isFinishing && !activity.isDestroyed) activity.recreate() }, 50)
                    return true
                }
            })
            return
        } catch (e: Throwable) { app.mg4dash.system.Log.w("Theme", "fade: ${e.message}") }
        recreating = false
        activity.recreate()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun fadeFromPrevious(activity: Activity) {}

    /**
     * Screen shorter than the panel (SAIC bars shown outside full screen, #43): everything is
     * reduced accordingly, so pages drawn for 720 dp of height fit without being cut.
     */
    private fun fitHeight(activity: Activity) {
        val h = activity.resources.configuration.screenHeightDp
        val sys = android.content.res.Resources.getSystem().displayMetrics
        // Full screen (default setting): the whole panel is ours, even if the configuration
        // announces a height reduced by the hidden SAIC bars; reducing shifted everything,
        // including the vertical bar under the fog light button (#38)
        val full = Prefs(activity).fullscreen
        val scale = if (!full && h in 400 until FULL_HEIGHT_DP - 8) h / FULL_HEIGHT_DP.toFloat() else 1f
        val m = activity.resources.displayMetrics
        m.density = sys.density * scale
        m.scaledDensity = sys.scaledDensity * scale
        m.densityDpi = (sys.densityDpi * scale).toInt()
    }

    private const val FULL_HEIGHT_DP = 720

    /**
     * What Radio Dash reads: background, accent, light, vertical bar, 0, wide bar, bar on the right
     * (the last two for its own bar, Radio Dash #2), AM band allowed (Radio Dash #2, off by default), fog light button hidden by the shortcuts menu.
     */
    fun published(context: Context): String {
        val p = Prefs(context)
        return "${p.themeBackground},${p.themeAccent},${if (isLight(context)) 1 else 0},${if (p.verticalDock) 1 else 0},0," +
            "${if (p.wideBar) 1 else 0},${if (Dock.rightSide(context)) 1 else 0},${if (p.radioDashAm) 1 else 0}," +
            // 9th: MG's fog light button hidden by the shortcuts menu (Radio Dash's left bar then takes the full height)
            "${if (p.shortcutsMenu) 1 else 0}"
    }

    /** Publishes the theme for Radio Dash (system uid required; no effect otherwise). */
    fun publish(context: Context) {
        try {
            Settings.Global.putString(context.contentResolver, GLOBAL_KEY, published(context))
        } catch (e: Exception) {
            Log.w("Theme", "publishing for Radio Dash failed: ${e.message}")
        }
    }

    private val ATTRS = mapOf(
        R.color.bg_top to R.attr.mgBgTop, R.color.bg_bottom to R.attr.mgBgBottom,
        R.color.panel to R.attr.mgPanel, R.color.panel_raised to R.attr.mgPanel, R.color.panel_border to R.attr.mgPanelBorder,
        R.color.button_idle to R.attr.mgButtonIdle, R.color.button_pressed to R.attr.mgButtonPressed,
        R.color.gauge_off to R.attr.mgGaugeOff, R.color.accent_action to R.attr.mgAction,
        R.color.text_value to R.attr.mgTextValue, R.color.text_title to R.attr.mgTextTitle,
        R.color.text_sub to R.attr.mgTextSub, R.color.text_disabled to R.attr.mgTextDisabled,
    )

    /** Colour of a resource, following the theme for adjustable tints. */
    fun color(context: Context, res: Int): Int {
        val attr = ATTRS[res] ?: return ContextCompat.getColor(context, res)
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
            tv.data else ContextCompat.getColor(context, res)
    }

    /** Energy green for a text or curve: darker in light mode, to stay readable on white. */
    fun energy(context: Context): Int =
        if (isLight(context)) ContextCompat.getColor(context, R.color.accent_energy_light) else color(context, R.color.accent_energy)

    /** Text over [bg]: black on a light colour, white on a dark one. */
    fun on(bg: Int): Int = if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) > 0.45) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

    /** Selection colour (active chips) of the current theme. */
    fun select(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.mgSelect, tv, true)) tv.data else ContextCompat.getColor(context, R.color.accent_cold)
    }
}
