package app.mg4dash.system

import android.app.Activity
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Maintenance mode (service mode, SAIC home back), 19/11: MG4Dash then does nothing at all but wait for the
 * three-finger gesture and its "Back to MG4Dash" banner (#123: in maintenance mode, the stock launcher's radio arrow
 * brought MG4Dash back). Started light (the gesture, what the banner needs, its rescue app kept up to date), every
 * screen of it closes at once but the offer to become the home again, and leaving maintenance restarts it in full.
 */
object Maintenance {
    private const val TAG = "Maintenance"
    private val MOCK = app.mg4dash.BuildConfig.MOCK

    fun on(context: Context): Boolean = Prefs(context).valetMode

    /**
     * For a MG4Dash screen opened in maintenance mode (a Radio Dash shortcut, MG4Link's "Open MG4Dash"): closed at
     * once, the offer to become the home again opened instead. True when it closed (its onCreate then stops there).
     */
    fun closeIfOn(activity: Activity): Boolean {
        if (!on(activity)) return false
        Log.i(TAG, "${activity.javaClass.simpleName} opened in maintenance mode: closed, the way back offered")
        try { activity.startActivity(Intent(activity, MaintenanceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }

    /**
     * Back to MG4Dash (the banner, the offer): it becomes the home again and restarts in full, as only its light part
     * runs in maintenance mode: the process stopped, home opened again a moment later.
     */
    fun backToDash(context: Context): Boolean {
        val app = context.applicationContext
        val ok = HomeSwitcher.replaceStock(app)
        Log.i(TAG, "back to MG4Dash → $ok: restarting in full")
        if (!ok) return false
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(HomeSwitcher.ours(app))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(app, 7, home, PendingIntent.FLAG_CANCEL_CURRENT)
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
            if (!MOCK) android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) { Log.w(TAG, "restart: ${e.message}") }
        return true
    }

    /**
     * Into maintenance mode while MG4Dash runs in full: it restarts in its light form (the gesture only), a moment
     * later, woken by its own WakeReceiver; nothing of it is opened.
     */
    fun restartLight(context: Context) {
        val app = context.applicationContext
        try {
            val wake = Intent(WakeReceiver.ACTION).setClass(app, WakeReceiver::class.java)
            val pi = PendingIntent.getBroadcast(app, 8, wake, PendingIntent.FLAG_CANCEL_CURRENT)
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
            Log.i(TAG, "maintenance mode: restarting with the gesture only")
            if (!MOCK) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
        } catch (e: Exception) { Log.w(TAG, "restart: ${e.message}") }
    }
}
