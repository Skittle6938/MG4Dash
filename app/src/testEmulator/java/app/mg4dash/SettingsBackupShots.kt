package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import app.mg4dash.system.Prefs
import app.mg4dash.system.UsbExport
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/** Settings › System, scrolled down to the settings backup panel under Update (#129). */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class SettingsBackupShots {
    @get:Rule val tmp = TemporaryFolder()
    @After fun eject() { UsbExport.simulated = null }

    @Test fun backupPanel() {
        UsbExport.simulated = tmp.newFolder("usb")
        Prefs(RuntimeEnvironment.getApplication()).raw.edit().putBoolean("vertical_dock", true).putInt("theme_mode", 1)
            .putBoolean("settings_tabs_v2", true).putInt("settings_tab", 5).putLong("upd_checked_at", 1L).commit()
        val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get()
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }
        val root = a.window.decorView
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 720)
        }
        layout()
        val found = ArrayList<View>()
        root.findViewsWithText(found, a.getString(R.string.bk_restore), View.FIND_VIEWS_WITH_TEXT)
        assertTrue("the Restore button is shown", found.isNotEmpty())
        var p = found[0].parent
        while (p != null && p !is ScrollView) p = p.parent
        (p as ScrollView).let { it.scrollTo(0, it.getChildAt(0).height - it.height) }
        layout()
        val bmp = Bitmap.createBitmap(1920, 720, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "settings_backup_fr.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
