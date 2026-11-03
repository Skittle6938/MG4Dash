package app.mg4dash

import app.mg4dash.ui.atMost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ChartPointsTest {
    @Test fun shortListUnchanged() {
        val v = List(50) { it.toFloat() }
        assertSame(v, v.atMost(50))
    }

    @Test fun longListAveragedInOrder() {
        val r = List(5000) { it.toFloat() }.atMost(50)
        assertEquals(50, r.size)
        assertEquals(49.5f, r.first(), 0.01f)
        assertEquals(4949.5f, r.last(), 0.01f)
        assertEquals(r.sorted(), r)
    }

    @Test fun constantStaysConstantWhenSlicesAreUneven() {
        assertEquals(List(50) { 15f }, List(173) { 15f }.atMost(50))
    }
}
