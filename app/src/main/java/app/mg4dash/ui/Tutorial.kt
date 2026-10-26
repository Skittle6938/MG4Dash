package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * Petit tuto des gestes de l'accueil : un voile sombre, la carte concernée mise en valeur, une flèche
 * vers elle et une bulle avec les gestes principaux. Suivant / Passer ; « Passer » le referme pour de bon.
 * Lancé une fois après une première installation, ou depuis Réglages › Système.
 */
object Tutorial {

    /** Étape : vue à mettre en valeur (null = aucune, bulle au centre), titre et lignes de gestes. */
    /** [swipes] : gestes de glisser montrés en flèches animées sur la carte ('H' gauche / droite, 'V' haut / bas). */
    /** [homePanel] : le panneau « Écran d'accueil » des réglages reproduit dans la bulle (ses deux boutons). */
    private class Step(val target: Int?, val title: Int, val lines: List<Int>, val images: List<Pair<Int, Int>> = emptyList(),
                       val swipes: String = "", val homePanel: Boolean = false)

    private val STEPS = listOf(
        Step(null, R.string.tu_welcome_t, listOf(R.string.tu_welcome_1)),
        // Remplacer l'accueil SAIC, et le mode Entretien pour revenir à l'origine
        Step(null, R.string.tu_home_t, listOf(R.string.tu_home_1, R.string.tu_home_2), homePanel = true),
        // Les trois dispositions de l'accueil, en miniature
        Step(null, R.string.tu_layouts_t, listOf(R.string.tu_layouts_1, R.string.tu_layouts_2), listOf(
            R.drawable.tu_layout_full to R.string.st_style_full,
            R.drawable.tu_layout_simple to R.string.st_style_simple,
            R.drawable.tu_layout_map to R.string.st_style_map,
        )),
        Step(R.id.dock, R.string.tu_bar_t, listOf(R.string.tu_bar_1, R.string.tu_bar_2, R.string.tu_bar_3, R.string.tu_bar_4, R.string.tu_bar_5)),
        Step(R.id.climateCard, R.string.tu_climate_t, listOf(R.string.tu_climate_1, R.string.tu_climate_2, R.string.tu_climate_3)),
        Step(R.id.energyCard, R.string.tu_energy_t, listOf(R.string.tu_energy_1, R.string.tu_energy_2)),
        Step(R.id.mapCard, R.string.tu_map_t, listOf(R.string.tu_map_1, R.string.tu_map_2)),
        Step(R.id.mediaCard, R.string.tu_media_t, listOf(R.string.tu_media_1, R.string.tu_media_2, R.string.tu_media_3), swipes = "VH"),
        Step(R.id.secondaryCard, R.string.tu_widget_t, listOf(R.string.tu_widget_1, R.string.tu_widget_2), swipes = "H"),
        Step(null, R.string.tu_end_t, listOf(R.string.tu_end_1)),
    )

    /** Première installation : le tuto s'ouvre une fois sur l'accueil complet. */
    fun showIfPending(activity: Activity) {
        val p = Prefs(activity)
        if (!p.raw.getBoolean("tutorial_pending", false)) return
        activity.window.decorView.post { show(activity) }
    }

