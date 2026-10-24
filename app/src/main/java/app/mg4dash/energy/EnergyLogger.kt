package app.mg4dash.energy

import android.content.Context
import app.mg4dash.car.CarBridge
import app.mg4dash.car.CarProps
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistreur de données énergie (étape 1 du chantier « consommations ») : lecture seule.
 *
 * Toutes les secondes : tension × courant batterie, cumulés en énergie sortie / rentrée.
 * Toutes les 5 s : une ligne CSV avec ces cumuls, les compteurs SAIC, l'état de la clim et des
 * équipements (feux, essuie-glaces, sièges et volant chauffants, dégivrage), vitesse et batterie.
 *
 * Fichier : /sdcard/Android/data/app.mg4dash/files/energy_log.csv (récupérable par
 * adb pull), remplacé par energy_log.old.csv au-delà de 8 Mo.
 */
object EnergyLogger {
    private const val TAG = "EnergyLog"
    private const val MAX_BYTES = 8L * 1024 * 1024

    /** Propriétés lues telles quelles (nom de colonne → identifiant). */
    private val PROPS = linkedMapOf(
        "cons_total_st" to CarProps.CONS_TOTAL_AFT_ST,
        "cons_atc_st" to CarProps.CONS_ATC_AFT_ST,
        "cons_accry_st" to CarProps.CONS_ACCRY_AFT_ST,
        "cons_rgen_st" to CarProps.CONS_RGEN_AFT_ST,
        "cons_total_chrg" to CarProps.CONS_TOTAL_AFT_CHRG,
        "cons_atc_chrg" to CarProps.CONS_ATC_AFT_CHRG,
        "cons_accry_chrg" to CarProps.CONS_ACCRY_AFT_CHRG,
        "cons_rgen_chrg" to CarProps.CONS_RGEN_AFT_CHRG,
        "old_acc_st" to CarProps.OLD_ACC_AFT_ST,
        "old_total_st" to CarProps.OLD_TOTAL_AFT_ST,
        "old_rgen_st" to CarProps.OLD_RGEN_AFT_ST,
        "csump_perkm" to CarProps.ELEC_CSUMP_PERKM,
        "crnt_avg_csump" to 560002075,      // CRNT_AVG_ELEC_CSUMP
        "veh_actu_csump" to 560002127,      // VEH_ACTU_ELEC_CSUMP
        "bat_enrg_avg_rate" to 560002081,   // BAT_ELEC_ENRG_AVG_RATE
        "pwr_md" to 356525623,              // PWR_MD
        "ptc_heat" to 557904937,            // DRVNG_PTC_HEAT
        "side_lights" to 557880099,         // VEH_SIDE_LGHT (feux, constaté sur la voiture)
        "day_night" to 289442317,           // PMS_SYSTEM_DAY_NIGHT_MODE (3 = feux allumés)
        "headlights" to 289410560,          // HEADLIGHTS_STATE
        "high_beam" to 289410561,           // HIGH_BEAM_LIGHTS_STATE
        "fog" to 289410562,                 // FOG_LIGHTS_STATE
        "dipped_beam" to 555782944,         // DIPD_BEAM_LGHT
        "main_beam" to 555782943,           // MAIN_BEAM_LGHT
        "drl" to 557880145,                 // DAY_TIME_RUN_LAMP
        "light_switch" to 557880119,        // LIGHT_SWITCH_POS_STS
        "wiper_sw" to 557897511,            // WIPER_SW_STS
        "wiper_md" to 557897519,            // FRT_WIPER_MD_STS
        "seat_heat_fl" to 356525331,        // HVAC_SEAT_HEAT_FL
        "seat_heat_fr" to 356525332,        // HVAC_SEAT_HEAT_FR
        "wheel_heat" to 289408269,          // HVAC_STEERING_WHEEL_HEAT
        "rear_defrost" to 356525334,        // HVAC_BACK_WINDOW_DEFROSTER
        "front_defrost" to 356525333,       // HVAC_FORNT_WINDOW_DEFROSTER
        "mirror_heat" to 339739916,         // HVAC_SIDE_MIRROR_HEAT
    )

    /** Énergie cumulée depuis le démarrage du launcher (Wh), selon le signe du courant. */
    @Volatile var whPositive = 0.0; private set
    @Volatile var whNegative = 0.0; private set
    @Volatile var lastPowerW: Float? = null; private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val prefs = Prefs(context)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "energy_log.csv")
        Log.i(TAG, "file: Android/data/<app>/files/${file.name} (enabled: ${prefs.energyLog})")

        // Intégration de la puissance, chaque seconde
        scope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(1000)
                val now = System.currentTimeMillis()
                val dtH = (now - last) / 3_600_000.0
                last = now
                val v = CarProps.getFloat(CarProps.BMS_PACK_VOL)
                val i = CarProps.getFloat(CarProps.BMS_PACK_CRNT)
                if (v == null || i == null || v < 100f || v > 900f) { lastPowerW = null; continue }
                val p = v * i
                lastPowerW = p
                if (dtH < 0.01) { if (p >= 0) whPositive += p * dtH else whNegative += -p * dtH }
            }
        }

        // Ligne CSV toutes les 5 s
        scope.launch {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            while (true) {
                delay(5000)
                if (!prefs.energyLog || !CarProps.ready) continue
                try {
                    if (file.length() > MAX_BYTES) file.renameTo(File(dir, "energy_log.old.csv"))
                    if (!file.exists()) file.writeText(header() + "\n")
                    file.appendText(row(fmt.format(Date())) + "\n")
                } catch (e: Exception) {
                    Log.w(TAG, "write: ${e.message}")
                }
            }
        }
    }

    private fun header(): String = (listOf(
        "time", "speed_kmh", "soc", "odo_km", "charge_status",
        "hvac_on", "ac", "auto", "eco", "fan", "flow", "recirc", "temp_set", "temp_out", "defrost_f", "defrost_r",
        "pack_v", "pack_a", "power_w", "wh_pos", "wh_neg",
    ) + PROPS.keys).joinToString(",")

    private fun row(time: String): String {
        val s = CarBridge.state.value
        fun b(x: Boolean?) = when (x) { true -> "1"; false -> "0"; null -> "" }
        fun n(x: Any?) = x?.toString() ?: ""
        val base = listOf(
            time, n(s.speedKmh), n(s.batteryPercent), n(s.odometerKm), n(s.chargeStatus),
            b(s.hvacOn), b(s.acOn), b(s.autoOn), b(s.ecoOn), n(s.fanSpeed), n(s.fanDirection), n(s.recirc),
            n(s.driverTemp), n(s.outsideTemp), b(s.defrostFront), b(s.defrostRear),
            n(CarProps.getFloat(CarProps.BMS_PACK_VOL)), n(CarProps.getFloat(CarProps.BMS_PACK_CRNT)),
            n(lastPowerW?.let { Math.round(it) }), String.format(Locale.US, "%.1f", whPositive), String.format(Locale.US, "%.1f", whNegative),
        )
        return (base + PROPS.values.map { CarProps.readAny(it) }).joinToString(",")
    }
}
