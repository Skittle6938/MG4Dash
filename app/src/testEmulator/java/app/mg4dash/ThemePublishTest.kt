package app.mg4dash

import app.mg4dash.ui.Themes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The theme Radio Dash reads: its bar follows MG4Dash's width and side (Radio Dash #2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ThemePublishTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs() = app.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE)

    @Test fun narrowBarOnTheLeft() {
        prefs().edit().putBoolean("vertical_dock", true).putBoolean("wide_bar", false).putInt("bar_side", 1).commit()
        assertEquals(listOf("1", "0", "0", "0"), Themes.published(app).split(",").takeLast(4))
    }

    @Test fun wideBarOnTheRight() {
        prefs().edit().putBoolean("vertical_dock", true).putBoolean("wide_bar", true).putInt("bar_side", 2).commit()
        val f = Themes.published(app).split(",")
        assertEquals(7, f.size)
        assertEquals(listOf("1", "0", "1", "1"), f.takeLast(4))
    }
}