    fun show(activity: Activity, from: Int = 0) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
        root.addView(Overlay(activity, from).apply { tag = TAG }, FrameLayout.LayoutParams(-1, -1))
    }

    /** Fermé (Terminer ou Passer) : ne revient plus tout seul. */
    private fun done(activity: Activity) {
        Prefs(activity).raw.edit().putBoolean("tutorial_pending", false).apply()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }

    private const val TAG = "mg4dash_tutorial"
    /** Extra de l'accueil : ouvrir le tuto (Réglages › Système). */
    const val EXTRA_SHOW = "show_tutorial"

    private class Overlay(val activity: Activity, var index: Int) : FrameLayout(activity) {
        private val d = resources.displayMetrics.density
        private fun dp(v: Int) = (v * d).toInt()
        private val accent = Themes.color(context, R.color.accent_action)
        private val veil = Paint().apply { color = 0xD9000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = accent }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 5 * d; color = accent; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val hole = RectF()
        private val bubble = LinearLayout(context)

        init {
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isClickable = true   // l'accueil dessous ne reçoit rien pendant le tuto
            addView(bubble, LayoutParams(-2, -2))
            render()
        }

        private fun text(t: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        private fun button(t: Int, filled: Boolean, onClick: () -> Unit) = TextView(context).apply {
            text = context.getString(t); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
            setTextColor(if (filled) Color.WHITE else Themes.color(context, R.color.text_title))
            setPadding(dp(32), dp(18), dp(32), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (filled) setColor(accent) else setStroke(dp(1), Themes.color(context, R.color.text_title))
            }
            setOnClickListener { onClick() }
        }

        /** Réglages › Système › Écran d'accueil, comme à l'écran : titre du panneau et ses deux boutons. */
        private fun homePanel(): View {
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(18))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setStroke(dp(1), Themes.color(context, R.color.text_title))
                }
            }
            panel.addView(text(context.getString(R.string.tu_home_path), 20f, Themes.color(context, R.color.text_title)))
            panel.addView(text(context.getString(R.string.st_home), 21f, Themes.color(context, R.color.text_value), bold = true),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(R.string.st_replace to R.color.accent_action, R.string.st_valet to R.color.accent_heat).forEachIndexed { i, (label, color) ->
                row.addView(TextView(context).apply {
                    text = context.getString(label); gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(Themes.color(context, color))
                    setPadding(dp(12), dp(14), dp(12), dp(14))
                    background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Themes.color(context, R.color.bg_top)) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(14) })
            }
            panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            return panel
        }

        private fun render() {
            val step = STEPS[index]
            bubble.removeAllViews()
            // Bulle à largeur fixe (texte), plus large pour les miniatures des dispositions
            bubble.layoutParams = (bubble.layoutParams as LayoutParams).apply { width = dp(if (step.images.isEmpty()) 760 else 1000) }
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(dp(28), dp(24), dp(28), dp(20))
            bubble.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Themes.color(context, R.color.bg_bottom)); setStroke(dp(2), accent)
            }
            bubble.addView(text(context.getString(step.title), 34f, Themes.color(context, R.color.text_value), bold = true))
            if (step.images.isNotEmpty()) {
                val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                step.images.forEachIndexed { i, (img, label) ->
                    val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                    col.addView(RoundedFrame(context).apply {
                        addView(android.widget.ImageView(context).apply {
                            setImageResource(img); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        }, FrameLayout.LayoutParams(-1, -1))
                    }, LinearLayout.LayoutParams(dp(264), dp(99)))
                    col.addView(text(context.getString(label), 23f, Themes.color(context, R.color.text_value)),
                        LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
                    row.addView(col, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = dp(16) })
                }
                bubble.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            }
            if (step.homePanel) bubble.addView(homePanel(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            step.lines.forEach { l ->
                bubble.addView(text("•  " + context.getString(l), 25f, Themes.color(context, R.color.text_value)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            }
            val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val last = index == STEPS.size - 1
            if (!last) bottom.addView(button(R.string.tu_skip, false) { done(activity) })
            bottom.addView(text("${index + 1} / ${STEPS.size}", 22f, Themes.color(context, R.color.text_title)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, -2, 1f))
            bottom.addView(button(if (last) R.string.tu_done else R.string.tu_next, true) {
                if (last) done(activity) else { index++; render() }
            })
            bubble.addView(bottom, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
            requestLayout(); invalidate()
        }

        private fun target(): View? {
            val id = STEPS[index].target ?: return null
            activity.findViewById<View>(id)?.takeIf { it.isShown && it.width > 0 }?.let { return it }
            // Barre verticale (réglage par défaut) : pas d'identifiant, c'est la colonne étroite à côté du contenu
            if (id != R.id.dock) return null
            var v: View? = activity.findViewById(R.id.climateCard)
            while (v != null) {
                val p = v.parent as? LinearLayout ?: return null
                if (p.orientation == LinearLayout.HORIZONTAL && p.childCount >= 2) {
                    val bar = listOf(p.getChildAt(0), p.getChildAt(p.childCount - 1)).firstOrNull { it.width in 1..dp(110) }
                    if (bar != null) return bar
                }
                v = p
            }
            return null
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val v = target()
            val bw = bubble.measuredWidth; val bh = bubble.measuredHeight
            var bx = (width - bw) / 2; var by = (height - bh) / 2
            hole.setEmpty()
            if (v != null) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                val me = IntArray(2); getLocationInWindow(me)
                val x = (loc[0] - me[0]).toFloat(); val y = (loc[1] - me[1]).toFloat()
                hole.set(x - dp(6), y - dp(6), x + v.width + dp(6), y + v.height + dp(6))
                // Bulle du côté libre de l'écran, à mi-hauteur de la carte si possible
                val gap = dp(110)
                bx = if (hole.centerX() < width / 2f) (hole.right + gap).toInt() else (hole.left - gap - bw).toInt()
                if (bx < dp(16) || bx + bw > width - dp(16)) {
                    // Pas la place à côté (carte large) : au-dessus ou au-dessous
                    bx = (hole.centerX() - bw / 2f).toInt()
                    by = if (hole.centerY() < height / 2f) (hole.bottom + gap).toInt() else (hole.top - gap - bh).toInt()
                } else by = (hole.centerY() - bh / 2f).toInt()
                bx = bx.coerceIn(dp(16), width - bw - dp(16)); by = by.coerceIn(dp(16), height - bh - dp(16))
            }
            bubble.layout(bx, by, bx + bw, by + bh)
        }

        override fun dispatchDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), veil)
            if (!hole.isEmpty) {
                val r = resources.getDimension(R.dimen.radius_panel) + dp(6)
                canvas.drawRoundRect(hole, r, r, clear)
                canvas.drawRoundRect(hole, r, r, ring)
                drawArrow(canvas)
                drawSwipes(canvas)
            }
            super.dispatchDraw(canvas)
        }

        private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Color.WHITE
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        /**
         * Glisser animé sur la carte : un doigt (rond) part du centre et file dans un sens puis dans l'autre,
         * suivi d'une flèche translucide. Les sens de [Step.swipes] se succèdent (1,6 s chacun).
         */
        private fun drawSwipes(canvas: Canvas) {
            val sw = STEPS[index].swipes
            if (sw.isEmpty()) return
            val cycle = 1600L
            val t = android.os.SystemClock.uptimeMillis()
            val n = (t / cycle).toInt()
            val axis = sw[(n / 2) % sw.length]          // chaque sens joué aller puis retour
            val dir = if (n % 2 == 0) 1f else -1f
            val u = (t % cycle) / cycle.toFloat()
            val e = (1 - Math.cos(Math.PI * u.coerceAtMost(0.8f) / 0.8f).toFloat()) / 2f      // départ et arrivée en douceur
            val alpha = when { u < 0.15f -> u / 0.15f; u > 0.8f -> 1f - (u - 0.8f) / 0.2f; else -> 1f }
            val cx = hole.centerX(); val cy = hole.centerY()
            val span = if (axis == 'H') hole.width() * 0.32f else hole.height() * 0.3f
            val sx = cx - dir * span * (if (axis == 'H') 1f else 0f); val sy = cy - dir * span * (if (axis == 'V') 1f else 0f)
            val ex = cx + dir * span * (if (axis == 'H') 1f else 0f); val ey = cy + dir * span * (if (axis == 'V') 1f else 0f)
            val px = sx + (ex - sx) * e; val py = sy + (ey - sy) * e
            // Traînée en flèche du départ au doigt
            swipePaint.strokeWidth = 14 * d; swipePaint.alpha = (110 * alpha).toInt()
            canvas.drawLine(sx, sy, px, py, swipePaint)
            val ang = Math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())
            val head = 34 * d
            swipePaint.strokeWidth = 12 * d; swipePaint.alpha = (150 * alpha).toInt()
            for (sgn in listOf(-1, 1)) canvas.drawLine(px, py,
                px - (head * Math.cos(ang + sgn * 0.6)).toFloat(), py - (head * Math.sin(ang + sgn * 0.6)).toFloat(), swipePaint)
            // Doigt
            dotPaint.alpha = (90 * alpha).toInt(); canvas.drawCircle(px, py, 30 * d, dotPaint)
            dotPaint.alpha = (170 * alpha).toInt(); canvas.drawCircle(px, py, 16 * d, dotPaint)
            postInvalidateOnAnimation()
        }

        /** Flèche courbe du bord de la bulle vers la carte mise en valeur. */
        private fun drawArrow(canvas: Canvas) {
            val bl = bubble.left.toFloat(); val br = bubble.right.toFloat(); val bt = bubble.top.toFloat(); val bb = bubble.bottom.toFloat()
            val bcx = (bl + br) / 2; val bcy = (bt + bb) / 2
            val (sx, sy) = when {
                bl >= hole.right -> bl to bcy
                br <= hole.left -> br to bcy
                bt >= hole.bottom -> bcx to bt
                else -> bcx to bb
            }
            val ex = sx.coerceIn(hole.left + dp(30), hole.right - dp(30)).let { if (bl >= hole.right) hole.right + dp(8) else if (br <= hole.left) hole.left - dp(8) else it }
            val ey = if (bt >= hole.bottom) hole.bottom + dp(8) else if (bb <= hole.top) hole.top - dp(8) else sy.coerceIn(hole.top + dp(30), hole.bottom - dp(30))
            val path = Path().apply {
                moveTo(sx, sy)
                quadTo((sx + ex) / 2, (sy + ey) / 2 - dp(30), ex, ey)
            }
            canvas.drawPath(path, arrow)
            // Pointe
            val ang = Math.atan2((ey - ((sy + ey) / 2 - dp(30))).toDouble(), (ex - (sx + ex) / 2).toDouble())
            val len = dp(18).toFloat()
            for (s in listOf(-0.5, 0.5)) canvas.drawLine(ex, ey,
                ex - (len * Math.cos(ang + s)).toFloat(), ey - (len * Math.sin(ang + s)).toFloat(), arrow)
        }
    }
}
