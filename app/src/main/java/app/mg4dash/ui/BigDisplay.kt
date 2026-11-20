package app.mg4dash.ui

import android.content.Context
import android.content.res.Configuration
import app.mg4dash.system.Prefs

/**
 * "Grand affichage" (accessibility): everything drawn bigger, for a weaker sight or a calmer screen while driving.
 *
 * Each enlarged screen gets its own configuration, a higher density (213 dpi instead of 160 on the car's panel,
 * x1.33125), so every size in dp and every text in sp grows by the same factor. Android's own setting is never
 * changed: the car's other apps, MG4Dash's Settings, the maintenance mode, the boot and screen-off screens keep
 * their size. Only the density and the sizes in dp it implies are overridden, so the car's night mode, language and
 * the rest still follow the car.
 *
 * Enlarged: home, every page, Apps, Keep climate on; off them, the shortcuts menu, the toasts and the speed camera
 * banner. What sits under MG's own buttons (the fog button zone, the top detection strip, the maintenance banner)
 * stays in pixels: see [unscaled].
 */
object BigDisplay {
    /** Density of an enlarged screen on a 160 dpi panel. */
    const val DPI = 213
    private const val BASE_DPI = 160

    /** Grand affichage chosen (Settings › Home). */
    fun on(context: Context): Boolean = try { Prefs(context).homeStyle == Prefs.STYLE_BIG } catch (_: Throwable) { false }

    /**
     * Configuration delta of an enlarged screen over [base]: the density, and the screen sizes in dp that go with it.
     * Every other field stays undefined, so it keeps following the car (night mode, language, font scale).
     */
    fun override(base: Configuration): Configuration {
        val from = if (base.densityDpi > 0) base.densityDpi else BASE_DPI
        val dpi = from * DPI / BASE_DPI
        return Configuration().apply {
            densityDpi = dpi
            if (base.screenWidthDp > 0) screenWidthDp = base.screenWidthDp * from / dpi
            if (base.screenHeightDp > 0) screenHeightDp = base.screenHeightDp * from / dpi
            if (base.smallestScreenWidthDp > 0) smallestScreenWidthDp = base.smallestScreenWidthDp * from / dpi
        }
    }

    /**
     * [base] enlarged when Grand affichage is on, [base] itself otherwise. For an activity, in attachBaseContext: the
     * activity's own context stays the base, so its resources still follow the car's configuration changes.
     */
    fun wrap(base: Context, big: Boolean = on(base)): Context =
        if (!big) base else try { base.createConfigurationContext(override(base.resources.configuration)) } catch (_: Throwable) { base }

    /** How much [context] is enlarged over the panel's own density: 1.33125 in an enlarged screen, 1 elsewhere. */
    fun factor(context: Context): Float {
        val own = context.resources.configuration.densityDpi
        val panel = context.applicationContext?.resources?.configuration?.densityDpi ?: own
        return if (own <= 0 || panel <= 0) 1f else own.toFloat() / panel
    }

    /** [dp] of the panel in [context]'s dp: the same pixels in an enlarged screen (MG's buttons do not grow). */
    fun unscaled(context: Context, dp: Int): Int = Math.round(dp / factor(context))
}
