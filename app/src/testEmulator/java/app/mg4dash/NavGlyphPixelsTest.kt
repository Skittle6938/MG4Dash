package app.mg4dash

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.mg4dash.ui.NavGlyph
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The manoeuvre drawings are worked out in the view's pixels, never on a scaled canvas (19/11: on the car the
 * arrow was a blurred blob and a roundabout showed its ring alone, its legs, head and number drawn in small units and
 * stretched by Android 9's hardware drawing).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class NavGlyphPixelsTest {

    private class Recorder(b: Bitmap) : Canvas(b) {
        val problems = mutableListOf<String>()
        var paths = 0; var texts = 0
        val drawn = RectF()
        private fun check(what: String, p: Paint) {
            if (!matrix.isIdentity) problems += "$what on a transformed canvas"
            if (p.style == Paint.Style.STROKE && p.strokeWidth < 4f) problems += "$what stroke ${p.strokeWidth} px"
        }
        override fun drawPath(path: Path, paint: Paint) {
            check("path", paint); paths++
            val r = RectF(); path.computeBounds(r, true); if (paint.style == Paint.Style.STROKE) r.inset(-paint.strokeWidth / 2, -paint.strokeWidth / 2); drawn.union(r)
            super.drawPath(path, paint)
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) { check("circle", paint); drawn.union(cx - radius, cy - radius, cx + radius, cy + radius); super.drawCircle(cx, cy, radius, paint) }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            check("text", paint); texts++
            if (paint.textSize < 20f) problems += "text size ${paint.textSize} px"
            super.drawText(text, x, y, paint)
        }
    }

    private val shapes = listOf(
        NavGlyph.Shape.Turn(0f), NavGlyph.Shape.Turn(90f), NavGlyph.Shape.Turn(-45f), NavGlyph.Shape.Turn(135f),
        NavGlyph.Shape.UTurn(false), NavGlyph.Shape.Fork(true), NavGlyph.Shape.Merge(false),
        NavGlyph.Shape.Round(90f, 1, false), NavGlyph.Shape.Round(0f, 2, false), NavGlyph.Shape.Round(270f, 3, false),
        NavGlyph.Shape.Round(180f, 4, false), NavGlyph.Shape.Round(45f, null, false), NavGlyph.Shape.Arrival,
    )

    @Test fun drawnInPixelsAtTheirSize() {
        for (s in shapes) {
            val box = RectF(0f, 0f, 220f, 220f)
            val c = Recorder(Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888))
            NavGlyph.draw(c, box, s, 0xFFFFFFFF.toInt())
            assertTrue("$s: ${c.problems}", c.problems.isEmpty())
            assertTrue("$s draws a path", c.paths >= 1)
            // Big in its box (every drawing of a size has the same scale, about 2.7 units high), and inside it
            assertTrue("$s fills its box: ${c.drawn}", maxOf(c.drawn.width(), c.drawn.height()) > 100f)
            assertTrue("$s inside its box: ${c.drawn}", c.drawn.left >= -1f && c.drawn.top >= -1f && c.drawn.right <= 221f && c.drawn.bottom <= 221f)
        }
    }

    @Test fun roundaboutDrawsItsLegsHeadAndNumber() {
        val c = Recorder(Bitmap.createBitmap(220, 220, Bitmap.Config.ARGB_8888))
        NavGlyph.draw(c, RectF(0f, 0f, 220f, 220f), NavGlyph.Shape.Round(90f, 1, false), 0xFFFFFFFF.toInt())
        // Entry leg, exit leg, head; the exit number
        assertTrue("paths ${c.paths}", c.paths >= 3)
        assertTrue("texts ${c.texts}", c.texts == 1)
    }

    /** The main manoeuvres side by side, as on the card (build/screenshots/nav_glyphs.png). */
    @Test fun sheet() {
        val cell = 220; val cols = 7
        val rows = (shapes.size + cols - 1) / cols
        val b = Bitmap.createBitmap(cols * cell, rows * cell, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(0xFF17191C.toInt())
        shapes.forEachIndexed { i, s ->
            val x = (i % cols) * cell.toFloat(); val y = (i / cols) * cell.toFloat()
            NavGlyph.draw(c, RectF(x + 20, y + 20, x + cell - 20, y + cell - 20), s, 0xFFF2F3F4.toInt())
        }
        java.io.File(java.io.File("build/screenshots").apply { mkdirs() }, "nav_glyphs.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
