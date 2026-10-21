/*
 * MG4Dash — accès voiture des firmwares « ancien SDK » (SWI68, SWI165, SWI133).
 * Clim adaptée de MG4Hardware.kt de MG4Control (https://github.com/SliDeeN/MG4Control),
 * Copyright © 2026 Slice Denkins et les contributeurs de MG4Control, GPL v3 ou ultérieure.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.car

import android.content.Context
import app.mg4dash.system.Firmware
import app.mg4dash.system.Log

/**
 * Clim des firmwares ancien SDK : `AirConditionManager`, chargé depuis le launcher d'origine
 * (`com.saicmotor.hmi.launcher`) puis initialisé avec un écouteur factice (le SDK refuse null).
 *
 * Non testé par l'auteur (SWI69) : tout est lu par réflexion et chaque échec rend null, l'écran
 * grise alors le réglage. Comportements relevés par MG4Control sur ces firmwares :
 *  - A/C, AUTO et recyclage sont des **appuis** : l'argument est ignoré, on avance d'un cran
 *    puis on relit ;
 *  - le recyclage se lit sur la propriété HVAC_AC_LOOP_MODE (0 intérieur, 1 extérieur, 2 auto).
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
        } ?: run { Log.w(TAG, "launcher SAIC (ancien SDK) introuvable"); return }
        val cls = try { launcher.classLoader.loadClass(AIRCON_CLASS) } catch (e: Exception) {
            Log.w(TAG, "AirConditionManager absent : ${e.message}"); return
        }
        fun singleton(): Any? = try { cls.getMethod("getInstance").invoke(null) } catch (_: Exception) { null }
        val initM = cls.methods.firstOrNull { it.name == "init" && it.parameterCount == 2 && Context::class.java.isAssignableFrom(it.parameterTypes[0]) }
        if (initM != null) {
            val lt = initM.parameterTypes[1]
            val listener = if (lt.isInterface) try {
                java.lang.reflect.Proxy.newProxyInstance(lt.classLoader, arrayOf(lt)) { _, m, _ ->
                    if (m.name == "onServiceConnected") { ac = singleton(); Log.i(TAG, "AirConditionManager connecté") }
                    null
                }
            } catch (_: Exception) { null } else null
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "AirConditionManager.init : ${e.message}") }
        }
        if (ac == null) ac = singleton()
    }

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
        catch (e: Exception) { Log.w(TAG, "$name($v) : ${(e.cause ?: e).message}"); false }
    }

    private fun call(name: String): Boolean {
        val a = ac ?: return false
        return try { a.javaClass.getMethod(name).invoke(a); true }
        catch (e: Exception) { Log.w(TAG, "$name() : ${(e.cause ?: e).message}"); false }
    }

    private fun prop(id: Int): Int? = CarProps.readIntArea(id, AREA_HVAC)?.takeIf { it >= 0 }

    /** Lecture de la clim dans l'état véhicule ; inchangé si le service ne répond pas. */
    fun readHvac(s: VehicleState): VehicleState {
        if (ac == null) { initDone = false; connect() }
        if (ac == null) return s
        fun b(v: Int?) = v?.takeIf { it >= 0 }?.let { it == 1 }
        return s.copy(
            connected = true,
            hvacOn = b(int("getHvacPowerStatus")),
            acOn = b(int("getAcSwitch")),
            autoOn = b(int("getAutoStatus")),
            recirc = prop(PROP_LOOP_MODE),
            fanSpeed = int("getAirVolumeLevel")?.takeIf { it >= 0 },
            fanDirection = (prop(PROP_BLOWER_DIRECTION) ?: int("getBlowerDirectionMode"))?.takeIf { it in 0..7 },
            driverTemp = int("getDrvTemp")?.takeIf { it in 10..40 }?.toFloat(),
            outsideTemp = float("getOutCarTemp")?.takeIf { !it.isNaN() && it > -60f && it < 70f } ?: s.outsideTemp,
            defrostFront = b(int("getFrontWindowDefroster")),
            defrostRear = b(int("getBackWindowDefroster")),
        )
    }

    // ── Commandes (bloquantes : appelées hors du fil principal par CarBridge) ──

    fun power(on: Boolean) = call(if (on) "openHvacPower" else "closeHvacPower")
    fun temp(c: Int) = set("setDrvTemp", c)
    fun fan(level: Int) = set("setAirVolumeLevel", level)
    fun direction(v: Int) = set("setBlowerDirectionMode", v)
    fun defrostFront(on: Boolean) = call(if (on) "openFrontWindowDefroster" else "closeFrontWindowDefroster")
    fun defrostRear(on: Boolean) = call(if (on) "openBackWindowDefroster" else "closeBackWindowDefroster")

    /** A/C : appui, relu sur HVAC_AC_ON (1 = allumé). */
    fun ac(on: Boolean) = cycle(PROP_AC_ON, if (on) 1 else 0, 2) { set("setAcStatus", 1) }

    /** AUTO : un appui l'allume ; pour en sortir, on écrit un niveau de ventilation (comme l'écran d'origine). */
    fun auto(on: Boolean): Boolean {
        val cur = int("getAutoStatus")?.let { it == 1 }
        if (cur == on) return true
        return if (on) set("setAutoStatus", 1) else fan((int("getAirVolumeLevel")?.takeIf { it in 1..10 } ?: 3))
    }

    /** Recyclage : appui qui avance d'un cran (extérieur → intérieur → auto), relu sur la propriété. */
    fun loop(target: Int) = cycle(PROP_LOOP_MODE, target, 3) { set("setLoopMode", 1) }

    private fun cycle(id: Int, target: Int, max: Int, advance: () -> Unit): Boolean {
        repeat(max + 1) {
            val cur = prop(id) ?: return false
            if (cur == target) return true
            advance()
            Thread.sleep(400)
        }
        return prop(id) == target
    }

    // ── Recharge et V2L : VehicleChargingManager du même SDK ──
    // Même logique que la voie A9 : 1 = démarrer, 2 = arrêter (signaux CHARGING / DISCHARGING
    // _CONTROL_SWITCH) ; l'état de charge suit la même table que le launcher A9.
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

    /** Charge un gestionnaire du SDK SAIC depuis le launcher d'origine et l'initialise (écouteur factice). */
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
                    if (m.name == "onServiceConnected") { onConnected(singleton()); Log.i(TAG, "${className.substringAfterLast('.')} connecté") }
                    null
                }
            } catch (_: Exception) { null } else null
            try { initM.invoke(null, ctx, listener) } catch (e: Exception) { Log.w(TAG, "${className.substringAfterLast('.')}.init : ${e.message}") }
        }
        return singleton()
    }

    // ── Réglages de conduite : VehicleSettingManager du même SDK (SWI68/165, comme MG4Control, #37) ──
    // Direction et pédale du mode Personnalisé (méthodes « …Level », échelles 1/2/3 et 1/0/2) et
    // Range+ (« LongerEndurance »). Les propriétés VHAL lues directement donnaient des valeurs sans sens.
    private const val SETTING_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleSettingManager"
    @Volatile private var setting: Any? = null
    @Volatile private var settingInit = false

    private fun vsInt(name: String): Int? {
        if (setting == null && !settingInit) { val ctx = appContext ?: return null; settingInit = true; setting = sdkManager(ctx, SETTING_CLASS) { setting = it } }
        val c = setting ?: return null
        return try { c.javaClass.getMethod(name).invoke(c) as? Int } catch (_: Exception) { null }
    }

    /** Direction et pédale seulement en mode Personnalisé (7), comme l'écran d'origine ; sinon masquées. */
    fun readDriving(s: VehicleState): VehicleState {
        val custom = s.driveMode == 7
        return s.copy(
            steeringMode = if (custom) vsInt("getSteeringLevel")?.takeIf { it in 1..3 } else null,
            brakePedalMode = if (custom) vsInt("getBrakePedalLevel")?.takeIf { it in 0..2 } else null,
            enduranceMode = vsInt("getLongerEndurance")?.takeIf { it in 0..1 },
        )
    }

    // ── ESC : VehicleControlManager du même SDK (SWI68/165, méthodes relevées par MG4Control) ──
    // Lecture 1 = actif, 0 ou 2 = coupé ; l'écriture est une bascule (1), comme l'interrupteur d'origine.
    private const val CONTROL_CLASS = "com.saicmotor.sdk.vehiclesettings.manager.VehicleControlManager"
    @Volatile private var control: Any? = null
    @Volatile private var controlInit = false

    private fun connectControl() {
        if (control != null || controlInit) return
        val ctx = appContext ?: return
        controlInit = true
        control = sdkManager(ctx, CONTROL_CLASS) { control = it }
    }

    /** ESC actif (true), coupé (false), ou null si ce SDK ne répond pas. */
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
        catch (e: Exception) { Log.w(TAG, "setEspSwitch : ${(e.cause ?: e).message}"); false }
    }

    private fun chInt(name: String): Int? {
        val c = charging ?: return null
        return try { c.javaClass.getMethod(name).invoke(c) as? Int } catch (_: Exception) { null }
    }

    private fun chSet(name: String, v: Int): Boolean {
        val c = charging ?: return false
        return try { c.javaClass.getMethod(name, Int::class.javaPrimitiveType).invoke(c, v); true }
        catch (e: Exception) { Log.w(TAG, "$name($v) : ${(e.cause ?: e).message}"); false }
    }

    /** État de charge et commandes en cours ; inchangé si le service ne répond pas. */
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

    /** Pour l'écran Compatibilité. */
    fun probe(): String = when {
        ac == null -> "absent"
        else -> "ok (temp ${int("getDrvTemp")}, fan ${int("getAirVolumeLevel")})"
    }

    fun probeCharging(): String = if (charging == null) "absent" else "ok (status ${chInt("getChargingStatus")}, ctrl ${chInt("getChargingControlSwitch")})"
}
