package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import app.mg4dash.energy.ArrivalBattery
import app.mg4dash.map.NavRepo
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.NavCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

/** The guidance card (19/11): its battery on arrival, its layout by place, all its information everywhere. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class NavCardTest {

    @Test fun batteryOnArrival() {
        // 62.4 % now, 18.4 km left at 16 kWh/100 km on 48 kWh usable: 2.94 kWh = 6.1 % -> 56 %
        assertEquals(56, ArrivalBattery.percent(62.4f, 18.4f, 16f, 48f))
        // Never below 0
        assertEquals(0, ArrivalBattery.percent(5f, 300f, 18f, 48f))
        // A figure missing: nothing shown
        assertNull(ArrivalBattery.percent(null, 18f, 16f, 48f))
        assertNull(ArrivalBattery.percent(60f, null, 16f, 48f))
        assertNull(ArrivalBattery.percent(60f, 18f, null, 48f))
        assertNull(ArrivalBattery.percent(60f, 18f, 0f, 48f))
        // Arrived: what is left now
        assertEquals(60, ArrivalBattery.percent(60f, 0f, 16f, 48f))
    }

    @Test fun consumptionUsed() {
        // The average since the last charge (Marc, 20/11), whatever the trip does
        assertEquals(17f, ArrivalBattery.consumption(12, 15f, 17f)!!, 0f)
        assertEquals(17f, ArrivalBattery.consumption(3, 15f, 17f)!!, 0f)
        assertEquals(17f, ArrivalBattery.consumption(12, null, 17f)!!, 0f)
        // No average yet: the trip's from 5 km
        assertEquals(15f, ArrivalBattery.consumption(12, 15f, null)!!, 0f)
        assertNull(ArrivalBattery.consumption(3, 15f, null))
    }

    @Test fun layoutByPlace() {
        assertEquals(NavCard.Form.SMALL, NavCard.formFor(HomeSlots.Size.SMALL))
        assertEquals(NavCard.Form.MEDIUM, NavCard.formFor(HomeSlots.Size.MEDIUM))
        assertEquals(NavCard.Form.TALL, NavCard.formFor(HomeSlots.Size.TALL))
        assertEquals(NavCard.Form.WIDE, NavCard.formFor(HomeSlots.Size.WIDE))
        assertEquals(NavCard.Form.WIDE, NavCard.formFor(HomeSlots.Size.LARGE))
        assertEquals(NavCard.Form.MEDIUM, NavCard.formFor(null))
        // Placed anywhere, by the driver only
        assertTrue(HomeSlots.Size.entries.all { HomeSlots.fits(HomeSlots.Card.NAV, it) })
        assertTrue(HomeSlots.Card.NAV.manual)
        assertEquals(R.color.accent_danger, NavCard.batteryColor(8))
        assertEquals(R.color.accent_heat, NavCard.batteryColor(18))
        assertEquals(R.color.text_value, NavCard.batteryColor(62))
    }

    private val guided = NavRepo.Nav(450, 40, "Avenue de la République", NavRepo.Turn.ROUNDABOUT, "", 2, 2, 180, 18400, 1380L, "17:42",
        then = NavRepo.Then(NavRepo.Turn.TURN, 2, null, null, "Rue Pasteur"))

    /** Every text of the card laid out in [form], [w] × [h] px. */
    private fun texts(form: NavCard.Form, w: Int, h: Int, n: NavRepo.Nav?, battery: Int?, shot: String? = null): List<String> {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("mg4launcher", android.content.Context.MODE_PRIVATE).edit().putInt("theme_mode", 1).commit()
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        val card = NavCard(act).apply { this.form = form; show(n, battery) }
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, w, h)
        if (shot != null) {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(b).apply { drawColor(0xFF12161F.toInt()); card.draw(this) }
            File(File("build/screenshots").apply { mkdirs() }, shot).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val out = ArrayList<String>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            if (v is android.widget.TextView) {
                out += v.text.toString()
                // Inside the card, never cut by its edges
                val l = IntArray(2); v.getLocationInWindow(l)
                assertTrue("${v.text} below the card", v.bottom <= h)
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(card)
        return out
    }

    @Test fun everythingInEveryPlace() {
        for ((form, size) in listOf(NavCard.Form.SMALL to (594 to 274), NavCard.Form.MEDIUM to (594 to 410), NavCard.Form.TALL to (595 to 696), NavCard.Form.WIDE to (1197 to 410))) {
            val t = texts(form, size.first, size.second, guided, 62, "nav_card_${form.name.lowercase()}.png").joinToString("\n")
            for (want in listOf("450 m", "2e sortie", "Avenue de la République", "62 %", "23 min", "18 km", "17:42"))
                assertTrue("$form: \"$want\" missing in\n$t", t.contains(want))
            // The next manoeuvre everywhere but in the small place
            assertEquals("$form: then", form != NavCard.Form.SMALL, t.contains("puis"))
        }
        // Without the battery figures: no battery line, the rest is there
        val t = texts(NavCard.Form.MEDIUM, 594, 410, guided.copy(then = null), null).joinToString("\n")
        assertTrue(!t.contains("%") && t.contains("18 km") && !t.contains("puis"))
        // No guidance
        assertTrue(texts(NavCard.Form.SMALL, 594, 274, null, null, "nav_card_idle.png").contains("Pas de guidage"))
    }
}
