package app.mg4dash.cards

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.MainActivity
import app.mg4dash.Screenshot
import app.mg4dash.settings.SettingsActivity
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.HomeSlots.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
 * The cards' long press (same options window everywhere) and the cards switched off in Settings › Home › Cards
 * offered: out of the home screen and of the edit mode, back in their place when ticked again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class CardOptionsTest {
    private val prefs get() = Prefs(RuntimeEnvironment.getApplication())
    private fun idle(n: Int = 4) = repeat(n) { shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS) }

    private fun home(slots: String, theme: Int = 1, off: Set<String> = emptySet()): MainActivity {
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", theme)
            .putInt("home_style", 0).putString("home_layout", "full").putString("slots_full", slots).commit()
        prefs.cardsOff = off
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun cards(a: MainActivity) = MainActivity::class.java.getDeclaredField("homeCards").apply { isAccessible = true }.get(a) as Map<Card, HomeCard>

    @Suppress("UNCHECKED_CAST")
    private fun placed(a: MainActivity): Set<Card> {
        val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(a) as Map<*, *>
        return frames.values.flatMap { f -> f!!.javaClass.getDeclaredField("cards").apply { isAccessible = true }.get(f) as List<Card> }.toSet()
    }

    private fun texts(v: View): List<String> = if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull((v as? TextView)?.text?.toString())

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(File("build/screenshots/cards").apply { mkdirs() }, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val slots = "tl=climate;tc=consumption,map;tr=media;bl=energy;bc=radar;br=trip,power,phone"

    /** A long press on each card with options opens the options window, titled with the card's name. */
    @Test fun longPressOpensTheOptionsWindow() {
        val a = home(slots)
        for (card in listOf(Card.CONSUMPTION, Card.RADAR, Card.ENERGY, Card.CLIMATE)) {
            val h = cards(a).getValue(card)
            assertTrue("${card.id}: long press taken", h.view.performLongClick())
            idle(2)
            val d = ShadowDialog.getLatestDialog()
            assertNotNull("${card.id}: a window", d?.takeIf { it.isShowing })
            val t = texts(d.window!!.decorView)
            assertTrue("${card.id}: titled with its name ($t)", a.getString(card.label).uppercase() in t)
            d.dismiss(); idle(1)
        }
        // A card without options takes no long press
        assertFalse(cards(a).getValue(Card.TRIPS).view.isLongClickable)
    }

    /** Switched off: out of its place and of the home screen; ticked again: back where it was. */
    @Test fun switchedOffCardsLeaveTheHomeScreen() {
        val a = home(slots, off = setOf("consumption", "radar"))
        val p = placed(a)
        assertFalse(Card.CONSUMPTION in p); assertFalse(Card.RADAR in p)
        assertTrue(Card.MAP in p)
        assertEquals(setOf(Card.CONSUMPTION, Card.RADAR), HomeSlots.switchedOff(prefs))
        // Their places kept in the stored layout
        val stored = prefs.raw.getString("slots_full", "")!!
        assertTrue(stored, "consumption" in stored && "radar" in stored)
        val b = home(stored, off = emptySet())
        assertTrue(Card.CONSUMPTION in placed(b) && Card.RADAR in placed(b))
    }

    @org.junit.experimental.categories.Category(Screenshot::class)
    @Test fun optionsWindows() {
        for ((theme, look) in listOf(1 to "dark", 2 to "light")) {
            val a = home(slots, theme)
            for (card in listOf(Card.CONSUMPTION, Card.RADAR, Card.ENERGY, Card.CLIMATE)) {
                cards(a).getValue(card).view.performLongClick(); idle(2)
                val d = ShadowDialog.getLatestDialog()
                save(d.window!!.decorView, "options_${card.id}_$look.png")
                d.dismiss(); idle(1)
            }
        }
    }

    @org.junit.experimental.categories.Category(Screenshot::class)
    @Test fun cardsOfferedSetting() {
        for ((theme, look) in listOf(1 to "dark", 2 to "light")) {
            prefs.raw.edit().clear().putInt("theme_mode", theme).commit()
            prefs.cardsOff = setOf("radar", "web", "nav")
            val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get(); idle()
            SettingsActivity::class.java.getDeclaredMethod("cardsOffered").apply { isAccessible = true }.invoke(s); idle(2)
            val d = ShadowDialog.getLatestDialog()
            save(d.window!!.decorView, "setting_cards_offered_$look.png")
            d.dismiss(); idle(1)
        }
    }
}
