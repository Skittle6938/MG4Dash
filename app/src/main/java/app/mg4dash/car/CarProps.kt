package app.mg4dash.car

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log

/**
 * Propriétés véhicule « BMS » et consommation, lues comme le fait l'appli Énergie SAIC
 * (com.saicmotor.saicev, variante EH32 EU) : CarPropertyManager, zone globale 0.
 * Accès par réflexion à android.car (bibliothèque système), comme MG4Control.
 */
object CarProps {
    private const val TAG = "BMS"

    // ── Identifiants relevés dans CarBMSManager / CarAdvancedAssistedDrivingManager SAIC ──
    const val CHRG_TRGT_SOC = 557904908          // limite de charge : code 1..7 = 40..100 %
    const val RESER_CTRL = 557904909             // charge programmée : 1 = oui, 2 = non, 3 = oui + continuer jusqu'à la limite
    const val RESER_ST_HOUR = 557904910
    const val RESER_ST_MIN = 557904911
    const val RESER_SP_HOUR = 557904912
    const val RESER_SP_MIN = 557904913
    const val ALTNG_CHRG_CRNT = 557904907        // courant AC : 1 = 6 A, 2 = 8 A, 3 = 16 A, 4 = max
    const val V2X_SP_SOC = 557904943             // seuil d'arrêt V2L : code 1..7 = 20..80 %
    const val V2X = 557904944                    // décharge V2L en cours : 1
    const val DRVNG_PTC_HEAT = 557904937         // réchauffage batterie : écriture 1 = activer, 2 = désactiver ; lecture 0 = activé, 1 = désactivé (relevé sur la voiture le 2026-10-15)
    const val BMS_CHRG_SP_RSN = 557904917        // motif d'arrêt de charge : 1 défaut, 2 surchauffe, 3 prise, 4 tension, 5 autre
    const val SPR_CHRG = 557904916               // « SPR_CHRG » : rôle inconnu, test caché dans les réglages
    const val ONBD_CHRG_AC_CRNT = 560002108      // chargeur embarqué : courant d'entrée AC (A)
    const val ONBD_CHRG_AC_VOL = 560002109       // chargeur embarqué : tension d'entrée AC (V)
    const val REGEN_RNG_AFT_CHRG = 559980964     // autonomie récupérée au freinage depuis la recharge (km)
    const val BMS_PACK_VOL = 560002054           // tension batterie (V, float)
    const val BMS_PACK_CRNT = 560002055          // courant batterie (A, float)
    /** Fonction des boutons du volant (combiné, 0x21407b14) : 2 = clim (fenêtre de clim rapide ouverte). */
    const val SWC_FUNCTION = 557873940
    const val ELEC_CSUMP_PERKM = 560002077       // conso récente (kWh/100 km, float)
    const val CONS_TOTAL_AFT_CHRG = 559980987    // énergie depuis la recharge (float)
    const val CONS_TOTAL_AFT_ST = 559980988      // énergie depuis le démarrage
    const val CONS_ACCRY_AFT_CHRG = 559980989    // accessoires
    const val CONS_ACCRY_AFT_ST = 559980990
    const val CONS_RGEN_AFT_CHRG = 559980991     // récupération
    const val CONS_RGEN_AFT_ST = 559980992
    const val CONS_ATC_AFT_CHRG = 559980993      // climatisation
    const val CONS_ATC_AFT_ST = 559980994
    // Anciens identifiants (autres variantes) : repli si les nouveaux ne répondent pas
    const val OLD_ACC_AFT_CHRG = 559980951
    const val OLD_ACC_AFT_ST = 559980952
    const val OLD_TOTAL_AFT_CHRG = 559980953
    const val OLD_TOTAL_AFT_ST = 559980954
    const val OLD_RGEN_AFT_ST = 559980961
    const val OLD_RGEN_AFT_CHRG = 559980962

    @Volatile private var car: Any? = null
    @Volatile private var cpm: Any? = null
    private var appContext: Context? = null
    private val mock = mutableMapOf<Int, Number>()

    fun start(context: Context) {
        appContext = context.applicationContext
        if (BuildConfig.MOCK) { seedMock(); return }
        connect()
    }

