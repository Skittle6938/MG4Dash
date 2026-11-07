package app.mg4dash.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs

/**
 * Web app that can leave its card (trial), instead of the fixed full screen when "Detached window" is chosen in the
 * edit mode. With that choice its panel always lives in a layer of the activity's own window (no overlay window, no
 * permission), over home: docked, it sits exactly over a placeholder [dock] that takes its place in the card; detached,
 * it floats with its own place and size. Going from one to the other only moves the panel, never takes it off the
 * window: the page goes on, loaded (taken off and put back, MG4Dash Web was started again, ~10 s of black on the car).
 * Detached and at rest, its touches are taken here and the page sees none of them:
 * - a corner pulled at once resizes it, the opposite corner fixed and the ratio kept; the middle of an edge, its
 *   width or its height alone;
 * - a long press then a slide moves it; a long press let go without moving opens the page's menu;
 * - two fingers resize it too: slanted the ratio is kept, flat the width alone, upright the height alone;
 * - a touch wakes it (outline lit, the page takes the touches) until left alone for the time of the edit mode;
 *   a double tap puts it back in its card (no buttons nor title bar over the page).
 * The handles are drawn on a thin frame around the page: the embedded page's layer covers whatever is drawn over it.
 * During a gesture only a dashed outline follows the finger: the window takes its place and size when it is let
 * go, the embedded page's virtual display and layer being redone once (each frame would be too heavy on the head
 * unit, and on the car the layer does not follow a view moving live, #98).
 * Its place and size are kept (Prefs), for the next time it is detached, even on another trip.
 */
