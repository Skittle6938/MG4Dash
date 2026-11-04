package app.mg4dash.system

import app.mg4dash.car.CarProps
import app.mg4dash.car.SaicHvacPage
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.mg4dash.media.MediaHub

/**
 * Steering wheel "*" button set to the 360 camera (issue #33). The SAIC launcher's service does receive
 * the button, but opens the camera through its own home screen, missing when
 * MG4Dash replaces it: nothing happens. Listen to the same broadcast
 * (com.saic.keyevent.hardkey.report, code 5 = left "*") and start the camera service as
 * it would. The button's other functions (drive mode, regeneration…) stay with SAIC.
 */
object StarKey {
    private const val TAG = "StarKey"
    /** Beyond this, the camera is considered closed in another way (on-screen button, while driving). */
    private const val AVM_OPEN_MS = 3 * 60_000L
    private const val ACTION = "com.saic.keyevent.hardkey.report"
    private const val KEY_LEFT_STAR = 5
    /** Climate key under the screen, old firmwares (found in their SystemUI, #62). */
    private const val KEY_HVAC = 288
    /** Right joystick (codes found on SWI173, #33, and in MG4Control): 299 left, 300 right, 301 OK. */
    private const val JOY_LEFT = 299
    private const val JOY_RIGHT = 300
    private const val JOY_OK = 301
    /** Stock launcher's media keys (SwcManager): 88 previous, 87 next. */
    private const val KEY_MEDIA_PREVIOUS = 88
    private const val KEY_MEDIA_NEXT = 87
    /** Volume: joystick up / down (297 / 298) and Android volume keys (24 / 25). */
    private val VOLUME_KEYS = setOf(297, 298, 24, 25)
    private const val SWC_HEAD_UNIT = 0
    /** Settings.System: left "*" function; 3 = rear / 360 camera (SAIC CarHardKey). */
    private const val STAR_FUNCTION = "star_key_function"
    /** Same for the right "*". */
    private const val RIGHT_STAR_FUNCTION = "right_star_key_function"
    private const val KEY_LEFT_STAR_ALT = 17
    private const val KEY_RIGHT_STAR = 286
    private const val KEY_RIGHT_STAR_ALT = 18
    private const val FUNCTION_AVM = 3

    private val AVM = listOf(
        "com.saicmotor.avm" to "com.saicmotor.avm.service.SaicMotorAvmService",
        "com.saicmotor.hmi.aroundview" to "com.saicmotor.hmi.aroundview.AvmService",
    )

