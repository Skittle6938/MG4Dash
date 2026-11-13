/*
 * MG4Dash — driving settings (Driving card): drive mode, regeneration, one-pedal driving,
 * Custom mode steering and brake pedal, lane keeping, front collision warning.
 * Ported from MG4Hardware.kt of MG4Control (https://github.com/SliDeeN/MG4Control, commit 38557c7b),
 * Copyright © 2026 Slice Denkins and the MG4Control contributors, GPL v3 or later.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.car

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.Parcel
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log

/**
 * Reads and writes with exactly MG4Control's commands, values and fallbacks, firmware by firmware
 * (line numbers: MG4Hardware.kt at commit 38557c7b). Blocking: call off the main thread.
 *
 * Three routes, as MG4Control's `customFamily()` (l. 1953):
 *  - **A9** (SWI69, SWI131, SWI132; SWI173 too, which MG4Dash puts in this family): the SAIC
 *    `CarVehicleSettingClient`, built on the binder of `queryClient(8)` (l. 1331);
 *  - **SWI68 / SWI165**: the SDK's `VehicleSettingManager` (l. 1134);
 *  - **SWI133** (and unknown firmwares, like MG4Control): `VehiclePropertyManager` (l. 551), the same
 *    `VehicleSettingManager` for lane keeping (l. 769) and the "vehiclesetting" binder (l. 356).
 * Drive mode and regeneration go through CarPropertyManager on every firmware, global area 0x1000000
 * (l. 1909, 2139), with the binder transaction as a fallback.
 *
 * Unlike MG4Control's optional VehicleWriteGate, nothing is refused while driving (owner's choice).
 */
object DriveSettings {
    private const val TAG = "Drive"

    enum class Route { A9, VSM_68, VPM_133 }

    val route: Route get() = routeOf(Firmware.gen)

    internal fun routeOf(gen: Firmware.Gen): Route = when (gen) {
        Firmware.Gen.SWI69, Firmware.Gen.SWI131, Firmware.Gen.SWI132, Firmware.Gen.SWI173 -> Route.A9
        Firmware.Gen.SWI68, Firmware.Gen.SWI165 -> Route.VSM_68
        else -> Route.VPM_133
    }

    // MG4Hardware.kt l. 54-70, 88-91, 120-125, 1930-1932
    private const val AREA_GLOBAL = 0x1000000
    private const val PROP_DRIVE_MODE = 0x2140a17c
    private const val PROP_REGEN_LEVEL = 0x2140a191
    private const val PROP_ONE_PEDAL = 0x2140a193
    private const val TX_SET_DRIVE_MODE = 0x82
    private const val TX_SET_REGEN_LEVEL = 0xa1
    private const val TX_SET_ONE_PEDAL = 0xa4
    private const val DESCRIPTOR_VEHICLE = "com.saicmotor.sdk.vehiclesettings.IVehicleSettingService"
    private const val TX_ELK_GET_MODE = 0x53
    private const val TX_ELK_SET_MODE = 0x54
    private const val PROP_AEB_SWITCH = 0x2140a108
    private const val PROP_AEB_SYS_MODE = 0x302000a
    private const val PROP_AEB_MODE = 0x302000b
    private const val SIG_CUSTOM_STEERING = 0x2040004
    private const val SIG_CUSTOM_PEDAL = 0x2040005

    /** Drive modes (DriveMode.kt): 2 Eco, 3 Normal, 4 Sport, 6 Snow, 7 Custom. */
    val DRIVE_MODES = intArrayOf(6, 2, 3, 4, 7)
    /** Regeneration (RegenLevel.kt): 0 Low, 1 Medium, 2 High, 3 Adaptive; 6 = one-pedal. */
    const val REGEN_ONE_PEDAL = 6
    /** Lane keeping (ElkMode, l. 104): 1 off, 2 warning, 3 assist, 5 emergency keeping. */
    val LANE_MODES = intArrayOf(1, 2, 3, 5)
    /** Front collision (AebMode, l. 225): 1 warning, 2 warning + braking. */
    const val AEB_ALARM = 1
    const val AEB_ALARM_BRAKE = 2

