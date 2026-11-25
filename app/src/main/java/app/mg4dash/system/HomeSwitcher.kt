package app.mg4dash.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager

/**
 * Home screen (HOME).
 *
 * The SAIC launcher declares priority 2 on HOME; an installed app is capped at 0,
 * and Android then ignores the "default" choice. For MG4Dash to be home (owner's choice
 * B), ONLY the SAIC home activity is DISABLED; its service (camera, audio sources,
 * SystemUI commands) stays active. Service mode and the crash safeguard
 * re-enable it. Requires the system uid.
 *
 * Warning: "adb shell pm enable …" is refused on the car (the shell has no right);
 * only MG4Dash (system uid) can re-enable SAIC home. If MG4Dash no longer starts:
 *   adb shell cmd package compile -m speed -f app.mg4dash
 *   adb shell cmd package set-home-activity --user 0 app.mg4dash/.MainActivity
 */
object HomeSwitcher {
    private const val TAG = "Home"
    private val DEFAULT_STOCK = ComponentName("com.saicmotor.launcher", "com.saicmotor.launcher.view.MainActivity")

    /**
     * SAIC home of this firmware: com.saicmotor.launcher on SWI69, com.saicmotor.hmi.launcher
     * on the old SDK. Found among HOME activities (disabled included) of a SAIC package,
     * then remembered: once disabled, it no longer appears in ordinary resolutions.
     */
    @Volatile var STOCK: ComponentName = DEFAULT_STOCK
        private set

    fun init(context: Context) {
        val sp = Prefs(context).raw
        val saved = sp.getString("stock_home", null)?.let { ComponentName.unflattenFromString(it) }
        if (saved != null && !isWizard(saved)) { STOCK = saved; return }
        // Versions ≤ 1.5.0 could keep the first boot wizard (SWI165, #50): look for
        // the real home. The wizard stays as it is (re-enabling it could restart it)
        val wizardWasReplaced = saved != null && try {
            context.packageManager.getComponentEnabledSetting(saved) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (_: Exception) { false }
        val found = try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
                .filter { it.packageName.startsWith("com.saicmotor") && !isWizard(it) }
                .sortedBy { when { it == DEFAULT_STOCK -> 0; it.packageName == "com.saicmotor.hmi.launcher" -> 1; else -> 2 } }
                .firstOrNull()
        } catch (_: Exception) { null }
        STOCK = found ?: DEFAULT_STOCK
        if (found != null) sp.edit().putString("stock_home", found.flattenToString()).apply()
        Log.i(TAG, "SAIC home: ${STOCK.flattenToShortString()}")
        // The user had asked to replace home: do it on the right component
        if (wizardWasReplaced && found != null) replaceStock(context)
    }

    /** The first boot wizard (com.saicmotor.hmi.setupwizard) also declares HOME: it is not home. */
    private fun isWizard(c: ComponentName) = c.packageName.contains("setupwizard")

    fun ours(context: Context) = ComponentName(context.packageName, "app.mg4dash.MainActivity")

