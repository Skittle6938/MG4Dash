package app.mg4dash.cards

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import app.mg4dash.R
import app.mg4dash.Screenshot
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots
import app.mg4dash.ui.HomeSlots.Size
import app.mg4dash.ui.Themes
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
 * Every card of the card contract ([HomeCards], and the template) alone, in every size it accepts, dark and light:
 * build/screenshots/cards/<card>_<size>_<theme>.png. A new card gets its pictures here without writing a test.
 * Run: ./gradlew testEmulatorDebugUnitTest -Pscreenshots --tests app.mg4dash.cards.CardGalleryShots
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "fr-w1920dp-h720dp-land-mdpi")
class CardGalleryShots {
    /** The places' size in dp (HomeSlots.Size's documentation), on the 1920 × 720 screen. */
    private val dpSizes = mapOf(
        Size.SMALL to (594 to 274), Size.MEDIUM to (594 to 410), Size.TALL to (595 to 696),
        Size.WIDE to (1197 to 410), Size.LARGE to (1189 to 696),
        Size.QUARTER to (659 to 252), Size.HALF to (659 to 516), Size.BAND to (1330 to 252),
    )

    private fun shoot(name: String, make: (Context) -> HomeCard) {
        val app = RuntimeEnvironment.getApplication()
        for ((theme, look) in listOf(1 to "dark", 2 to "light")) {
            Prefs(app).raw.edit().clear().putInt("theme_mode", theme).commit()
            val act = Robolectric.buildActivity(Activity::class.java).setup().get()
            val ctx = Themes.themed(act, big = false)
            for ((size, wh) in dpSizes) {
                val h = make(ctx)
                if (!HomeSlots.fits(h.card, size) && name != "template") continue
                val d = ctx.resources.displayMetrics.density
                val w = (wh.first * d).toInt(); val ht = (wh.second * d).toInt()
                // In the cards' frame, as on the home screen (panel background, corners)
                val frame = (android.view.LayoutInflater.from(ctx).inflate(R.layout.card_frame, null, false) as FrameLayout)
                frame.addView(h.view, FrameLayout.LayoutParams(-1, -1))
                h.place(CardPlace(size))
                h.shown(true)
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
                frame.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ht, View.MeasureSpec.EXACTLY))
                frame.layout(0, 0, w, ht)
                val bmp = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
                Canvas(bmp).apply { drawColor(Themes.color(ctx, R.color.bg_top)) }.also { frame.draw(it) }
                h.shown(false)
                val out = File("build/screenshots/cards").apply { mkdirs() }
                File(out, "${name}_${size.name.lowercase()}_$look.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue("$name drew nothing in $size", frame.width > 0)
            }
        }
    }

    @Test fun registeredCards() = HomeCards.factories.forEach { (card, make) -> shoot(card.id, make) }
    @Test fun template() = shoot("template", ::TemplateCard)
}