    enum class Custom { STEERING, PEDAL }

    // ── Custom mode scale (CustomDriveScale.kt): index 0 comfort, 1 normal, 2 sport ──
    private val PEDAL_VALUES = intArrayOf(1, 0, 2)

    /** Value written for an index (steering 1/2/3, pedal 1/0/2, the same on the three routes). */
    fun customValue(setting: Custom, index: Int): Int? = if (index !in 0..2) null else when (setting) {
        Custom.STEERING -> index + 1
        Custom.PEDAL -> PEDAL_VALUES[index]
    }

    /** Index of a value read, or null if out of scale. */
    fun customIndex(setting: Custom, value: Int?): Int? = when {
        value == null -> null
        setting == Custom.STEERING -> (value - 1).takeIf { it in 0..2 }
        else -> PEDAL_VALUES.indexOf(value).takeIf { it >= 0 }
    }

    // ── Access objects ──

    private var appContext: Context? = null
    /** Binder of queryClient(8), handed by CarBridge (A9 route). */
    @Volatile internal var a9Binder: (() -> IBinder?)? = null
    @Volatile private var a9Client: Any? = null
    @Volatile private var a9ClientBinder: IBinder? = null
    @Volatile private var vpm: Any? = null
    @Volatile private var vpmTried = 0L
    @Volatile private var vehicleBinder: IBinder? = null

    fun init(context: Context) { appContext = context.applicationContext }

    /** A9: CarVehicleSettingClient(IBinder) from the stock launcher's classes (l. 1255-1349). */
    private fun a9(): Any? {
        val b = a9Binder?.invoke() ?: return null
        if (a9Client != null && a9ClientBinder === b && b.isBinderAlive) return a9Client
        val ctx = appContext ?: return null
        // l. 163-167: the launcher, or the Vehicle settings app on SWI131 (l. 3522)
        val cls = listOf("com.saicmotor.launcher", "com.saicmotor.vehiclesetting").firstNotNullOfOrNull { pkg ->
            try {
                ctx.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                    .classLoader.loadClass("com.saicmotor.carapi.client.CarVehicleSettingClient")
            } catch (_: Exception) { null }
        } ?: return null
        a9Client = try { cls.getConstructor(IBinder::class.java).newInstance(b) } catch (e: Exception) { Log.w(TAG, "CarVehicleSettingClient: ${e.message}"); null }
        a9ClientBinder = b
        return a9Client
    }

    /** The SDK's VehicleSettingManager (SWI68/165 and SWI133), shared with LegacyCar. */
    private fun vsm(): Any? = LegacyCar.settingManager()