    /** Currently resolved HOME component, or null. */
    fun currentHome(context: Context): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.let { ComponentName(it.packageName, it.name) }
    }

    fun isStockEnabled(context: Context): Boolean = try {
        context.packageManager.getComponentEnabledSetting(STOCK) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    } catch (_: Exception) { true }

    /** MG4Dash becomes home: preference set, then SAIC home disabled. */
    fun replaceStock(context: Context): Boolean {
        Prefs(context).valetMode = false
        Prefs(context).valetAuto = false
        // Out of maintenance mode, whatever the way: Radio Dash back if it was switched off for it
        RadioDashGuard.maintenanceOff(context)
        setPreferred(context, ours(context))
        return try {
            context.packageManager.setComponentEnabledSetting(
                STOCK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            val now = currentHome(context)
            Log.i(TAG, "SAIC home disabled; HOME resolves to: ${now?.flattenToShortString()}")
            tellRescue(context, "1")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "disabling SAIC home refused (system uid required)", e)
            false
        }
    }

    /**
     * Out of maintenance mode with the SAIC home kept as the home (Discussion #111: MG4Dash used as an app, opened from
     * MG4Swipe): nothing disabled, Radio Dash back if it was switched off for maintenance, the rescue told.
     */
    fun leaveMaintenanceKeepStock(context: Context) {
        Prefs(context).valetMode = false
        Prefs(context).valetAuto = false
        RadioDashGuard.maintenanceOff(context)
        noteHome(context)
        Log.i(TAG, "maintenance mode left, SAIC home kept: MG4Dash used as an app")
    }

    /**
     * Should MG4Dash set itself as home again? Only when the driver chose it ([stockDisabled]: SAIC home replaced),
     * not in maintenance mode, and HOME no longer resolves to it ([current]: the chooser, or nothing).
     */
    internal fun needsHomeBack(valet: Boolean, stockDisabled: Boolean, current: ComponentName?, ours: ComponentName): Boolean =
        !valet && stockDisabled && current != ours

    /**
     * MG4Dash's default home kept (15/11): during an update its HOME activities are gone for a moment, and Android
     * drops the home preference if HOME is resolved then (its set of candidates changed). The car then shows
     * Android's home chooser. Checked at each start, after an update and again a few seconds later.
     */
    fun ensureHome(context: Context, why: String) {
        try {
            val cur = currentHome(context)
            if (!needsHomeBack(Prefs(context).valetMode, !isStockEnabled(context), cur, ours(context))) { noteHome(context); return }
            setPreferred(context, ours(context))
            Log.i(TAG, "MG4Dash set as home again ($why): HOME was ${cur?.flattenToShortString()}, now ${currentHome(context)?.flattenToShortString()}")
            noteHome(context)
        } catch (e: Exception) { Log.w(TAG, "home check ($why): ${e.message}") }
    }

    /** Global setting read by MG4Dash Rescue: "1" MG4Dash is the home chosen, "0" it is not. */
    const val KEY_HOME = "mg4dash_home"

    /**
     * What to tell MG4Dash Rescue about the home (19/11: with SAIC home enabled, the rescue did not know MG4Dash was the
     * home and left it crashing): "auto" in the service mode MG4Dash's crash guard put (the rescue may bring it back
     * after a repair), "0" in the driver's maintenance mode or with another home in use, "1" with SAIC home replaced or
     * MG4Dash the HOME resolved; null with Android's chooser (after a crash loop Android forgets the preference: the
     * last word stays).
     */
    internal fun homeWord(valet: Boolean, stockDisabled: Boolean, current: ComponentName?, ours: ComponentName, valetAuto: Boolean = false): String? = when {
        valet && valetAuto -> "auto"
        valet -> "0"
        stockDisabled || current == ours -> "1"
        current == null || current.packageName == "android" || current.className.contains("Resolver") || current.packageName.contains("lenspicker") -> null
        else -> "0"
    }

    /** Tells MG4Dash Rescue whether MG4Dash is the home now. */
    fun noteHome(context: Context) = Prefs(context).let { p -> tellRescue(context, homeWord(p.valetMode, !isStockEnabled(context), currentHome(context), ours(context), p.valetAuto)) }

    /**
     * Back as the home after MG4Dash Rescue repaired it (19/11): a service mode MG4Dash's own crash guard had put is left,
     * as [replaceStock] leaves it (SAIC home itself as the driver had it); the driver's own service mode stays.
     */
    fun backAfterRescue(context: Context) {
        val p = Prefs(context)
        if (!p.valetMode || !p.valetAuto) return
        if (currentHome(context) != ours(context)) return
        p.valetMode = false; p.valetAuto = false
        Log.i(TAG, "service mode left: MG4Dash is the home again after its repair")
        tellRescue(context, "1")
    }

    private fun tellRescue(context: Context, word: String?) {
        if (word == null || app.mg4dash.BuildConfig.MOCK) return
        try {
            if (android.provider.Settings.Global.getString(context.contentResolver, KEY_HOME) != word) {
                android.provider.Settings.Global.putString(context.contentResolver, KEY_HOME, word)
                Log.i(TAG, "rescue told: home $word")
            }
        } catch (e: Exception) { Log.w(TAG, "home word: ${e.message}") }
    }

    /**
     * Service mode: re-enables SAIC home, makes it preferred, then opens it. [auto]: put by MG4Dash's crash guard, not by
     * the driver (MG4Dash Rescue then brings MG4Dash back once its code is repaired).
     */
    fun switchToStock(context: Context, auto: Boolean = false): Boolean {
        Prefs(context).valetMode = true
        Prefs(context).valetAuto = auto
        Maintenance.hideLyricsOverlay()
        // The driver's maintenance mode: Radio Dash off where the car has its own radio (not for the crash guard's)
        if (!auto) RadioDashGuard.maintenanceOn(context)
        val ok = try {
            context.packageManager.setComponentEnabledSetting(
                STOCK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
            )
            setPreferred(context, STOCK)
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "re-enabling SAIC home refused", e)
            false
        }
        Log.i(TAG, "SAIC home re-enabled → $ok")
        tellRescue(context, if (auto) "auto" else "0")
        if (ok) {
            try {
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(STOCK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.w(TAG, "opening SAIC launcher: ${e.message}")
            }
        }
        return ok
    }

    /**
     * Before MG4Dash uninstalls itself: SAIC home enabled again and the preferred HOME, without opening it nor any
     * maintenance mode; true only when HOME really resolves to it.
     */
    fun stockHomeBack(context: Context): Boolean = try {
        RadioDashGuard.maintenanceOff(context)
        context.packageManager.setComponentEnabledSetting(STOCK, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
        setPreferred(context, STOCK)
        (currentHome(context) == STOCK).also { Log.i(TAG, "SAIC home back before the uninstall → $it") }
    } catch (e: Exception) { Log.e(TAG, "SAIC home back: ${e.message}"); false }

    @Suppress("DEPRECATION")
    private fun setPreferred(context: Context, target: ComponentName) {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = (pm.queryIntentActivities(homeIntent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) } + target).distinct()
        try {
            candidates.map { it.packageName }.distinct().forEach { pm.clearPackagePreferredActivities(it) }
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
            }
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY, candidates.toTypedArray(), target)
        } catch (e: SecurityException) {
            Log.w(TAG, "HOME preference refused: ${e.message}")
        }
    }
}
