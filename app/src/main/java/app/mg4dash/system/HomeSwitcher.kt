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
        setPreferred(context, ours(context))
        return try {
            context.packageManager.setComponentEnabledSetting(
                STOCK, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP,
            )
            val now = currentHome(context)
            Log.i(TAG, "SAIC home disabled; HOME resolves to: ${now?.flattenToShortString()}")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "disabling SAIC home refused (system uid required)", e)
            false
        }
    }

    /** Service mode: re-enables SAIC home, makes it preferred, then opens it. */
    fun switchToStock(context: Context): Boolean {
        Prefs(context).valetMode = true
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
