package app.mg4dash

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import app.mg4dash.system.Prefs
import app.mg4dash.system.RadioDashUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.mg4dash.R
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RadioDashVersionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun installRadio(declared: String?) = shadowOf(app.packageManager).installPackage(PackageInfo().apply {
        packageName = RadioDashUpdater.PKG
        applicationInfo = ApplicationInfo().apply {
            packageName = RadioDashUpdater.PKG
            metaData = declared?.let { v -> Bundle().apply { putString("radiodash.version", v) } }
        }
    })

    private fun remember(v: String) = Prefs(app).raw.edit().putString("radio_dash_version", v).commit()

    @Test fun manifestVersionWinsOverTheRememberedOne() {
        installRadio("1.6.1-beta7")
        remember("1.5.2")
        assertEquals("1.6.1-beta7", RadioDashUpdater.installedVersion(app))
    }

    @Test fun withoutItTheRememberedOneIsKept() {
        installRadio(null)
        remember("1.5.2")
        assertEquals("1.5.2", RadioDashUpdater.installedVersion(app))
    }

    @Test fun aNewerStockRadioBlocksTheInstall() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = RadioDashUpdater.PKG; longVersionCode = 3
            applicationInfo = ApplicationInfo().apply { packageName = RadioDashUpdater.PKG }
        })
        assertEquals(R.string.st_rd_newer_radio, RadioDashUpdater.blocker(app))
    }

    @Test fun theSameVersionCodeIsNotBlocked() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = RadioDashUpdater.PKG; longVersionCode = 2
            applicationInfo = ApplicationInfo().apply { packageName = RadioDashUpdater.PKG }
        })
        assertNull(RadioDashUpdater.blocker(app))
    }

    @Test fun unknownWithoutEither() {
        assertNull(RadioDashUpdater.installedVersion(app))
    }
}
