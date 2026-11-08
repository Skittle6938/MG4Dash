package app.mg4dash

import android.provider.Settings
import app.mg4dash.system.Rescue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * MG4Dash's side of the safety net (rescue/ module): the rescue's report read once at launch, and the embedded rescue
 * app's version, which decides whether MG4Dash installs it again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RescueLinkTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun reportReadOnceThenErased() {
        // Written by RescueService.mark: "what:time"
        Settings.Global.putString(ctx.contentResolver, "mg4dash_rescued", "stock:1762630000000")
        assertEquals("stock", Rescue.takeRescued(ctx))
        assertNull(Settings.Global.getString(ctx.contentResolver, "mg4dash_rescued"))
        assertNull("only once", Rescue.takeRescued(ctx))
    }

    @Test fun recompiledReport() {
        Settings.Global.putString(ctx.contentResolver, "mg4dash_rescued", "recompiled:1762630000000")
        assertEquals("recompiled", Rescue.takeRescued(ctx))
    }

    @Test fun noReport() = assertNull(Rescue.takeRescued(ctx))

    @Test fun bundledVersionIsTheRescueModulesVersion() {
        // Bumped in rescue/build.gradle.kts without Rescue.BUNDLED_VERSION: the cars would keep the old rescue app
        val gradle = listOf(File("../rescue/build.gradle.kts"), File("rescue/build.gradle.kts")).first { it.exists() }.readText()
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(gradle)!!.groupValues[1].toLong()
        val bundled = Rescue::class.java.getDeclaredField("BUNDLED_VERSION").apply { isAccessible = true }.getLong(null)
        assertEquals(code, bundled)
    }
}
