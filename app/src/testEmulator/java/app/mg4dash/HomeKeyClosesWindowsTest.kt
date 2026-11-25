package app.mg4dash

import android.app.Activity
import android.os.Looper
import android.widget.TextView
import app.mg4dash.chrono.ChronoWindow
import app.mg4dash.cards.LyricsCard
import app.mg4dash.memo.MemoCard
import app.mg4dash.media.MediaHub
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
        // Windows other tests of this run left open are not this test's
        Dialogs.closeAll()
        shadowOf(Looper.getMainLooper()).idle()
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        a.setTheme(R.style.Theme_MG4Launcher)
        var closed = 0
        val w = Windows.show(a, "Window", TextView(a)) { closed++ }
        val q = Windows.confirm(a, "Question", "Sure?", "Erase") {}
        OptionsPopup(a, "Card").toggle("On", null, { true }) {}.show { closed++ }

        val lyricCard = LyricsCard(a)
        lyricCard.options(OptionsPopup(a, a.getString(lyricCard.card.label), lyricCard.card.icon))!!.show { closed++ }
        MediaHub::class.java.getDeclaredField("_now").apply { isAccessible = true }
            .let { (it.get(MediaHub) as kotlinx.coroutines.flow.MutableStateFlow<MediaHub.NowPlaying>).value = MediaHub.NowPlaying() }
        lyricCard.shown(true)
        lyricCard.javaClass.getDeclaredMethod("openKaraoke").apply { isAccessible = true }.invoke(lyricCard)
        val karaoke = lyricCard.javaClass.getDeclaredField("karaoke").apply { isAccessible = true }.get(lyricCard) as android.app.Dialog

        OptionsPopup(a, a.getString(app.mg4dash.R.string.wc_chrono), app.mg4dash.R.drawable.ic_chrono)
            .content(ChronoWindow(a).view(), 1760).show { closed++ }
        val memoCard = MemoCard(a)
        memoCard.options(OptionsPopup(a, a.getString(memoCard.card.label), memoCard.card.icon))!!.show { closed++ }
        val menu = android.widget.PopupWindow(TextView(a), 200, 100).also { it.showAtLocation(a.window.decorView, 0, 0, 0); app.mg4dash.ui.Themes.track(it) }
        assertTrue(w.isShowing && q.isShowing && karaoke.isShowing && menu.isShowing)
        assertEquals("settings, question, generic card, Lyrics settings, Karaoke, Chrono settings, and Memo settings", 7, Dialogs.openCount())

        Dialogs.closeAll()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(w.isShowing); assertFalse(q.isShowing); assertFalse(karaoke.isShowing); assertFalse(menu.isShowing)
        assertEquals(0, Dialogs.openCount())
        // Their own close handling ran, as with the cross
        assertEquals(5, closed)
        lyricCard.shown(false)
        memoCard.shown(false)
    }
}