@SuppressLint("ViewConstructor")
class FloatWindow(
    private val activity: Activity,
    /** The panel shown in the window (the web app's card). */
    val panel: View,
    /** Its placeholder in the card, which it covers while docked. */
    private val dock: View,
    /** Where the window may go, in the window's coordinates (the cards' area, the bar left free). */
    private val bounds: () -> Rect,
    private val onAttach: () -> Unit,
    private val onMenu: () -> Unit,
    /** Moved without changing size: the embedded page's layer goes to the new place. */
    private val onMoved: () -> Unit,
    /** Awake and typing in the page: it stays awake. */
    private val keepAwake: () -> Boolean,
) {
    private val prefs = Prefs(activity)
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val content = activity.findViewById<ViewGroup>(android.R.id.content)
    private val accent = Themes.color(activity, R.color.accent_action)
    /** Width of the frame around the page when detached, where the handles are. */
    private val frame = dp(12)

    /** Over home's cards, the size of the window; lets the touches it does not take go through. */
    private val layer = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
    private val holder = FrameLayout(activity).apply {
        // Rounded as the cards (its shadow over home, the page's corners clipped)
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, corner()); o.alpha = 1f }
        }
        clipToOutline = true
    }
    /** The window's corner radius: the cards' one, plus the frame's width when detached. */
    private fun corner() = FadeOutline.RADIUS * d + if (floating) frame else 0
    private val chrome = Chrome()
    private val ghost = Ghost()

    /** The window's place and size, in the layer's (= the window's) coordinates. */
    private val rect = Rect()
    /** Detached from its card. */
    var floating = false
        private set
    var awake = false
        private set

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleep = Runnable { if (keepAwake()) setAwake(true) else setAwake(false) }
    /** Docked: follows its placeholder at each layout (another card, another place, full screen of the map…). */
    private val follow = android.view.ViewTreeObserver.OnGlobalLayoutListener { if (!floating) followDock() }

    /** Docked over its placeholder; the panel is taken off its old parent this once. */
    fun open() {
        (panel.parent as? ViewGroup)?.removeView(panel)
        holder.addView(panel, FrameLayout.LayoutParams(-1, -1))
        holder.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        layer.addView(holder, FrameLayout.LayoutParams(0, 0))
        layer.addView(ghost, FrameLayout.LayoutParams(0, 0))
        ghost.visibility = View.GONE
        // Just above home's screen: what comes later over it (edit mode, tutorial, alerts) stays above the page
        content.addView(layer, (0 until content.childCount).firstOrNull { content.getChildAt(it) !== layer }?.let { it + 1 } ?: -1,
            FrameLayout.LayoutParams(-1, -1))
        content.viewTreeObserver.addOnGlobalLayoutListener(follow)
        look()
        holder.visibility = View.INVISIBLE
        Log.i(TAG, "web app hosted over home")
    }

    /** The panel leaves the layer (back to a card as before), the layer goes. */
    fun close() {
        handler.removeCallbacksAndMessages(null)
        content.viewTreeObserver.removeOnGlobalLayoutListener(follow)
        holder.removeView(panel)
        panel.setPadding(0, panel.paddingTop, panel.paddingRight, panel.paddingBottom)
        content.removeView(layer)
        Log.i(TAG, "web app back in the cards")
    }

    /** Detached at its kept place (or a first one), or docked again over its placeholder. */
    fun setFloating(on: Boolean) {
        if (on == floating) return
        floating = on
        setAwake(false)
        look()
        if (on) {
            holder.visibility = View.VISIBLE
            place(saved() ?: initial())
            Log.i(TAG, "detached at $rect")
        } else {
            followDock()
            Log.i(TAG, "attached again")
        }
    }

    /** Docked: no frame, flat, as a card; detached: a frame with its handles, a shadow over home. */
    private fun look() {
        val f = if (floating) frame else 0
        (panel.layoutParams as FrameLayout.LayoutParams).apply { setMargins(f, f, f, f) }.also { panel.layoutParams = it }
        holder.elevation = if (floating) dp(16).toFloat() else 0f
        holder.background = if (floating) android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = FadeOutline.RADIUS * d + frame; setColor(Themes.color(activity, R.color.bg_bottom))
        } else null
        if (!floating) panel.setPadding(dock.paddingLeft, panel.paddingTop, panel.paddingRight, panel.paddingBottom)
        else panel.setPadding(0, panel.paddingTop, panel.paddingRight, panel.paddingBottom)
        chrome.visibility = if (floating) View.VISIBLE else View.GONE
        holder.invalidateOutline()
    }

    private fun rectOf(v: View): Rect {
        val a = IntArray(2).also { v.getLocationInWindow(it) }
        val c = IntArray(2).also { layer.getLocationInWindow(it) }
        return Rect(a[0] - c[0], a[1] - c[1], a[0] - c[0] + v.width, a[1] - c[1] + v.height)
    }

    /** Over its placeholder, shown when it is (hidden behind another card, or with no place, it is hidden too). */
    private fun followDock() {
        val shown = dock.isAttachedToWindow && dock.isShown && dock.width > 0 && dock.height > 0
        if (shown) {
            val r = rectOf(dock)
            if (r != rect) place(r)
            // Room for MG's fog light button in the top left place (#38), as the card had
            if (panel.paddingLeft != dock.paddingLeft) panel.setPadding(dock.paddingLeft, panel.paddingTop, panel.paddingRight, panel.paddingBottom)
        }
        val v = if (shown) View.VISIBLE else View.INVISIBLE
        if (holder.visibility != v) holder.visibility = v
    }

    private fun saved(): Rect? = prefs.webFloatRect?.let { Rect(it[0], it[1], it[0] + it[2], it[1] + it[3]) }?.let { fit(it) }

    /** First time: 60 % of the cards' width, 16:9 if it fits, in the middle. */
    private fun initial(): Rect {
        val b = bounds()
        var w = (b.width() * 0.6f).toInt(); var h = w * 9 / 16
        if (h > b.height() * 0.85f) { h = (b.height() * 0.85f).toInt(); w = h * 16 / 9 }
        val l = b.centerX() - w / 2; val t = b.centerY() - h / 2
        return fit(Rect(l, t, l + w, t + h))
    }

    private fun minW() = maxOf(dp(240), bounds().width() / 6)
    private fun minH() = maxOf(dp(150), bounds().height() / 4)

    /** Within the cards' area, at least its smallest size (a saved place after the bar or the layout changed). */
    private fun fit(r: Rect): Rect {
        val b = bounds()
        val w = r.width().coerceIn(minOf(minW(), b.width()), b.width()); val h = r.height().coerceIn(minOf(minH(), b.height()), b.height())
        val l = r.left.coerceIn(b.left, b.right - w); val t = r.top.coerceIn(b.top, b.bottom - h)
        return Rect(l, t, l + w, t + h)
    }

    /** Close to an edge of the cards' area: on it. */
    private fun snap(r: Rect): Rect {
        val b = bounds(); val s = dp(24)
        val dx = when { Math.abs(r.left - b.left) < s -> b.left - r.left; Math.abs(b.right - r.right) < s -> b.right - r.right; else -> 0 }
        val dy = when { Math.abs(r.top - b.top) < s -> b.top - r.top; Math.abs(b.bottom - r.bottom) < s -> b.bottom - r.bottom; else -> 0 }
        return Rect(r).apply { offset(dx, dy) }
    }

    private fun place(r: Rect) {
        val resized = r.width() != rect.width() || r.height() != rect.height()
        val moved = r.left != rect.left || r.top != rect.top
        rect.set(r)
        (holder.layoutParams as FrameLayout.LayoutParams).apply { width = r.width(); height = r.height(); leftMargin = r.left; topMargin = r.top }
            .also { holder.layoutParams = it }
        if (floating) prefs.webFloatRect = intArrayOf(r.left, r.top, r.width(), r.height())
        // Resized, the embedded app follows by itself (EmbeddedApp: new size, new surface); moved only, its layer is redone here
        if (moved && !resized) holder.postDelayed({ onMoved() }, 300)
    }

    private fun setAwake(on: Boolean) {
        handler.removeCallbacks(sleep)
        if (awake != on) {
            awake = on
            holder.foreground = if (on) FadeOutline.Awake(d, accent, dp(3).toFloat()) else null
            chrome.invalidate()
            Log.i(TAG, if (on) "awake" else "at rest")
        }
        if (on) handler.postDelayed(sleep, prefs.webWakeSeconds * 1000L)
    }

    fun rest() = setAwake(false)

    /** Docked: its card's outline, lit while the page is awake (the card's own is under the page, out of sight). */
    fun dockOutline(on: Boolean) {
        if (floating) return
        holder.foreground = if (on) FadeOutline.Awake(d, accent, dp(3).toFloat()) else null
    }

    // ── Touches ──────────────────────────────────────────────────────────────

    private enum class Mode { NONE, PENDING, LIFTED, DRAG, RESIZE, PINCH, IGNORE }
    private enum class Pinch { WIDTH, HEIGHT, BOTH }

    private var mode = Mode.NONE
    /** Resize handle: which edges move (−1 the left / top one, 1 the right / bottom one, 0 neither). */
    private var hx = 0; private var hy = 0
    private var downX = 0f; private var downY = 0f
    private val start = Rect()
    private var pinch = Pinch.BOTH
    private var span0X = 1f; private var span0Y = 1f; private var span0 = 1f
    private var tapAt = 0L
    private val wakeTapped = Runnable { setAwake(true) }
    private val lifted = Runnable {
        if (mode != Mode.PENDING) return@Runnable
        mode = Mode.LIFTED
        holder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showGhost(rect)
    }

    private fun local(ev: MotionEvent, i: Int = 0): FloatArray {
        val at = IntArray(2).also { layer.getLocationOnScreen(it) }
        val dx = ev.rawX - ev.getX(); val dy = ev.rawY - ev.getY()
        return floatArrayOf(ev.getX(i) + dx - at[0], ev.getY(i) + dy - at[1])
    }

    /** The gesture started on the window awake: it goes straight to the page, none of home's gestures (pinch to edit…) take it. */
    var through = false
        private set

    /** True when the touch is taken here (the page and the rest of home see none of it). */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (x, y) = local(ev).let { it[0] to it[1] }
                mode = Mode.NONE; through = false
                if (!floating) return false
                if (!rect.contains(x.toInt(), y.toInt())) return false
                if (awake) { setAwake(true); through = true; return false }
                downX = x; downY = y; start.set(rect)
                val c = dp(64); val e = dp(44)
                val left = x - rect.left < c; val right = rect.right - x < c
                val top = y - rect.top < c; val bottom = rect.bottom - y < c
                when {
                    (left || right) && (top || bottom) -> { mode = Mode.RESIZE; hx = if (left) -1 else 1; hy = if (top) -1 else 1; showGhost(rect) }
                    x - rect.left < e || rect.right - x < e -> { mode = Mode.RESIZE; hx = if (x - rect.left < e) -1 else 1; hy = 0; showGhost(rect) }
                    y - rect.top < e || rect.bottom - y < e -> { mode = Mode.RESIZE; hx = 0; hy = if (y - rect.top < e) -1 else 1; showGhost(rect) }
                    else -> { mode = Mode.PENDING; handler.postDelayed(lifted, ViewConfiguration.getLongPressTimeout().toLong()) }
                }
                chrome.invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.NONE) return false
                if (ev.pointerCount == 2 && mode in setOf(Mode.PENDING, Mode.LIFTED, Mode.RESIZE)) {
                    handler.removeCallbacks(lifted)
                    val a = local(ev, 0); val b = local(ev, 1)
                    span0X = Math.abs(a[0] - b[0]).coerceAtLeast(1f); span0Y = Math.abs(a[1] - b[1]).coerceAtLeast(1f)
                    span0 = Math.hypot(span0X.toDouble(), span0Y.toDouble()).toFloat()
                    val angle = Math.toDegrees(Math.atan2(span0Y.toDouble(), span0X.toDouble()))
                    pinch = when { angle < 25 -> Pinch.WIDTH; angle > 65 -> Pinch.HEIGHT; else -> Pinch.BOTH }
                    start.set(rect); mode = Mode.PINCH; showGhost(rect)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) return false
                val (x, y) = local(ev).let { it[0] to it[1] }
                val dx = x - downX; val dy = y - downY
                val slop = dp(16)
                when (mode) {
                    Mode.PENDING -> if (Math.abs(dx) > slop || Math.abs(dy) > slop) { handler.removeCallbacks(lifted); mode = Mode.IGNORE; chrome.invalidate() }
                    Mode.LIFTED -> if (Math.abs(dx) > slop || Math.abs(dy) > slop) mode = Mode.DRAG
                    else -> {}
                }
                when (mode) {
                    Mode.DRAG -> showGhost(snap(fit(Rect(start).apply { offset(dx.toInt(), dy.toInt()) })))
                    Mode.RESIZE -> showGhost(resized(dx, dy))
                    Mode.PINCH -> if (ev.pointerCount >= 2) {
                        val a = local(ev, 0); val b = local(ev, 1)
                        val sx = Math.abs(a[0] - b[0]); val sy = Math.abs(a[1] - b[1])
                        val (fw, fh) = when (pinch) {
                            Pinch.WIDTH -> sx / span0X to 1f
                            Pinch.HEIGHT -> 1f to sy / span0Y
                            Pinch.BOTH -> (Math.hypot(sx.toDouble(), sy.toDouble()).toFloat() / span0).let { it to it }
                        }
                        showGhost(around(fw, fh))
                    }
                    else -> {}
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(lifted)
                // A handle only touched, not pulled: a touch like anywhere else on the window
                val still = local(ev).let { Math.abs(it[0] - downX) <= dp(16) && Math.abs(it[1] - downY) <= dp(16) }
                val m = if (mode == Mode.RESIZE && still) { hideGhost(); Mode.PENDING } else mode
                mode = Mode.NONE
                chrome.invalidate()
                when (m) {
                    Mode.NONE -> return false
                    Mode.PENDING -> {
                        // A double tap puts it back in its card; a single touch wakes it
                        if (ev.eventTime - tapAt < ViewConfiguration.getDoubleTapTimeout()) {
                            handler.removeCallbacks(wakeTapped); tapAt = 0L; onAttach()
                        } else {
                            tapAt = ev.eventTime
                            handler.postDelayed(wakeTapped, ViewConfiguration.getDoubleTapTimeout().toLong())
                        }
                    }
                    Mode.LIFTED -> { hideGhost(); onMenu() }
                    Mode.DRAG, Mode.RESIZE, Mode.PINCH -> { val r = Rect(ghostRect); hideGhost(); place(r) }
                    else -> hideGhost()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(lifted)
                val m = mode; mode = Mode.NONE; hideGhost(); chrome.invalidate()
                return m != Mode.NONE
            }
            else -> return mode != Mode.NONE
        }
    }

    /** A corner (the opposite one fixed, ratio kept) or an edge (its width or its height alone) pulled. */
    private fun resized(dx: Float, dy: Float): Rect {
        val b = bounds()
        val w0 = start.width().toFloat(); val h0 = start.height().toFloat()
        var w = w0; var h = h0
        if (hx != 0 && hy != 0) {
            // Along the diagonal: the finger's move projected on it
            val s = ((w0 + hx * dx) * w0 + (h0 + hy * dy) * h0) / (w0 * w0 + h0 * h0)
            val maxW = (if (hx < 0) start.right - b.left else b.right - start.left).toFloat()
            val maxH = (if (hy < 0) start.bottom - b.top else b.bottom - start.top).toFloat()
            val k = clamp(s, maxOf(minW() / w0, minH() / h0), minOf(maxW / w0, maxH / h0))
            w = w0 * k; h = h0 * k
        } else if (hx != 0) {
            val maxW = (if (hx < 0) start.right - b.left else b.right - start.left).toFloat()
            w = clamp(w0 + hx * dx, minW().toFloat(), maxW)
        } else {
            val maxH = (if (hy < 0) start.bottom - b.top else b.bottom - start.top).toFloat()
            h = clamp(h0 + hy * dy, minH().toFloat(), maxH)
        }
        val l = if (hx < 0) start.right - w.toInt() else start.left
        val t = if (hy < 0) start.bottom - h.toInt() else start.top
        return Rect(l, t, l + w.toInt(), t + h.toInt())
    }

    /** Two fingers: resized around the window's middle, then kept within the cards' area. */
    private fun around(fw: Float, fh: Float): Rect {
        val b = bounds()
        var w = start.width() * fw; var h = start.height() * fh
        if (fw == fh) {
            val k = clamp(fw, maxOf(minW().toFloat() / start.width(), minH().toFloat() / start.height()),
                minOf(b.width().toFloat() / start.width(), b.height().toFloat() / start.height()))
            w = start.width() * k; h = start.height() * k
        }
        val l = (start.centerX() - w / 2).toInt(); val t = (start.centerY() - h / 2).toInt()
        return fit(Rect(l, t, l + w.toInt(), t + h.toInt()))
    }

    /** [v] within [lo]..[hi], [hi] if the window is already past it. */
    private fun clamp(v: Float, lo: Float, hi: Float) = if (lo > hi) hi else v.coerceIn(lo, hi)

    private val ghostRect = Rect()
    private fun showGhost(r: Rect) {
        ghostRect.set(r)
        (ghost.layoutParams as FrameLayout.LayoutParams).apply { width = r.width(); height = r.height(); leftMargin = r.left; topMargin = r.top }
            .also { ghost.layoutParams = it }
        ghost.visibility = View.VISIBLE
        ghost.bringToFront()
        chrome.invalidate()
    }
    private fun hideGhost() { ghost.visibility = View.GONE }

    // ── Drawing ──────────────────────────────────────────────────────────────

    /** Corner and edge handles (white, as the rest of the app), at rest only. */
    private inner class Chrome : View(activity) {
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND; color = 0xFFFFFFFF.toInt() }
        private val path = android.graphics.Path()
        private val arc = RectF()

        override fun onDraw(c: Canvas) {
            if (awake) return
            handle.alpha = if (mode == Mode.NONE) 150 else 255
            val w = width.toFloat(); val h = height.toFloat(); val i = frame / 2f; val l = 30 * d; val e = 32 * d
            // Corners, rounded as the window's (its radius, taken in by half the frame)
            val r = (corner() - i).coerceAtLeast(1f); val t = (l - r).coerceAtLeast(6 * d)
            path.reset()
            for ((x, y, start) in listOf(Triple(i, i, 180f), Triple(w - i - 2 * r, i, 270f), Triple(w - i - 2 * r, h - i - 2 * r, 0f), Triple(i, h - i - 2 * r, 90f))) {
                arc.set(x, y, x + 2 * r, y + 2 * r)
                path.addArc(arc, start, 90f)
            }
            c.drawPath(path, handle)
            // Short straight tails after each arc, along the edges
            c.drawLine(i, i + r, i, i + r + t, handle); c.drawLine(i + r, i, i + r + t, i, handle)
            c.drawLine(w - i - r - t, i, w - i - r, i, handle); c.drawLine(w - i, i + r, w - i, i + r + t, handle)
            c.drawLine(w - i, h - i - r - t, w - i, h - i - r, handle); c.drawLine(w - i - r - t, h - i, w - i - r, h - i, handle)
            c.drawLine(i + r, h - i, i + r + t, h - i, handle); c.drawLine(i, h - i - r - t, i, h - i - r, handle)
            // Edges
            c.drawLine(i, h / 2 - e, i, h / 2 + e, handle); c.drawLine(w - i, h / 2 - e, w - i, h / 2 + e, handle)
            c.drawLine(w / 2 - e, i, w / 2 + e, i, handle); c.drawLine(w / 2 - e, h - i, w / 2 + e, h - i, handle)
        }
    }

    /** The outline that follows the finger during a gesture. */
    private inner class Ghost : View(activity) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3 * d; color = 0xFFFFFFFF.toInt(); pathEffect = DashPathEffect(floatArrayOf(14 * d, 9 * d), 0f)
        }
        private val fill = Paint().apply { color = 0x1FFFFFFF }
        private val rf = RectF()
        init { elevation = dp(20).toFloat() }
        override fun onDraw(c: Canvas) {
            rf.set(2 * d, 2 * d, width - 2 * d, height - 2 * d)
            val rad = FadeOutline.RADIUS * d
            c.drawRoundRect(rf, rad, rad, fill); c.drawRoundRect(rf, rad, rad, stroke)
        }
    }

    companion object { private const val TAG = "FloatWeb" }
}
