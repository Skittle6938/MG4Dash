/*
 * MG4Dash — head unit firmware detection.
 * Adapted from FirmwareInfo.kt of MG4Control (https://github.com/SliDeeN/MG4Control),
 * Copyright © 2026 Slice Denkins and the MG4Control contributors, GPL v3 or later.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package app.mg4dash.system

import android.annotation.SuppressLint
import android.content.Context

/**
 * SAIC firmware generation, read from `ro.build.mt2712.version` (fallback on Android's
 * incremental version), for example "SWI69-…".
 *
 * Two families of car access (found by MG4Control):
 *  - **A9** (SWI69, SWI131, SWI132, SWI173): `com.saicmotor.caradapter` service, one binder per domain
 *    (climate, EV, settings, general). This is MG4Dash's original route, tested on SWI69.
 *  - **Old SDK** (SWI68, SWI165, SWI133): SAIC managers (climate: AirConditionManager)
 *    are loaded from the stock launcher `com.saicmotor.hmi.launcher`.
 * In both cases, battery measurements (CarPropertyManager) use the same identifiers.
 *
 * An unknown firmware tries the A9 route, then the old SDK; the settings' Compatibility screen
 * says what answers.
 */
object Firmware {
    enum class Gen { SWI69, SWI131, SWI132, SWI173, SWI68, SWI165, SWI133, UNKNOWN }
    enum class Family { A9, LEGACY, UNKNOWN }

    @Volatile private var cached: Gen? = null
    @Volatile private var raw: String? = null

    /** Raw firmware string (e.g. "SWI69-12345"), or "?". */
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
     * The old SDK stops charging at the chosen limit itself: MG4Dash's "auto stop"
     * feature (designed for SWI69) is hidden there.
     */
    val nativeChargeLimit: Boolean get() = family == Family.LEGACY

    /** Firmware on which MG4Dash was developed and tested. */
    val tested: Boolean get() = gen == Gen.SWI69

    /** Stock launcher of the old SDK (holds the SAIC climate classes). */
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
