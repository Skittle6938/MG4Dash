package app.mg4dash

import android.os.Looper
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** The focus back on home (after the SAIC call window): the bars end up hidden again, full screen kept. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class FullscreenFocusTest {
    @Suppress("DEPRECATION")
    @Test fun barsHiddenAfterFocusBack() {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("tutorial_shortcuts_seen", true).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val decor = act.window.decorView
        // The call window had the focus: the system showed the bars
        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        act.onWindowFocusChanged(true)
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        assertEquals(View.SYSTEM_UI_FLAG_FULLSCREEN, decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN)
        assertEquals(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY, decor.systemUiVisibility and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }
}