    private fun connect() {
        val ctx = appContext ?: return
        try {
            val carClass = Class.forName("android.car.Car")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) { resolve(carClass) }
                override fun onServiceDisconnected(name: ComponentName?) { cpm = null }
            }
            val c = carClass.getMethod("createCar", Context::class.java, ServiceConnection::class.java).invoke(null, ctx, conn)
            car = c
            carClass.getMethod("connect").invoke(c)
            Log.i(TAG, "vehicle service connection requested")
        } catch (e: Throwable) {
            Log.w(TAG, "android.car unavailable: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun resolve(carClass: Class<*>) {
        try {
            val svc = carClass.getField("PROPERTY_SERVICE").get(null) as String
            cpm = carClass.getMethod("getCarManager", String::class.java).invoke(car, svc)
            Log.i(TAG, "CarPropertyManager ready")
        } catch (e: Throwable) {
            Log.w(TAG, "CarPropertyManager unavailable: ${e.message}")
        }
    }

    val ready: Boolean get() = BuildConfig.MOCK || cpm != null

    /**
     * Diagnostic : liste toutes les propriétés exposées par la voiture avec leur valeur, dans le
     * journal (balise « Props »). Sert à trouver des mesures (températures…) sans modifier quoi que ce soit.
     */
    fun dumpAll() {
        val m = cpm ?: return
        try {
            @Suppress("UNCHECKED_CAST")
            val list = m.javaClass.getMethod("getPropertyList").invoke(m) as List<Any>
            Log.i("Props", "${list.size} properties")
            list.forEach { cfg ->
                val id = cfg.javaClass.getMethod("getPropertyId").invoke(cfg) as Int
                val areas = (cfg.javaClass.getMethod("getAreaIds").invoke(cfg) as? IntArray)?.joinToString(",") { "0x" + Integer.toHexString(it) } ?: ""
                Log.i("Props", "0x${Integer.toHexString(id)} areas=[$areas] = ${readAny(id)}")
            }
        } catch (e: Throwable) {
            Log.w("Props", "list failed: ${(e.cause ?: e).message}")
        }
    }

    fun getInt(id: Int): Int? {
        if (BuildConfig.MOCK) return mock[id]?.toInt()
        val m = cpm ?: return null
        return try {
            m.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, 0) as? Int
        } catch (_: Throwable) { null }
    }

    fun getFloat(id: Int): Float? {
        if (BuildConfig.MOCK) return mock[id]?.toFloat()
        val m = cpm ?: return null
        return try {
            (m.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, 0) as? Float)
                ?.takeIf { !it.isNaN() }
        } catch (_: Throwable) { null }
    }

    /**
     * Lecture « quel que soit le type » pour l'enregistreur : essaie entier, réel puis booléen,
     * sur la zone globale puis sur les zones habituelles (siège conducteur, rangée 1…).
     */
    /** Lecture entière sur une zone précise (ex. 0x1000000 pour le mode jour / nuit). */
    fun readIntArea(id: Int, area: Int): Int? {
        if (BuildConfig.MOCK) return null
        val m = cpm ?: return null
        return try {
            m.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, area) as? Int
        } catch (_: Throwable) { null }
    }

    /** Lecture réelle sur une zone précise (ex. 0x1000000, zone globale des mesures batterie). */
    fun readFloatArea(id: Int, area: Int): Float? {
        if (BuildConfig.MOCK) return null
        val m = cpm ?: return null
        return try {
            m.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, area) as? Float
        } catch (_: Throwable) { null }
    }

    private val areaCache =java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** Propriété en octets (type 0x0070…), ou null ; zones essayées : globale puis 0x1000000. */
    fun readBytes(id: Int): ByteArray? {
        if (BuildConfig.MOCK) return null
        val m = cpm ?: return null
        for (area in intArrayOf(0, 0x1000000)) {
            try {
                val pv = m.javaClass.getMethod("getProperty", Class::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(m, ByteArray::class.java, id, area) ?: continue
                (pv.javaClass.getMethod("getValue").invoke(pv) as? ByteArray)?.let { return it }
            } catch (_: Throwable) { }
        }
        return null
    }

    /**
     * Côté conduite (calibration CAL_DRIVE_HDL_TYPE, lue comme le fait le service CarPlay d'allgo) :
     * 0x00 = conduite à gauche, 0x01 = conduite à droite ; null si la voiture ne la fournit pas.
     */
    fun rightHandDrive(): Boolean? = readBytes(CAL_DRIVE_HDL_TYPE)?.firstOrNull()?.toInt()?.let { when (it) { 0 -> false; 1 -> true; else -> null } }
    const val CAL_DRIVE_HDL_TYPE = 561005086   // 0x2170421e (YFVehicleProperty)

    fun readAny(id: Int): String {
        if (BuildConfig.MOCK) return mock[id]?.toString() ?: ""
        val m = cpm ?: return ""
        val type = id and 0x00FF0000
        val areas = areaCache[id]?.let { intArrayOf(it) } ?: intArrayOf(0, 1, 4, 0x10, 0x40, 0x100, 117, 0x1000000)
        for (area in areas) {
            try {
                val v = when (type) {
                    0x00200000 -> m.javaClass.getMethod("getBooleanProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, area)
                    0x00600000 -> m.javaClass.getMethod("getFloatProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, area)
                    else -> m.javaClass.getMethod("getIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(m, id, area)
                }
                if (v != null) { areaCache[id] = area; return v.toString() }
            } catch (_: Throwable) { }
        }
        return ""
    }

    /** Écriture entière sur une zone précise (ex. 0x1000000 pour l'ESC, comme le menu MG). */
    fun setIntArea(id: Int, area: Int, value: Int, label: String): Boolean {
        if (BuildConfig.MOCK) { mock[id] = value; return true }
        val m = cpm ?: run { Log.w(TAG, "SET $label ignored: service not ready"); return false }
        return try {
            m.javaClass.getMethod("setIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(m, id, area, value)
            Log.i(TAG, "SET $label = $value (0x${Integer.toHexString(id)}, area 0x${Integer.toHexString(area)})")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "SET $label failed: ${(e.cause ?: e).message}")
            false
        }
    }

    /** Écriture entière ; relue ensuite par l'appelant pour confirmer. */
    fun setInt(id: Int, value: Int, label: String): Boolean {
        if (BuildConfig.MOCK) { mock[id] = value; return true }
        val m = cpm ?: run { Log.w(TAG, "SET $label ignored: service not ready"); return false }
        return try {
            m.javaClass.getMethod("setIntProperty", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(m, id, 0, value)
            Log.i(TAG, "SET $label = $value (0x${Integer.toHexString(id)})")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "SET $label failed: ${(e.cause ?: e).message}")
            false
        }
    }

    private fun seedMock() {
        mock[CHRG_TRGT_SOC] = 7; mock[RESER_CTRL] = 2; mock[RESER_ST_HOUR] = 22; mock[RESER_ST_MIN] = 30
        mock[RESER_SP_HOUR] = 6; mock[RESER_SP_MIN] = 0; mock[ALTNG_CHRG_CRNT] = 4; mock[V2X_SP_SOC] = 3; mock[V2X] = 0; mock[DRVNG_PTC_HEAT] = 0
        mock[BMS_PACK_VOL] = 352.4f; mock[BMS_PACK_CRNT] = 35.2f; mock[ELEC_CSUMP_PERKM] = 15.6f
        mock[CONS_TOTAL_AFT_CHRG] = 18.4f; mock[CONS_ATC_AFT_CHRG] = 2.1f; mock[CONS_ACCRY_AFT_CHRG] = 0.9f; mock[CONS_RGEN_AFT_CHRG] = 3.2f
        mock[REGEN_RNG_AFT_CHRG] = 50.9f; mock[BMS_CHRG_SP_RSN] = 0
        listOf(252, 248, 272, 276).forEachIndexed { i, v -> mock[0x21401553 + i] = v; mock[0x2140155b + i] = 21 + i % 2; mock[0x21401557 + i] = 0 }
        mock[CONS_TOTAL_AFT_ST] = 3.6f; mock[CONS_ATC_AFT_ST] = 0.5f; mock[CONS_ACCRY_AFT_ST] = 0.2f; mock[CONS_RGEN_AFT_ST] = 0.7f
    }

    // ── Conversions code ↔ pourcentage (tables de l'appli Énergie SAIC) ──
    fun targetSocPercent(code: Int?): Int? = code?.takeIf { it in 1..7 }?.let { 30 + it * 10 }
    fun targetSocCode(percent: Int): Int = ((percent - 30) / 10).coerceIn(1, 7)
    fun v2lPercent(code: Int?): Int? = code?.takeIf { it in 1..7 }?.let { 10 + it * 10 }
    fun v2lCode(percent: Int): Int = ((percent - 10) / 10).coerceIn(1, 7)
    fun currentLabel(code: Int?): String = when (code) { 1 -> "6 A"; 2 -> "8 A"; 3 -> "16 A"; 4 -> "Max"; else -> "—" }
}
