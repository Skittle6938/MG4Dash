package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.car.DriveSettings
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * The shortcuts menu's tiles: what each one shows (name, state read from the car) and does. A touch sends a single
 * command; the state shown is the car's reading, as for the climate buttons. A tile whose command does not exist on
 * this car (its state cannot be read) is not offered.
 */
object ShortcutTiles {
    private const val TAG = "Shortcuts"

    enum class Tile(val id: String, val icon: Int, val label: Int) {
        FOG("fog", R.drawable.ic_sc_fog, R.string.sc_fog),
        POWER("power", R.drawable.ic_sc_power, R.string.sc_power),
        ESP("esp", R.drawable.ic_sc_esp, R.string.sc_esp),
        AIDS("aids", R.drawable.ic_sc_adas, R.string.sc_adas),
        DEFROST("defrost", R.drawable.ic_sc_defrost, R.string.sc_defrost),
        MODE("mode", R.drawable.ic_sc_mode, R.string.sc_mode),
        REGEN("regen", R.drawable.ic_sc_regen, R.string.sc_regen),
        ONE_PEDAL("one_pedal", R.drawable.ic_sc_one_pedal, R.string.sc_one_pedal),
        CLIMATE("climate", R.drawable.ic_sc_clim, R.string.sc_clim),
        BATTERY_HEAT("battery_heat", R.drawable.ic_sc_battery_heat, R.string.sc_battery_heat),
        WHEEL_HEAT("wheel_heat", R.drawable.ic_sc_wheel_heat, R.string.sc_wheel_heat),
        DOORS("doors", R.drawable.ic_sc_doors, R.string.sc_doors),
        CHARGE_LOCK("charge_lock", R.drawable.ic_sc_charge_lock, R.string.sc_charge_lock),
        V2L("v2l", R.drawable.ic_sc_v2l, R.string.sc_v2l),
        SCREEN("screen", R.drawable.ic_sc_screen, R.string.sc_screen),
        AA("aa", R.drawable.ic_sc_aa, R.string.sc_aa),
        ADB("adb", R.drawable.ic_sc_adb, R.string.sc_adb),
    }

    /** By default only the first row (Marc, 13/11): the users add the others. */
    val DEFAULT = listOf(Tile.FOG, Tile.POWER, Tile.ESP, Tile.AIDS, Tile.DEFROST, Tile.MODE)

    /** Tiles chosen, in order; unknown ids (older or newer version) are skipped. */
    fun chosen(prefs: Prefs): List<Tile> = order(prefs.shortcutTiles)

    internal fun order(ids: List<String>?): List<Tile> =
        ids?.mapNotNull { id -> Tile.values().firstOrNull { it.id == id } }?.distinct() ?: DEFAULT

    /** [tile] added at the end, or removed. */
    internal fun toggled(current: List<Tile>, tile: Tile): List<Tile> = if (tile in current) current - tile else current + tile

    fun choose(prefs: Prefs, tiles: List<Tile>) { prefs.shortcutTiles = tiles.map { it.id } }

    // ── States (read off the main thread) ──

    /** What a tile shows: its state line, inverted (on / unusual state) and whether the car answers for it. */
    data class Shown(val state: String, val inverted: Boolean, val available: Boolean = true, val disabled: Boolean = false, val level: Int? = null,
                     /** Name shown instead of the tile's own (climate: "Heating" or "Cooling"). */
                     val label: String? = null)

