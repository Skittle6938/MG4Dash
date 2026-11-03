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
 * Secondary page (Climate, Vehicle…): "‹ Home | TITLE | actions" header + content,
 * full screen, refreshed at each vehicle state.
 */
abstract class PageActivity : AppCompatActivity() {
    protected lateinit var kit: Kit
    protected lateinit var header: LinearLayout
    protected lateinit var body: LinearLayout
    private var themeSig = 0
    private var builtVertical = false

    /** Auto mode: day / night switch (lights) while the page is open. */
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
        // Top left corner left free: MG overlays the fog light button there.
        // (with the vertical bar, the button sits on the bar: no room to leave)
        header.addView(android.view.View(this), kit.lp(if (Dock.vertical(this)) kit.dp(4) else kit.dp(Kit.FOG_SAFE_DP), 1))
        // Fixed height: the header keeps the same size on all pages.
        val title = kit.title(pageTitle).apply { textSize = 20f; gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(title, kit.lp(0, kit.dp(52), 1f, l = 8))
        root.addView(header, kit.lp(-1, -2))
        body = kit.row().apply { isBaselineAligned = false }
        root.addView(body, kit.lp(-1, 0, 1f, t = 8))
        // Same dock as on home: move directly from one page to another.
        val dock = LinearLayout(this)   // appearance set by Dock.build(), as on home
        root.addView(dock, kit.lp(-1, kit.dp(84), t = 12))
        Dock.mount(this, root, dock, page)
        builtVertical = Dock.vertical(this)
        setContentView(root)
        buildBody()
        // Same panel outline as home, faded or full according to the setting (#40)
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
