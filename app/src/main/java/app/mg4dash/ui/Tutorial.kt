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
 * Small tutorial of home gestures: a dark veil, the relevant card highlighted, an arrow
 * towards it and a bubble with the main gestures. Next / Skip; "Skip" closes it for good.
 * Launched once after a first install, or from Settings › System.
 */
object Tutorial {

    /** Step: view to highlight (null = none, bubble in the centre), title and gesture lines. */
    /** [swipes]: swipe gestures shown as animated arrows on the card ('H' left / right, 'V' up / down). */
    /** [homePanel]: the settings' "Home screen" panel reproduced in the bubble (its two buttons). */
    /** [bottomTitle]: title when the bar is the bottom one. */
    /** [wide]: a wider bubble, for long lines (the detached windows' steps). */
    private class Step(val target: Int?, val title: Int, val lines: List<Int>, val images: List<Pair<Int, Int>> = emptyList(),
                       val swipes: String = "", val homePanel: Boolean = false, val bottomTitle: Int? = null,
                       val wide: Boolean = false) {
        /** Width of the bubble, in dp: wider for the layout miniatures and the long lines. */
        val width get() = if (images.isEmpty() && !wide) 760 else 1000
    }

    /**
     * Home whose widgets can change place (#97): the view a step points at, where it is now (the place of
     * its card), or null to look it up by its id.
     */
    interface Places { fun tutorialView(id: Int): View? }

    private val STEPS = listOf(
        Step(null, R.string.tu_welcome_t, listOf(R.string.tu_welcome_1)),
        // Replace SAIC home, and Service mode to go back to stock
        Step(null, R.string.tu_home_t, listOf(R.string.tu_home_1, R.string.tu_home_2), homePanel = true),
        // The three home layouts, in miniature
        Step(null, R.string.tu_layouts_t, listOf(R.string.tu_layouts_1, R.string.tu_layouts_2), listOf(
            R.drawable.tu_layout_full to R.string.st_style_full,
            R.drawable.tu_layout_simple to R.string.st_style_simple,
            R.drawable.tu_layout_map to R.string.st_style_map,
        )),
        // The bar in use: vertical (side) or at the bottom
        Step(R.id.dock, R.string.tu_bar_t, listOf(R.string.tu_bar_1, R.string.tu_bar_2, R.string.tu_bar_3, R.string.tu_bar_4, R.string.tu_bar_5, R.string.tu_bar_6),
            bottomTitle = R.string.tu_bar_bottom_t),
        // The widgets where they are now (#97): each step points at its card's place
        Step(R.id.climateCard, R.string.tu_climate_t, listOf(R.string.tu_climate_1, R.string.tu_climate_2, R.string.tu_climate_3)),
        Step(R.id.energyCard, R.string.tu_energy_t, listOf(R.string.tu_energy_1, R.string.tu_energy_2)),
        Step(R.id.mapCard, R.string.tu_map_t, listOf(R.string.tu_map_1, R.string.tu_map_3, R.string.tu_map_2, R.string.tu_map_4), swipes = "H"),
        Step(R.id.mediaCard, R.string.tu_media_t, listOf(R.string.tu_media_1, R.string.tu_media_2, R.string.tu_media_3), swipes = "VH"),
        // Any widget with several cards (the trip, power and phone one first)
        Step(R.id.secondaryCard, R.string.tu_widget_t, listOf(R.string.tu_widget_1, R.string.tu_widget_2), swipes = "H"),
        // Edit mode of the cards (#97): pinch the home screen
        Step(null, R.string.tu_cards_t, listOf(R.string.tu_cards_1, R.string.tu_cards_2)),
        // Web app and App card detached as a window over home (#98): how to detach it, wake it, put it back...
        Step(null, R.string.tu_float_t, listOf(R.string.tu_float_1, R.string.tu_float_2, R.string.tu_float_3, R.string.tu_float_4), wide = true),
        // ... then move and resize it (long press › Move / resize)
        Step(null, R.string.tu_move_t, listOf(R.string.tu_move_1, R.string.tu_move_2, R.string.tu_move_3, R.string.tu_move_4), wide = true),
        Step(null, R.string.tu_end_t, listOf(R.string.tu_end_1)),
    )

    /** First install: the tutorial opens once on full home. */
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

    /** Closed (Finish or Skip): no longer comes back by itself. */
    private fun done(activity: Activity) {
        Prefs(activity).raw.edit().putBoolean("tutorial_pending", false).apply()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.findViewWithTag<View>(TAG)?.let { root.removeView(it) }
    }

    private const val TAG = "mg4dash_tutorial"
    /** Home extra: open the tutorial (Settings › System). */
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
            isClickable = true   // home underneath receives nothing during the tutorial
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