    /**
     * Lights on as the car needs them for the rear fog light: SAIC's rule (SystemUI CarLampController.refreshData,
     * message "set the light setting to Auto or Low Beam"): switch on Auto (LIGHT_SWITCH_POS_STS 1) or dipped beam on
     * (DIPD_BEAM_LGHT). null if the car does not say (the tile then stays usable).
     */
    internal fun fogAllowed(switchPos: Int?, dippedBeam: Boolean?): Boolean? = when {
        switchPos == null && dippedBeam == null -> null
        else -> switchPos == 1 || dippedBeam == true
    }
    // Read as the theme reads the lights (CarProps.readAny: every type and area the car may use)
    private fun lightInt(id: Int): Int? = CarProps.readAny(id).trim().toIntOrNull()
    private fun lightBool(id: Int): Boolean? = when (CarProps.readAny(id).trim()) { "true", "1" -> true; "false", "0" -> false; else -> null }
    private const val SIDE_LIGHTS = 557880099        // 0x21409323 VEH_SIDE_LGHT (3 = lights on, the theme's reading)
    /** Lights on exactly as the theme sees them (Themes.rawNight): VEH_SIDE_LGHT not 0. */
    private fun sideLightsOn(): Boolean = CarProps.readAny(SIDE_LIGHTS).let { it.isNotEmpty() && it != "0" && it != "false" }
    private const val LIGHT_SWITCH_POS = 557880119   // 0x21409337 LIGHT_SWITCH_POS_STS
    private const val DIPPED_BEAM = 555782944        // 0x21209320 DIPD_BEAM_LGHT (boolean)
    private const val REAR_FOG = 557880115   // 0x21409333 MASTER_LIGHT_REAR_FOG: read 0 off / 1 on; write 1 = press (stock SystemUI, CarLampController)

    /** Driving aids readable on this car: lane keeping, collision warning, drowsiness warning; true = on. */
    data class Aids(val lane: Boolean?, val fcw: Boolean?, val udw: Boolean?) {
        val known get() = listOfNotNull(lane, fcw, udw)
    }

    fun aids(s: VehicleState): Aids = Aids(
        lane = s.lasMode?.let { it != 1 },
        fcw = s.fcwState?.let { it == 2 },
        udw = (try { DriveSettings.udwOn() } catch (_: Exception) { null }).let { read ->
            udwHold?.let { (v, until) -> if (read == v || System.currentTimeMillis() >= until) { udwHold = null; read } else v } ?: read
        },
    )

    /** Drowsiness warning switched: shown at once until the car reads it (not among the driving settings CarBridge holds). */
    @Volatile private var udwHold: Pair<Boolean, Long>? = null

    fun read(context: Context, tile: Tile): Shown {
        val s = CarBridge.state.value
        fun t(r: Int) = context.getString(r)
        fun onOff(on: Boolean?) = if (on == null) Shown("", false, false) else Shown(t(if (on) R.string.sc_on else R.string.sc_off), on)
        return when (tile) {
            Tile.FOG -> onOff(CarProps.getInt(REAR_FOG)?.let { if (it in 0..1) it == 1 else null }).let { s ->
                // Lights off: greyed, a touch only says to turn them on (the car would refuse it)
                if (s.available && !s.inverted && fogAllowed(lightInt(LIGHT_SWITCH_POS), lightBool(DIPPED_BEAM)) == false &&
                    // Lights seen on by the theme's reading (VEH_SIDE_LGHT 3): never greyed, the car decides. On SWI69
                    // LIGHT_SWITCH_POS_STS and DIPD_BEAM_LGHT always read 0 / false and VEH_SIDE_LGHT is 3 with the
                    // parking lights as with the dipped beam (14/11): a refusal is told after the press instead (act)
                    !sideLightsOn())
                    Shown(t(R.string.sc_lights_off), false, disabled = true) else s
            }
            Tile.POWER -> Shown("", false)
            Tile.ESP -> CarBridge.escActive()?.let { Shown(t(if (it) R.string.sc_active else R.string.sc_inactive), !it) } ?: Shown("", false, false)
            Tile.AIDS -> aidsState(context, aids(s))
            Tile.DEFROST -> onOff(s.defrostRear)
            Tile.MODE -> s.driveMode?.let { Shown(VehicleState.driveModeLabel(it), false, level = it) } ?: Shown("", false, false)
            Tile.REGEN -> s.regenLevel?.let { Shown(VehicleState.regenLabel(it, s.onePedal), false, level = it.takeIf { _ -> s.onePedal != 1 }) } ?: Shown("", false, false)
            Tile.ONE_PEDAL -> onOff(s.onePedal?.let { it == 1 })
            Tile.CLIMATE -> climate(context, s)
            // DRVNG_PTC_HEAT: read 0 = on, 1 = off (found on the car on 2026-10-15)
            Tile.BATTERY_HEAT -> onOff(CarProps.getInt(CarProps.DRVNG_PTC_HEAT)?.let { if (it in 0..1) it == 0 else null })
            Tile.WHEEL_HEAT -> onOff(s.wheelHeat?.let { it > 0 })
            Tile.DOORS -> CarBridge.doorsLocked()?.let { Shown(t(if (it) R.string.sc_locked else R.string.sc_unlocked), !it) } ?: Shown("", false, false)
            Tile.CHARGE_LOCK -> CarBridge.chargeLockLocked()?.let { Shown(t(if (it) R.string.sc_locked else R.string.sc_unlocked), it) } ?: Shown("", false, false)
            Tile.V2L -> if (!CarBridge.chargeControl) Shown("", false, false) else onOff(VehicleState.isDischarging(s))
            Tile.SCREEN -> Shown("", false)
            Tile.ADB -> onOff(app.mg4dash.system.AdbSwitch.on(context))
            Tile.AA -> app.mg4dash.system.ConnectionToggle.isOff(context).let { Shown(t(if (it) R.string.sc_cut else R.string.sc_connected), it) }
        }
    }