    /** SWI133: VehiclePropertyManager, built and initialised as in initKatman4 (l. 551-650). */
    private fun vpm(): Any? {
        vpm?.let { return it }
        val now = System.currentTimeMillis()
        if (now - vpmTried < 10_000) return null
        vpmTried = now
        val ctx = appContext ?: return null
        val launcher = try { ctx.createPackageContext(Firmware.LEGACY_LAUNCHER, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { return null }
        val cls = try { launcher.classLoader.loadClass("com.saicmotor.sdk.vehiclesettings.manager.VehiclePropertyManager") } catch (_: Exception) { return null }
        fun attempt(block: () -> Any?): Any? = try { block() } catch (_: Exception) { null }
        val v = attempt { cls.getConstructor(Context::class.java).newInstance(launcher) }
            ?: attempt { cls.getConstructor(Context::class.java).newInstance(ctx) }
            ?: attempt { @Suppress("DEPRECATION") cls.newInstance() }
            ?: attempt { cls.getMethod("getInstance", Context::class.java).invoke(null, launcher) }
            ?: attempt { cls.getMethod("getInstance", Context::class.java).invoke(null, ctx) }
            ?: attempt { cls.getMethod("getInstance").invoke(null) }
            ?: return null
        attempt { v.javaClass.getMethod("bindService").invoke(v) }
        // initWithServiceListener (l. 654): init(Context, listener proxy), else init(Context, null), init(Context), init()
        val init2 = v.javaClass.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        var done = false
        if (init2 != null) {
            val lt = init2.parameterTypes[1]
            if (lt.isInterface) done = attempt { init2.invoke(v, ctx, java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { _, _, _ -> null }); true } != null
            if (!done) done = attempt { init2.invoke(v, ctx, null); true } != null
        }
        if (!done) done = attempt { v.javaClass.getMethod("init", Context::class.java).invoke(v, ctx); true } != null
        if (!done) attempt { v.javaClass.getMethod("init").invoke(v) }
        vpm = v
        Log.i(TAG, "VehiclePropertyManager ready")
        return v
    }

    /** ServiceManager "vehiclesetting" (l. 356, 533). */
    private fun vehicleBinder(): IBinder? {
        vehicleBinder?.takeIf { it.isBinderAlive }?.let { return it }
        vehicleBinder = try {
            @SuppressLint("PrivateApi")
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, "vehiclesetting") as? IBinder
        } catch (_: Exception) { null }
        return vehicleBinder
    }

    // ── Low-level calls, as MG4Control ──

    /** The route's VSM: CarVehicleSettingClient on A9, VehicleSettingManager on SWI68/165 (MG4Control's sVsm, null on SWI133). */
    private fun routeVsm(): Any? = when (route) { Route.A9 -> a9(); Route.VSM_68 -> vsm(); Route.VPM_133 -> null }

    /** Methods that failed once: read every 3 s, a missing one is logged once only. */
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun invoke(target: Any?, name: String, vararg args: Int): Any? {
        val t = target ?: return null
        return try {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            t.javaClass.getMethod(name, *types).invoke(t, *args.toTypedArray())
        } catch (e: Exception) { if (failed.add(name)) Log.w(TAG, "$name(): ${(e.cause ?: e).message}"); null }
    }

    /** callVsm (l. 1359). */
    private fun callVsm(name: String, vararg args: Int): Any? = invoke(routeVsm(), name, *args)

    /** callVsmVoid (l. 1376): true if the method ran without an exception. */
    private fun callVsmVoid(name: String, vararg args: Int): Boolean {
        val t = routeVsm() ?: return false
        return try {
            val types = Array<Class<*>>(args.size) { Int::class.javaPrimitiveType!! }
            t.javaClass.getMethod(name, *types).invoke(t, *args.toTypedArray())
            Log.i(TAG, "SET $name(${args.joinToString()})")
            true
        } catch (e: Exception) { Log.w(TAG, "$name: ${(e.cause ?: e).message}"); false }
    }

    /** callVsm133 (l. 1120): the VehicleSettingManager on SWI133. */
    private fun callVsm133(name: String, vararg args: Int): Any? = invoke(vsm(), name, *args)

    /** getIntPropertyCPM (l. 1747), -1 if unreadable. */
    private fun cpm(id: Int): Int = CarProps.readIntArea(id, AREA_GLOBAL) ?: -1

    /** setIntPropertyCPM (l. 1773). */
    private fun setCpm(id: Int, value: Int, label: String): Boolean = CarProps.setIntArea(id, AREA_GLOBAL, value, label)

    /** getIntPropertyVpm (l. 1390), -1 if unreadable. */
    internal fun vpmInt(id: Int): Int {
        val v = vpm() ?: return -1
        return try { (v.javaClass.getMethod("getIntProperty", Int::class.java).invoke(v, id) as? Int) ?: -1 } catch (_: Exception) { -1 }
    }

    /** setIntPropertyVpmRecovery (l. 1411), falling back on setIntProperty (l. 1398). */
    private fun setVpmRecovery(id: Int, value: Int): Boolean {
        val v = vpm() ?: return false
        return try {
            v.javaClass.getMethod("setIntPropertyRecovery", Int::class.java, Int::class.java).invoke(v, id, value)
            Log.i(TAG, "SET VPM recovery 0x${id.toString(16)} = $value"); true
        } catch (_: Exception) {
            try { v.javaClass.getMethod("setIntProperty", Int::class.java, Int::class.java).invoke(v, id, value); true } catch (_: Exception) { false }
        }
    }

    /** binderTransact (l. 1814): [token, AREA_GLOBAL, 1, value, float[], byte[]], status 0 = accepted. */
    private fun binderTransact(txCode: Int, value: Int): Boolean {
        val binder = vehicleBinder() ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            data.writeInt(AREA_GLOBAL); data.writeInt(1); data.writeInt(value)
            data.writeFloatArray(FloatArray(0)); data.writeByteArray(ByteArray(0))
            binder.transact(txCode, data, reply, 0)
            val status = if (reply.dataAvail() > 0) reply.readInt() else 0
            Log.i(TAG, "binder tx 0x${txCode.toString(16)} = $value → $status"); status == 0
        } catch (e: Exception) { Log.w(TAG, "binder tx 0x${txCode.toString(16)}: ${e.message}"); false }
        finally { data.recycle(); reply.recycle() }
    }

