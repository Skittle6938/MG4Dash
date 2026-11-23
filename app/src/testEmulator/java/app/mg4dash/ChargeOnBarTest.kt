package app.mg4dash

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #29: the energy card (live power) charging in a small place that holds the connection bar: the charge line goes
 * on the bar's own line, so the card does not move down and the trip start marker stays. Medium places unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class ChargeOnBarTest {
    private val small = "tl=energy;bl=media;tc=hourly,daily;bc=climate;r=map"
    private val medium = "tl=climate;tc=energy;tr=media;bl=power;bc=hourly,daily;br=phone"

    private class Home(val act: Activity, val box: View) {
        val state: TextView get() = act.findViewById(R.id.chargeState)
        val card: View get() = act.findViewById(R.id.energyCard)
        /** The % on screen, from the top of the place. */
        fun batteryTop(): Int { val a = IntArray(2); val c = IntArray(2); act.findViewById<View>(R.id.battery).getLocationInWindow(a); box.getLocationInWindow(c); return a[1] - c[1] }
        fun inCard(v: View): Boolean { var p = v.parent; while (p != null) { if (p === card) return true; p = p.parent }; return false }
    }

    private fun home(slots: String, layout: String, slot: String, bar: Boolean, status: Int, all: Boolean = false, shot: String? = null): Home {
        val prefs = Prefs(RuntimeEnvironment.getApplication())
        prefs.raw.edit().clear().putBoolean("vertical_dock", true).putBoolean("tutorial_shortcuts_seen", true).putInt("theme_mode", 1)
            .putInt("home_style", 0).putString("home_layout", layout).putString("slots_$layout", slots)
            .putBoolean("energy_card_power", true).putBoolean("power_va", true).commit()
        if (all) prefs.raw.edit().putStringSet("conn_icons_hidden", emptySet()).putStringSet("conn_usb_shown", setOf("usb_a", "usb_c")).commit()
        HomeSlots.setConnectionsChoice(prefs, HomeSlots.Layout.values().first { it.key == layout }, if (bar) slot else HomeSlots.CONN_NONE)
        val tf = app.mg4dash.energy.EnergyMonitor::class.java.getDeclaredField("_tripStartSoc").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (tf.get(app.mg4dash.energy.EnergyMonitor) as kotlinx.coroutines.flow.MutableStateFlow<Int?>).value = 92
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val sf = app.mg4dash.car.CarBridge::class.java.getDeclaredField("_state").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val flow = sf.get(app.mg4dash.car.CarBridge) as kotlinx.coroutines.flow.MutableStateFlow<app.mg4dash.car.VehicleState>
        flow.value = flow.value.copy(chargeStatus = status, chargeRemainMin = 448, chargeCtrl = if (status == 1) 1 else 0)
        repeat(8) { shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS) }
        val frames = MainActivity::class.java.getDeclaredField("frames").apply { isAccessible = true }.get(act) as Map<*, *>
        val frame = frames[slot]!!
        val box = frame.javaClass.getDeclaredField("box").apply { isAccessible = true }.get(frame) as View
        if (shot != null) {
            val root = act.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888); root.draw(Canvas(bmp))
            val xy = IntArray(2); box.getLocationInWindow(xy)
            val crop = Bitmap.createBitmap(bmp, xy[0], xy[1], minOf(box.width, bmp.width - xy[0]), minOf(box.height, bmp.height - xy[1]))
            File(File("build/screenshots").apply { mkdirs() }, shot).outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Home(act, box)
    }

    private fun gaugeTrip(h: Home) = h.act.findViewById<app.mg4dash.ui.SocBar>(R.id.batteryGauge).tripStart

    @Test fun smallPlaceChargeLineOnTheBarCardNotMoved() {
        val withBar = home(small, "map", "tl", bar = true, status = 1, shot = "charge_on_bar_small.png")
        // Out of the card, over the bar in the place
        assertTrue(!withBar.inCard(withBar.state))
        assertSame(withBar.box, withBar.state.parent.parent)
        assertEquals("En charge · 7 h 28", withBar.state.text.toString())
        assertNotNull("the trip start marker kept", gaugeTrip(withBar))
        val top = withBar.batteryTop()
        // The % no lower than without the bar in the place (the bar keeps its height, the % moves up a little)
        val noBar = home(small, "map", "tl", bar = false, status = 1)
        assertTrue("$top vs ${noBar.batteryTop()}", top <= noBar.batteryTop())
    }

    @Test fun everyIconTwoLines() {
        val h = home(small, "map", "tl", bar = true, status = 1, all = true, shot = "charge_on_bar_all_icons.png")
        assertEquals("En charge\n7 h 28", h.state.text.toString())
    }

    @Config(qualifiers = "it-w1920dp-h720dp-land-mdpi")
    @Test fun tooLongOverTheIconsOnASolidBackground() {
        val h = home(small, "map", "tl", bar = true, status = 6, all = true, shot = "charge_on_bar_over_it.png")
        assertEquals("Connesso,\nnon riconosciuto", h.state.text.toString())
        assertNotNull(h.state.background)
    }

    @Test fun mediumPlaceUnchanged() {
        val h = home(medium, "full", "tc", bar = true, status = 1)
        assertTrue(h.inCard(h.state))
        assertNull("plugged in the card as before: no trip marker", gaugeTrip(h))
    }

    @Test fun unpluggedUnchanged() {
        val h = home(small, "map", "tl", bar = true, status = 0)
        assertTrue(h.inCard(h.state))
        assertEquals(View.GONE, (h.state.parent as View).visibility)
    }
}
