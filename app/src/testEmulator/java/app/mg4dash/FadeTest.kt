package app.mg4dash

import android.os.Looper
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Changement de thème en fondu : l'image de l'ancien écran doit recouvrir le nouveau puis s'effacer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1920dp-h720dp-land-mdpi")
class FadeTest {
    @Test fun overlayOnNewScreen() {
        val f = app.mg4dash.ui.Themes::class.java.getDeclaredField("fadeShot").apply { isAccessible = true }
        f.set(app.mg4dash.ui.Themes, android.graphics.Bitmap.createBitmap(1920, 720, android.graphics.Bitmap.Config.ARGB_8888))
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        shadowOf(Looper.getMainLooper()).idleFor(300, TimeUnit.MILLISECONDS)
        val dv = act.window.decorView as android.view.ViewGroup
        val last = dv.getChildAt(dv.childCount - 1)
        org.junit.Assert.assertTrue("pas de fondu", last is android.widget.ImageView)
    }
}
