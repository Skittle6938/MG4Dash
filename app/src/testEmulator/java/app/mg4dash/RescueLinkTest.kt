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

    @Test fun carBuildEmbedsTheRescueApp() {
        // Without it, MG4Dash has nothing to install and the cars lose the safety net
        val apk = listOf(File("src/car/assets/rescue.apk"), File("app/src/car/assets/rescue.apk")).first { it.exists() }
        val head = apk.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        assertEquals("PK", String(head, 0, 2))
        assert(apk.length() > 10_000) { "rescue.apk too small: ${apk.length()}" }
        // The embedded APK is the module's current version (rebuilt into the assets after a bump)
        assertEquals(Rescue::class.java.getDeclaredField("BUNDLED_VERSION").apply { isAccessible = true }.getLong(null), apkVersionCode(apk))
    }

    /** versionCode read from the APK's binary manifest (android:versionCode, resource id 0x0101021b). */
    private fun apkVersionCode(apk: File): Long {
        val bytes = java.util.zip.ZipFile(apk).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var ids = IntArray(0)
        var pos = 8
        while (pos < bytes.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val header = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            if (type == 0x0180) ids = IntArray((size - header) / 4) { b.getInt(pos + header + it * 4) }
            if (type == 0x0102) {
                val attrStart = b.getShort(pos + 16 + 8).toInt() and 0xffff
                val attrSize = b.getShort(pos + 16 + 10).toInt() and 0xffff
                val count = b.getShort(pos + 16 + 12).toInt() and 0xffff
                for (k in 0 until count) {
                    val a = pos + 16 + attrStart + k * attrSize
                    val name = b.getInt(a + 4)
                    if (name in ids.indices && ids[name] == 0x0101021b) return b.getInt(a + 16).toLong() and 0xffffffffL
                }
            }
            pos += size
        }
        error("no versionCode in ${apk.name}")
    }

    @Test fun bundledVersionIsTheRescueModulesVersion() {
        // Bumped in rescue/build.gradle.kts without Rescue.BUNDLED_VERSION: the cars would keep the old rescue app
        val gradle = listOf(File("../rescue/build.gradle.kts"), File("rescue/build.gradle.kts")).first { it.exists() }.readText()
        val code = Regex("versionCode\\s*=\\s*(\\d+)").find(gradle)!!.groupValues[1].toLong()
        val bundled = Rescue::class.java.getDeclaredField("BUNDLED_VERSION").apply { isAccessible = true }.getLong(null)
        assertEquals(code, bundled)
    }
}
