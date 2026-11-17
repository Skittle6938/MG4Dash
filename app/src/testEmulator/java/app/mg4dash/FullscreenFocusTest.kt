package app.mg4dash

import android.app.Activity
import android.os.Looper
import android.view.View
import app.mg4dash.ui.Fullscreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The SAIC call window (Marc, 17/11 16:47): it takes the focus, the system shows the top bar, then it shrinks to its
 * floating card and gives the focus back. Home's flags are unchanged and no visibility change reaches it, yet the bar
 * is on screen: home must notice and hide it again, on the home screen and on the pages alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FullscreenFocusTest {
    @After fun reset() {
        Fullscreen.barsShown = { d -> d.rootWindowInsets?.let { it.systemWindowInsetTop != 0 || it.systemWindowInsetLeft != 0 || it.systemWindowInsetRight != 0 || it.systemWindowInsetBottom != 0 } ?: false }
    }

    /** The window manager giving or taking the focus, as on the car (hasWindowFocus() follows). */
    private fun focus(act: Activity, on: Boolean) {
        val root = act.window.decorView.rootView.parent
        root.javaClass.getMethod("windowFocusChanged", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).invoke(root, on, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(on, act.hasWindowFocus())
    }

    @Suppress("DEPRECATION")
    private fun callWindowGone(act: Activity) {
        val decor = act.window.decorView
        repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val flags = decor.systemUiVisibility
        assertEquals(View.SYSTEM_UI_FLAG_FULLSCREEN, flags and View.SYSTEM_UI_FLAG_FULLSCREEN)
        // The call window takes the focus and the bar shows; no visibility change reaches home, its flags stay the same
        var callbacks = 0
        var shown = true
        Fullscreen.barsShown = { shown }
        focus(act, false)
        // The floating card gives the focus back
        val before = Fullscreen.restores
        focus(act, true)
        decor.setOnSystemUiVisibilityChangeListener { callbacks++ }
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        // Home hid it again (once it is hidden, nothing more happens)
        assertTrue("the bar left on screen after the call window", Fullscreen.restores > before)
        assertEquals(View.SYSTEM_UI_FLAG_FULLSCREEN, decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
        shown = false
        val after = Fullscreen.restores
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        assertEquals(after, Fullscreen.restores)
    }

    @Test fun homeHidesTheBarAgain() {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("tutorial_shortcuts_seen", true).commit()
        callWindowGone(Robolectric.buildActivity(MainActivity::class.java).setup().get())
    }

    @Test fun pageHidesTheBarAgain() =
        callWindowGone(Robolectric.buildActivity(app.mg4dash.pages.ClimateActivity::class.java).setup().get())
}
