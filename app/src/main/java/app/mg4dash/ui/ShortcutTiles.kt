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
    data class Shown(val state: String, val inverted: Boolean, val available: Boolean = true, val disabled: Boolean = false)

    /**
     * Lights on as the car needs them for the rear fog light: SAIC's rule (SystemUI CarLampController.refreshData,
     * message "set the light setting to Auto or Low Beam"): switch on Auto (LIGHT_SWITCH_POS_STS 1) or dipped beam on
     * (DIPD_BEAM_LGHT). null if the car does not say (the tile then stays usable).
     */
    internal fun fogAllowed(switchPos: Int?, dippedBeam: Boolean?): Boolean? = when {
        switchPos == null && dippedBeam == null -> null
        else -> switchPos == 1 || dippedBeam == true
    }
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
                if (s.available && !s.inverted && fogAllowed(CarProps.getInt(LIGHT_SWITCH_POS), CarProps.getBool(DIPPED_BEAM)) == false)
                    Shown(t(R.string.sc_lights_off), false, disabled = true) else s
            }
            Tile.POWER -> Shown("", false)
            Tile.ESP -> CarBridge.escActive()?.let { Shown(t(if (it) R.string.sc_active else R.string.sc_inactive), !it) } ?: Shown("", false, false)
            Tile.AIDS -> aidsState(context, aids(s))
            Tile.DEFROST -> onOff(s.defrostRear)
            Tile.MODE -> s.driveMode?.let { Shown(VehicleState.driveModeLabel(it), false) } ?: Shown("", false, false)
            Tile.REGEN -> s.regenLevel?.let { Shown(VehicleState.regenLabel(it, s.onePedal), false) } ?: Shown("", false, false)
            Tile.ONE_PEDAL -> onOff(s.onePedal?.let { it == 1 })
            Tile.CLIMATE -> onOff(s.hvacOn)
            // DRVNG_PTC_HEAT: read 0 = on, 1 = off (found on the car on 2026-10-15)
            Tile.BATTERY_HEAT -> onOff(CarProps.getInt(CarProps.DRVNG_PTC_HEAT)?.let { if (it in 0..1) it == 0 else null })
            Tile.WHEEL_HEAT -> onOff(s.wheelHeat?.let { it > 0 })
            Tile.DOORS -> CarBridge.doorsLocked()?.let { Shown(t(if (it) R.string.sc_locked else R.string.sc_unlocked), !it) } ?: Shown("", false, false)
            Tile.CHARGE_LOCK -> CarBridge.chargeLockLocked()?.let { Shown(t(if (it) R.string.sc_locked else R.string.sc_unlocked), it) } ?: Shown("", false, false)
            Tile.V2L -> if (!CarBridge.chargeControl) Shown("", false, false) else onOff(VehicleState.isDischarging(s))
            Tile.SCREEN -> Shown("", false)
            Tile.AA -> app.mg4dash.system.ConnectionToggle.isOff(context).let { Shown(t(if (it) R.string.sc_cut else R.string.sc_connected), it) }
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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var modeTarget: Int? = null
    private var regenTarget: Int? = null
    private val sendMode = Runnable { modeTarget?.let { modeTarget = null; CarBridge.setDriveMode(it) } }
    private val sendRegen = Runnable { regenTarget?.let { regenTarget = null; CarBridge.setRegenLevel(it) } }

    /**
     * Touch on [tile] showing [shown]; returns the state to show at once (held until the car confirms it).
     * The aids (their list) and the switch off (refused while driving) are handled by the menu.
     */
    fun act(activity: Activity, tile: Tile, shown: Shown?): Shown? {
        val s = CarBridge.state.value
        Log.i(TAG, "tile ${tile.id} (state ${shown?.state})")
        if (shown?.disabled == true) {
            if (tile == Tile.FOG) Toasts.show(activity, R.string.sc_lights_needed)
            return null
        }
        val next = flipped(activity, tile, shown)
        when (tile) {
            Tile.FOG -> background { CarProps.setInt(REAR_FOG, 1, "rear fog light (press)") }
            Tile.ESP -> background { CarBridge.toggleEsc() }
            Tile.DEFROST -> CarBridge.toggleDefrostRear()
            Tile.MODE -> {
                val target = nextDriveMode(modeTarget ?: s.driveMode)
                modeTarget = target; main.removeCallbacks(sendMode); main.postDelayed(sendMode, SETTLE_MS)
                return hold(tile, Shown(VehicleState.driveModeLabel(target), false))
            }
            Tile.REGEN -> {
                val target = nextRegen(regenTarget ?: s.regenLevel?.takeIf { s.onePedal != 1 })
                regenTarget = target; main.removeCallbacks(sendRegen); main.postDelayed(sendRegen, SETTLE_MS)
                return hold(tile, Shown(VehicleState.regenLabel(target, 0), false))
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
            Tile.SCREEN -> activity.startActivity(Intent(activity, ScreenOffActivity::class.java))
            Tile.AA -> app.mg4dash.system.ConnectionToggle.toggle(activity) { }
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
            k.all { it } -> Shown(t(R.string.sc_aids_on), false)
            k.none { it } -> Shown(t(R.string.sc_aids_off), true)
            else -> Shown(t(R.string.sc_aids_some), true)
        }
    }

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
