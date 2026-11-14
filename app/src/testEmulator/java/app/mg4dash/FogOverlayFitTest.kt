package app.mg4dash

import app.mg4dash.system.FogOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #113: the fog light overlay only on a SystemUI whose lamp icons it holds, whatever their numbers in its range. */
class FogOverlayFitTest {
    @Test fun knownSystemUisFit() {
        // SWI69 R45, A9_Systemui V1.0.0.222
        assertTrue(FogOverlay.idsMatch(0x7f0800b8, 0x7f0800ba))
        // SWI173 R08 (and SWI86 R14), V1.0.0.153: version 1 of the overlay left it without a front lamp, a crash at each
        // start of SystemUI and the head unit restarting
        assertTrue(FogOverlay.idsMatch(0x7f0800b6, 0x7f0800b8))
    }

    @Test fun otherSystemUisNeverGetTheOverlay() {
        // SWI68, SWI133, SWI165, SWI196: no FloatBallView, ids and layout not found
        assertFalse(FogOverlay.idsMatch(0, 0, hasLayout = false))
        assertFalse("one id only", FogOverlay.idsMatch(0x7f0800b8, 0))
        assertFalse("no layout_ball", FogOverlay.idsMatch(0x7f0800b8, 0x7f0800ba, hasLayout = false))
        assertFalse("out of the overlay's range", FogOverlay.idsMatch(0x7f080200, 0x7f080202))
        assertFalse("another type", FogOverlay.idsMatch(0x7f0900b8, 0x7f0900ba))
    }

    @Test fun layoutsHoldEveryIdOfTheRange() {
        // gen.py and FogOverlay must agree on the range
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "overlay/nofog").isDirectory }
        for (name in listOf("layout_ball.xml", "layout_ball_as33.xml")) {
            val xml = File(root, "overlay/nofog/res/layout/$name").readText()
            val ids = Regex("id/i_([0-9a-f]{8})").findAll(xml).map { it.groupValues[1].toLong(16).toInt() }.toList()
            assertEquals(name, (FogOverlay.ID_FIRST..FogOverlay.ID_LAST).toList(), ids)
            assertFalse("$name: every icon at size 0", Regex("layout_width=\"(?!0dp)").containsMatchIn(xml.substringAfter("<ImageView")))
        }
    }
}
