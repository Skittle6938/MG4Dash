/*
 * MG4Dash — car access for "old SDK" firmwares (SWI68, SWI165, SWI133).
 * Climate adapted from MG4Hardware.kt of MG4Control (https://github.com/SliDeeN/MG4Control),
 * Copyright © 2026 Slice Denkins and the MG4Control contributors, GPL v3 or later.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.car

import android.content.Context
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log

/**
 * Climate on old SDK firmwares: `AirConditionManager`, loaded from the stock launcher
 * (`com.saicmotor.hmi.launcher`) then initialised with a dummy listener (the SDK refuses null).
 *
 * Not tested by the author (SWI69): everything is read by reflection and each failure returns null, the screen
 * then greys the setting. Behaviours found by MG4Control on these firmwares:
 *  - A/C, AUTO and recirculation are **presses**: the argument is ignored, move one step
 *    then reread;
 *  - recirculation is read on the HVAC_AC_LOOP_MODE property (0 inside, 1 outside, 2 auto).
 */
object LegacyCar {
    private const val TAG = "CarLegacy"
    private const val AIRCON_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.AirConditionManager"
    private const val PROP_LOOP_MODE = 0x15402507
    private const val PROP_BLOWER_DIRECTION = 0x1540250e
    private const val PROP_AC_ON = 0x15402500
    private const val AREA_HVAC = 117

    @Volatile private var ac: Any? = null
    @Volatile private var initDone = false
    private var appContext: Context? = null

    val ready: Boolean get() = ac != null

    fun init(context: Context) {
        appContext = context.applicationContext
        connect()
        connectCharging()
    }

