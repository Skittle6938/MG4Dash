package app.mg4dash.ui

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.mg4dash.R
import app.mg4dash.car.CarBridge
import app.mg4dash.car.VehicleState
import kotlinx.coroutines.launch

/**
 * Page secondaire (Clim, Véhicule…) : en-tête « ‹ Accueil | TITRE | actions » + contenu,
 * plein écran, rafraîchie à chaque état véhicule.
 */
abstract class PageActivity : AppCompatActivity() {
    protected lateinit var kit: Kit
    protected lateinit var header: LinearLayout
    protected lateinit var body: LinearLayout
    private var themeSig = 0
    private var builtVertical = false

    /** Mode auto : bascule jour / nuit (feux) pendant que la page est ouverte. */
    private val themeWatch = object : Runnable {
        override fun run() {
            if (Themes.signature(this@PageActivity) != themeSig && !Themes.popupOpen(this@PageActivity)) { Themes.publish(this@PageActivity); Themes.recreateSmooth(this@PageActivity); return }
            body.postDelayed(this, 5000)
        }
    }

    override fun onPause() {
        super.onPause()
        body.removeCallbacks(themeWatch)
    }

    abstract val pageTitle: String
    abstract val page: Dock.Page
    abstract fun buildBody()
    abstract fun render(s: VehicleState)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Themes.apply(this)
        themeSig = Themes.signature(this)
        kit = Kit(this)
        val root = kit.column().apply {
            setBackgroundResource(R.drawable.bg_root)
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
        }
        header = kit.row()
        // Coin haut-gauche laissé libre : MG y superpose le bouton des antibrouillards.
        // (avec la barre verticale, le bouton se pose sur la barre : pas de place à laisser)
        header.addView(android.view.View(this), kit.lp(if (Dock.vertical(this)) kit.dp(4) else kit.dp(Kit.FOG_SAFE_DP), 1))
        // Hauteur fixe : l'en-tête garde la même taille sur toutes les pages.
        val title = kit.title(pageTitle).apply { textSize = 20f; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(title, kit.lp(0, kit.dp(52), 1f, l = 8))
        root.addView(header, kit.lp(-1, -2))
        body = kit.row().apply { isBaselineAligned = false }
        root.addView(body, kit.lp(-1, 0, 1f, t = 8))
        // Même dock qu'à l'accueil : on passe d'une page à l'autre directement.
        val dock = LinearLayout(this)   // apparence fixée par Dock.build(), comme sur l'accueil
        root.addView(dock, kit.lp(-1, kit.dp(84), t = 12))
        Dock.mount(this, root, dock, page)
        builtVertical = Dock.vertical(this)
        setContentView(root)
        buildBody()
        // Même contour de panneaux que l'accueil, estompé ou complet selon le réglage (#40)
        FadeOutline.applyTo(root)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { CarBridge.state.collect { render(it) } }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Themes.signature(this) != themeSig || Dock.vertical(this) != builtVertical) { Themes.recreateSmooth(this); return }
        CarBridge.setForeground(true)
        body.postDelayed(themeWatch, 5000)
        Fullscreen.apply(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Fullscreen.apply(this)
    }
}
