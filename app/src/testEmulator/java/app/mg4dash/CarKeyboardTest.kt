package app.mg4dash

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.mg4dash.ui.CarKeyboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CarKeyboardTest {
    private val typed = StringBuilder()
    private var clears = 0
    private var dones = 0
    private lateinit var keyboard: CarKeyboard

    @Before fun setUp() { keyboard = make() }

    private fun make(numeric: Boolean = false) = CarKeyboard(
        RuntimeEnvironment.getApplication(), "fr", numeric,
        onText = { typed.append(it) },
        onBackspace = { typed.setLength(maxOf(typed.length - 1, 0)) },
        onClear = { clears++ },
        onDone = { dones++ },
    )

    private fun keys(v: View = keyboard): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { keys(v.getChildAt(it)) }
        else -> emptyList()
    }
    private fun key(label: String) = keys().first { it.text.toString() == label }

    @Test fun layoutsFollowTheLanguage() {
        assertTrue(CarKeyboard.lettersFor("fr")[0].startsWith("azerty"))
        assertTrue(CarKeyboard.lettersFor("de")[0].startsWith("qwertz"))
        assertTrue(CarKeyboard.lettersFor("en")[0].startsWith("qwerty"))
        assertTrue(CarKeyboard.lettersFor("tr")[0].startsWith("qwerty"))
    }

    @Test fun lettersAreLowerCaseAndShiftAppliesOnce() {
        key("A").performClick()
        key("⇧").performClick()
        key("B").performClick()
        key("C").performClick()
        assertEquals("aBc", typed.toString())
    }

    @Test fun longPressedShiftStaysOn() {
        key("⇧").performLongClick()
        key("A").performClick()
        key("B").performClick()
        key("⇪").performClick()
        key("C").performClick()
        assertEquals("ABc", typed.toString())
    }

    @Test fun longPressOffersAccentsThenComesBack() {
        key("E").performLongClick()
        key("É").performClick()
        assertEquals("é", typed.toString())
        assertEquals("A", keys()[0].text.toString())
    }

    @Test fun accentRowCanBeClosedWithoutTyping() {
        key("E").performLongClick()
        key("✕").performClick()
        assertEquals("", typed.toString())
        assertEquals("A", keys()[0].text.toString())
    }

    @Test fun symbolsLayerAndBack() {
        key("123").performClick()
        key("7").performClick()
        key("@").performClick()
        key("ABC").performClick()
        key("A").performClick()
        assertEquals("7@a", typed.toString())
    }

    @Test fun spaceAndDone() {
        keys().first { it.text.isEmpty() }.performClick()
        key("OK").performClick()
        assertEquals(" ", typed.toString())
        assertEquals(1, dones)
    }

    @Test fun numericFieldsOnlyOfferNumbers() {
        keyboard = make(numeric = true)
        assertEquals(setOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", ".", "OK"), keys().map { it.text.toString() }.toSet())
    }
}
