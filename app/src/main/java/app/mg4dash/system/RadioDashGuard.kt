package app.mg4dash.system

import android.content.Context
import android.content.pm.PackageManager

/**
 * Radio Dash switched off for the time of maintenance mode (Marc, 21/11, #123), where the car has its own radio beside
 * it: on SWI68, SWI133, SWI165 the stock radio is com.saicmotor.hmi.radio and Radio Dash (com.saicmotor.radio) runs
 * next to it, its shortcuts then opening MG4Dash. On SWI69 Radio Dash replaces the stock radio (same package): never
 * touched there, or the car would have no radio at all. Nothing is uninstalled: Android's own "disabled" state, put
 * back on each way out of maintenance; MG4Dash Rescue puts it back too if MG4Dash goes meanwhile ([KEY]).
 */
object RadioDashGuard {
    private const val TAG = "RadioGuard"
    const val RADIO_DASH = RadioDashUpdater.PKG
    const val STOCK_RADIO = "com.saicmotor.hmi.radio"
    /** Global setting read by MG4Dash Rescue: "1" while MG4Dash keeps Radio Dash switched off. */
    const val KEY = "mg4dash_radio_off"

    /** Switched off only when Radio Dash is there, enabled, and the car has its own radio beside it. */
    internal fun shouldSwitchOff(radioDash: Boolean, radioDashEnabled: Boolean, stockRadio: Boolean): Boolean =
        radioDash && radioDashEnabled && stockRadio

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        try { pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS); true } catch (_: Exception) { false }

    private fun enabled(pm: PackageManager, pkg: String): Boolean = try {
        val s = pm.getApplicationEnabledSetting(pkg)
        s == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || s == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (_: Exception) { false }

    /** Into maintenance mode, and at each start in it: Radio Dash switched off where the car has its own radio. */
    fun maintenanceOn(context: Context) {
        if (app.mg4dash.BuildConfig.MOCK) return
        val pm = context.packageManager
        if (!shouldSwitchOff(installed(pm, RADIO_DASH), enabled(pm, RADIO_DASH), installed(pm, STOCK_RADIO))) return
        try {
            pm.setApplicationEnabledSetting(RADIO_DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
            mark(context, true)
            Log.i(TAG, "maintenance mode: Radio Dash switched off (the car's own radio $STOCK_RADIO is there)")
        } catch (e: Exception) { Log.w(TAG, "switching Radio Dash off: ${e.message}") }
    }

    /** Out of maintenance mode (any way): Radio Dash back, if MG4Dash switched it off. */
    fun maintenanceOff(context: Context) {
        if (app.mg4dash.BuildConfig.MOCK || !marked(context)) return
        try {
            context.packageManager.setApplicationEnabledSetting(RADIO_DASH, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0)
            Log.i(TAG, "maintenance mode left: Radio Dash switched on again")
        } catch (e: Exception) { Log.w(TAG, "switching Radio Dash on: ${e.message}") }
        mark(context, false)
    }

    private fun marked(context: Context): Boolean = try {
        android.provider.Settings.Global.getString(context.contentResolver, KEY) == "1"
    } catch (_: Exception) { false }

    private fun mark(context: Context, off: Boolean) {
        try { android.provider.Settings.Global.putString(context.contentResolver, KEY, if (off) "1" else "0") }
        catch (e: Exception) { Log.w(TAG, "mark: ${e.message}") }
    }
}
