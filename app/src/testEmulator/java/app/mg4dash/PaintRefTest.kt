package app.mg4dash

import android.graphics.BitmapFactory
import app.mg4dash.showroom.CarLook
import app.mg4dash.showroom.CarPaint
import app.mg4dash.showroom.Livery
import app.mg4dash.showroom.PaintShop
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The Kotlin paint engine against the mock-up's own pictures (26/11, pages redesign): the 30 cars of the study's
 * ref/ folder (not in the repo; its path in the MG4_PAINT_REFS environment variable), rendered at scale 1.
 * MG colours: a unit or two per channel; computed paints and liveries: the drawing must lie on top.
 */
@org.junit.experimental.categories.Category(Screenshot::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class PaintRefTest {
    @Test fun matchesTheMockUp() {
        val dir = System.getenv("MG4_PAINT_REFS")?.let { File(it) }
        assumeTrue(dir != null && File(dir, "index.json").isFile)
        val ctx = RuntimeEnvironment.getApplication()
        val pics = JSONObject(File(dir, "index.json").readText()).getJSONArray("pictures")
        val report = StringBuilder()
        var worst = 0.0
        for (i in 0 until pics.length()) {
            val p = pics.getJSONObject(i)
            val view = if (p.getString("view") == "side") PaintShop.View.SIDE else PaintShop.View.Q34
            val cfg = PaintShop.Config(view, p.getInt("body"), CarPaint.byId(p.getString("paint")), p.getInt("roof") == 1,
                PaintShop.Wheel.values().first { it.id == p.getString("wheel") }, p.getInt("trim") == 1, Livery.byId(p.getString("liv")))
            val mine = runBlocking { PaintShop.render(ctx, cfg, 1f) }
            val ref = BitmapFactory.decodeFile(File(dir, p.getString("file")).path)
            val w = ref.width; val h = ref.height
            val a = IntArray(w * h); val b = IntArray(w * h)
            ref.getPixels(a, 0, w, 0, 0, w, h); mine.getPixels(b, 0, w, 0, 0, w, h)
            var sum = 0L; var n = 0; var far = 0
            for (k in a.indices) {
                if ((a[k] ushr 24) < 250) continue
                n++
                var d = 0
                for (sh in intArrayOf(16, 8, 0)) d = maxOf(d, kotlin.math.abs(((a[k] shr sh) and 0xFF) - ((b[k] shr sh) and 0xFF)))
                sum += d; if (d > 24) far++
            }
            val mean = sum.toDouble() / n; val farPct = 100.0 * far / n
            worst = maxOf(worst, farPct)
            report.append(String.format("%-34s n %d mean %.2f far %.2f %% ref %08x mine %08x\n", p.getString("file"), n, mean, farPct, a[w * (h / 2) + w / 2], b[w * (h / 2) + w / 2]))
        }
        File("build/paint_ref_report.txt").writeText(report.toString())
        println(report)
        assertTrue("pictures off the mock-up:\n$report", worst < 3.0)
    }
}
