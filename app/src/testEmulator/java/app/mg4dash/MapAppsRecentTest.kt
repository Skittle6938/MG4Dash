package app.mg4dash

import app.mg4dash.system.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The map card's long press menu offers the last 3 apps chosen, newest first (#98). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MapAppsRecentTest {
    @Test fun lastThreeNewestFirst() {
        val p = Prefs(RuntimeEnvironment.getApplication())
        listOf("a/.A", "b/.B", "c/.C", "d/.D").forEach { p.mapApp = it }
        assertEquals(listOf("d/.D", "c/.C", "b/.B"), p.mapAppsRecent)
        // One chosen again goes back to the top, without a duplicate
        p.mapApp = "b/.B"
        assertEquals(listOf("b/.B", "d/.D", "c/.C"), p.mapAppsRecent)
        // Back to OpenStreetMap: the list stays
        p.mapApp = null
        assertEquals(listOf("b/.B", "d/.D", "c/.C"), p.mapAppsRecent)
    }
}