    /**
     * Tile lit in the theme's accent colour (Marc, 14/11, mockup A): what it switches is on / active, ESP active
     * included (unlike [Shown.inverted], which marks the unusual state for the icons). Padlocks: locked. Android Auto:
     * connected. Levels (drive mode, regeneration) and actions (switch off, screen off) are never lit.
     */
    fun lit(tile: Tile, s: Shown?): Boolean {
        if (s == null || !s.available || s.state.isEmpty() || s.disabled) return false
        return when (tile) {
            Tile.ESP, Tile.DOORS, Tile.AA -> !s.inverted
            Tile.AIDS -> s.level != 0
            Tile.MODE, Tile.REGEN, Tile.POWER, Tile.SCREEN -> false
            else -> s.inverted
        }
    }

    /** Icon of [tile] for the state [s]: padlock open when unlocked, gauge needle on the drive mode (eco left, sport right). */
    fun icon(tile: Tile, s: Shown?): Int {
        // State unknown (not read yet, or not given by the car): the tile's usual icon
        if (s == null || !s.available || s.state.isEmpty()) return tile.icon
        val on = s.inverted
        return when (tile) {
            Tile.DOORS -> if (on) R.drawable.ic_sc_doors_open else tile.icon
            Tile.MODE -> when (s.level) { 2 -> R.drawable.ic_sc_mode_eco; 3 -> R.drawable.ic_sc_mode_normal; 4 -> R.drawable.ic_sc_mode_sport; else -> tile.icon }
            Tile.FOG -> if (on) tile.icon else R.drawable.ic_sc_fog_off
            // ESP: inverted when it is off
            Tile.ESP -> if (on) R.drawable.ic_sc_esp_off else tile.icon
            Tile.CLIMATE -> when { !on -> R.drawable.ic_sc_clim_off; s.level == 1 -> R.drawable.ic_sc_clim_heat; else -> tile.icon }
            Tile.AIDS -> if (s.level == 0) R.drawable.ic_sc_adas_off else tile.icon
            Tile.DEFROST -> if (on) tile.icon else R.drawable.ic_sc_defrost_off
            Tile.WHEEL_HEAT -> if (on) tile.icon else R.drawable.ic_sc_wheel_heat_off
            Tile.BATTERY_HEAT -> if (on) tile.icon else R.drawable.ic_sc_battery_heat_off
            Tile.REGEN -> when (s.level) { 0 -> R.drawable.ic_sc_regen_0; 1 -> R.drawable.ic_sc_regen_1; 2 -> R.drawable.ic_sc_regen_2; 3 -> R.drawable.ic_sc_regen_3; else -> tile.icon }
            Tile.ONE_PEDAL -> if (on) R.drawable.ic_sc_one_pedal_on else tile.icon
            Tile.CHARGE_LOCK -> if (on) tile.icon else R.drawable.ic_sc_charge_lock_open
            Tile.V2L -> if (on) R.drawable.ic_sc_v2l_on else tile.icon
            Tile.ADB -> if (on) R.drawable.ic_sc_adb_on else tile.icon
            else -> tile.icon
        }
    }

