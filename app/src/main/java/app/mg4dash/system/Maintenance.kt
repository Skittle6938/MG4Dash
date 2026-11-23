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
 * screen of it closes at once and silently, and leaving maintenance restarts it in full.
 */
object Maintenance {
    private const val TAG = "Maintenance"
    private val MOCK = app.mg4dash.BuildConfig.MOCK

    fun on(context: Context): Boolean = Prefs(context).valetMode

    /**
     * For a MG4Dash screen opened in maintenance mode (a Radio Dash shortcut, the stock radio's arrow, MG4Link's "Open
     * MG4Dash"): closed at once and silently, nothing offered (21/11, #123: in maintenance nothing of MG4Dash
     * reacts; the way back is the three-finger gesture or MG4Dash chosen again as the home in Android's settings;
     * opened from an app list, it leaves maintenance as an app, the SAIC home kept).
     * True when it closed (its onCreate then stops there).
     */
    fun closeIfOn(activity: Activity): Boolean {
        if (!on(activity)) return false
        // The driver chose MG4Dash as the home again in Android's settings: maintenance left, MG4Dash the home again.
        // The other apps' shortcuts open its pages, never this.
        if (activity is app.mg4dash.MainActivity && chosenAsHome(activity)) {
            Log.i(TAG, "MG4Dash chosen as the home: leaving maintenance mode")
            activity.finish()
            backToDash(activity)
            return true
        }
        // Opened from an app list (another launcher, MG4Swipe): maintenance left, the SAIC home kept, MG4Dash opened
        // as a normal app (Discussion #111: used as an app, not as the home). Back as the home: the three-finger
        // gesture, or Settings › System › Replace SAIC home.
        if (activity is app.mg4dash.MainActivity && openedFromAppList(activity.intent)) {
            Log.i(TAG, "MG4Dash opened from an app list: leaving maintenance mode, SAIC home kept")
            activity.finish()
            openAsApp(activity)
            return true
        }
        Log.i(TAG, "${activity.javaClass.simpleName} opened in maintenance mode: closed silently")
        activity.finish()
        activity.overridePendingTransition(0, 0)
        return true
    }

    /** MG4Dash's own entry of an app list (a launcher's app drawer): MAIN with the LAUNCHER category. */
    internal fun openedFromAppList(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)

    /** HOME resolves to MG4Dash although maintenance mode made SAIC's the home: the driver chose it again. */
    internal fun chosenAsHome(context: Context): Boolean = !MOCK && try {
        HomeSwitcher.currentHome(context) == HomeSwitcher.ours(context)
    } catch (_: Exception) { false }

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
            val pi = PendingIntent.getActivity(app, 7, home, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
            if (!MOCK) android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) { Log.w(TAG, "restart: ${e.message}") }
        return true
    }

    /**
     * Out of maintenance mode as an app, the SAIC home kept: restarted in full (only its light part runs in
     * maintenance mode), its home screen opened a moment later as from an app list.
     */
    fun openAsApp(context: Context) {
        val app = context.applicationContext
        HomeSwitcher.leaveMaintenanceKeepStock(app)
        try {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(HomeSwitcher.ours(app))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pi = PendingIntent.getActivity(app, 9, main, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 800, pi)
            if (!MOCK) android.os.Process.killProcess(android.os.Process.myPid())
        } catch (e: Exception) { Log.w(TAG, "restart as an app: ${e.message}") }
    }

    /**
     * Into maintenance mode while MG4Dash runs in full: it restarts in its light form (the gesture only), a moment
     * later, woken by its own WakeReceiver; nothing of it is opened.
     */
    fun restartLight(context: Context) {
        val app = context.applicationContext
        try {
            val wake = Intent(WakeReceiver.ACTION).setClass(app, WakeReceiver::class.java)
            val pi = PendingIntent.getBroadcast(app, 8, wake, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 1500, pi)
            Log.i(TAG, "maintenance mode: restarting with the gesture only")
            if (!MOCK) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 600)
        } catch (e: Exception) { Log.w(TAG, "restart: ${e.message}") }
    }
}