    private fun connect() {
        if (ac != null || initDone) return
        val ctx = appContext ?: return
        initDone = true
        val launcher = listOf(Firmware.LEGACY_LAUNCHER, "com.saicmotor.launcher").firstNotNullOfOrNull { pkg ->
            try { ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { null }
        } ?: run { Log.w(TAG, "SAIC launcher (old SDK) not found"); return }
        val cls = try { launcher.classLoader.loadClass(AIRCON_CLASS) } catch (e: Exception) {
            Log.w(TAG, "AirConditionManager missing: ${e.message}"); return
        }
        fun singleton(): Any? = try { cls.getMethod("getInstance").invoke(null) } catch (_: Exception) { null }
        val initM = cls.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        if (initM != null) {
            val lt = initM.parameterTypes[1]
            val listener = if (lt.isInterface) try {
                java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { _, m, _ ->
                    if (m.name == "onServiceConnected") { ac = singleton(); Log.i(TAG, "AirConditionManager connected") }
                    null
                }
            } catch (_: Exception) { null } else null
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "AirConditionManager.init: ${e.message}") }
        }
        if (ac == null) ac = singleton()
    }

    /** The car's own set point and fan limits read (#107), once connected. */
    @Volatile private var limitsRead = false
    /** For the Compatibility report: "LO 15, HI 31, fan 1..11 (car)" or the fallback used. */
    @Volatile var limitsText = "—"; private set

    /**
     * The LO / HI set points of this car (#107): getMinTemp / getMaxTemp give the LO and HI values (17 / 33 on SWI68
     * R46, 15 / 31 on R67, R73 and recent SWI165), the numbers shown in between. 17 / 33 if not answered.
     */
    private fun readLimits() {
        if (limitsRead || ac == null) return
        limitsRead = true
        val (lo, hi) = tempLimits(int("getMinTemp"), int("getMaxTemp"))
        VehicleState.tempLo = lo.toFloat(); VehicleState.tempHi = hi.toFloat()
        val fanMax = int("getMaxAirVolume")
        limitsText = "LO $lo, HI $hi (read ${int("getMinTemp")} / ${int("getMaxTemp")}), fan max ${fanMax ?: "—"}"
        Log.i(TAG, "climate limits: $limitsText")
    }

    /** LO and HI from the car's getMinTemp / getMaxTemp, if plausible; otherwise 17 / 33. */
    internal fun tempLimits(min: Int?, max: Int?): Pair<Int, Int> =
        if (min != null && max != null && min in 10..20 && max in 28..40 && max - min >= 10) min to max else 17 to 33

    /** The set point as the car service holds it, raw (-1 for a half degree or out of its range). */
    fun drvTempRaw(): Int? = int("getDrvTemp")

    private fun int(name: String): Int? {
        val a = ac ?: return null
        return try { a.javaClass.getMethod(name).invoke(a) as? Int } catch (_: Exception) { null }
    }

    private fun float(name: String): Float? {
        val a = ac ?: return null
        return try { a.javaClass.getMethod(name).invoke(a) as? Float } catch (_: Exception) { null }
    }

    private fun set(name: String, v: Int): Boolean {
        val a = ac ?: return false
        return try { a.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(a, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v): ${(e.cause ?: e).message}"); false }
    }

    private fun call(name: String): Boolean {
        val a = ac ?: return false
        return try { a.javaClass.getMethod(name).invoke(a); true }
        catch (e: Exception) { Log.w(TAG, "$name(): ${(e.cause ?: e).message}"); false }
    }

    private fun prop(id: Int): Int? = CarProps.readIntArea(id, AREA_HVAC)?.takeIf { it >= 0 }

    /** Reads the climate into the vehicle state; unchanged if the service does not answer. */
    fun readHvac(s: VehicleState): VehicleState {
        if (ac == null) { initDone = false; connect() }
        if (ac == null) return s
        readLimits()
        fun b(v: Int?) = v?.takeIf { it >= 0 }?.let { it == 1 }
        val fan = int("getAirVolumeLevel")
        return s.copy(
            connected = true,
            hvacOn = b(int("getHvacPowerStatus")),
            acOn = b(int("getAcSwitch")),
            // Fan level 15 is AUTO (#107): shown as AUTO, as the stock climate app, even before the AUTO state follows
            autoOn = if (fan == FAN_AUTO) true else b(int("getAutoStatus")),
            recirc = prop(PROP_LOOP_MODE),
            // AUTO (15) or invalid (-1): the last manual level kept, − / + go on from it as in the stock app
            fanSpeed = fan?.let { validFan(it, s.fanSpeed) },
            fanDirection = (prop(PROP_BLOWER_DIRECTION) ?: int("getBlowerDirectionMode"))?.takeIf { it in 0..7 },
            driverTemp = int("getDrvTemp")?.takeIf { it in 10..40 }?.toFloat(),
            outsideTemp = float("getOutCarTemp")?.takeIf { !it.isNaN() && it > -60f && it < 70f } ?: s.outsideTemp,
            defrostFront = b(int("getFrontWindowDefroster")),
            defrostRear = b(int("getBackWindowDefroster")),
            // Climate settings (#65): VehicleSettingManager, same values assumed as on SWI69
            autoAirVolume = vsInt("getAutoModeAirVolume")?.takeIf { it in 0..2 },
            defrostLinked = vsInt("getDefrostLinkage")?.takeIf { it in 0..1 }?.let { it == 0 },
            ecoLinked = vsInt("getHvacEconLinkage")?.takeIf { it in 1..2 }?.let { it == 1 },
        )
    }

    private fun vsSet(name: String, v: Int): Boolean {
        vsInt("getAutoModeAirVolume")   // connect to the manager if needed
        val c = setting ?: return false
        return try { c.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(c, v); true }
        catch (e: Exception) { Log.w(TAG, "$name: ${(e.cause ?: e).message}"); false }
    }
    fun setAutoAirVolume(level: Int) = vsSet("setAutoModeAirVolume", level)
    fun setDefrostLinked(on: Boolean) = vsSet("setDefrostLinkage", if (on) 0 else 1)
    fun setEcoLinked(on: Boolean) = vsSet("setHvacEconLinkage", if (on) 1 else 2)

    // ── Commands (blocking: called off the main thread by CarBridge) ──

    fun power(on: Boolean) = call(if (on) "openHvacPower" else "closeHvacPower")
    fun temp(c: Int) = set("setDrvTemp", c)
    fun fan(level: Int) = set("setAirVolumeLevel", level)
    fun direction(v: Int) = set("setBlowerDirectionMode", v)
    fun defrostFront(on: Boolean) = call(if (on) "openFrontWindowDefroster" else "closeFrontWindowDefroster")
    fun defrostRear(on: Boolean) = call(if (on) "openBackWindowDefroster" else "closeBackWindowDefroster")

    /** A/C: press, reread on HVAC_AC_ON (1 = on). */
    fun ac(on: Boolean) = cycle(PROP_AC_ON, if (on) 1 else 0, 2) { set("setAcStatus", 1) }

    /** AUTO: a press turns it on; to leave it, write a fan level (like the stock screen). */
    fun auto(on: Boolean): Boolean {
        val cur = int("getAutoStatus")?.let { it == 1 }
        if (cur == on) return true
        return if (on) set("setAutoStatus", 1) else fan((int("getAirVolumeLevel")?.takeIf { it in 1..10 } ?: 3))
    }

    /** Recirculation: press moving one step (outside → inside → auto), reread on the property. */
    fun loop(target: Int) = cycle(PROP_LOOP_MODE, target, 3) { set("setLoopMode", 1) }

    /** Fan level the old SDK gives in AUTO (stock HVAC app: "air_volume_auto"). */
    const val FAN_AUTO = 15

    /** Fan level read: kept when 0 (off) to [VehicleState.FAN_MAX]; otherwise the last valid one ([last]). */
    internal fun validFan(read: Int, last: Int?): Int? = if (read in 0..VehicleState.FAN_MAX) read else last

    /** Recirculation mode read (0 inside, 1 outside, 2 auto), or null. */
    fun loopMode(): Int? = prop(PROP_LOOP_MODE)

    /** One recirculation step. */
    fun loopStep() { set("setLoopMode", 1) }

    private fun cycle(id: Int, target: Int, max: Int, advance: () -> Unit): Boolean {
        repeat(max + 1) {
            val cur = prop(id) ?: return false
            if (cur == target) return true
            advance()
            Thread.sleep(400)
        }
        return prop(id) == target
    }

    // ── Charging and V2L: VehicleChargingManager of the same SDK ──
    // Same logic as the A9 route: 1 = start, 2 = stop (CHARGING / DISCHARGING
    // _CONTROL_SWITCH signals); the charge state follows the same table as the A9 launcher.
    private const val CHARGING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleChargingManager"
    @Volatile private var charging: Any? = null
    @Volatile private var chargingInit = false

    val chargingReady: Boolean get() = charging != null

    private fun connectCharging() {
        if (charging != null || chargingInit) return
        val ctx = appContext ?: return
        chargingInit = true
        charging = sdkManager(ctx, CHARGING_CLASS) { charging = it }
    }

    /** Loads a SAIC SDK manager from the stock launcher and initialises it (dummy listener). */
    private fun sdkManager(ctx: Context, className: String, onConnected: (Any?) -> Unit): Any? {
        val launcher = listOf(Firmware.LEGACY_LAUNCHER, "com.saicmotor.launcher").firstNotNullOfOrNull { pkg ->
            try { ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { null }
        } ?: return null
        val cls = try { launcher.classLoader.loadClass(className) } catch (_: Exception) { return null }
        fun singleton(): Any? = (try { cls.getMethod("getInstance").invoke(null) } catch (_: Exception) { null })
            ?: cls.declaredFields.firstOrNull { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == cls }
                ?.let { f -> try { f.isAccessible = true; f.get(null) } catch (_: Exception) { null } }
        val initM = cls.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        if (initM != null) {
            val lt = initM.parameterTypes[1]
            val listener = if (lt.isInterface) try {
                java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { _, m, _ ->
                    if (m.name == "onServiceConnected") { onConnected(singleton()); Log.i(TAG, "${className.substringAfterLast('.')} connected") }
                    null
                }
            } catch (_: Exception) { null } else null
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "${className.substringAfterLast('.')}.init: ${e.message}") }
        }
        return singleton()
    }

    // ── Driving settings: VehicleSettingManager of the same SDK (SWI68/165, like MG4Control, #37) ──
    // Custom mode steering and pedal ("…Level" methods, scales 1/2/3 and 1/0/2) and
    // Range+ ("LongerEndurance"). VHAL properties read directly gave meaningless values.
    private const val SETTING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    @Volatile private var setting: Any? = null
    @Volatile private var settingInit = false

    // ── Vehicle condition (READY, gear): VehicleConditionManager of the same SDK (SWI68/165, as MG4Control) ──
    private const val CONDITION_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleConditionManager"
    @Volatile private var condition: Any? = null
    @Volatile private var conditionInit = false

    /** The SDK's VehicleConditionManager, connected on first use; used by [ReadyState]. */
    internal fun conditionManager(): Any? {
        if (condition == null && !conditionInit) { val ctx = appContext ?: return null; conditionInit = true; condition = sdkManager(ctx, CONDITION_CLASS) { condition = it } }
        return condition
    }

    /** The SDK's VehicleSettingManager, connected on first use; also used by [DriveSettings]. */
    internal fun settingManager(): Any? {
        if (setting == null && !settingInit) { val ctx = appContext ?: return null; settingInit = true; setting = sdkManager(ctx, SETTING_CLASS) { setting = it } }
        return setting
    }

    private fun vsInt(name: String): Int? {
        val c = settingManager() ?: return null
        return try { c.javaClass.getMethod(name).invoke(c) as? Int } catch (_: Exception) { null }
    }

    /** Range+ (the driving settings themselves are read by [DriveSettings]). */
    fun readDriving(s: VehicleState): VehicleState =
        s.copy(enduranceMode = vsInt("getLongerEndurance")?.takeIf { it in 0..1 })

    // ── ESC: VehicleControlManager of the same SDK (SWI68/165, methods found by MG4Control) ──
    // Read 1 = active, 0 or 2 = off; writing is a toggle (1), like the stock switch.
    private const val CONTROL_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleControlManager"
    @Volatile private var control: Any? = null
    @Volatile private var controlInit = false

    private fun connectControl() {
        if (control != null || controlInit) return
        val ctx = appContext ?: return
        controlInit = true
        control = sdkManager(ctx, CONTROL_CLASS) { control = it }
    }

    /** ESC active (true), off (false), or null if this SDK does not answer. */
    fun escActive(): Boolean? {
        connectControl()
        val c = control ?: return null
        val v = try { c.javaClass.getMethod("getEspSwitch").invoke(c) as? Int } catch (_: Exception) { null } ?: return null
        return when (v) { 1 -> true; 0, 2 -> false; else -> null }
    }

    fun toggleEsc(): Boolean {
        connectControl()
        val c = control ?: return false
        return try { c.javaClass.getMethod("setEspSwitch", Int::class.javaPrimitiveType).invoke(c, 1); true }
        catch (e: Exception) { Log.w(TAG, "setEspSwitch: ${(e.cause ?: e).message}"); false }
    }

    private fun chInt(name: String): Int? {
        val c = charging ?: return null
        return try { c.javaClass.getMethod(name).invoke(c) as? Int } catch (_: Exception) { null }
    }

    private fun chSet(name: String, v: Int): Boolean {
        val c = charging ?: return false
        return try { c.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(c, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v): ${(e.cause ?: e).message}"); false }
    }

    /** Charge state and running commands; unchanged if the service does not answer. */
    fun readCharging(s: VehicleState): VehicleState {
        if (charging == null) { chargingInit = false; connectCharging() }
        if (charging == null) return s
        return s.copy(
            chargeStatus = chInt("getChargingStatus")?.takeIf { it >= 0 } ?: s.chargeStatus,
            chargeCtrl = chInt("getChargingControlSwitch")?.takeIf { it >= 0 } ?: s.chargeCtrl,
            dischargeCtrl = chInt("getDischrgControlStatus")?.takeIf { it >= 0 } ?: s.dischargeCtrl,
            chargeRemainMin = chInt("getPredictChargingTime")?.takeIf { it in 0..6000 } ?: s.chargeRemainMin,
            dischargeRemainMin = chInt("getPredictDischrgTime")?.takeIf { it in 0..6000 } ?: s.dischargeRemainMin,
        )
    }

    fun chargeCtrl(v: Int) = chSet("setChargingControlSwitch", v)
    fun dischargeCtrl(v: Int) = chSet("setDischrgControlSwitch", v)

    /** For the Compatibility screen. */
    fun probe(): String = when {
        ac == null -> "absent"
        else -> "ok (temp ${int("getDrvTemp")}, fan ${int("getAirVolumeLevel")})"
    }

    fun probeCharging(): String = if (charging == null) "absent" else "ok (status ${chInt("getChargingStatus")}, ctrl ${chInt("getChargingControlSwitch")})"
}