    // ── Shown at once (as the climate and driving settings): the state touched, held against older readings ──

    /** How long a state touched is kept while the car has not confirmed it. */
    const val HOLD_MS = 4_000L
    private val holds = java.util.concurrent.ConcurrentHashMap<Tile, Pair<Shown, Long>>()

    /**
     * What to show from the car's [read] and the state [hold] (state, until) at [now]; true if still held: the car
     * confirming it ends the hold, a reading still on the old value does not bring the tile back meanwhile, and once
     * the hold is over the car's value is shown whatever it is.
     */
    internal fun held(read: Shown, hold: Pair<Shown, Long>?, now: Long): Pair<Shown, Boolean> = when {
        hold == null -> read to false
        read.state == hold.first.state && read.inverted == hold.first.inverted -> read to false
        now < hold.second -> hold.first to true
        else -> read to false
    }

    /** The state to show for [tile] given the car's [read] now. */
    fun shown(tile: Tile, read: Shown, now: Long = System.currentTimeMillis()): Shown {
        val (v, keep) = held(read, holds[tile], now)
        if (!keep) holds.remove(tile)
        return v
    }

    private fun hold(tile: Tile, s: Shown): Shown { holds[tile] = s to System.currentTimeMillis() + HOLD_MS; return s }

    /** [cur] switched: on ↔ off with the tile's own words and inversion; null if the state is not known. */
    internal fun flipped(context: Context, tile: Tile, cur: Shown?): Shown? {
        if (cur == null || !cur.available || cur.state.isEmpty()) return null
        fun t(r: Int) = context.getString(r)
        val inv = cur.inverted
        return when (tile) {
            // inverted = on
            Tile.FOG, Tile.DEFROST, Tile.ONE_PEDAL, Tile.CLIMATE, Tile.BATTERY_HEAT, Tile.WHEEL_HEAT, Tile.V2L ->
                Shown(t(if (!inv) R.string.sc_on else R.string.sc_off), !inv)
            // inverted = off (unusual)
            Tile.ESP -> Shown(t(if (inv) R.string.sc_active else R.string.sc_inactive), !inv)
            // inverted = unlocked
            Tile.DOORS -> Shown(t(if (inv) R.string.sc_locked else R.string.sc_unlocked), !inv)
            // inverted = locked; Android Auto: inverted = cut
            Tile.CHARGE_LOCK -> Shown(t(if (!inv) R.string.sc_locked else R.string.sc_unlocked), !inv)
            Tile.AA -> Shown(t(if (!inv) R.string.sc_cut else R.string.sc_connected), !inv)
            else -> null
        }
    }

    // ── Actions: a single command each, from the state shown (quick touches follow one another) ──

    /** Drive mode and regeneration chosen by quick touches: only the last one is sent, once the touches stop. */
    private const val SETTLE_MS = 600L
    /** Rear fog light turned on: read again after this long (the car answers in about 0.3 s). */
    internal const val FOG_CHECK_MS = 1_500L
    /** The rear fog light still off after the press: the car refused it (lights not on dipped beam or Auto). */
    internal fun fogRefused(read: Int?): Boolean = read == 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var modeTarget: Int? = null
    private var regenTarget: Int? = null
    private val sendMode = Runnable { modeTarget?.let { modeTarget = null; CarBridge.setDriveMode(it) } }
    private val sendRegen = Runnable { regenTarget?.let { regenTarget = null; CarBridge.setRegenLevel(it) } }

