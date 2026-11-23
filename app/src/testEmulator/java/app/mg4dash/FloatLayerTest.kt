package app.mg4dash

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.mg4dash.ui.FloatWindow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
 * #98 (mquiambao00, 19/11): the web app touched (in front of the map's window) was drawn over Keep climate on and the edit
 * mode, and took their touches. What is opened over home must come above the web app's and the map's windows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class FloatLayerTest {
    private fun idle() = repeat(3) { shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS) }

    private fun home(): MainActivity {
        RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().clear()
            .putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("home_style", 0).putString("home_layout", "full")
            .putString("slots_full", "tl=climate;tc=web,map;tr=media;bl=energy;bc=hourly,daily;br=trip,power,phone")
            .putString("web_url", "https://example.org").putBoolean("web_float", true).putBoolean("map_float", true).commit()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get().also { idle() }
    }

    /** The children of the window's content in the order they are drawn (and touched, the other way): by elevation, then by order. */
    private fun drawOrder(content: ViewGroup): List<View> =
        (0 until content.childCount).map { content.getChildAt(it) }.withIndex().sortedWith(compareBy({ it.value.z }, { it.index })).map { it.value }

    private fun web(a: MainActivity): FloatWindow? =
        MainActivity::class.java.getDeclaredField("floatWeb").apply { isAccessible = true }.get(a) as FloatWindow?

    @Test fun keepClimateOnAboveTheWebAppTouched() {
        val a = home()
        val fw = web(a)
        assertNotNull("the web app in its window", fw)
        fw!!.front(true)
        app.mg4dash.climate.ClimateHoldPanel.show(a); idle()
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val top = drawOrder(content).last()
        assertTrue("Keep climate on drawn last, over the web app", top.getTag(R.id.home_panel) == true)
        // The windows' own layer stays flat among home's screen and what goes over it
        val host = content.findViewWithTag<View>(FloatWindow.HOST)
        assertSame(content, host.parent)
        assertTrue("host z ${host.z}", host.z == 0f)
    }

    @Test fun editModeAboveTheWebAppTouched() {
        val a = home()
        web(a)!!.front(true)
        a.editCards(); idle()
        val content = a.findViewById<ViewGroup>(android.R.id.content)
        val top = drawOrder(content).last()
        assertTrue("the edit mode drawn last: ${top.javaClass.name}", top.javaClass.name.contains("CardsEditor"))
    }

    private fun map(a: MainActivity): FloatWindow? =
        MainActivity::class.java.getDeclaredField("floatMap").apply { isAccessible = true }.get(a) as FloatWindow?

    private fun layerOf(a: MainActivity, fw: FloatWindow): View {
        var v: View = fw.frameView
        while ((v.parent as View).tag != FloatWindow.HOST) v = v.parent as View
        return v
    }

    private fun detach(a: MainActivity, name: String) {
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(a); idle()
    }

    /** #98 (23/11): detached, a window is drawn over the other one docked in its card, with no touch needed. */
    @Test fun detachedWebOverDockedMap() {
        val a = home()
        val web = web(a)!!; val map = map(a)!!
        // The map touched last while both were in their cards
        web.front(false); map.front(true)
        detach(a, "detachWeb")
        assertTrue(web.floating && !map.floating)
        assertTrue("web ${layerOf(a, web).z} over map ${layerOf(a, map).z}", layerOf(a, web).z > layerOf(a, map).z)
    }

    @Test fun detachedMapOverDockedWeb() {
        val a = home()
        val web = web(a)!!; val map = map(a)!!
        map.front(false); web.front(true)
        detach(a, "detachMap")
        assertTrue(map.floating && !web.floating)
        assertTrue("map ${layerOf(a, map).z} over web ${layerOf(a, web).z}", layerOf(a, map).z > layerOf(a, web).z)
        // The docked web app touched afterwards stays under the detached map
        web.front(true); map.front(false)
        assertTrue(layerOf(a, map).z > layerOf(a, web).z)
    }

    /** Both detached: the last touched in front. */
    @Test fun bothDetachedLastTouchedInFront() {
        val a = home()
        val web = web(a)!!; val map = map(a)!!
        detach(a, "detachWeb"); detach(a, "detachMap")
        assertTrue(layerOf(a, map).z > layerOf(a, web).z)
        map.front(false); web.front(true)
        assertTrue(layerOf(a, web).z > layerOf(a, map).z)
    }

    /** The two windows keep their order between them: the last touched in front. */
    @Test fun lastTouchedWindowInFront() {
        val a = home()
        val fw = web(a)!!
        fw.front(true)
        val host = a.findViewById<ViewGroup>(android.R.id.content).findViewWithTag<ViewGroup>(FloatWindow.HOST)
        val webLayer = drawOrder(host).last()
        assertTrue(webLayer.z > 0f)
        fw.front(false)
        assertTrue(webLayer.z == 0f)
    }
}
