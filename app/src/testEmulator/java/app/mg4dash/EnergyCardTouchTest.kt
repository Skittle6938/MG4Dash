package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.mg4dash.system.Prefs
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * The energy card with the live power (21/11): a tap anywhere changes the consumption, a long press anywhere opens
 * its options; no part of it (left half, power bar, the kW and V / A line) takes the touch for itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class EnergyCardTouchTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())

    private fun all(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()

    @Test fun tapAndLongPressAnywhere() {
        prefs.raw.edit().putBoolean("energy_card_power", true).putBoolean("power_va", true).putBoolean("tutorial_shortcuts_seen", true).commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        val card = a.findViewById<ViewGroup>(R.id.energyCard)
        // Nothing inside takes the touch but the charge button (its own action)
        val takers = all(card).filter { it !== card && (it.isClickable || it.isLongClickable) && it.id != R.id.chargeAction }
        assertTrue("inner views taking the touch: $takers", takers.isEmpty())
        assertTrue(card.isClickable && card.isLongClickable)
        val before = prefs.consSource
        card.performClick()
        assertNotEquals("a tap changes the consumption", before, prefs.consSource)
        assertTrue(card.performLongClick())
    }
}