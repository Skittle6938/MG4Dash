package app.mg4dash

import android.provider.Settings
import app.mg4dash.ui.EmbeddedApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The SAIC call window kept in full screen (17/11): added to policy_control with the option, removed without it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CallBarPolicyTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val policy get() = Settings.Global.getString(ctx.contentResolver, "policy_control").orEmpty()
    private fun set(fullscreen: Boolean, callBar: Boolean) =
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putBoolean("fullscreen", fullscreen).putBoolean("call_bar_hidden", callBar).remove("map_app").commit()

    @Test fun onByDefault() {
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        EmbeddedApp.refreshImmersive(ctx)
        assertTrue(policy, policy.contains(EmbeddedApp.SAIC_PHONE))
    }

    @Test fun addedThenRemovedCleanly() {
        // Another app's entry is kept whatever happens
        Settings.Global.putString(ctx.contentResolver, "policy_control", "immersive.full=com.iternio.abrpapp")
        set(fullscreen = true, callBar = true)
        EmbeddedApp.refreshImmersive(ctx)
        assertTrue(policy, policy.contains(EmbeddedApp.SAIC_PHONE))
        assertTrue(policy, policy.contains("com.iternio.abrpapp"))
        set(fullscreen = true, callBar = false)
        EmbeddedApp.refreshImmersive(ctx)
        assertFalse(policy, policy.contains(EmbeddedApp.SAIC_PHONE))
        assertTrue(policy, policy.contains("com.iternio.abrpapp"))
    }

    @Test fun notWithoutFullScreen() {
        Settings.Global.putString(ctx.contentResolver, "policy_control", null)
        set(fullscreen = false, callBar = true)
        EmbeddedApp.refreshImmersive(ctx)
        assertFalse(policy, policy.contains(EmbeddedApp.SAIC_PHONE))
        assertEquals(false, policy.contains("app.mg4dash"))
    }
}
