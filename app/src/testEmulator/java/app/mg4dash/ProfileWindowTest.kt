package app.mg4dash

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.mg4dash.settings.ProfileWindow
import app.mg4dash.system.Profiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.TimeUnit

/** Profiles, step 10: the menu of the Settings banner and the New profile window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ProfileWindowTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    @Before fun clean() {
        listOf("mg4launcher", "mg4_sound_triggers", Profiles.FILE).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).edit().putBoolean("vertical_dock", true).commit()
    }

    private fun texts(v: View, out: MutableList<TextView> = mutableListOf()): List<TextView> {
        if (v is TextView) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
        return out
    }

    @Test fun bannerShowsTheCurrentProfile() {
        val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get(); idle()
        assertTrue(texts(a.window.decorView).any { it.text.toString().startsWith("Profil : Principal") })
        // Shared settings marked
        assertTrue(texts(a.window.decorView).any { it.text.toString() == "Tous les profils" })
    }

    @Test fun newProfileWindow() {
        val a = Robolectric.buildActivity(app.mg4dash.settings.SettingsActivity::class.java).setup().get(); idle()
        var switched = 0
        ProfileWindow.edit(a, null) { switched++ }
        idle()
        val d = ShadowDialog.getLatestDialog() as android.app.AlertDialog
        val name = texts(d.window!!.decorView).filterIsInstance<EditText>().first()
        // Empty: refused, the window stays
        texts(d.window!!.decorView).first { it.text.toString() == a.getString(app.mg4dash.R.string.pf_save) }.performClick(); idle()
        assertTrue(d.isShowing)
        assertTrue(texts(d.window!!.decorView).any { it.text.toString() == "Donnez un nom au profil." })
        // The same name as another: refused
        name.setText("principal"); texts(d.window!!.decorView).first { it.text.toString() == a.getString(app.mg4dash.R.string.pf_save) }.performClick(); idle()
        assertTrue(d.isShowing)
        // A new name: created, switched to, closed
        name.setText("Sophie"); texts(d.window!!.decorView).first { it.text.toString() == a.getString(app.mg4dash.R.string.pf_save) }.performClick(); idle()
        assertTrue(!d.isShowing)
        assertEquals("Sophie", Profiles.current(ctx).name)
        assertEquals(1, switched)
    }
}
