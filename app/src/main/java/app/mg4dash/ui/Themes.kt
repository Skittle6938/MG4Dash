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
 * Thèmes : fond et couleur des boutons, choisis séparément (5 × 5).
 * Les ressources utilisent des attributs (?attr/mgPanel…) ; on superpose ici les styles choisis
 * au thème de chaque écran, avant son affichage. Les couleurs « qui ont un sens » (batterie,
 * chaud, froid, alerte) ne changent pas.
 *
 * Le choix est aussi publié dans Settings.Global ("mg4dash_theme" = "fond,boutons") pour que
 * Radio Dash, qui tourne dans l'appli radio SAIC, suive le même thème.
 */
object Themes {
    val BACKGROUNDS = listOf(R.style.MgBg_Night, R.style.MgBg_Black, R.style.MgBg_Charcoal, R.style.MgBg_Forest, R.style.MgBg_Burgundy)
    val ACCENTS = listOf(R.style.MgAccent_Blue, R.style.MgAccent_Grey, R.style.MgAccent_Red, R.style.MgAccent_Green, R.style.MgAccent_Orange)
    val BACKGROUNDS_LIGHT = listOf(R.style.MgBgLight_Night, R.style.MgBgLight_Black, R.style.MgBgLight_Charcoal, R.style.MgBgLight_Forest, R.style.MgBgLight_Burgundy)
    val ACCENTS_LIGHT = listOf(R.style.MgAccentLight_Blue, R.style.MgAccentLight_Grey, R.style.MgAccentLight_Red, R.style.MgAccentLight_Green, R.style.MgAccentLight_Orange)

    /**
     * Mode clair ? Réglage : 0 = auto, 1 = sombre, 2 = clair. En auto, on suit le mode jour/nuit de
     * la voiture : mode nuit d'Android (piloté par la voiture) ou PMS_SYSTEM_DAY_NIGHT_MODE (3 = nuit,
     * relevé sur la voiture : passe à 3 quand les feux s'allument).
     */
    fun isLight(context: Context): Boolean = when (Prefs(context).themeMode) {
        1 -> false
        2 -> true
        else -> !isNight(context)
    }

    /**
     * Jour / nuit stabilisé : le changement n'est retenu que si les feux restent dans le nouvel état
     * au moins [HOLD_MS] (un pont ou un court tunnel ne fait plus basculer l'écran).
     */
    fun isNight(context: Context): Boolean {
        val raw = rawNight(context)
        val now = android.os.SystemClock.elapsedRealtime()
        val stable = stableNight ?: raw.also { stableNight = it }
        if (raw == stable) { pendingSince = 0L; return stable }
        if (pendingSince == 0L) { pendingSince = now; return stable }
        if (now - pendingSince < HOLD_MS) return stable
        stableNight = raw; pendingSince = 0L
        return raw
    }

    private const val HOLD_MS = 5_000L
    @Volatile private var stableNight: Boolean? = null
    @Volatile private var pendingSince = 0L

    private fun rawNight(context: Context): Boolean {
        val ui = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (ui == android.content.res.Configuration.UI_MODE_NIGHT_YES) return true
        // Relevé sur la voiture : feux allumés → VEH_SIDE_LGHT = 3 et PMS_SYSTEM_DAY_NIGHT_MODE = 3 (zone 0x1000000)
        return try {
            val side = app.mg4dash.car.CarProps.readAny(557880099)
            (side.isNotEmpty() && side != "0" && side != "false") ||
                app.mg4dash.car.CarProps.readIntArea(289442317, 0x1000000) == 3
        } catch (_: Exception) { false }
    }

    /** Couleurs des pastilles du sélecteur (même ordre que les listes). */
    val BACKGROUND_SWATCHES = intArrayOf(0xFF141B29.toInt(), 0xFF0E0E10.toInt(), 0xFF2B2F35.toInt(), 0xFF16271F.toInt(), 0xFF29161C.toInt())
    val ACCENT_SWATCHES = intArrayOf(0xFF2979FF.toInt(), 0xFF8E99A8.toInt(), 0xFFE53935.toInt(), 0xFF00C853.toInt(), 0xFFFF8F00.toInt())

    private const val GLOBAL_KEY = "mg4dash_theme"

