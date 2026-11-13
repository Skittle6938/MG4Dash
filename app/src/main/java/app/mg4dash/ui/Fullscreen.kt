package app.mg4dash.ui

import android.app.Activity
import android.view.View
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Full screen: hides the SAIC side bar and banner (standard SystemUI windows
 * TYPE_NAVIGATION_BAR / TYPE_STATUS_BAR) through Android's immersive mode.
 * "Sticky" mode: a swipe from the left edge or the top brings them back for
 * a few seconds, which always keeps access to the stock controls.
 */
object Fullscreen {
    @Suppress("DEPRECATION")
    private val HIDDEN = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_FULLSCREEN

    /** Bars shown again by the system: hidden again after this delay, the stock controls stay usable meanwhile. */
    private const val REHIDE_MS = 3_000L

    @Suppress("DEPRECATION")
    fun apply(activity: Activity) {
        val decor = activity.window.decorView
        watch(activity)
        decor.systemUiVisibility = if (Prefs(activity).fullscreen) HIDDEN else View.SYSTEM_UI_FLAG_VISIBLE
    }

    /**
     * The car's call window brings the bars back, which then stayed over home:
     * rewriting the same settings has no effect, so leave them for a moment. Nothing to do if the bars are already hidden.
     */
    @Suppress("DEPRECATION")
    fun restore(activity: Activity) {
        if (!Prefs(activity).fullscreen) return
        val decor = activity.window.decorView
        val insets = decor.rootWindowInsets ?: return
        // All four sides: the SAIC bar is not always on the left (right-hand drive, #108)
        if (insets.systemWindowInsetTop == 0 && insets.systemWindowInsetLeft == 0 &&
            insets.systemWindowInsetRight == 0 && insets.systemWindowInsetBottom == 0) return
        selfChange = true
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        decor.post { selfChange = false; apply(activity) }
    }

    @Volatile private var selfChange = false

    /**
     * Bars brought back by another window (SAIC window, another app's floating window that takes
     * the focus…): drawn over home, half transparent, and they stayed there because our own
     * settings had not changed (#108). Each return is logged, then the bars are hidden again.
     */
    @Suppress("DEPRECATION")
    private fun watch(activity: Activity) {
        val decor = activity.window.decorView
        if (decor.getTag(app.mg4dash.R.id.fullscreen_watch) != null) return
        val rehide = object : Runnable {
            var tries = 0
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed || !Prefs(activity).fullscreen) return
                // Another window has the focus (our menus included): wait until home has it back
                if (!activity.hasWindowFocus()) { if (++tries < 20) decor.postDelayed(this, REHIDE_MS); return }
                restore(activity)
            }
        }
        decor.setTag(app.mg4dash.R.id.fullscreen_watch, rehide)
        decor.setOnSystemUiVisibilityChangeListener { vis ->
            if (selfChange || !Prefs(activity).fullscreen) return@setOnSystemUiVisibilityChangeListener
            decor.removeCallbacks(rehide)
            if (vis and View.SYSTEM_UI_FLAG_FULLSCREEN != 0) return@setOnSystemUiVisibilityChangeListener
            Log.i("Bars", "SAIC bars shown (${activity.javaClass.simpleName}, focus ${activity.hasWindowFocus()}, flags 0x${Integer.toHexString(vis)}): hidden again in ${REHIDE_MS / 1000} s")
            rehide.tries = 0
            decor.postDelayed(rehide, REHIDE_MS)
        }
    }

    /**
     * Apps able to draw over the others, and accessibility services: one of their windows that
     * takes the focus brings the SAIC bars back (#108). Third-party apps only.
     */
    fun overlayApps(context: android.content.Context): String = try {
        val pm = context.packageManager
        val ops = context.getSystemService(android.content.Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val list = pm.getInstalledPackages(android.content.pm.PackageManager.GET_PERMISSIONS).filter { p ->
            val ai = p.applicationInfo ?: return@filter false
            ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 && p.packageName != context.packageName &&
                p.requestedPermissions?.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == true &&
                ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, ai.uid, p.packageName) == android.app.AppOpsManager.MODE_ALLOWED
        }.map { it.packageName }.sorted()
        val a11y = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }?.map { it.substringBefore('/') }?.distinct().orEmpty()
        "overlay=" + (list.joinToString(" ").ifEmpty { "—" }) + "; accessibility=" + (a11y.joinToString(" ").ifEmpty { "—" })
    } catch (e: Throwable) { "? (${e.javaClass.simpleName})" }
}
