package app.mg4dash.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * Screen brightness kept from one start to the next (15/11): the car puts the automatic brightness and its
 * shift (AUTO −2 … AUTO +2) back to its own values at each start. The last state seen (from MG4Dash's bar, the stock
 * panel or anything else) is remembered, and put back at start and when the screen lights again. For a minute after
 * that, a change that is not the remembered state is taken as the car resetting it, and put back again.
 */
object BrightnessMemory {
    private const val TAG = "Brightness"
    private const val MODE = "screen_brightness_mode"
    private const val VALUE = "screen_brightness"
    private const val ADJ = "screen_auto_brightness_adj"
    /** After a start or the screen lit again: changes not ours are the car resetting, for this long. */
    internal const val RESTORE_WINDOW_MS = 60_000L

    data class State(val auto: Boolean, val adj: Float, val value: Int)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var restoreUntil = 0L
    @Volatile private var started = false

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE)

    fun saved(ctx: Context): State? {
        val sp = sp(ctx)
        if (!sp.contains("bright_auto")) return null
        return State(sp.getBoolean("bright_auto", false), sp.getFloat("bright_adj", 0f), sp.getInt("bright_value", 128))
    }

    fun current(ctx: Context): State? = try {
        val cr = ctx.contentResolver
        State(Settings.System.getInt(cr, MODE, 0) == 1, Settings.System.getFloat(cr, ADJ, 0f), Settings.System.getInt(cr, VALUE, 128))
    } catch (_: Exception) { null }

    /** What differs between [now] and [saved] and matters: the shift only in automatic, the level only by hand. */
    internal fun differs(now: State, saved: State): Boolean =
        now.auto != saved.auto || (saved.auto && Math.abs(now.adj - saved.adj) > 0.01f) || (!saved.auto && now.value != saved.value)

    /** The current state remembered (MG4Dash's bar calls it after each change, the observer for the others). */
    fun save(ctx: Context) {
        // The smart brightness sets the screen itself: its values are not the driver's to keep
        if (app.mg4dash.brightness.SmartBrightness.enabled(ctx)) return
        val s = current(ctx) ?: return
        val old = saved(ctx)
        if (old == s) return
        sp(ctx).edit().putBoolean("bright_auto", s.auto).putFloat("bright_adj", s.adj).putInt("bright_value", s.value).apply()
        Log.i(TAG, "remembered: ${if (s.auto) "auto, shift ${s.adj}" else "by hand ${s.value}"}")
    }

    /** The remembered state put back if the car changed it. */
    fun restore(ctx: Context, why: String) {
        if (app.mg4dash.brightness.SmartBrightness.enabled(ctx)) return
        val saved = saved(ctx) ?: return
        val now = current(ctx) ?: return
        if (!differs(now, saved)) return
        try {
            val cr = ctx.contentResolver
            if (saved.auto) {
                Settings.System.putFloat(cr, ADJ, saved.adj)
                Settings.System.putInt(cr, MODE, 1)
            } else {
                Settings.System.putInt(cr, MODE, 0)
                Settings.System.putInt(cr, VALUE, saved.value)
            }
            Log.i(TAG, "put back ($why): ${if (saved.auto) "auto, shift ${saved.adj}" else "by hand ${saved.value}"} (was ${if (now.auto) "auto, shift ${now.adj}" else "by hand ${now.value}"})")
        } catch (e: Exception) { Log.w(TAG, "put back ($why): ${e.message}") }
    }

    private fun openWindow(ctx: Context, why: String) {
        restoreUntil = SystemClock.elapsedRealtime() + RESTORE_WINDOW_MS
        restore(ctx, why)
    }

    /** MG4Dash start: put back now, then watch the settings (car resetting them, or a change to remember). */
    fun start(context: Context) {
        if (started) return
        started = true
        val ctx = context.applicationContext
        openWindow(ctx, "start")
        val watch = object : android.database.ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                if (SystemClock.elapsedRealtime() < restoreUntil) restore(ctx, "changed by the car") else save(ctx)
            }
        }
        try {
            val cr = ctx.contentResolver
            for (k in listOf(MODE, VALUE, ADJ)) cr.registerContentObserver(Settings.System.getUriFor(k), false, watch)
            ctx.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: android.content.Intent?) { openWindow(ctx, "screen on") }
            }, android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_ON))
        } catch (e: Throwable) { Log.w(TAG, "watch: ${e.message}") }
    }

    /** A change asked by the driver in MG4Dash: remembered at once, and no longer taken as the car resetting. */
    fun chosen(ctx: Context) {
        restoreUntil = 0L
        save(ctx)
    }
}
