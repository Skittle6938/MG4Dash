package app.mg4dash.system

import android.content.Context

/**
 * Profiles, step 12 (option, off by default, shared): each profile keeps the car's drive mode and regeneration. Leaving
 * a profile, MG4Dash notes them; arriving on a profile, it puts them back, stopped or driving. Nothing is sent when the
 * car is already there.
 */
object DriveMemory {
    /** The car's drive mode, regeneration level and one pedal (1 = on), as noted; null where unknown. */
    data class Drive(val mode: Int?, val regen: Int?, val onePedal: Int?) {
        fun encode() = "${mode ?: ""},${regen ?: ""},${onePedal ?: ""}"
        companion object {
            fun decode(s: String?): Drive? = s?.split(',')?.takeIf { it.size == 3 }?.let { p -> Drive(p[0].toIntOrNull(), p[1].toIntOrNull(), p[2].toIntOrNull()) }
        }
    }

    /** What to send to go from [now] to [stored]: the drive mode, and the regeneration or one pedal; empty when it is already so. */
    internal fun commands(stored: Drive?, now: Drive): List<Pair<String, Int>> {
        stored ?: return emptyList()
        val out = mutableListOf<Pair<String, Int>>()
        if (stored.mode != null && stored.mode != now.mode) out += "mode" to stored.mode
        if (stored.onePedal == 1) { if (now.onePedal != 1) out += "onePedal" to 1 }
        else if (stored.regen != null && (stored.regen != now.regen || now.onePedal == 1)) out += "regen" to stored.regen
        return out
    }

    private fun now(): Drive = app.mg4dash.car.CarBridge.state.value.let { Drive(it.driveMode, it.regenLevel, it.onePedal) }

    fun enabled(c: Context) = Prefs(c).profilesDrive

    /** Leaving [from]: the car's drive noted for it. */
    fun note(c: Context, from: String) {
        if (!enabled(c)) return
        c.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).edit().putString("drive_$from", now().encode()).apply()
    }

    /** Arriving on [to]: its drive put back, what differs only. */
    fun putBack(c: Context, to: String) {
        if (!enabled(c)) return
        val stored = Drive.decode(c.getSharedPreferences(Profiles.FILE, Context.MODE_PRIVATE).getString("drive_$to", null))
        commands(stored, now()).forEach { (what, v) ->
            Log.i("Profiles", "drive of the profile put back: $what $v")
            when (what) {
                "mode" -> app.mg4dash.car.CarBridge.setDriveMode(v)
                "onePedal" -> app.mg4dash.car.CarBridge.setOnePedal()
                "regen" -> app.mg4dash.car.CarBridge.setRegenLevel(v)
            }
        }
    }
}
