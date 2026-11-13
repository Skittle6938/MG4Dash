package app.mg4dash.car

import android.content.Context
import android.media.AudioAttributes
import app.mg4dash.system.Log

/**
 * Car media group muted without touching its level (#98): the driver's volume presses keep stepping from the
 * real level, nothing to restore over them. A record is kept while muted, so a crash meanwhile is undone at the
 * next start ([restoreAtStart]).
 */
object CarMediaMute {
    private const val TAG = "CarMute"
    private const val PREFS = "car_media_mute"
    private const val KEY = "muted_group"

    private val i = Int::class.javaPrimitiveType
    private val z = Boolean::class.javaPrimitiveType

    private fun group(manager: Any): Int = runCatching {
        (manager.javaClass.getMethod("getVolumeGroupIdForUsage", i).invoke(manager, AudioAttributes.USAGE_MEDIA) as Number).toInt()
    }.getOrDefault(0)

    private fun isMuted(manager: Any, group: Int): Boolean? = runCatching {
        manager.javaClass.getMethod("getGroupMute", i).invoke(manager, group) as Boolean
    }.getOrNull()

    private fun set(manager: Any, group: Int, mute: Boolean): Boolean = runCatching {
        manager.javaClass.getMethod("setGroupMute", i, z, i).invoke(manager, group, mute, 0)
    }.onFailure { Log.w(TAG, "setGroupMute($group, $mute) failed: ${it.cause ?: it}") }.isSuccess

    /** Mutes the media group; false when not possible, or already muted (by the driver: left alone). */
    fun mute(context: Context): Boolean {
        val m = CarProps.carAudioManager() ?: return false.also { Log.w(TAG, "mute: no car audio manager") }
        val g = group(m)
        if (isMuted(m, g) == true) { Log.i(TAG, "media group $g already muted: left"); return false }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.edit().putInt(KEY, g).commit()) return false
        if (!set(m, g, true)) { p.edit().remove(KEY).commit(); return false }
        Log.i(TAG, "media group $g muted (level ${runCatching { m.javaClass.getMethod("getGroupVolume", i).invoke(m, g) }.getOrNull()}), muted=${isMuted(m, g)}")
        return true
    }

    /** Unmutes the group [mute] muted. */
    fun unmute(context: Context, why: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val g = p.getInt(KEY, -1).takeIf { it >= 0 } ?: return
        val m = CarProps.carAudioManager() ?: return Log.w(TAG, "unmute ($why): no car audio manager, kept for the next start")
        if (set(m, g, false)) {
            p.edit().remove(KEY).commit()
            Log.i(TAG, "media group $g unmuted ($why), muted=${isMuted(m, g)}")
        }
    }

    /** A mute left by a crash: undone. */
    fun restoreAtStart(context: Context) = unmute(context, "left at the previous start")
}