    /** elkBinderGet (l. 3172). */
    private fun elkBinderGet(txCode: Int): Int {
        val binder = vehicleBinder() ?: return -1
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE)
            if (!binder.transact(txCode, data, reply, 0)) return -1
            reply.readException(); reply.readInt()
        } catch (_: Exception) { -1 } finally { data.recycle(); reply.recycle() }
    }

    /** elkBinderSet (l. 3203): one-way. */
    private fun elkBinderSet(txCode: Int, value: Int): Boolean {
        val binder = vehicleBinder() ?: return false
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR_VEHICLE); data.writeInt(value)
            binder.transact(txCode, data, null, IBinder.FLAG_ONEWAY); true
        } catch (_: Exception) { false } finally { data.recycle() }
    }

    // ── Reads ──

    /** getDriveMode (l. 2196): the property, then the SDK method (getDrivingMode on A9, l. 2221). */
    fun driveMode(): Int? {
        cpm(PROP_DRIVE_MODE).takeIf { it in DRIVE_MODES }?.let { return it }
        return (callVsm(if (route == Route.A9) "getDrivingMode" else "getDriveMode") as? Int)?.takeIf { it in DRIVE_MODES }
    }

    /** getRegenLevel (l. 2224): the property only. */
    fun regenLevel(): Int? = cpm(PROP_REGEN_LEVEL).takeIf { it in 0..3 || it == 5 || it == REGEN_ONE_PEDAL }

    /** One-pedal driving: the property MG4Control writes (l. 2159), or regeneration at 6 (RegenLevel.ONE_PEDAL). */
    fun onePedal(regen: Int?): Int? = cpm(PROP_ONE_PEDAL).takeIf { it in 0..1 } ?: regen?.let { if (it == REGEN_ONE_PEDAL) 1 else null }

    /** getCustom (l. 2040): the direct way, then on SWI68/165 the settings snapshot (l. 2029). Raw value. */
    fun custom(setting: Custom): Int? {
        val direct = when (route) {
            Route.A9 -> callVsm(if (setting == Custom.STEERING) "getSteeringMode" else "getBrakePedalMode") as? Int
            Route.VSM_68 -> callVsm(if (setting == Custom.STEERING) "getSteeringLevel" else "getBrakePedalLevel") as? Int
            Route.VPM_133 -> vpmInt(if (setting == Custom.STEERING) SIG_CUSTOM_STEERING else SIG_CUSTOM_PEDAL)
        }
        val bean = if (route == Route.VSM_68) {
            val getter = if (setting == Custom.STEERING) "getSteeringLevel" else "getBrakePedalLevel"
            callVsm("getVehicleSettingStatus")?.let { b -> try { b.javaClass.getMethod(getter).invoke(b) as? Int } catch (_: Exception) { null } }
        } else null
        return listOf(direct, bean).firstOrNull { it != null && it >= 0 && customIndex(setting, it) != null }
    }

    /** getElkMode (l. 3032), 1 off, 2 warning, 3 assist, 5 emergency. */
    fun laneMode(): Int? = when (route) {
        Route.VPM_133 -> (callVsm133("getLaneKeepingAsstMode") as? Int)?.takeIf { it > 0 } ?: elkBinderGet(TX_ELK_GET_MODE)
        Route.A9 -> (callVsm("getLasMode") as? Int)?.takeIf { it > 0 }
        Route.VSM_68 -> (callVsm("getLaneKeepingAsstMode") as? Int)?.takeIf { it > 0 }
    }?.takeIf { it in LANE_MODES }

    /** isAebEnabled (l. 2417): 2 = on, 1 = off, null if unreadable. */
    fun fcwState(): Int? = when (route) {
        Route.A9 -> callVsm("getFcwState") as? Int
        Route.VSM_68 -> callVsm("getFcwAlarmMode") as? Int
        Route.VPM_133 -> cpm(PROP_AEB_SWITCH)
    }?.takeIf { it in 1..2 }

    /** getAebMode (l. 2467): 1 warning, 2 warning + braking. */
    fun fcwMode(): Int? = when (route) {
        Route.VPM_133 -> vpmInt(PROP_AEB_MODE)
        else -> callVsm("getFcwAutoBrakeMode") as? Int
    }?.takeIf { it in 1..2 }

    /**
     * Drowsiness warning (UDW, "unfit driver warning"), MG4Control's isDrowsinessOn / setDrowsiness:
     * 1 = off, 2 = on (0 read as off). A9: CarVehicleSettingClient get/setUdwStatus; SWI68/165:
     * get/setUnsteadyDrivingWarning; SWI133: VPM property 0x3010005 (UDW_MAIN_SWITCH, not DMS_SWITCH).
     * null if unreadable.
     */
    fun udwOn(): Boolean? = when (route) {
        Route.A9 -> callVsm("getUdwStatus") as? Int
        Route.VSM_68 -> callVsm("getUnsteadyDrivingWarning") as? Int
        Route.VPM_133 -> vpmInt(PROP_UDW_MAIN_SWITCH)
    }?.let { when (it) { 2 -> true; 0, 1 -> false; else -> null } }

    fun setUdw(on: Boolean): Boolean {
        val v = if (on) 2 else 1
        return when (route) {
            Route.A9 -> callVsmVoid("setUdwStatus", v)
            Route.VSM_68 -> callVsmVoid("setUnsteadyDrivingWarning", v)
            Route.VPM_133 -> setVpmRecovery(PROP_UDW_MAIN_SWITCH, v)
        }
    }
    private const val PROP_UDW_MAIN_SWITCH = 0x3010005

    /** Every setting into the vehicle state; a setting that does not answer stays null (greyed on the card). */
    fun read(s: VehicleState): VehicleState {
        val regen = regenLevel()
        return s.copy(
            driveMode = driveMode(), regenLevel = regen, onePedal = onePedal(regen),
            steeringMode = custom(Custom.STEERING), brakePedalMode = custom(Custom.PEDAL),
            lasMode = laneMode(), fcwState = fcwState(), fcwBrakeMode = fcwMode(),
        )
    }

    // ── Writes ──

    /** setDriveMode (l. 1909). */
    fun setDriveMode(mode: Int): Boolean {
        val ok = setCpm(PROP_DRIVE_MODE, mode, "drive mode")
        if (!ok) binderTransact(TX_SET_DRIVE_MODE, mode)
        return ok
    }

    /** setOnePedal (l. 2156). */
    fun setOnePedal(on: Boolean): Boolean {
        val v = if (on) 1 else 0
        val ok = setCpm(PROP_ONE_PEDAL, v, "one pedal")
        if (!ok) binderTransact(TX_SET_ONE_PEDAL, v)
        return ok
    }

    /** setRegenLevel (l. 2139): 0..3, or [REGEN_ONE_PEDAL]. */
    fun setRegenLevel(level: Int): Boolean = if (level == REGEN_ONE_PEDAL) {
        val ok = setOnePedal(true)
        if (!ok) { setCpm(PROP_REGEN_LEVEL, level, "regen"); binderTransact(TX_SET_REGEN_LEVEL, level) }
        ok
    } else {
        setOnePedal(false)
        val ok = setCpm(PROP_REGEN_LEVEL, level, "regen")
        if (!ok) binderTransact(TX_SET_REGEN_LEVEL, level)
        ok
    }

    /** setCustom (l. 1986): [index] 0 comfort, 1 normal, 2 sport. */
    fun setCustom(setting: Custom, index: Int): Boolean {
        val value = customValue(setting, index) ?: return false
        return when (route) {
            Route.A9 -> callVsmVoid(if (setting == Custom.STEERING) "setSteeringMode" else "setBrakePedalMode", value)
            Route.VSM_68 -> callVsmVoid(if (setting == Custom.STEERING) "setSteeringLevel" else "setBrakePedalLevel", value)
            Route.VPM_133 -> setVpmRecovery(if (setting == Custom.STEERING) SIG_CUSTOM_STEERING else SIG_CUSTOM_PEDAL, value)
        }
    }

    /** setElkMode (l. 3053). */
    fun setLaneMode(mode: Int): Boolean = when (route) {
        Route.VPM_133 -> if (vsm() != null) { callVsm133("setLaneKeepingAsstMode", mode); true } else elkBinderSet(TX_ELK_SET_MODE, mode)
        Route.A9 -> { callVsm("setLasMode", mode); true }
        Route.VSM_68 -> { callVsm("setLaneKeepingAsstMode", mode); true }
    }

    /** setAebEnabled (l. 2427). */
    fun setFcwEnabled(on: Boolean): Boolean = when (route) {
        Route.A9 -> if (on) {
            val sOk = callVsmVoid("setFcwState", 2)
            val cur = (callVsm("getFcwAutoBrakeMode") as? Int) ?: 1
            callVsmVoid("setFcwAutoBrakeMode", cur) || sOk
        } else {
            callVsmVoid("setFcwState", 1); callVsmVoid("setFcwAutoBrakeMode", 1); callVsmVoid("setFcwSensitivity", 0)
        }
        Route.VSM_68 -> if (on) callVsmVoid("setFcwAlarmMode", 2) else callVsmVoid("setFcwAlarmMode", 1) or callVsmVoid("setFcwAutoBrakeMode", 1)
        Route.VPM_133 -> setCpm(PROP_AEB_SWITCH, if (on) 2 else 1, "front collision")
    }

    /** setAebMode (l. 2476). */
    fun setFcwMode(mode: Int): Boolean = when (route) {
        Route.A9 -> {
            val m = if (mode == AEB_ALARM_BRAKE) 2 else 1
            callVsmVoid("setFcwAutoBrakeMode", m) or callVsmVoid("setFcwState", 2)
        }
        Route.VSM_68 -> callVsm("setFcwAutoBrakeMode", if (mode == AEB_ALARM_BRAKE) 2 else 1) != null
        Route.VPM_133 -> if (mode == AEB_ALARM_BRAKE) {
            setVpmRecovery(PROP_AEB_SYS_MODE, AEB_ALARM_BRAKE) or setVpmRecovery(PROP_AEB_MODE, AEB_ALARM_BRAKE)
        } else setVpmRecovery(PROP_AEB_MODE, AEB_ALARM)
    }

    /**
     * The card's three choices: 0 off ([setFcwEnabled] false), 1 warning, 2 warning + braking. On A9 [setFcwMode]
     * turns the system on itself (l. 2482); elsewhere it only sets the mode, so the system is turned on first.
     */
    fun setFcw(choice: Int, current: Int?): Boolean = when {
        choice == 0 -> setFcwEnabled(false)
        route == Route.A9 -> setFcwMode(choice)
        else -> { if (current != 2) setFcwEnabled(true); setFcwMode(choice) }
    }
}