        /** Settings › System › Home screen, as on screen: panel title and its two buttons. */
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
            // Fixed width bubble (text), wider for the layout miniatures
            bubble.layoutParams = (bubble.layoutParams as LayoutParams).apply { width = dp(step.width) }
            bubble.orientation = LinearLayout.VERTICAL
            bubble.setPadding(dp(28), dp(24), dp(28), dp(20))
            bubble.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Themes.color(context, R.color.bg_bottom)); setStroke(dp(2), accent)
            }
            val title = step.bottomTitle?.takeIf { !Dock.vertical(context) } ?: step.title
            bubble.addView(text(context.getString(title), 34f, Themes.color(context, R.color.text_value), bold = true))
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

        /** The vertical bar carries the dock's id too (Dock.mount): the bar in use is found either way. */
        private fun target(): View? {
            val id = STEPS[index].target ?: return null
            fun View.visible() = takeIf { it.isShown && it.width > 0 }
            (activity as? Places)?.tutorialView(id)?.visible()?.let { return it }
            return activity.findViewById<View>(id)?.visible()
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            val v = target()
            hole.setEmpty()
            if (v != null) {
                val loc = IntArray(2); v.getLocationInWindow(loc)
                val me = IntArray(2); getLocationInWindow(me)
                val x = (loc[0] - me[0]).toFloat(); val y = (loc[1] - me[1]).toFloat()
                hole.set(x - dp(6), y - dp(6), x + v.width + dp(6), y + v.height + dp(6))
            }
            placeBubble()
        }

        /**
         * The bubble never covers the highlighted view: beside it on the free side, otherwise above or
         * below where there is more room, made wider when it would be too high (the bottom bar: the
         * whole width, its lines on one line each).
         */
        private fun placeBubble() {
            val margin = dp(16); val gap = dp(64)
            val base = dp(STEPS[index].width)
            val maxH = height - 2 * margin
            fun heightAt(w: Int): Int {
                bubble.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                return bubble.measuredHeight
            }
            val fog = dp(Kit.FOG_SAFE_DP)
            /**
             * MG's fog light button, a system overlay at the top left (#38), covered the start of the title
             * of a bubble placed in that corner: lower it under the button (as long as it stays above [maxY]),
             * otherwise move it to the right of it.
             */
            fun put(x0: Int, y0: Int, maxY: Int = height - margin) {
                var x = x0; var y = y0
                val w = bubble.measuredWidth; val h = bubble.measuredHeight
                if (x < fog && y < fog) {
                    if (fog + h <= maxY) y = fog else if (fog + w <= width - margin) x = fog
                }
                bubble.layout(x, y, x + w, y + h)
            }
            if (hole.isEmpty) {
                val h = heightAt(base)
                put((width - base) / 2, ((height - h) / 2).coerceAtLeast(margin)); return
            }
            // Widths tried in a room: the usual one, wider, the whole room (never too narrow to read)
            fun widths(room: Int) = listOf(base, dp(1100), room).map { minOf(it, room) }.filter { it >= dp(520) }.distinct()
            // Beside, on the free side, at mid height of the view if possible
            val left = (hole.left - gap - margin).toInt(); val right = (width - hole.right - gap - margin).toInt()
            for (w in widths(maxOf(left, right))) {
                val h = heightAt(w)
                if (h > maxH) continue
                put(if (right >= left) (hole.right + gap).toInt() else (hole.left - gap - w).toInt(),
                    (hole.centerY() - h / 2f).toInt().coerceIn(margin, height - h - margin))
                return
            }
            // Above or below (wide view: the bottom bar, a wide place)
            val above = (hole.top - gap - margin).toInt(); val below = (height - hole.bottom - gap - margin).toInt()
            for (w in widths(width - 2 * margin)) {
                val h = heightAt(w)
                if (h > maxOf(above, below)) continue
                put((hole.centerX() - w / 2f).toInt().coerceIn(margin, width - w - margin),
                    if (above >= below) (hole.top - gap - h).toInt() else (hole.bottom + gap).toInt(),
                    if (above >= below) (hole.top - dp(24)).toInt() else height - margin)
                return
            }
            // No room anywhere: centred, the whole width
            val h = heightAt(width - 2 * margin)
            put(margin, ((height - h) / 2).coerceAtLeast(margin))
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
         * Animated swipe on the card: a finger (circle) starts from the centre and runs one way then the other,
         * followed by a translucent arrow. The [Step.swipes] directions follow each other (1.6 s each).
         */
        private fun drawSwipes(canvas: Canvas) {
            val sw = STEPS[index].swipes
            if (sw.isEmpty()) return
            val cycle = 1600L
            val t = android.os.SystemClock.uptimeMillis()
            val n = (t / cycle).toInt()
            val axis = sw[(n / 2) % sw.length]          // each direction played there and back
            val dir = if (n % 2 == 0) 1f else -1f
            val u = (t % cycle) / cycle.toFloat()
            val e = (1 - Math.cos(Math.PI * u.coerceAtMost(0.8f) / 0.8f).toFloat()) / 2f      // gentle start and arrival
            val alpha = when { u < 0.15f -> u / 0.15f; u > 0.8f -> 1f - (u - 0.8f) / 0.2f; else -> 1f }
            val cx = hole.centerX(); val cy = hole.centerY()
            val span = if (axis == 'H') hole.width() * 0.32f else hole.height() * 0.3f
            val sx = cx - dir * span * (if (axis == 'H') 1f else 0f); val sy = cy - dir * span * (if (axis == 'V') 1f else 0f)
            val ex = cx + dir * span * (if (axis == 'H') 1f else 0f); val ey = cy + dir * span * (if (axis == 'V') 1f else 0f)
            val px = sx + (ex - sx) * e; val py = sy + (ey - sy) * e
            // Arrow trail from the start to the finger
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

        /** Curved arrow from the bubble's edge to the highlighted card. */
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
