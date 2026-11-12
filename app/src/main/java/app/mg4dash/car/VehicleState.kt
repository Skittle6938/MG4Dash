package app.mg4dash.car

import app.mg4dash.R
import app.mg4dash.Tr
import java.util.Locale

/**
 * Snapshot of the vehicle state shown by the launcher.
 * A null field = value not read (service missing or getter failed): the UI shows "—".
 */
data class VehicleState(
    val connected: Boolean = false,
    // Climatisation (CarHvacClient, code 7)
    val hvacOn: Boolean? = null,
    val acOn: Boolean? = null,
    val autoOn: Boolean? = null,
    /** 0 = recirculated air, 1 = outside air, 2 = auto (MG4Control convention, to confirm on A9). */
    val recirc: Int? = null,
    val fanSpeed: Int? = null,
    val fanDirection: Int? = null,
    val driverTemp: Float? = null,
    val outsideTemp: Float? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val ecoOn: Boolean? = null,
    /** Air flow in AUTO mode (#65): 0 = high, 1 = medium, 2 = low. */
    val autoAirVolume: Int? = null,
    /** Automatic rear defrost with the front (#65). */
    val defrostLinked: Boolean? = null,
    /** Climate following ECO mode (#65). */
    val ecoLinked: Boolean? = null,
    /** Heated seats (0 off, 1 to 3) and heated steering wheel (0 / 1), equipped versions. */
    val seatHeatL: Int? = null,
    val seatHeatR: Int? = null,
    val wheelHeat: Int? = null,
    // Energy (CarEvClient, code 4)
    val batteryPercent: Int? = null,
    /** Battery level to the tenth (BMS_PACK_SOC_DSP), when the car gives it. */
    val socPrecise: Float? = null,
    val rangeKm: Int? = null,
    val chargeStatus: Int? = null,
    val chargeRemainMin: Int? = null,
    /** Charge button: 1 = charging (offer "Stop"), 2 = stopped ("Charge"). */
    val chargeCtrl: Int? = null,
    /** V2L button: 1 = discharging, 2 = stopped. */
    val dischargeCtrl: Int? = null,
    val dischargeRemainMin: Int? = null,
    // Conduite (CarVehicleSettingClient, code 8)
    val driveMode: Int? = null,
    val regenLevel: Int? = null,
    val onePedal: Int? = null,
    val steeringMode: Int? = null,
    val brakePedalMode: Int? = null,
    val enduranceMode: Int? = null,
    // Driving aids (read only)
    val lasMode: Int? = null,
    val fcwState: Int? = null,
    val fcwBrakeMode: Int? = null,
    val overSpeedSound: Int? = null,
    val speedLimitSound: Int? = null,
    // General (CarGeneralClient, code 1)
    val odometerKm: Int? = null,
    val speedKmh: Float? = null,
) {
    companion object {
        /**
         * Set point of the LO and HI positions. SWI69 (A9): 17.5 / 32.5, the numbers 18 to 32. Old SDK firmwares give
         * their own (#107, getMinTemp / getMaxTemp: LO and HI values): 17 / 33 on SWI68 R46, 15 / 31 (16 to 30) on R67,
         * R73 and recent SWI165; read by LegacyCar at connection.
         */
        @Volatile var tempLo = 17.5f
        @Volatile var tempHi = 32.5f
        val TEMP_MIN: Float get() = tempLo   // position LO
        val TEMP_MAX: Float get() = tempHi   // position HI
        /** First and last numbered set point (18 and 32 on SWI69, 16 and 30 on SWI68 R67). */
        val tempFirst: Float get() = Math.floor(tempLo.toDouble()).toFloat() + 1f
        val tempLast: Float get() = Math.ceil(tempHi.toDouble()).toFloat() - 1f
        const val TEMP_STEP = 1f
        /** "78.4 %" if the tenth is available and consistent with the whole %, otherwise "78 %". */
        fun socLabel(s: VehicleState): String? {
            val p = s.socPrecise?.takeIf { v -> s.batteryPercent == null || kotlin.math.abs(v - s.batteryPercent) < 1.5f }
            return p?.let { String.format(java.util.Locale.getDefault(), "%.1f %%", it) } ?: s.batteryPercent?.let { "$it %" }
        }

        /** Air direction (0–6 scale) as active outlets: face, feet, windscreen. */
        fun flowParts(d: Int?): BooleanArray? = when (d) {
            0 -> booleanArrayOf(true, false, false)
            1 -> booleanArrayOf(true, true, false)
            2 -> booleanArrayOf(false, true, false)
            3 -> booleanArrayOf(false, true, true)
            4 -> booleanArrayOf(false, false, true)
            5 -> booleanArrayOf(true, true, true)
            6 -> booleanArrayOf(true, false, true)
            else -> null
        }

        fun flowDirection(p: BooleanArray): Int? = when {
            p[0] && p[1] && p[2] -> 5
            p[0] && p[2] -> 6
            p[0] && p[1] -> 1
            p[1] && p[2] -> 3
            p[0] -> 0
            p[1] -> 2
            p[2] -> 4
            else -> null
        }

        const val FAN_MIN = 1
        const val FAN_MAX = 11

        /**
         * Charge state (getChargeStatus), a9_ev_status table of the stock launcher:
         * 0 = unplugged, 1..10 and 12 = charging, 11 and 13..15 = discharging (V2L).
         */
        fun chargeStatusLabel(v: Int?): String = when (v) {
            0 -> Tr.s(R.string.cs_0)
            1 -> Tr.s(R.string.cs_1)
            2 -> Tr.s(R.string.cs_2)
            3 -> Tr.s(R.string.cs_3)
            4 -> Tr.s(R.string.cs_4)
            5 -> Tr.s(R.string.cs_5)
            6 -> Tr.s(R.string.cs_6)
            7 -> Tr.s(R.string.cs_7)
            8 -> Tr.s(R.string.cs_8)
            9 -> Tr.s(R.string.cs_9)
            10 -> Tr.s(R.string.cs_10)
            11 -> Tr.s(R.string.cs_11)
            12 -> Tr.s(R.string.cs_12)
            13 -> Tr.s(R.string.cs_13)
            14 -> Tr.s(R.string.cs_14)
            15 -> Tr.s(R.string.cs_15)
            null -> "—"
            else -> Tr.s(R.string.cs_other, v)
        }

        /** V2L mode: same rule as the stock launcher (11 and ≥ 13 = discharge screen). */
        fun isDischargeMode(v: Int?): Boolean = v == 11 || (v != null && v >= 13)
        /**
         * V2L running: V2L button at 1, or "discharging" state (13). Old firmwares
         * (SWI68, #8) do not return the button state: the button stayed on "Start".
         */
        fun isDischarging(s: VehicleState): Boolean = s.dischargeCtrl == 1 || s.chargeStatus == 13
        fun isPlugged(v: Int?): Boolean = v != null && v != 0
        fun isCharging(v: Int?): Boolean = v == 1 || v == 3 || v == 10 || v == 12

        fun driveModeLabel(v: Int?): String = when (v) {
            2 -> Tr.s(R.string.dm_eco)
            3 -> Tr.s(R.string.dm_normal)
            4 -> Tr.s(R.string.dm_sport)
            6 -> Tr.s(R.string.dm_snow)
            7 -> Tr.s(R.string.dm_custom)
            null -> "—"
            else -> Tr.s(R.string.mode_n, v)
        }

        /** Regeneration level (KERS) as a gauge index 1..4, 0 if unknown. */
        /** -1 = unknown level (greyed gauge). */
        fun regenGauge(v: Int?): Int = when (v) {
            0 -> 1
            1 -> 2
            2 -> 3
            3 -> 4
            6 -> 4          // 6 = one-pedal driving (found on the car on 2026-10-18)
            else -> -1
        }

        fun regenLabel(v: Int?, onePedal: Int?): String = when {
            onePedal == 1 || v == 6 -> Tr.s(R.string.rg_one_pedal)
            v == 0 -> Tr.s(R.string.rg_low)
            v == 1 -> Tr.s(R.string.rg_medium)
            v == 2 -> Tr.s(R.string.rg_high)
            v == 3 -> Tr.s(R.string.rg_adaptive)
            v == null -> "—"
            else -> Tr.s(R.string.level_n, v)
        }

        fun recircLabel(v: Int?): String = when (v) {
            0 -> Tr.s(R.string.rc_inner)
            1 -> Tr.s(R.string.rc_outer)
            2 -> Tr.s(R.string.rc_auto)
            else -> "—"
        }

        /** Steering: 1 = comfort, 2 = normal, 3 = sport (A9 scale found by MG4Control). */
        fun steeringLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.cf_comfort); 2 -> Tr.s(R.string.dm_normal); 3 -> Tr.s(R.string.dm_sport); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Brake pedal: 1 = comfort, 0 = normal, 2 = sport (normal is zero). */
        fun pedalLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.cf_comfort); 0 -> Tr.s(R.string.dm_normal); 2 -> Tr.s(R.string.dm_sport); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Lane keeping: 1 = off, 2 = warning, 3 = assist, 5 = emergency. */
        fun lasLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.st_off); 2 -> Tr.s(R.string.alert); 3 -> Tr.s(R.string.assist); 5 -> Tr.s(R.string.emergency); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Emergency braking (A9): state 1 = off, 2 = active; mode 1 = warning, 2 = warning + braking. */
        fun aebLabel(state: Int?, mode: Int?): String = when {
            state == 1 -> Tr.s(R.string.st_off)
            state == 2 && mode == 2 -> Tr.s(R.string.aeb_alert_brake)
            state == 2 && mode == 1 -> Tr.s(R.string.alert)
            state == 2 -> Tr.s(R.string.active)
            else -> "—"
        }

        fun onOff(v: Int?): String = when (v) { 0 -> Tr.s(R.string.st_off); 1 -> Tr.s(R.string.st_on); null -> "—"; else -> Tr.s(R.string.value_n, v) }

        /** Calibration of the displayed set point (long press on the temperature), set at startup. */
        @Volatile var tempOffset = 0

        /** LO / HI from the car's set point; the displayed number includes the calibration. */
        fun tempLabel(t: Float?): String = when {
            t == null || t.isNaN() || t <= 0f -> "—"
            t <= TEMP_MIN -> "LO"
            t >= TEMP_MAX -> "HI"
            else -> (t + tempOffset).let { d ->
                if (d % 1f == 0f) "${d.toInt()}°" else String.format(Locale.getDefault(), "%.1f°", d)
            }
        }
    }
}
