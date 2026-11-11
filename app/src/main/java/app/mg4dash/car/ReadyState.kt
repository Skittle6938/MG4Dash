/*
 * MG4Dash — the car's READY state and gear lever, read as MG4Control does.
 * Ported from MG4Hardware.kt of MG4Control (https://github.com/SliDeeN/MG4Control, commit 38557c7b:
 * readEptReadyRaw, readGearRaw), Copyright © 2026 Slice Denkins and the MG4Control contributors, GPL v3 or later.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.car

import android.content.Context
import android.os.IBinder
import app.mg4dash.system.Log

/**
 * READY (SENSOR_EPTRDY) and the gear, per firmware route ([DriveSettings.route]):
 *  - SWI133: VehiclePropertyManager, READY 0x5030048, gear 0x5030043;
 *  - SWI68 / SWI165: VehicleConditionManager.getEngineState() (SENSOR_EPTRDY 0x2140157c) and getCarGear();
 *  - A9 (SWI69, 131, 132, 173): CarStateClient.getSensorEptrdyState() and getGearState(), on queryClient(0xb).
 * READY: 1 = on, 0 = off. Gear: P = 1 on every firmware. Null when unreadable. Blocking: off the main thread.
 */
object ReadyState {
    private const val TAG = "Ready"
    private const val PROP_EPTRDY_VPM = 0x5030048
    private const val PROP_GEAR_VPM = 0x5030043
    const val GEAR_PARK = 1

    @Volatile private var carState: Any? = null
    @Volatile private var carStateBinder: IBinder? = null
    @Volatile private var errLogged = false
    private var appContext: Context? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** READY on (true), off (false), or null if this firmware does not say. */
    fun ready(): Boolean? = when (readyRaw()) { 1 -> true; 0 -> false; else -> null }

    /** The lever in P (true), elsewhere (false), or null if unreadable. */
    fun inPark(): Boolean? = gearRaw()?.let { it == GEAR_PARK }

    fun readyRaw(): Int? = when (DriveSettings.route) {
        DriveSettings.Route.VPM_133 -> DriveSettings.vpmInt(PROP_EPTRDY_VPM).takeIf { it >= 0 }
        DriveSettings.Route.A9 -> call(a9(), "getSensorEptrdyState")
        DriveSettings.Route.VSM_68 -> call(LegacyCar.conditionManager(), "getEngineState")
    }

    fun gearRaw(): Int? = when (DriveSettings.route) {
        DriveSettings.Route.VPM_133 -> DriveSettings.vpmInt(PROP_GEAR_VPM).takeIf { it >= 0 }
        DriveSettings.Route.A9 -> call(a9(), "getGearState")
        DriveSettings.Route.VSM_68 -> call(LegacyCar.conditionManager(), "getCarGear")
    }?.takeIf { it >= 0 }

    private fun call(target: Any?, name: String): Int? {
        val t = target ?: return null
        return try { t.javaClass.getMethod(name).invoke(t) as? Int } catch (_: Exception) { null }
    }

    /** A9: CarStateClient(IBinder) from the stock launcher's classes, on queryClient(0xb), made on first use. */
    private fun a9(): Any? {
        val b = carStateBinder?.takeIf { it.isBinderAlive } ?: CarBridge.carStateBinder()?.also { carStateBinder = it; carState = null } ?: return null
        carState?.let { return it }
        val ctx = appContext ?: return null
        val cls = listOf("com.saicmotor.launcher", "com.saicmotor.vehiclesetting").firstNotNullOfOrNull { pkg ->
            try {
                ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                    .classLoader.loadClass("com.saicmotor.carapi.client.CarStateClient")
            } catch (_: Exception) { null }
        }
        carState = try { cls?.getConstructor(IBinder::class.java)?.newInstance(b) } catch (e: Exception) {
            // Read every second: one line, not one per try
            if (!errLogged) { errLogged = true; Log.w(TAG, "CarStateClient: ${e.message}") }
            null
        }
        return carState
    }
}
