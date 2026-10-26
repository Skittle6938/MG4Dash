package app.mg4dash.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.R

/** Briques d'interface partagées par les pages (même charte que l'accueil). */
class Kit(private val c: Context) {
    companion object {
        /**
         * Largeur réservée en haut à gauche de tous les écrans : MG y superpose le bouton des
         * antibrouillards (environ 20→76 px en x, 12→68 px en y sur la dalle 1920 × 720).
         */
        const val FOG_SAFE_DP = 76
    }

    /** Sélecteur à choix unique (puces) ; renvoie la rangée et une fonction de mise à jour. */
    fun segmented(labels: List<String>, onPick: (Int) -> Unit): Pair<LinearLayout, (Int?) -> Unit> {
        val r = row()
        val chips = labels.mapIndexed { i, l ->
            toggleTile(l) { onPick(i) }.apply { textSize = 18f }.also {
                r.addView(it, lp(0, dp(52), 1f, l = if (i > 0) 8 else 0))
            }
        }
        return r to { sel -> chips.forEachIndexed { i, v -> v.isSelected = i == sel } }
    }

    /** Réglage d'heure « − HH:MM + » par pas de 15 min ; renvoie la rangée et le libellé. */
    /** Heure réglable : − / + par 30 min, toucher l'heure pour la régler aux roues ([onPick]). */
    fun timeStepper(label: String, onPick: (() -> Unit)? = null, onStep: (Int) -> Unit): Pair<LinearLayout, TextView> {
        val r = row()
        r.addView(text(label, 19f, R.color.text_sub), lp(0, -2, 1f))
        r.addView(roundButton(R.drawable.ic_minus, 52) { onStep(-30) })
        val v = text("--:--", 26f).apply {
            gravity = Gravity.CENTER
            if (onPick != null) { setBackgroundResource(R.drawable.bg_button); setPadding(dp(6), dp(6), dp(6), dp(6)); setOnClickListener { onPick() } }
        }
        r.addView(v, lp(dp(120), -2, l = 6, r = 6))
        r.addView(roundButton(R.drawable.ic_plus, 52) { onStep(30) })
        return r to v
    }

    /**
     * Roues heures : minutes (comme « clim à une heure précise ») : flèches ou glisser sur le chiffre,
     * minutes par [minStep]. Rend la vue et une lecture de l'heure choisie (minutes depuis minuit).
     */
    fun timeWheels(initialMin: Int, minStep: Int = 5): Pair<View, () -> Int> {
        var t = Math.floorMod(initialMin, 1440)
        val hh = text("", 76f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        val mm = text("", 76f, light = true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        fun paint() { hh.text = String.format(java.util.Locale.getDefault(), "%02d", t / 60); mm.text = String.format(java.util.Locale.getDefault(), "%02d", t % 60) }
        fun wheel(value: TextView, onStep: (Int) -> Unit): View {
            fun arrow(icon: Int, d: Int) = android.widget.ImageView(c).apply {
                setImageResource(icon); setColorFilter(color(R.color.text_sub)); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                val p = dp(6); setPadding(p, p, p, p); setBackgroundResource(R.drawable.bg_button); setOnClickListener { onStep(d) }
            }
            var lastY = 0f; var acc = 0f; val stepPx = dp(36).toFloat()
            value.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { lastY = e.rawY; acc = 0f }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        acc += lastY - e.rawY; lastY = e.rawY
                        while (acc >= stepPx) { onStep(1); acc -= stepPx }
                        while (acc <= -stepPx) { onStep(-1); acc += stepPx }
                    }
                }
                true
            }
            return column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(arrow(R.drawable.ic_chevron_up, 1), lp(dp(96), dp(42)))
                addView(value, lp(dp(130), -2))
                addView(arrow(R.drawable.ic_chevron_down, -1), lp(dp(96), dp(42)))
            }
        }
        val clock = row().apply { gravity = Gravity.CENTER }
        clock.addView(wheel(hh) { d -> t = Math.floorMod(t + d * 60, 1440); paint() })
        clock.addView(text(":", 70f, R.color.text_sub, light = true), lp(-2, -2, l = 6, r = 6))
        clock.addView(wheel(mm) { d -> t = Math.floorMod(t / minStep * minStep + d * minStep, 1440); paint() })
        paint()
        return clock to { t }
    }
    fun dp(v: Int) = (v * c.resources.displayMetrics.density).toInt()
    fun color(res: Int) = app.mg4dash.ui.Themes.color(c, res)

    fun column(): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    fun row(): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    fun panel(title: String? = null): LinearLayout = column().apply {
        setBackgroundResource(R.drawable.bg_panel)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        elevation = dp(6).toFloat()
        if (title != null) addView(title(title))
    }

    fun title(text: String): TextView = TextView(c).apply {
        this.text = text
        setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        letterSpacing = 0.12f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
    }

    fun text(value: String = "", sp: Float = 18f, colorRes: Int = R.color.text_value, light: Boolean = false): TextView =
        TextView(c).apply {
            text = value; setTextColor(color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (light) typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false; maxLines = 1
        }

    fun roundButton(icon: Int, sizeDp: Int = 64, tintRes: Int = R.color.text_value, onClick: () -> Unit): ImageButton =
        ImageButton(c).apply {
            setImageResource(icon); setBackgroundResource(R.drawable.bg_button)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = dp(sizeDp) / 4; setPadding(p, p, p, p)
            setColorFilter(color(tintRes))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setOnClickListener { onClick() }
        }

    /** Tuile-bascule : libellé + état, sélectionnée = accent froid (ou chaud). */
    fun toggleTile(label: String, heat: Boolean = false, onClick: () -> Unit): TextView = TextView(c).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
        setTextColor(ContextCompat.getColorStateList(c, if (heat) R.color.chip_text_heat else R.color.chip_text))
        setBackgroundResource(if (heat) R.drawable.bg_chip_heat else R.drawable.bg_chip)
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Ligne « libellé ……… valeur » pour les pages d'information. */
    fun infoRow(label: String): Pair<LinearLayout, TextView> {
        val r = row().apply { setPadding(0, dp(10), 0, dp(10)) }
        r.addView(text(label, 19f, R.color.text_sub), LinearLayout.LayoutParams(0, -2, 1f))
        val v = text("—", 20f)
        r.addView(v)
        return r to v
    }

    fun divider(): View = View(c).apply {
        setBackgroundColor(color(R.color.panel_border))
        layoutParams = LinearLayout.LayoutParams(-1, 1)
    }

    fun lp(w: Int, h: Int, weight: Float = 0f, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }
}