    /**
     * Touch on [tile] showing [shown]; returns the state to show at once (held until the car confirms it).
     * The aids (their list) and the switch off (refused while driving) are handled by the menu.
     */
    fun act(activity: Context, tile: Tile, shown: Shown?): Shown? {
        val s = CarBridge.state.value
        Log.i(TAG, "tile ${tile.id} (state ${shown?.state})")
        if (shown?.disabled == true) {
            if (tile == Tile.FOG) Toasts.show(activity, R.string.sc_lights_needed)
            return null
        }
        val next = flipped(activity, tile, shown)
        when (tile) {
            Tile.FOG -> {
                val turningOn = shown?.inverted != true
                background {
                    CarProps.setInt(REAR_FOG, 1, "rear fog light (press)")
                    if (turningOn) {
                        // With the parking lights only the car ignores the press silently (#38, SWI69: answered in
                        // 0.34 s with the dipped beam), and no readable property tells parking lights from dipped beam
                        Thread.sleep(FOG_CHECK_MS)
                        if (fogRefused(CarProps.getInt(REAR_FOG))) {
                            Log.i(TAG, "rear fog light refused by the car (parking lights only?)")
                            holds.remove(Tile.FOG)
                            main.post { Toasts.show(activity, R.string.sc_lights_needed) }
                        }
                    }
                }
            }
            Tile.ESP -> background { CarBridge.toggleEsc() }
            Tile.DEFROST -> CarBridge.toggleDefrostRear()
            Tile.MODE -> {
                val target = nextDriveMode(modeTarget ?: s.driveMode)
                modeTarget = target; main.removeCallbacks(sendMode); main.postDelayed(sendMode, SETTLE_MS)
                return hold(tile, Shown(VehicleState.driveModeLabel(target), false, level = target))
            }
            Tile.REGEN -> {
                val target = nextRegen(regenTarget ?: s.regenLevel?.takeIf { s.onePedal != 1 })
                regenTarget = target; main.removeCallbacks(sendRegen); main.postDelayed(sendRegen, SETTLE_MS)
                return hold(tile, Shown(VehicleState.regenLabel(target, 0), false, level = target))
            }
            Tile.ONE_PEDAL -> CarBridge.toggleOnePedal()
            Tile.CLIMATE -> CarBridge.togglePower()
            Tile.BATTERY_HEAT -> {
                val on = shown?.inverted == true
                background { CarProps.setInt(CarProps.DRVNG_PTC_HEAT, if (on) 2 else 1, "battery heating ${if (on) "off" else "on"}") }
            }
            Tile.WHEEL_HEAT -> CarBridge.toggleWheelHeat()
            Tile.DOORS -> { val locked = shown?.let { !it.inverted } ?: return null; background { CarBridge.setDoors(!locked) } }
            Tile.CHARGE_LOCK -> { val locked = shown?.inverted ?: return null; CarBridge.setChargeLock(!locked) }
            Tile.V2L -> if (shown?.inverted ?: VehicleState.isDischarging(s)) CarBridge.stopV2L() else CarBridge.startV2L()
            Tile.SCREEN -> activity.startActivity(Intent(activity, ScreenOffActivity::class.java).apply { if (activity !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            Tile.AA -> app.mg4dash.system.ConnectionToggle.toggle(activity) { }
            // ADB: on only (adbutil has no off either; the car's next start switches it off)
            Tile.ADB -> if (shown?.inverted == true) { Toasts.show(activity, R.string.sc_adb_already); return null }
                else app.mg4dash.system.AdbSwitch.enable(activity, "shortcuts menu")
            Tile.POWER, Tile.AIDS -> {}
        }
        return next?.let { hold(tile, it) }
    }

    /** The aids tile after a change in its list, shown at once. */
    fun aidsShown(context: Context, a: Aids): Shown = aidsState(context, a).let { if (it.available) hold(Tile.AIDS, it) else it }

    private fun aidsState(context: Context, a: Aids): Shown {
        val k = a.known
        fun t(r: Int) = context.getString(r)
        return when {
            k.isEmpty() -> Shown("", false, false)
            k.all { it } -> Shown(t(R.string.sc_aids_on), false, level = 2)
            k.none { it } -> Shown(t(R.string.sc_aids_off), true, level = 0)
            else -> Shown(t(R.string.sc_aids_some), true, level = 1)
        }
    }

    /** Last climate mode shown (true = cooling), kept within [CLIM_HYSTERESIS] so it does not flicker. */
    @Volatile private var lastCooling: Boolean? = null
    private const val CLIM_HYSTERESIS = 0.5f

    /**
     * Climate (Marc's mockup B): off = fan crossed out; on = snowflake ("Cooling") with the A/C on and a set point below
     * the outside temperature, otherwise heat waves ("Heating"); with no valid outside temperature, snowflake with the
     * A/C on. The state line: set point and outside temperature.
     */
    internal fun climate(context: Context, s: VehicleState): Shown {
        val on = s.hvacOn ?: return Shown("", false, false)
        fun t(r: Int) = context.getString(r)
        if (!on) return Shown(t(R.string.sc_off), false, level = 0)
        val out = s.outsideTemp?.takeIf { it > -60f && it < 70f }
        val set = s.driverTemp
        val cooling = coolingFor(s.acOn == true, set, out, lastCooling).also { lastCooling = it }
        val state = if (set != null) context.getString(R.string.sc_clim_state, temp(set), out?.let { temp(it) } ?: "?") else ""
        return Shown(state, true, level = if (cooling) 2 else 1, label = t(if (cooling) R.string.sc_cooling else R.string.sc_heating))
    }

    internal fun coolingFor(ac: Boolean, set: Float?, out: Float?, last: Boolean?): Boolean = when {
        out == null || set == null -> ac
        !ac -> false
        last != null && Math.abs(set - out) < CLIM_HYSTERESIS -> last
        else -> set < out
    }

    private fun temp(v: Float): String =
        if (v % 1f == 0f) "${v.toInt()} °C" else String.format(java.util.Locale.getDefault(), "%.1f °C", v)

    /** Drive mode touched: eco → normal → sport → eco (snow and custom go to normal). */
    internal fun nextDriveMode(cur: Int?): Int = when (cur) { 2 -> 3; 3 -> 4; 4 -> 2; else -> 3 }

    /** Regeneration touched: 0 → 1 → 2 → 3 → 0 (one pedal or unknown → 1). */
    internal fun nextRegen(cur: Int?): Int = when (cur) { 0 -> 1; 1 -> 2; 2 -> 3; 3 -> 0; else -> 1 }

    /** Driving aid switched from the list; the mode turned off is kept to be put back. */
    fun setLane(context: Context, on: Boolean) {
        val p = Prefs(context)
        if (on) CarBridge.setLaneMode(p.shortcutLaneMode)
        else { CarBridge.state.value.lasMode?.takeIf { it != 1 }?.let { p.shortcutLaneMode = it }; CarBridge.setLaneMode(1) }
    }

    fun setFcw(context: Context, on: Boolean) {
        val p = Prefs(context)
        if (on) CarBridge.setFcw(p.shortcutFcw)
        else { CarBridge.state.value.fcwBrakeMode?.takeIf { it in 1..2 }?.let { p.shortcutFcw = it }; CarBridge.setFcw(0) }
    }

    fun setUdw(on: Boolean) { udwHold = on to System.currentTimeMillis() + HOLD_MS; background { DriveSettings.setUdw(on) } }

    private fun background(block: () -> Unit) = Thread {
        try { block() } catch (e: Exception) { Log.w(TAG, "command: ${e.message}") }
    }.start()
}
