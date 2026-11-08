package app.mg4dash

import android.graphics.Rect
import app.mg4dash.ui.FloatWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The detached window settles on the cards' edges (#98). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WindowSettleTest {
    private val climate = Rect(96, 298, 689, 708)
    private val energy = Rect(703, 12, 1296, 284)
    private val cards = listOf(climate, energy, Rect(96, 12, 689, 284))

    @Test fun nearlyOnACardGoesOnIt() =
        // The kept place seen on the car: 300 against the card's 298, 693 against 689
        assertEquals(Rect(96, 298, 689, 708), FloatWindow.settle(Rect(96, 300, 693, 708), cards, 12, stretch = true))

    @Test fun movedOnlyKeepsItsSize() {
        val r = FloatWindow.settle(Rect(105, 305, 605, 605), cards, 12, stretch = false)
        assertEquals(Rect(96, 298, 596, 598), r)
    }

    @Test fun farFromAnyEdgeStaysPut() =
        assertEquals(Rect(300, 400, 500, 500), FloatWindow.settle(Rect(300, 400, 500, 500), cards, 12, stretch = true))

    @Test fun anEdgePulledAloneGoesOnTheCard() =
        // Left edge already on the card, right edge pulled 4 px short of the radio card's left: size changes
        assertEquals(Rect(96, 298, 703, 708), FloatWindow.settle(Rect(96, 298, 699, 708), cards, 12, stretch = true))
}
