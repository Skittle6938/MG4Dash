package app.mg4dash

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.mg4dash.cards.LyricsFit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The lyrics' text size from the room (25/11: a tall place showed small text). Placeholder lines, never real lyrics. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class LyricsFitTest {
    private val song = (1..40).map { i -> "Placeholder line number $i" + " word".repeat(i % 6) }

    @Test fun tallPlaceGetsBiggerText() {
        val medium = LyricsFit.size(600, 300, song, 2, 3, .6f, 0, 14f, 300f)
        val tall = LyricsFit.size(600, 680, song, 3, 3, .6f, 0, 14f, 680f)
        assertTrue("tall $tall > medium $medium", tall > medium * 1.3f)
    }

    @Test fun longLineStillFits() {
        val long = listOf("A very long placeholder line that goes on and on across the whole width and further still") + song
        val w = 500; val h = 360
        val px = LyricsFit.size(w, h, long, 2, 3, .6f, 0, 14f, 300f)
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); textSize = px }
        val p90 = LyricsFit.longLine(long)
        val layout = StaticLayout.Builder.obtain(p90, 0, p90.length, paint, w).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.08f).setIncludePad(false).build()
        assertTrue("wraps in 2 lines at most (${layout.lineCount})", layout.lineCount <= 2)
        paint.textSize = px * .6f
        assertTrue("current + next + 3 small rows within the height", 2 * layout.height + 3 * paint.fontSpacing <= h)
    }

    @Test fun boundsKept() {
        assertTrue(LyricsFit.size(80, 40, song, 2, 3, .6f, 0, 14f, 300f) == 14f)
        assertTrue(LyricsFit.size(4000, 4000, listOf("short"), 2, 0, .6f, 0, 14f, 120f) == 120f)
    }
}
