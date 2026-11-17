package app.mg4dash.ui

import android.content.Context
import android.content.Intent
import app.mg4dash.system.Firmware

/**
 * Gestures from the top strip over every app (17/11), recognised by the app that gets the first finger and told
 * to the other by a message, never by touches injected again. Only the first finger has to start in the strip, the
 * others land anywhere (the strip is not split-touch, so it gets them all):
 * - one finger down: the shortcuts menu, which follows the finger;
 * - two fingers down: back to MG4Dash's home (on MG4Dash's own screens, Android Auto / CarPlay instead, as there);
 * - three fingers down: MG4Link is asked to end its session ([LinkContract.ACTION_USER_DISCONNECT]).
 */
internal class StripGesture(private val density: Float) {
    enum class Action { NONE, MENU, HOME, DISCONNECT }

    companion object {
        /** Two or three fingers: the first one goes down at least this far (dp), mostly vertically. */
        const val MULTI_PULL_DP = 40
    }

    private var downX = 0f
    private var downY = 0f
    private var fingers = 0
    private var decided: Action? = null

    /** The menu follows the finger (one finger, pulled). */
    val menu: Boolean get() = decided == Action.MENU

    fun down(x: Float, y: Float) { downX = x; downY = y; fingers = 1; decided = null }

    /**
     * Another finger, [count] fingers now. True when a menu already pulled must go back: the gesture becomes a two or
     * three finger one, recognised afresh from the first finger's start.
     */
    fun pointerDown(count: Int): Boolean {
        fingers = maxOf(fingers, count)
        if (decided == Action.MENU) { decided = null; return true }
        return false
    }

    /** The first finger at [x], [y] with [count] fingers down: the action to start now, at most one per touch. */
    fun move(x: Float, y: Float, count: Int): Action {
        if (decided != null) return Action.NONE
        fingers = maxOf(fingers, count)
        val dx = x - downX; val dy = y - downY
        val a = when {
            fingers == 1 -> if (ShortcutsPanel.isPull(dx, dy, density, 1)) Action.MENU else Action.NONE
            dy >= MULTI_PULL_DP * density && Math.abs(dx) < dy -> if (fingers >= 3) Action.DISCONNECT else Action.HOME
            else -> Action.NONE
        }
        if (a != Action.NONE) decided = a
        return a
    }

    /** End of the touch. */
    fun up() { decided = null; fingers = 0 }

    /** Whether the strip catches the touches now ([Mode.CATCH]) or lets them through to the app in front. */
    enum class Mode { CATCH, PASS }
}

/**
 * The strip's mode: over MG4Link's projection it lets everything through when Android Auto reaches the top (full screen,
 * MG4Link pulls the menu itself) or when MG4Link leaves the top to the car's own pull-down menu (its option,
 * [LinkContract.SETTING_CAR_PULLDOWN]); everywhere else it catches the gestures.
 */
internal fun stripMode(linkInFront: Boolean, immersive: Boolean?, gen: Firmware.Gen, carPulldown: Boolean): StripGesture.Mode =
    if (linkInFront && (carPulldown || ShortcutsPanel.GlobalStrip.linkTakesTop(immersive, gen))) StripGesture.Mode.PASS
    else StripGesture.Mode.CATCH

/**
 * What MG4Dash and MG4Link tell each other about the top of the screen (#113 follow-up, 17/11). Each works without the
 * other: nothing here is needed for MG4Dash to run, and a missing MG4Link just gets no message.
 *
 * - MG4Dash → MG4Link: [ACTION_USER_DISCONNECT], an explicit broadcast to MG4Link's package; MG4Link ignores it without
 *   a session. MG4Link's receiver requires a permission only platform-signed apps hold (MG4Dash is), so no other app can
 *   end the session.
 * - MG4Link → MG4Dash: Settings.Global [SETTING_CAR_PULLDOWN] = 1 while its "car pull-down menu over Android Auto"
 *   option is on; 0 or absent otherwise.
 * - Unchanged: LINK_IMMERSIVE, SHORTCUTS_PULL, mg4dash_shortcuts_strip.
 */
object LinkContract {
    const val PACKAGE = "com.allgo.app.androidauto"
    const val ACTION_USER_DISCONNECT = "com.allgo.app.androidauto.USER_DISCONNECT"
    const val SETTING_CAR_PULLDOWN = "mg4link_car_pulldown"

    fun disconnectIntent(): Intent = Intent(ACTION_USER_DISCONNECT).setPackage(PACKAGE)

    /** MG4Link leaves the top of the screen to the car's pull-down menu. */
    fun carPulldown(context: Context): Boolean = try {
        android.provider.Settings.Global.getInt(context.contentResolver, SETTING_CAR_PULLDOWN, 0) == 1
    } catch (_: Exception) { false }

    /** Asks MG4Link to end its session; false when MG4Link is not installed (nothing sent). */
    fun sendDisconnect(context: Context): Boolean {
        val installed = try { context.packageManager.getPackageInfo(PACKAGE, 0); true } catch (_: Exception) { false }
        if (!installed) return false
        context.sendBroadcast(disconnectIntent())
        return true
    }
}
