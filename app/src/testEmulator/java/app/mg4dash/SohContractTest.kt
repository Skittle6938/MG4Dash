package app.mg4dash

import android.content.Context
import app.mg4dash.energy.BatteryCapacity
import app.mg4dash.system.Prefs
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Cross-app contract: MG4Link reads MG4Dash's private preferences file directly (shared_prefs/mg4launcher.xml) to show
 * the battery health: "cap_samples" (JSON array of {t: Long ms, k: kWh, d: Int % drop, m: km}) and "battery_model"
 * (Int, gross kWh). The file name, keys, field names and types below must not change without telling the MG4Link side.
 * k and m are numbers: a whole value is written without decimals (org.json), so they must be read as numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SohContractTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val file get() = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    @Test fun samplesKeyAndJsonFields() {
        file.edit().clear().commit()
        // 11 kWh net over a 15 % drop and 60 km: one sample of 73.3 kWh
        BatteryCapacity.accumulate(ctx, 11f, 15f, 60f)
        val raw = file.getString("cap_samples", null)!!
        val o = JSONArray(raw).getJSONObject(0)
        assertEquals(setOf("t", "k", "d", "m"), o.keys().asSequence().toSet())
        assertTrue(o.get("t") is Long)
        assertTrue(o.get("k") is Number)
        assertTrue(o.get("d") is Int)
        assertTrue(o.get("m") is Number)
        assertTrue(o.getDouble("k") in 30.0..100.0)
        assertTrue(o.getInt("d") >= BatteryCapacity.MIN_DROP.toInt())
    }

    @Test fun batteryModelKeyIsAnInt() {
        Prefs(ctx).batteryModel = 64
        assertEquals(64, file.getInt("battery_model", 0))
    }
}
