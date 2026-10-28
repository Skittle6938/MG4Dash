package app.mg4dash.car

import app.mg4dash.R
import app.mg4dash.Tr
import java.util.Locale

/**
 * Instantané de l'état véhicule affiché par le launcher.
 * Un champ à null = valeur non lue (service absent ou getter en échec) : l'UI affiche « — ».
 */
data class VehicleState(
    val connected: Boolean = false,
    // Climatisation (CarHvacClient, code 7)
    val hvacOn: Boolean? = null,
    val acOn: Boolean? = null,
    val autoOn: Boolean? = null,
    /** 0 = air recyclé, 1 = air extérieur, 2 = auto (convention MG4Control, à confirmer sur A9). */
    val recirc: Int? = null,
    val fanSpeed: Int? = null,
    val fanDirection: Int? = null,
    val driverTemp: Float? = null,
    val outsideTemp: Float? = null,
    val defrostFront: Boolean? = null,
    val defrostRear: Boolean? = null,
    val ecoOn: Boolean? = null,
    /** Débit d'air en mode AUTO (#65) : 0 = haut, 1 = moyen, 2 = bas. */
    val autoAirVolume: Int? = null,
    /** Dégivrage arrière automatique avec l'avant (#65). */
    val defrostLinked: Boolean? = null,
    /** Clim qui suit le mode ECO (#65). */
    val ecoLinked: Boolean? = null,
    /** Sièges chauffants (0 éteint, 1 à 3) et volant chauffant (0 / 1), versions équipées. */
    val seatHeatL: Int? = null,
    val seatHeatR: Int? = null,
    val wheelHeat: Int? = null,
    // Énergie (CarEvClient, code 4)
    val batteryPercent: Int? = null,
    /** Niveau de batterie au dixième (BMS_PACK_SOC_DSP), quand la voiture le donne. */
    val socPrecise: Float? = null,
    val rangeKm: Int? = null,
    val chargeStatus: Int? = null,
    val chargeRemainMin: Int? = null,
    /** Bouton de charge : 1 = charge en cours (proposer « Arrêter »), 2 = arrêtée (« Charger »). */
    val chargeCtrl: Int? = null,
    /** Bouton V2L : 1 = décharge en cours, 2 = arrêtée. */
    val dischargeCtrl: Int? = null,
    val dischargeRemainMin: Int? = null,
    // Conduite (CarVehicleSettingClient, code 8)
    val driveMode: Int? = null,
    val regenLevel: Int? = null,
    val onePedal: Int? = null,
    val steeringMode: Int? = null,
    val brakePedalMode: Int? = null,
    val enduranceMode: Int? = null,
    // Aides à la conduite (lecture seule)
    val lasMode: Int? = null,
    val fcwState: Int? = null,
    val fcwBrakeMode: Int? = null,
    val overSpeedSound: Int? = null,
    val speedLimitSound: Int? = null,
    // Général (CarGeneralClient, code 1)
    val odometerKm: Int? = null,
    val speedKmh: Float? = null,
) {
    companion object {
        const val TEMP_MIN = 17.5f   // position LO
        const val TEMP_MAX = 32.5f   // position HI
        const val TEMP_STEP = 1f
        /** « 78.4 % » si le dixième est disponible et cohérent avec le % entier, sinon « 78 % ». */
        fun socLabel(s: VehicleState): String? {
            val p = s.socPrecise?.takeIf { v -> s.batteryPercent == null || kotlin.math.abs(v - s.batteryPercent) < 1.5f }
            return p?.let { String.format(java.util.Locale.getDefault(), "%.1f %%", it) } ?: s.batteryPercent?.let { "$it %" }
        }

        /** Direction de l'air (échelle 0–6) en sorties actives : visage, pieds, pare-brise. */
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
         * État de charge (getChargeStatus), table a9_ev_status du launcher d'origine :
         * 0 = débranché, 1..10 et 12 = recharge, 11 et 13..15 = décharge (V2L).
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

        /** Mode V2L : même règle que le launcher d'origine (11 et ≥ 13 = écran de décharge). */
        fun isDischargeMode(v: Int?): Boolean = v == 11 || (v != null && v >= 13)
        /**
         * V2L en cours : bouton V2L à 1, ou état « décharge en cours » (13). Les anciens firmwares
         * (SWI68, #8) ne renvoient pas l'état du bouton : le bouton restait sur « Lancer ».
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

        /** Niveau de récupération (KERS) en index de jauge 1..4, 0 si inconnu. */
        /** -1 = niveau inconnu (jauge grisée). */
        fun regenGauge(v: Int?): Int = when (v) {
            0 -> 1
            1 -> 2
            2 -> 3
            3 -> 4
            6 -> 4          // 6 = conduite à une pédale (relevé sur la voiture le 2026-10-18)
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

        /** Direction : 1 = confort, 2 = normal, 3 = sport (échelle A9 relevée par MG4Control). */
        fun steeringLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.cf_comfort); 2 -> Tr.s(R.string.dm_normal); 3 -> Tr.s(R.string.dm_sport); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Pédale de frein : 1 = confort, 0 = normal, 2 = sport (normal vaut zéro). */
        fun pedalLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.cf_comfort); 0 -> Tr.s(R.string.dm_normal); 2 -> Tr.s(R.string.dm_sport); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Aide au maintien de voie : 1 = arrêt, 2 = alerte, 3 = assistance, 5 = urgence. */
        fun lasLabel(v: Int?): String = when (v) { 1 -> Tr.s(R.string.st_off); 2 -> Tr.s(R.string.alert); 3 -> Tr.s(R.string.assist); 5 -> Tr.s(R.string.emergency); null -> "—"; else -> Tr.s(R.string.mode_n, v) }

        /** Freinage d'urgence (A9) : état 1 = arrêt, 2 = actif ; mode 1 = alerte, 2 = alerte + freinage. */
        fun aebLabel(state: Int?, mode: Int?): String = when {
            state == 1 -> Tr.s(R.string.st_off)
            state == 2 && mode == 2 -> Tr.s(R.string.aeb_alert_brake)
            state == 2 && mode == 1 -> Tr.s(R.string.alert)
            state == 2 -> Tr.s(R.string.active)
            else -> "—"
        }

        fun onOff(v: Int?): String = when (v) { 0 -> Tr.s(R.string.st_off); 1 -> Tr.s(R.string.st_on); null -> "—"; else -> Tr.s(R.string.value_n, v) }

        /** Calibration de la consigne affichée (appui long sur la température), réglée au démarrage. */
        @Volatile var tempOffset = 0

        /** LO / HI d'après la consigne de la voiture ; le chiffre affiché inclut la calibration. */
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
