package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Prefs
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The settings' windows and pages in both themes (design "grouped rows", 24/11): the Settings page's Home and
 * System tabs, a confirmation, the log, the uninstall window. Written to build/screenshots/design/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
@org.junit.experimental.categories.Category(Screenshot::class)
class SettingsDesignShots {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle(n: Int = 3) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }
    private val looks = listOf(1 to "dark", 2 to "light")

    private fun save(bmp: Bitmap, name: String) =
        File(File("build/screenshots/design").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun page(a: Activity, name: String) {
        val root = a.window.decorView
        save(Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }, name)
    }

    /** The latest window, laid out at its own width. */
    private fun window(a: Activity, name: String) {
        idle(2)
        val w = ShadowDialog.getLatestDialog().window!!
        val dv = w.decorView
        val width = if (w.attributes.width > 0) w.attributes.width else dv.width
        dv.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.window.decorView.height, View.MeasureSpec.AT_MOST))
        dv.layout(0, 0, dv.measuredWidth, dv.measuredHeight)
        save(Bitmap.createBitmap(dv.measuredWidth, dv.measuredHeight, Bitmap.Config.ARGB_8888).also { dv.draw(Canvas(it)) }, name)
        ShadowDialog.getLatestDialog().dismiss(); idle(1)
    }

    private fun settings(theme: Int, tab: Int): SettingsActivity {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme)
            .putBoolean("settings_tabs_v2", true).putInt("settings_tab", tab).commit()
        return Robolectric.buildActivity(SettingsActivity::class.java).setup().get().also { idle() }
    }

    @Test fun pages() {
        for ((theme, look) in looks) {
            page(settings(theme, 0), "page_home_$look.png")
            page(settings(theme, 5), "page_system_$look.png")
            page(settings(theme, 1), "page_look_$look.png")
        }
    }

    @Test fun windows() {
        for ((theme, look) in looks) {
            val s = settings(theme, 5)
            SettingsActivity::class.java.getDeclaredMethod("showLogDialog").apply { isAccessible = true }.invoke(s)
            window(s, "log_$look.png")
            app.mg4dash.ui.UninstallWindow.show(s)
            window(s, "uninstall_$look.png")
            SettingsActivity::class.java.getDeclaredMethod("confirmValet").apply { isAccessible = true }.invoke(s)
            window(s, "confirm_valet_$look.png")
        }
    }

    /** The consumption curve's confirmation (an anchored bubble before, a window now). */
    @Test fun clearConfirmation() {
        for ((theme, look) in looks) {
            prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme).commit()
            val a = Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
            val anchor = a.window.decorView
            app.mg4dash.ui.SourceMenu.confirm(a, anchor, a.getString(R.string.en_clear_confirm, a.getString(R.string.en_since_charge)), a.getString(R.string.en_clear_curve)) {}
            idle(2)
            val d = ShadowDialog.getLatestDialog()
            if (d != null && d.isShowing) window(a, "confirm_clear_$look.png")
            else {
                // Before: a PopupWindow; its content drawn alone
                val pw = runCatching { org.robolectric.shadows.ShadowApplication::class.java.getMethod("getLatestPopupWindow").invoke(null) as android.widget.PopupWindow? }.getOrNull()
                val v = pw?.contentView ?: continue
                save(Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }, "confirm_clear_$look.png")
            }
        }
    }
}