    /** Signature du thème courant : sert à savoir si un écran doit se redessiner. */
    fun signature(context: Context): Int = Prefs(context).let { it.themeBackground * 10 + it.themeAccent + (if (isLight(context)) 100 else 0) + (if (it.verticalDock) 1000 else 0) + (if (it.panelFade) 0 else 10000) }

    /** À appeler dans onCreate, avant setContentView ; [dark] force le thème sombre. */
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
    /** Fenêtre posée au-dessus de tout pendant la recréation (garde l'ancienne image, sans noir). */
    private var fadeWindow: android.widget.ImageView? = null

    /**
     * Change de thème (jour / nuit…) en fondu. L'image de l'écran actuel est posée dans une petite
     * fenêtre système au-dessus de tout, qui survit à la recréation de l'activité : pendant que
     * l'écran se reconstruit (court passage au noir d'Android), on voit toujours l'ancienne image,
     * puis elle s'efface en fondu sur le nouvel écran.
     */
    fun recreateSmooth(activity: Activity) {
        val shot = try {
            val v = activity.window.decorView
            if (v.width > 0 && v.height > 0) android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { v.draw(android.graphics.Canvas(it)) } else null
        } catch (_: Throwable) { null }
        if (shot != null) try {
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
            // Fondu image par image (sans animateur : l'échelle des animations peut être à 0 sur la voiture),
            // après un court délai pour laisser le nouvel écran se dessiner dessous
            val t0 = android.os.SystemClock.uptimeMillis() + 400
            img.post(object : Runnable {
                override fun run() {
                    val k = (android.os.SystemClock.uptimeMillis() - t0).toFloat() / FADE_MS
                    if (k >= 1f) { try { wm.removeView(img) } catch (_: Throwable) {}; if (fadeWindow === img) fadeWindow = null; shot.recycle(); return }
                    img.alpha = 1f - k.coerceAtLeast(0f).let { it * it * (3 - 2 * it) }
                    img.postDelayed(this, 16)
                }
            })
        } catch (e: Throwable) { android.util.Log.w("MG4L.Theme", "fondu : ${e.message}") }
        activity.recreate()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun fadeFromPrevious(activity: Activity) {}

    /**
     * Écran moins haut que la dalle (barres SAIC affichées hors plein écran, #43) : tout est
     * réduit d'autant, pour que les pages dessinées pour 720 dp de haut tiennent sans être coupées.
     */
    private fun fitHeight(activity: Activity) {
        val h = activity.resources.configuration.screenHeightDp
        val sys = android.content.res.Resources.getSystem().displayMetrics
        // Plein écran (réglage par défaut) : la dalle entière est à nous, même si la configuration
        // annonce une hauteur réduite par les barres SAIC masquées ; réduire décalait tout,
        // dont la barre verticale sous le bouton antibrouillards (#38)
        val full = Prefs(activity).fullscreen
        val scale = if (!full && h in 400 until FULL_HEIGHT_DP - 8) h / FULL_HEIGHT_DP.toFloat() else 1f
        val m = activity.resources.displayMetrics
        m.density = sys.density * scale
        m.scaledDensity = sys.scaledDensity * scale
        m.densityDpi = (sys.densityDpi * scale).toInt()
    }

    private const val FULL_HEIGHT_DP = 720

    /** Publie le thème pour Radio Dash (uid système requis ; sans effet sinon). */
    fun publish(context: Context) {
        val p = Prefs(context)
        try {
            Settings.Global.putString(context.contentResolver, GLOBAL_KEY, "${p.themeBackground},${p.themeAccent},${if (isLight(context)) 1 else 0},${if (p.verticalDock) 1 else 0}")
        } catch (e: Exception) {
            Log.w("Thème", "publication pour Radio Dash impossible : ${e.message}")
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

    /** Couleur d'une ressource, en suivant le thème pour les teintes réglables. */
    fun color(context: Context, res: Int): Int {
        val attr = ATTRS[res] ?: return ContextCompat.getColor(context, res)
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true) && tv.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT)
            tv.data else ContextCompat.getColor(context, res)
    }

    /** Couleur de sélection (puces actives) du thème courant. */
    fun select(context: Context): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(R.attr.mgSelect, tv, true)) tv.data else ContextCompat.getColor(context, R.color.accent_cold)
    }
}
