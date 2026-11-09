package app.mg4dash

import android.graphics.Outline
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import app.mg4dash.ui.FadeOutline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/**
 * The App card with "Detached window" chosen (#15): one corner radius for all the cards and the windows, and the
 * map back in its card at each swipe after a round trip in its window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class CardCornersTest {
    private fun idle(ms: Long = 200) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    private fun home(): MainActivity {
        org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme_mode", 1).putInt("home_style", 2).putString("wc_map", "map,consumption,shortcuts,web").putInt("home_widget", 0)
            .putBoolean("map_float", true).putBoolean("map_floating", false).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(6) { idle() }
        layout(act)
        return act
    }

    private fun layout(act: MainActivity) {
        val decor = act.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1920, 720)
    }

    private fun field(act: MainActivity, name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(act)
    private fun call(act: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(act)

    /** Corner radius of each card-sized view that clips its content to a rounded outline. */
    private fun radii(root: View): Map<String, Float> {
        val out = LinkedHashMap<String, Float>()
        val o = Outline()
        fun walk(v: View) {
            if (v.clipToOutline && v.width >= 300 && v.height >= 150 && v.outlineProvider != null) {
                o.setEmpty(); v.outlineProvider.getOutline(v, o)
                if (o.radius > 0f) out["${v.javaClass.simpleName}#${out.size} ${v.width}x${v.height}"] = o.radius
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }

    @Test fun oneRadiusEverywhere() {
        val act = home()
        val r = FadeOutline.radius(act)
        fun check(state: String) {
            val all = radii(act.window.decorView)
            assertTrue("$state: card clips found", all.size >= 2)
            all.forEach { (v, radius) -> assertEquals("$state: $v", r, radius, 0.01f) }
        }
        check("in its card")
        call(act, "detachMap"); repeat(3) { idle() }; layout(act)
        check("detached at rest")
        val fw = field(act, "floatMap") as app.mg4dash.ui.FloatWindow
        app.mg4dash.ui.FloatWindow::class.java.getDeclaredMethod("setAwake", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(fw, true)
        idle()
        check("detached awake")
        // The concentric line: a stroke drawn inside the corner keeps its curve
        assertEquals(r - 3f, FadeOutline.inset(r, 3f), 0.01f)
    }

    @Test fun mapBackAtEachSwipe() {
        val act = home()
        call(act, "detachMap"); repeat(3) { idle() }
        call(act, "attachMap"); repeat(3) { idle() }
        layout(act)
        val fw = field(act, "floatMap") as app.mg4dash.ui.FloatWindow
        @Suppress("UNCHECKED_CAST")
        val frames = field(act, "frames") as Map<String, Any>
        fun get(o: Any, name: String) = o.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(o)
        val f = frames.values.first { (get(it, "getCards") as List<*>).contains(app.mg4dash.ui.HomeSlots.Card.MAP) }
        val cards = get(f, "getCards") as List<*>
        val swipe = MainActivity::class.java.declaredMethods.first { it.name == "swipeSlot" }.apply { isAccessible = true }
        fun shown() = get(f, "getShown")
        assertEquals(app.mg4dash.ui.HomeSlots.Card.MAP, shown())
        // No layout between the swipes: the visibility changes alone must show it again
        for (dir in listOf(1, -1)) {
            repeat(cards.size * 2) { step ->
                swipe.invoke(act, f, dir); idle()
                val onMap = shown() == app.mg4dash.ui.HomeSlots.Card.MAP
                assertEquals("dir $dir, swipe ${step + 1}: window shown with its card", if (onMap) View.VISIBLE else View.INVISIBLE, fw.frameView.visibility)
            }
        }
    }
}
