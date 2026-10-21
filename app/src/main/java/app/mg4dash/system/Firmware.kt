/*
 * MG4Dash — détection du firmware de l'autoradio.
 * Adapté de FirmwareInfo.kt de MG4Control (https://github.com/SliDeeN/MG4Control),
 * Copyright © 2026 Slice Denkins et les contributeurs de MG4Control, GPL v3 ou ultérieure.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.system

import android.annotation.SuppressLint
import android.content.Context

/**
 * Génération du firmware SAIC, lue dans `ro.build.mt2712.version` (repli sur la version
 * incrémentale d'Android), par exemple « SWI69-… ».
 *
 * Deux familles d'accès à la voiture (relevées par MG4Control) :
 *  - **A9** (SWI69, SWI131, SWI132, SWI173) : service `com.saicmotor.caradapter`, un binder par domaine
 *    (clim, EV, réglages, général). C'est la voie d'origine de MG4Dash, testée sur SWI69.
 *  - **Ancien SDK** (SWI68, SWI165, SWI133) : les gestionnaires SAIC (clim : AirConditionManager)
 *    sont chargés depuis le launcher d'origine `com.saicmotor.hmi.launcher`.
 * Dans les deux cas, les mesures batterie (CarPropertyManager) utilisent les mêmes identifiants.
 *
 * Un firmware inconnu essaie la voie A9, puis l'ancien SDK ; l'écran Compatibilité des réglages
 * dit ce qui répond.
 */
object Firmware {
    enum class Gen { SWI69, SWI131, SWI132, SWI173, SWI68, SWI165, SWI133, UNKNOWN }
    enum class Family { A9, LEGACY, UNKNOWN }

    @Volatile private var cached: Gen? = null
    @Volatile private var raw: String? = null

    /** Chaîne brute du firmware (ex. « SWI69-12345 »), ou « ? ». */
    val version: String get() { gen; return raw ?: "?" }

    val gen: Gen get() {
        cached?.let { return it }
        val v = prop("ro.build.mt2712.version") ?: prop("ro.build.version.incremental")
        raw = v
        val g = when {
            v == null -> Gen.UNKNOWN
            v.startsWith("SWI173") -> Gen.SWI173
            v.startsWith("SWI165") -> Gen.SWI165
            v.startsWith("SWI133") -> Gen.SWI133
            v.startsWith("SWI132") -> Gen.SWI132   // avant SWI131 / SWI13x
            v.startsWith("SWI131") -> Gen.SWI131
            v.startsWith("SWI68") -> Gen.SWI68
            v.startsWith("SWI69") -> Gen.SWI69
            else -> Gen.UNKNOWN
        }
        cached = g
        return g
    }

    val family: Family get() = when (gen) {
        Gen.SWI69, Gen.SWI131, Gen.SWI132, Gen.SWI173 -> Family.A9
        Gen.SWI68, Gen.SWI165, Gen.SWI133 -> Family.LEGACY
        Gen.UNKNOWN -> Family.UNKNOWN
    }

    /**
     * L'ancien SDK arrête lui-même la charge à la limite choisie : la fonction « arrêt auto »
     * de MG4Dash (pensée pour SWI69) y est masquée.
     */
    val nativeChargeLimit: Boolean get() = family == Family.LEGACY

    /** Firmware sur lequel MG4Dash a été mis au point et testé. */
    val tested: Boolean get() = gen == Gen.SWI69

    /** Launcher d'origine de l'ancien SDK (porte les classes SAIC de la clim). */
    const val LEGACY_LAUNCHER = "com.saicmotor.hmi.launcher"

    fun installed(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    @SuppressLint("PrivateApi")
    private fun prop(key: String): String? = try {
        val sp = Class.forName("android.os.SystemProperties")
        (sp.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as? String)
            ?.takeIf { it.isNotBlank() && it != "0" }
    } catch (_: Exception) { null }
}
