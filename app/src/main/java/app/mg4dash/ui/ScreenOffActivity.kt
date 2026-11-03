package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.mg4dash.car.CarBridge

/**
 * Écran éteint (double toucher sur Énergie dans la barre verticale, issue #12) : le rétroéclairage
 * est coupé par la commande du launcher d'origine. Comme le launcher d'origine (ScreenOffView), un
 * écran noir reste par-dessus : la voiture ne rallume pas l'écran d'elle-même, c'est ce toucher
 * (ou une touche) qui le rallume.
 */
class ScreenOffActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var off = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(View(this).apply { setBackgroundColor(Color.BLACK) })
        Fullscreen.apply(this)
        // Petit délai : le doigt du double toucher est levé avant l'extinction
        handler.postDelayed({ off = CarBridge.setDisplay(false); if (!off) finish() }, 300)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) wake()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean { wake(); return true }

    /** SWI68 : c'est la fenêtre noire de SystemUI qui prend le toucher ; à sa disparition, l'écran est déjà rallumé. */
    private var covered = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) covered = true else if (covered && off) wake()
    }

    private fun wake() {
        handler.removeCallbacksAndMessages(null)
        if (off) { CarBridge.setDisplay(true); off = false }
        finish()
    }

    override fun onDestroy() {
        if (off) CarBridge.setDisplay(true)
        super.onDestroy()
    }
}
