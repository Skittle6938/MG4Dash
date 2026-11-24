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
    // The first five of each list are the ones offered first; the others (24/11) in the "More colours" grid (themes_palette.xml)
    val BACKGROUNDS = listOf(R.style.MgBg_Night, R.style.MgBg_Black, R.style.MgBg_Charcoal, R.style.MgBg_Forest, R.style.MgBg_Burgundy,
        R.style.MgBg_Slate, R.style.MgBg_Petrol, R.style.MgBg_Teal, R.style.MgBg_Emerald, R.style.MgBg_Moss, R.style.MgBg_Olive, R.style.MgBg_Sand, R.style.MgBg_Bronze, R.style.MgBg_Brown, R.style.MgBg_Rust, R.style.MgBg_DeepRed, R.style.MgBg_Wine, R.style.MgBg_Plum, R.style.MgBg_Purple, R.style.MgBg_Indigo, R.style.MgBg_Midnight, R.style.MgBg_Steel, R.style.MgBg_Graphite, R.style.MgBg_WarmGrey)
    val ACCENTS = listOf(R.style.MgAccent_Blue, R.style.MgAccent_Grey, R.style.MgAccent_Red, R.style.MgAccent_Green, R.style.MgAccent_Orange,
        R.style.MgAccent_Cyan, R.style.MgAccent_Teal, R.style.MgAccent_Turquoise, R.style.MgAccent_LightGreen, R.style.MgAccent_Lime, R.style.MgAccent_Yellow, R.style.MgAccent_Gold, R.style.MgAccent_Amber, R.style.MgAccent_DeepOrange, R.style.MgAccent_Coral, R.style.MgAccent_Rose, R.style.MgAccent_Pink, R.style.MgAccent_Magenta, R.style.MgAccent_Purple, R.style.MgAccent_DeepPurple, R.style.MgAccent_Lavender, R.style.MgAccent_Indigo, R.style.MgAccent_LightBlue, R.style.MgAccent_Sky)
    val BACKGROUNDS_LIGHT = listOf(R.style.MgBgLight_Night, R.style.MgBgLight_Black, R.style.MgBgLight_Charcoal, R.style.MgBgLight_Forest, R.style.MgBgLight_Burgundy,
        R.style.MgBgLight_Slate, R.style.MgBgLight_Petrol, R.style.MgBgLight_Teal, R.style.MgBgLight_Emerald, R.style.MgBgLight_Moss, R.style.MgBgLight_Olive, R.style.MgBgLight_Sand, R.style.MgBgLight_Bronze, R.style.MgBgLight_Brown, R.style.MgBgLight_Rust, R.style.MgBgLight_DeepRed, R.style.MgBgLight_Wine, R.style.MgBgLight_Plum, R.style.MgBgLight_Purple, R.style.MgBgLight_Indigo, R.style.MgBgLight_Midnight, R.style.MgBgLight_Steel, R.style.MgBgLight_Graphite, R.style.MgBgLight_WarmGrey)
    val ACCENTS_LIGHT = listOf(R.style.MgAccentLight_Blue, R.style.MgAccentLight_Grey, R.style.MgAccentLight_Red, R.style.MgAccentLight_Green, R.style.MgAccentLight_Orange,
        R.style.MgAccentLight_Cyan, R.style.MgAccentLight_Teal, R.style.MgAccentLight_Turquoise, R.style.MgAccentLight_LightGreen, R.style.MgAccentLight_Lime, R.style.MgAccentLight_Yellow, R.style.MgAccentLight_Gold, R.style.MgAccentLight_Amber, R.style.MgAccentLight_DeepOrange, R.style.MgAccentLight_Coral, R.style.MgAccentLight_Rose, R.style.MgAccentLight_Pink, R.style.MgAccentLight_Magenta, R.style.MgAccentLight_Purple, R.style.MgAccentLight_DeepPurple, R.style.MgAccentLight_Lavender, R.style.MgAccentLight_Indigo, R.style.MgAccentLight_LightBlue, R.style.MgAccentLight_Sky)

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
    val BACKGROUND_SWATCHES = intArrayOf(
        0xFF0E1939.toInt(), 0xFF0E0E10.toInt(), 0xFF1B222D.toInt(), 0xFF11372A.toInt(), 0xFF3A0D18.toInt(), 0xFF152632.toInt(), 0xFF09313F.toInt(), 0xFF0B3C38.toInt(), 0xFF0A3E1B.toInt(), 0xFF1E3314.toInt(), 0xFF303611.toInt(), 0xFF312817.toInt(), 0xFF3C280B.toInt(), 0xFF39200E.toInt(), 0xFF3F1609.toInt(), 0xFF3F0909.toInt(), 0xFF390E29.toInt(), 0xFF361136.toInt(), 0xFF270E39.toInt(), 0xFF0F0B3C.toInt(), 0xFF061042.toInt(), 0xFF132935.toInt(), 0xFF242424.toInt(), 0xFF2D241B.toInt())
    val ACCENT_SWATCHES = intArrayOf(0xFF2979FF.toInt(), 0xFF8E99A8.toInt(), 0xFFE53935.toInt(), 0xFF00C853.toInt(), 0xFFFF8F00.toInt(),
        0xFF00B8D4.toInt(), 0xFF00BFA5.toInt(), 0xFF1DE9B6.toInt(), 0xFF64DD17.toInt(), 0xFFAEEA00.toInt(), 0xFFFFD600.toInt(), 0xFFFFC400.toInt(), 0xFFFFAB00.toInt(), 0xFFFF6D00.toInt(), 0xFFFF6E40.toInt(), 0xFFFF4081.toInt(), 0xFFF50057.toInt(), 0xFFD500F9.toInt(), 0xFFAA00FF.toInt(), 0xFF7C4DFF.toInt(), 0xFFB388FF.toInt(), 0xFF536DFE.toInt(), 0xFF00B0FF.toInt(), 0xFF40C4FF.toInt())
    /**
     * The dots of the background choice as they look in the current mode (24/11: in light mode the dark colours
     * hid the differences): each background's button colour, dark or light.
     */
    fun backgroundDots(context: Context): IntArray {
        val styles = if (isLight(context)) BACKGROUNDS_LIGHT else BACKGROUNDS
        return IntArray(styles.size) { i ->
            val t = context.resources.newTheme().apply { applyStyle(styles[i], true) }
            val a = t.obtainStyledAttributes(intArrayOf(R.attr.mgButtonIdle))
            try { a.getColor(0, BACKGROUND_SWATCHES[i]) } finally { a.recycle() }
        }
    }

    /** How many of each are offered first (the rest in the grid). */
    const val FIRST_SWATCHES = 5

    private const val GLOBAL_KEY = "mg4dash_theme"

    /** Signature of the current theme: used to know if a screen must redraw. */
    fun signature(context: Context): Int = Prefs(context).let {
        // Every part distinct (24 colours each since 24/11: no sum that two choices could share)
        listOf(it.themeBackground, it.themeAccent, isLight(context), it.verticalDock, it.panelFade, it.wideBar, it.homeStyle == Prefs.STYLE_BIG,
            // A profile switch rebuilds the open screens even when both profiles have the same theme
            app.mg4dash.system.Profiles.generation(context)).hashCode() }

    /**
     * A context with MG4Dash's theme (background and buttons chosen), for windows outside the screens; enlarged in
     * Grand affichage unless [big] is false (what sits under MG's own buttons keeps its pixels).
     */
    fun themed(context: Context, big: Boolean = BigDisplay.on(context)): Context {
        val p = Prefs(context)
        val light = isLight(context)
        val bgs = if (light) BACKGROUNDS_LIGHT else BACKGROUNDS
        val accs = if (light) ACCENTS_LIGHT else ACCENTS
        return android.view.ContextThemeWrapper(BigDisplay.wrap(context.applicationContext, big), R.style.Theme_MG4Launcher).apply {
            theme.applyStyle(bgs.getOrElse(p.themeBackground) { bgs[0] }, true)
            theme.applyStyle(accs.getOrElse(p.themeAccent) { accs[0] }, true)
            if (big) theme.applyStyle(if (light) R.style.MgBigLight else R.style.MgBigDark, true)
        }
    }

    /** The big numbers' font: light, regular in Grand affichage (its theme overlay). */
    fun valueFont(context: Context): android.graphics.Typeface {
        val tv = TypedValue()
        val name = if (context.theme.resolveAttribute(R.attr.mgValueFont, tv, true) && tv.string != null) tv.string.toString() else "sans-serif-light"
        return android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL)
    }

    /** To call in onCreate, before setContentView; [dark] forces the dark theme. */
    fun apply(activity: Activity, dark: Boolean = false) {
        val p = Prefs(activity)
        val light = !dark && isLight(activity)
        val bgs = if (light) BACKGROUNDS_LIGHT else BACKGROUNDS
        val accs = if (light) ACCENTS_LIGHT else ACCENTS
        activity.theme.applyStyle(bgs.getOrElse(p.themeBackground) { bgs[0] }, true)
        activity.theme.applyStyle(accs.getOrElse(p.themeAccent) { accs[0] }, true)
        // Grand affichage: stronger contrast and regular big numbers, on the enlarged screens
        if (BigDisplay.factor(activity) > 1f) activity.theme.applyStyle(if (light) R.style.MgBigLight else R.style.MgBigDark, true)
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
     *
     * Grand affichage: the screen's own factor (BigDisplay) is kept, the reduction multiplies it. The metrics are shared
     * by the screens of the same configuration, so they are set from the panel's, never reduced in place; and the height
     * is the panel's real one (an enlarged screen reports 540 dp for the 720 of the panel), or it was reduced twice.
     */
    private fun fitHeight(activity: Activity) {
        val h = activity.applicationContext.resources.configuration.screenHeightDp
        val sys = android.content.res.Resources.getSystem().displayMetrics
        val big = BigDisplay.factor(activity)
        // Full screen (default setting): the whole panel is ours, even if the configuration
        // announces a height reduced by the hidden SAIC bars; reducing shifted everything,
        // including the vertical bar under the fog light button (#38)
        val full = Prefs(activity).fullscreen
        val scale = if (!full && h in 400 until FULL_HEIGHT_DP - 8) h / FULL_HEIGHT_DP.toFloat() else 1f
        val m = activity.resources.displayMetrics
        m.density = sys.density * big * scale
        m.scaledDensity = sys.scaledDensity * big * scale
        m.densityDpi = (sys.densityDpi * big * scale).toInt()
    }

    private const val FULL_HEIGHT_DP = 720

    /**
     * What Radio Dash reads: background, accent, light, vertical bar, 0, wide bar, bar on the right
     * (the last two for its own bar, Radio Dash #2), AM band allowed (Radio Dash #2, off by default), fog light button hidden by the shortcuts menu, the Android Auto
     * button's choice and app (MG4Radio #5), Grand affichage (12th, 1 or 0).
     */
    fun published(context: Context): String {
        val p = Prefs(context)
        return "${p.themeBackground},${p.themeAccent},${if (isLight(context)) 1 else 0},${if (Dock.vertical(context)) 1 else 0},0," +
            "${if (p.wideBar) 1 else 0},${if (Dock.rightSide(context)) 1 else 0},${if (p.radioDashAm) 1 else 0}," +
            // 9th: MG's fog light button hidden by the shortcuts menu (Radio Dash's left bar then takes the full height)
            "${if (app.mg4dash.system.FogOverlay.hides(context)) 1 else 0}," +
            // 10th, 11th: the Android Auto button's choice (0 Android Auto, 1 CarPlay, 2 another app) and that app's
            // component (empty otherwise), so Radio Dash names it and opens it through MG4Dash (MG4Radio #5)
            "${Dock.aaChoice(p.dockAaTarget)},${p.dockAaTarget?.takeIf { Dock.aaChoice(it) == 2 } ?: ""}," +
            // 12th: Grand affichage (study v4, step 13), for MG4Radio to follow; published again at each profile switch
            "${if (p.homeStyle == Prefs.STYLE_BIG) 1 else 0}"
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

    /** A colour attribute of the current theme ([fallback] if the theme does not set it). */
    fun attr(context: Context, attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) tv.data else fallback
    }

    /** Selection colour (active chips) of the current theme. */
    fun select(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.mgSelect, tv, true)) tv.data else ContextCompat.getColor(context, R.color.accent_cold)
    }
}