    fun start(context: Context) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val code = i.getIntExtra("android.intent.extra.hardkey.keycode", -1)
                val down = i.getBooleanExtra("android.intent.extra.hardkey.down", false)
                val long = i.getBooleanExtra("android.intent.extra.hardkey.longpress", false)
                // Diagnostic (#33): each wheel button received, visible in the settings log
                Log.i(TAG, "steering wheel button: code $code ${if (down) "pressed" else "released"}${if (long) " (long)" else ""}")
                // Music from another app (SimpMusic…): the car does not send next / previous to
                // this app; pass them to the shown player (DiAuto only reads play / pause)
                // (left = previous, right = next, as for the other sources)
                // Wheel buttons assigned to the cluster or the quick climate window (#80): MG4Dash does not pass
                // them to the player, like the stock launcher, which only acts on function 0 "central screen"
                if ((code in JOY_LEFT..JOY_OK || code in VOLUME_KEYS) &&
                    (CarProps.getInt(CarProps.SWC_FUNCTION) ?: SWC_HEAD_UNIT) != SWC_HEAD_UNIT) {
                    if (down) Log.i(TAG, "steering wheel on the driver's display or climate window: button ignored")
                    return
                }
                // Radio Dash on old firmware: right joystick, or media keys depending on wheel versions
                if (down && !long && code in setOf(JOY_LEFT, JOY_RIGHT, JOY_OK, KEY_MEDIA_PREVIOUS, KEY_MEDIA_NEXT) && MediaHub.radioDashNeedsWheelKeys()) {
                    Log.i(TAG, "steering wheel button $code sent to Radio Dash")
                    when (code) {
                        JOY_LEFT, KEY_MEDIA_PREVIOUS -> MediaHub.previous()
                        JOY_RIGHT, KEY_MEDIA_NEXT -> MediaHub.next()
                        JOY_OK -> MediaHub.playPause()
                    }
                    return
                }
                if (code == JOY_OK && down && !long) MediaHub.wheelPlayPressed()
                if (code == JOY_LEFT || code == JOY_RIGHT) {
                    // From the press: the command goes to the app without waiting for the release
                    if (down && !long) { MediaHub.wheelPressed(code == JOY_RIGHT); if (MediaHub.wheelReleased(code == JOY_RIGHT)) return }
                }
                // Volume (wheel up / down): the stock launcher restarts the paused radio, MG4Dash
                // returns at once to the app that was playing
                if (down && code in VOLUME_KEYS) MediaHub.wheelPressed(null)
                // Climate key under the screen (288, old firmwares): SystemUI opens its climate page over
                // MG4Dash; it is closed right after, the previous screen stays shown (#62)
                if (code == KEY_HVAC && !down && !long && Firmware.family != Firmware.Family.A9 && Prefs(app).hideSaicClimate) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        Thread { SaicHvacPage.close(app) }.start()
                    }, 250)
                    return
                }
                if (!down || long) return
                // SWI173: the stock launcher keeps the camera state and drives it itself (LauncherLink, #78)
                if (Firmware.gen == Firmware.Gen.SWI173) return
                // "*" codes by firmware: 5 (left) on the SWI69 launcher; 17 (left) and
                // 286 or 18 (right) on old firmwares, as in MG4Control
                val a9 = Firmware.gen in setOf(Firmware.Gen.SWI69, Firmware.Gen.SWI131, Firmware.Gen.SWI132)
                val setting = when {
                    code == KEY_LEFT_STAR && a9 -> STAR_FUNCTION
                    code == KEY_LEFT_STAR_ALT && !a9 -> STAR_FUNCTION
                    (code == KEY_RIGHT_STAR || code == KEY_RIGHT_STAR_ALT) && !a9 -> RIGHT_STAR_FUNCTION
                    else -> return
                }
                val fn = try { android.provider.Settings.System.getInt(app.contentResolver, setting, -1) } catch (_: Exception) { -1 }
                Log.i(TAG, "\"*\" ($code) pressed, $setting = $fn")
                if (fn != FUNCTION_AVM || Prefs(app).valetMode) return
                starAvm(app)
            }
        }
        SaicHvacPage.prepare(app)
        try { app.registerReceiver(receiver, IntentFilter(ACTION)); Log.i(TAG, "listening to steering wheel buttons") }
        catch (e: Exception) { Log.w(TAG, "listening failed: ${e.message}") }
    }

    /**
     * "*" set to the 360 camera: opens it, or closes it if already on screen (#33),
     * like the SAIC launcher (START_ENTER / STOP to the installed camera's service).
     */
    /** Time of the last opening by "*" (0 = closed): on some firmwares the camera
     *  shows on top without becoming the top task (#33), so the state is remembered. */
    @Volatile private var avmStartedAt = 0L

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Camera installed and in the foreground (on some firmwares it does not show there, #33). */
    private fun avmTop(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getRunningTasks(1).firstOrNull()?.topActivity?.packageName == pkg
    } catch (_: Exception) { false }

    /**
     * "*" set to the 360 camera: priority to the stock launcher, which can sometimes open it itself
     * (its home screen was created). The state is noted at the press, wait 0.7 s, and MG4Dash only acts if
     * nothing changed; otherwise it opened the camera then closed it at once over the launcher.
     */
    private fun starAvm(context: Context) {
        val pkg = AVM.firstOrNull { Firmware.installed(context, it.first) }?.first ?: return
        val recent = avmStartedAt != 0L && android.os.SystemClock.elapsedRealtime() - avmStartedAt < AVM_OPEN_MS
        val topBefore = avmTop(context, pkg)
        val wasShown = topBefore || recent
        handler.postDelayed({
            val topNow = avmTop(context, pkg)
            when {
                !wasShown && topNow -> {
                    avmStartedAt = android.os.SystemClock.elapsedRealtime()
                    Log.i(TAG, "360 camera opened by the stock launcher")
                }
                wasShown && topBefore && !topNow -> {
                    avmStartedAt = 0L
                    Log.i(TAG, "360 camera closed by the stock launcher")
                }
                else -> toggleAvm(context)
            }
        }, 700)
    }

    fun toggleAvm(context: Context): Boolean {
        for ((pkg, svc) in AVM) {
            if (!Firmware.installed(context, pkg)) continue
            @Suppress("DEPRECATION")
            val top = try {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                    .getRunningTasks(1).firstOrNull()?.topActivity?.packageName
            } catch (_: Exception) { null }
            val recent = avmStartedAt != 0L && android.os.SystemClock.elapsedRealtime() - avmStartedAt < AVM_OPEN_MS
            val shown = top == pkg || recent
            val action = if (shown) "com.saicmotor.avm.STOP" else "com.saicmotor.avm.START_ENTER"
            avmStartedAt = if (shown) 0L else android.os.SystemClock.elapsedRealtime()
            return try {
                context.startService(Intent(action).setComponent(ComponentName(pkg, svc)))
                Log.i(TAG, "360 camera: $action → $svc (top task: $top)"); true
            } catch (e: Exception) { Log.w(TAG, "360 camera: $svc: ${e.message}"); false }
        }
        return false
    }
}
