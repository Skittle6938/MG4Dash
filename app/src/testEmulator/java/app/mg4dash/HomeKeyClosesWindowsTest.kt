package app.mg4dash

import android.app.Activity
import android.os.Looper
import android.widget.TextView
import app.mg4dash.ui.Dialogs
import app.mg4dash.ui.OptionsPopup
import app.mg4dash.ui.Windows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The car's Home key closes every settings window and popup, back to the home screen (24/11). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HomeKeyClosesWindowsTest {
    @Test
    fun everyWindowCloses() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setTheme(R.style.Theme_MG4Launcher)
        var closed = 0
        val w = Windows.show(a, "Window", TextView(a)) { closed++ }
        val q = Windows.confirm(a, "Question", "Sure?", "Erase") {}
        OptionsPopup(a, "Card").toggle("On", null, { true }) {}.show { closed++ }
        val menu = android.widget.PopupWindow(TextView(a), 200, 100).also { it.showAtLocation(a.window.decorView, 0, 0, 0); app.mg4dash.ui.Themes.track(it) }
        assertTrue(w.isShowing && q.isShowing && menu.isShowing)
        assertEquals(3, Dialogs.openCount())

        Dialogs.closeAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(w.isShowing); assertFalse(q.isShowing); assertFalse(menu.isShowing)
        assertEquals(0, Dialogs.openCount())
        // Their own close handling ran, as with the cross
        assertEquals(2, closed)
    }
}
