package app.mg4dash.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import app.mg4dash.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Animation « pleine puissance » du widget Puissance en direct : au-delà de 80 % de la puissance
 * maximale du modèle, une fusée, un éclair ou des flammes, puis le pic atteint (kW et ch) affiché
 * jusqu'à 10 s après le dernier passage au-dessus du seuil. Dessinée dans l'overlay de la carte du
 * widget : par-dessus son contenu, sans toucher à la mise en page.
 */
class PowerFx(private val context: Context) : Drawable() {
    companion object {
        const val NONE = 0; const val ROCKET = 1; const val LIGHTNING = 2; const val FLAMES = 3
        /** Choix des réglages seulement : une des trois animations, tirée à chaque déclenchement. */
        const val RANDOM = 4
        const val HOLD_MS = 10_000L
        private const val FADE_MS = 700L
        /** Chevaux (ch DIN) d'une puissance en kW. */
        fun hp(kw: Float) = Math.round(kw * 1.35962f)
    }

    var style = ROCKET
        set(v) { if (v != field) { field = v; if (active) stop() } }
    /** Appelé quand le badge du pic apparaît (true) ou disparaît (false) : l'hôte masque son texte dessous. */
    var onBadge: ((Boolean) -> Unit)? = null
    /** Arrondi des bords : celui des cartes, 0 quand l'animation couvre tout l'écran. */
    var corner = 20 * context.resources.displayMetrics.density

    private val d = context.resources.displayMetrics.density
    private val sp = context.resources.displayMetrics.scaledDensity
    private fun col(res: Int) = Themes.color(context, res)
    private val cAction = col(R.color.accent_action)
    private val cCold = col(R.color.accent_cold)
    private val cHeat = col(R.color.accent_heat)
    private val cDanger = col(R.color.accent_danger)
    private val cSub = col(R.color.text_sub)
    private val cPanel = col(R.color.button_idle)
    private val cText = col(R.color.text_value)

    // Géométrie, en coordonnées de la carte (place())
    private val badgeArea = RectF()
    private var barY = 0f
    private var barX0 = 0f; private var barZero = 0f; private var barX1 = 0f; private var barMax = 140f
    private fun barX(kw: Float) = barZero + (barX1 - barZero) * (kw / barMax).coerceIn(0f, 1f)

    private var active = false
    private var record = 0f            // pic de la séquence
    private var shown = 0f             // compteur affiché, monte vers le pic
    private var eventAt = 0L           // dernier départ / nouveau record
    private var lastAbove = 0L
    private var heat = 0f; private var heatTarget = 0f   // intensité des flammes
    private var lastFrame = 0L
    private val rnd = java.util.Random()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.LEFT
    }
    private val path = Path()
    private val clip = Path()
    private val r = RectF()

    private var host: View? = null; private var hBar: PowerBar? = null; private var hUnder: View? = null

    /**
     * Rattache l'animation : [host] est la vue dont l'overlay la dessine (la carte du widget, ou toute la
     * fenêtre pour l'éclair et les flammes), [bar] la barre de puissance, [under] la zone du badge.
     * La géométrie est relue à chaque image : le widget peut venir d'être construit.
     */
    fun attach(host: View, bar: PowerBar, under: View) { this.host = host; hBar = bar; hUnder = under; invalidateSelf() }

    private fun relayout() { val h = host; val b = hBar; val u = hUnder; if (h != null && b != null && u != null) place(h, b, u) }

    private fun place(card: View, bar: PowerBar, under: View) {
        setBounds(0, 0, card.width, card.height)
        val c = IntArray(2); card.getLocationInWindow(c)
        val p = IntArray(2); bar.getLocationInWindow(p)
        val u = IntArray(2); under.getLocationInWindow(u)
        val bx = (p[0] - c[0]).toFloat(); val by = (p[1] - c[1]).toFloat()
        barX0 = bx + bar.xAt(-bar.maxRegenKw); barZero = bx + bar.xAt(0f); barX1 = bx + bar.xAt(bar.maxDrawKw)
        barMax = bar.maxDrawKw; barY = by + bar.barCenterY()
        badgeArea.set((u[0] - c[0]).toFloat(), (u[1] - c[1]).toFloat(), (u[0] - c[0] + under.width).toFloat(), (u[1] - c[1] + under.height).toFloat())
    }

    /** Nouvelle mesure : déclenche, relance (nouveau record) ou laisse retomber l'animation. */
    fun update(kw: Float, threshold: Float, peakKw: Float) {
        val now = SystemClock.uptimeMillis()
        if (style == NONE) { if (active) stop(); return }
        if (kw >= threshold) {
            heatTarget = 0.45f + 0.55f * ((kw - threshold) / (peakKw - threshold).coerceAtLeast(1f)).coerceIn(0f, 1f)
            if (!active) {
                active = true; record = kw; shown = threshold; eventAt = now; lastAbove = now; lastFrame = 0L
                clearParticles(); strike(now)
                onBadge?.invoke(true)
            } else if (kw > record + 0.5f) {
                record = kw; eventAt = now; strike(now)
            }
            lastAbove = now
        } else {
            heatTarget = 0.15f
            // Fin aussi sans image dessinée (écran éteint, appli en arrière-plan)
            if (active && now - lastAbove > HOLD_MS + FADE_MS) { stop(); return }
        }
        invalidateSelf()
    }

    fun stop() {
        if (active) { active = false; onBadge?.invoke(false) }
        clearParticles(); invalidateSelf()
    }

    val isActive get() = active

    override fun draw(canvas: Canvas) {
        if (!active) return
        relayout()
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceIn(0f, 0.05f)
        lastFrame = now
        val since = now - lastAbove
        if (since > HOLD_MS + FADE_MS) { stop(); return }
        val alpha = if (since > HOLD_MS) 1f - (since - HOLD_MS).toFloat() / FADE_MS else 1f
        shown += (record - shown) * (1f - exp(-dt * 5f))
        if (record - shown < 0.05f) shown = record
        heat += (heatTarget - heat) * (1f - exp(-dt * (if (heatTarget > heat) 6f else 0.6f)))

        val b = bounds
        canvas.save()
        clip.reset(); r.set(b); clip.addRoundRect(r, corner, corner, Path.Direction.CW)
        canvas.clipPath(clip)
        val layer = if (alpha < 1f) canvas.saveLayerAlpha(r, (alpha * 255).toInt()) else -1
        when (style) {
            ROCKET -> drawRocketScene(canvas, now, dt)
            LIGHTNING -> drawLightningScene(canvas, now, dt)
            FLAMES -> drawFlameScene(canvas, now, dt)
        }
        if (layer >= 0) canvas.restoreToCount(layer)
        canvas.restore()
        invalidateSelf()   // image suivante
    }

    // ── Badge du pic ────────────────────────────────────────────────────────

    private fun peakText() = context.getString(R.string.fx_peak,
        String.format(Locale.getDefault(), "%.0f", shown), hp(shown).toString())

    /** Pastille sombre du pic ; [revealX] : texte visible jusqu'à cette abscisse (la fusée l'écrit). */
    private fun drawBadge(c: Canvas, x: Float, cy: Float, w: Float, h: Float, accent: Int, text: String, textX: Float, revealX: Float = Float.MAX_VALUE) {
        r.set(x, cy - h / 2f, x + w, cy + h / 2f)
        paint.shader = null; paint.style = Paint.Style.FILL
        paint.color = cPanel; paint.alpha = 225; c.drawRoundRect(r, h / 2f, h / 2f, paint)
        stroke.color = accent; stroke.alpha = 200; stroke.strokeWidth = 1.5f * d; c.drawRoundRect(r, h / 2f, h / 2f, stroke)
        c.save()
        c.clipRect(x, cy - h, minOf(revealX, x + w), cy + h)
        textPaint.color = accent; textPaint.alpha = 255
        c.drawText(text, textX, cy - (textPaint.descent() + textPaint.ascent()) / 2f, textPaint)
        c.restore()
    }

    private fun sizeText() { textPaint.textSize = (badgeArea.height() * 0.5f).coerceIn(18 * sp, 26 * sp) }

    private fun badgeSize(text: String, icon: Float): Pair<Float, Float> {
        val h = textPaint.textSize * 1.75f
        return Pair(textPaint.measureText(text) + icon + h * 0.9f, h)
    }

    // ── Fusée : elle traverse la carte en écrivant le pic, puis se pose au bout en flottant ──

    private class Puff(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, var age: Float, val life: Float, val spark: Boolean)
    private val puffs = ArrayList<Puff>()

    private fun drawRocketScene(c: Canvas, now: Long, dt: Float) {
        sizeText()
        val text = peakText()
        val (bw, bh) = badgeSize(text, 0f)
        val L = bh * 1.6f
        // La fusée file le long de la barre depuis le bord gauche de l'écran et pose son nez sur le curseur
        // du pic ; un nouveau record la fait avancer jusqu'au nouveau pic.
        val target = barX(record) - L
        val t = ((now - eventAt) / 650f).coerceIn(0f, 1f)
        if (fly) {
            val ease = 1f - (1f - t) * (1f - t) * (1f - t)
            val startX = bounds.left - L * 1.3f
            rocketX = startX + (target - startX) * ease
            if (t >= 1f) { fly = false; badgeAt = now }
        } else rocketX += (target - rocketX) * (1f - exp(-dt * 7f))
        val moving = fly || target - rocketX > 1.5f * d
        val x = rocketX
        val y = barY + sin(now / 320.0).toFloat() * (if (moving) 0.5f else 1.5f) * d
        val boost = if (moving) 1f else 0.25f + 0.1f * sin(now / 90.0).toFloat()
        // Fumée et étincelles derrière la tuyère
        val rate = if (moving) 110f else 12f
        var n = rate * dt
        while (n > 0f) {
            if (n >= 1f || rnd.nextFloat() < n) {
                val spark = rnd.nextFloat() < 0.35f
                puffs.add(Puff(x - L * 0.05f, y + (rnd.nextFloat() - 0.5f) * L * 0.18f,
                    -(30 + rnd.nextFloat() * 70) * d * (if (spark) 1.6f else 1f), (rnd.nextFloat() - 0.6f) * 22 * d,
                    (if (spark) 1.5f else 4f + rnd.nextFloat() * 4f) * d, 0f, if (spark) 0.35f else 0.8f + rnd.nextFloat() * 0.5f, spark))
            }
            n -= 1f
        }
        stepPuffs(dt)
        for (p in puffs) {
            val k = p.age / p.life
            paint.shader = null
            if (p.spark) { paint.color = if (k < 0.5f) 0xFFFFE082.toInt() else cHeat; paint.alpha = ((1 - k) * 255).toInt() }
            else { paint.color = cSub; paint.alpha = ((1 - k) * 90).toInt() }
            c.drawCircle(p.x, p.y, p.r * (if (p.spark) 1f else 1f + k * 1.8f), paint)
        }
        // Badge du pic sous les chiffres : apparaît quand la fusée arrive
        val gx = badgeArea.centerX() - bw / 2f
        val appear = if (fly) 0f else ((now - badgeAt) / 250f).coerceIn(0f, 1f)
        if (appear > 0f) {
            val l = c.saveLayerAlpha(gx - 4 * d, badgeArea.centerY() - bh, gx + bw + 4 * d, badgeArea.centerY() + bh, (appear * 255).toInt())
            drawBadge(c, gx, badgeArea.centerY(), bw, bh, cAction, text, gx + bh * 0.45f)
            c.restoreToCount(l)
        }
        drawRocket(c, x, y, L, boost, now)
    }
    private var rocketX = 0f
    private var badgeAt = 0L
    private var fly = true

    private fun stepPuffs(dt: Float) {
        val it = puffs.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy -= 8 * d * dt
            if (p.age >= p.life) it.remove()
        }
        while (puffs.size > 160) puffs.removeAt(0)
    }

    /** Fusée couchée vers la droite : [x] = tuyère, [y] = axe. */
    private fun drawRocket(c: Canvas, x: Float, y: Float, L: Float, boost: Float, now: Long) {
        val h = L * 0.21f
        // Flammes : trois couches qui vacillent
        val flick = 0.85f + rnd.nextFloat() * 0.3f
        val fl = L * (0.35f + 0.55f * boost) * flick
        val layers = intArrayOf(cDanger, cHeat, 0xFFFFF3B0.toInt())
        for (i in 0..2) {
            val s = 1f - i * 0.28f
            path.reset()
            path.moveTo(x + 1, y - h * 0.8f * s)
            path.quadTo(x - fl * 0.45f * s, y - h * 1.05f * s, x - fl * s, y)
            path.quadTo(x - fl * 0.45f * s, y + h * 1.05f * s, x + 1, y + h * 0.8f * s)
            path.close()
            paint.shader = null; paint.color = layers[i]; paint.alpha = 230
            c.drawPath(path, paint)
        }
        // Ailerons (couleur du thème)
        paint.color = cAction; paint.alpha = 255
        path.reset()
        path.moveTo(x + L * 0.30f, y - h); path.lineTo(x - L * 0.04f, y - h * 2.0f); path.lineTo(x + L * 0.04f, y - h * 0.6f); path.close()
        path.moveTo(x + L * 0.30f, y + h); path.lineTo(x - L * 0.04f, y + h * 2.0f); path.lineTo(x + L * 0.04f, y + h * 0.6f); path.close()
        c.drawPath(path, paint)
        // Corps : blanc avec un reflet, bord gris
        path.reset()
        path.moveTo(x, y - h); path.lineTo(x + L * 0.62f, y - h)
        path.quadTo(x + L * 0.92f, y - h * 0.85f, x + L, y)
        path.quadTo(x + L * 0.92f, y + h * 0.85f, x + L * 0.62f, y + h)
        path.lineTo(x, y + h); path.close()
        paint.shader = LinearGradient(0f, y - h, 0f, y + h, intArrayOf(0xFFFFFFFF.toInt(), 0xFFC9D1DE.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, paint); paint.shader = null
        stroke.color = 0xFF5A6475.toInt(); stroke.alpha = 255; stroke.strokeWidth = 1f * d; c.drawPath(path, stroke)
        // Nez rouge
        c.save(); c.clipPath(path); paint.color = cDanger; c.drawRect(x + L * 0.72f, y - h * 1.2f, x + L * 1.1f, y + h * 1.2f, paint); c.restore()
        // Hublot
        paint.color = cCold; c.drawCircle(x + L * 0.45f, y, h * 0.48f, paint)
        paint.color = 0xAAFFFFFF.toInt(); c.drawCircle(x + L * 0.43f, y - h * 0.15f, h * 0.16f, paint)
        stroke.color = 0xFF5A6475.toInt(); stroke.strokeWidth = 1.5f * d; c.drawCircle(x + L * 0.45f, y, h * 0.48f, stroke)
        // Aileron central (vu de profil)
        paint.color = cAction; c.drawRect(x - L * 0.02f, y - h * 0.18f, x + L * 0.22f, y + h * 0.18f, paint)
    }

    // ── Éclair : il traverse toute la carte en passant par le pic sur la barre, flash, puis grésille ──

    private val bolt = ArrayList<FloatArray>()     // segments de l'éclair principal et de ses branches
    private var boltSeed = 0L
    private var nextAftershock = 0L
    private class Crackle(val pts: FloatArray, val until: Long)
    private val crackles = ArrayList<Crackle>()

    private fun jagged(x0: Float, y0: Float, x1: Float, y1: Float, disp: Float, depth: Int, out: ArrayList<Float>) {
        if (depth == 0) { out.add(x1); out.add(y1); return }
        val mx = (x0 + x1) / 2f + (rnd.nextFloat() - 0.5f) * disp * 0.4f
        val my = (y0 + y1) / 2f + (rnd.nextFloat() - 0.5f) * disp
        jagged(x0, y0, mx, my, disp / 2f, depth - 1, out)
        jagged(mx, my, x1, y1, disp / 2f, depth - 1, out)
    }

    private fun buildBolt() {
        bolt.clear()
        val b = bounds
        val px = barX(record); val py = barY
        val main = ArrayList<Float>()
        val sx = b.left + b.width() * (0.02f + rnd.nextFloat() * 0.12f)
        main.add(sx); main.add(b.top.toFloat())
        jagged(sx, b.top.toFloat(), px, py, b.height() * 0.7f, 5, main)
        jagged(px, py, b.right - b.width() * rnd.nextFloat() * 0.1f, b.bottom.toFloat(), b.height() * 0.7f, 5, main)
        bolt.add(main.toFloatArray())
        // Branches vers le bas
        repeat(3) {
            val i = (2 + rnd.nextInt(main.size / 2 - 4)) * 2
            val bx = main[i]; val by = main[i + 1]
            val br = ArrayList<Float>(); br.add(bx); br.add(by)
            jagged(bx, by, bx + (rnd.nextFloat() - 0.3f) * b.width() * 0.2f, by + b.height() * (0.15f + rnd.nextFloat() * 0.25f), b.height() * 0.15f, 3, br)
            bolt.add(br.toFloatArray())
        }
    }

    private fun drawPoly(c: Canvas, pts: FloatArray, color: Int, alpha: Int, width: Float) {
        path.reset(); path.moveTo(pts[0], pts[1])
        var i = 2; while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        stroke.color = color; stroke.alpha = alpha; stroke.strokeWidth = width
        c.drawPath(path, stroke)
    }

    private fun drawBoltSet(c: Canvas, a: Float, scale: Float) {
        bolt.forEachIndexed { k, pts ->
            val w = if (k == 0) 1f else 0.55f
            drawPoly(c, pts, cCold, (40 * a).toInt(), 26 * d * w * scale)
            drawPoly(c, pts, cCold, (110 * a).toInt(), 10 * d * w * scale)
            drawPoly(c, pts, 0xFFFFFFFF.toInt(), (255 * a).toInt(), 3.2f * d * w * scale)
        }
    }

    private fun drawLightningScene(c: Canvas, now: Long, dt: Float) {
        val b = bounds
        val t = now - eventAt
        // Coup de foudre : 3 éclats en 450 ms, nouvelle forme à chaque éclat, flash de toute la carte
        if (t < 450) {
            val flick = (t / 150).toLong()
            if (flick != boltSeed || bolt.isEmpty()) { boltSeed = flick; buildBolt() }
            val a = 1f - (t % 150) / 220f
            paint.shader = null; paint.color = 0xFFFFFFFF.toInt(); paint.alpha = ((1f - t / 450f) * 120).toInt()
            c.drawRect(b, paint)
            paint.color = cCold; paint.alpha = ((1f - t / 450f) * 60).toInt(); c.drawRect(b, paint)
            drawBoltSet(c, a, 1f)
            nextAftershock = now + 2500
        } else if (now >= nextAftershock) {
            // Réplique : un petit éclair du pic de la barre jusqu'au badge
            if (now - nextAftershock < 220) {
                if (crackles.isEmpty() || crackles.last().until < nextAftershock) {
                    val pts = ArrayList<Float>(); val px = barX(record)
                    pts.add(px); pts.add(barY)
                    jagged(px, barY, badgeArea.centerX() + (rnd.nextFloat() - 0.5f) * badgeArea.width() * 0.3f, badgeArea.centerY(), badgeArea.height(), 4, pts)
                    crackles.add(Crackle(pts.toFloatArray(), nextAftershock + 220))
                }
            } else nextAftershock = now + 2500 + rnd.nextInt(1500)
        }
        sizeText()
        val text = peakText()
        val icon = textPaint.textSize * 0.9f
        val (bw, bh) = badgeSize(text, icon)
        val bx = badgeArea.centerX() - bw / 2f; val cy = badgeArea.centerY()
        // Grésillement autour du badge
        if (t > 300 && rnd.nextFloat() < dt * 9f && crackles.count { it.until > now } < 3) {
            val side = rnd.nextInt(4)
            val px = bx + rnd.nextFloat() * bw; val py = cy + (if (side % 2 == 0) -bh / 2f else bh / 2f)
            val pts = ArrayList<Float>(); pts.add(px); pts.add(py)
            jagged(px, py, px + (rnd.nextFloat() - 0.5f) * 30 * d, py + (if (side % 2 == 0) -1 else 1) * (6 + rnd.nextFloat() * 12) * d, 10 * d, 3, pts)
            crackles.add(Crackle(pts.toFloatArray(), now + 90 + rnd.nextInt(80)))
        }
        crackles.removeAll { it.until < now }
        drawBadge(c, bx, cy, bw, bh, cCold, text, bx + bh * 0.45f + icon)
        for (k in crackles) { drawPoly(c, k.pts, cCold, 110, 5 * d); drawPoly(c, k.pts, 0xFFFFFFFF.toInt(), 255, 1.4f * d) }
        // Petit éclair devant le texte
        val ix = bx + bh * 0.45f; val s = icon
        path.reset()
        path.moveTo(ix + s * 0.55f, cy - s * 0.6f); path.lineTo(ix + s * 0.1f, cy + s * 0.08f); path.lineTo(ix + s * 0.42f, cy + s * 0.08f)
        path.lineTo(ix + s * 0.25f, cy + s * 0.6f); path.lineTo(ix + s * 0.78f, cy - s * 0.12f); path.lineTo(ix + s * 0.45f, cy - s * 0.12f); path.close()
        paint.shader = null; paint.color = cCold; paint.alpha = 255; c.drawPath(path, paint)
    }

    // ── Flammes : feu sur toute la largeur du widget, plus haut quand on pousse, braises et bord rougeoyant ──

    private class Flame(var x: Float, var y: Float, var vy: Float, val r0: Float, var age: Float, val life: Float, val phase: Float, val ember: Boolean)
    private val flames = ArrayList<Flame>()
    private val sprites by lazy {
        intArrayOf(0xFFFFF3B0.toInt(), cHeat, cDanger, 0xFF5A1A10.toInt()).map { color ->
            val s = 64
            Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888).also { bm ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(color, color and 0x00FFFFFF or 0x88000000.toInt(), color and 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                Canvas(bm).drawCircle(s / 2f, s / 2f, s / 2f, p)
            }
        }
    }
    private val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val src = Rect(0, 0, 64, 64)

    private fun drawFlameScene(c: Canvas, now: Long, dt: Float) {
        val b = bounds
        val burst = ((900 - (now - eventAt)) / 900f).coerceIn(0f, 1f)   // gerbe au départ et à chaque record
        val power = (heat + burst * 0.6f).coerceAtMost(1.4f)
        // Lueur du bas et bord rougeoyant
        paint.shader = LinearGradient(0f, b.bottom.toFloat(), 0f, b.top + b.height() * 0.35f,
            intArrayOf(cDanger and 0x00FFFFFF or ((110 * power.coerceAtMost(1f)).toInt() shl 24), 0), null, Shader.TileMode.CLAMP)
        c.drawRect(b, paint); paint.shader = null
        val pulse = 0.75f + 0.25f * sin(now / 180.0).toFloat()
        r.set(b); r.inset(1.5f * d, 1.5f * d)
        stroke.color = cHeat; stroke.alpha = (90 * pulse * power.coerceAtMost(1f)).toInt(); stroke.strokeWidth = 9 * d; c.drawRoundRect(r, corner, corner, stroke)
        stroke.alpha = (220 * pulse * power.coerceAtMost(1f)).toInt(); stroke.strokeWidth = 2.5f * d; c.drawRoundRect(r, corner, corner, stroke)
        // Nouvelles flammes au pied du widget (ou de l'écran), braises qui montent jusqu'en haut
        val area = (b.width() / (600 * d)).coerceIn(1f, 4f)
        var n = (60f + 380f * power) * dt * area
        while (n > 0f) {
            if (n >= 1f || rnd.nextFloat() < n) {
                val ember = rnd.nextFloat() < 0.08f
                flames.add(Flame(b.left + rnd.nextFloat() * b.width(), b.bottom + 6 * d,
                    -(b.height() * (0.45f + rnd.nextFloat() * 0.6f) * (0.5f + power)) * (if (ember) 1.3f else 1f),
                    (if (ember) 2.2f else (10f + rnd.nextFloat() * 12f) * (0.8f + 0.25f * area)) * d * (0.7f + power * 0.4f), 0f,
                    if (ember) 1.6f + rnd.nextFloat() else 0.55f + rnd.nextFloat() * 0.5f, rnd.nextFloat() * 6.28f, ember))
            }
            n -= 1f
        }
        val it = flames.iterator()
        while (it.hasNext()) {
            val f = it.next()
            f.age += dt; f.y += f.vy * dt
            f.x += sin(f.phase + f.age * 7f) * (if (f.ember) 30f else 18f) * d * dt
            if (f.age >= f.life) it.remove()
        }
        while (flames.size > 360 * area) flames.removeAt(0)
        for (f in flames) {
            val k = f.age / f.life
            if (f.ember) {
                paint.color = 0xFFFFE082.toInt(); paint.alpha = ((1f - k) * (180 + rnd.nextInt(75))).toInt()
                c.drawCircle(f.x, f.y, f.r0, paint)
            } else {
                val sprite = sprites[when { k < 0.18f -> 0; k < 0.45f -> 1; k < 0.75f -> 2; else -> 3 }]
                val rad = f.r0 * (1f - k * 0.55f)
                add.alpha = ((1f - k) * 200).toInt()
                r.set(f.x - rad, f.y - rad * 2.1f, f.x + rad, f.y + rad * 1.2f)
                c.drawBitmap(sprite, src, r, add)
            }
        }
        sizeText()
        val text = peakText()
        val icon = textPaint.textSize * 0.9f
        val (bw, bh) = badgeSize(text, icon)
        val bx = badgeArea.centerX() - bw / 2f; val cy = badgeArea.centerY()
        drawBadge(c, bx, cy, bw, bh, cHeat, text, bx + bh * 0.45f + icon)
        // Petite flamme devant le texte
        val ix = bx + bh * 0.45f + icon * 0.42f; val s = icon * 0.5f
        val fk = 0.92f + rnd.nextFloat() * 0.12f
        path.reset()
        path.moveTo(ix, cy - s * 1.1f * fk)
        path.cubicTo(ix + s * 0.9f, cy - s * 0.2f, ix + s * 0.8f, cy + s * 0.9f, ix, cy + s * 0.9f)
        path.cubicTo(ix - s * 0.8f, cy + s * 0.9f, ix - s * 0.9f, cy - s * 0.1f, ix, cy - s * 1.1f * fk)
        paint.color = cDanger; paint.alpha = 255; c.drawPath(path, paint)
        paint.color = 0xFFFFE082.toInt(); c.drawCircle(ix, cy + s * 0.4f, s * 0.38f, paint)
    }

    private fun clearParticles() {
        puffs.clear(); flames.clear(); crackles.clear(); bolt.clear(); fly = true; heat = 0f
    }

    private fun strike(now: Long) {
        if (style == LIGHTNING) { bolt.clear(); boltSeed = -1 }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Aperçu de l'animation pleine puissance dans les réglages : une mini carte Puissance en direct
 * qui simule une accélération franche (montée à 86 %, nouveau record à 95 %, puis relâché).
 */
class PowerFxPreview(context: Context) : android.widget.LinearLayout(context) {
    private val d = resources.displayMetrics.density
    val bar = PowerBar(context)
    private val value = android.widget.TextView(context).apply {
        setTextColor(Themes.color(context, R.color.text_value)); textSize = 34f; gravity = android.view.Gravity.CENTER
        fontFeatureSettings = "tnum"
    }
    private val under = android.widget.TextView(context).apply {
        setTextColor(Themes.color(context, R.color.text_sub)); textSize = 20f; gravity = android.view.Gravity.CENTER
    }
    val fx = PowerFx(context)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var start = 0L

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.bg_aa_tile_idle)
        setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
        addView(bar, LayoutParams(-1, (40 * d).toInt()))
        addView(value, LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        addView(under, LayoutParams(-1, (56 * d).toInt()))
        overlay.add(fx)
        fx.attach(this, bar, under)
        fx.onBadge = { on -> under.animate().alpha(if (on) 0f else 1f).setDuration(250).start() }
        show(12f)
    }

    private fun show(kw: Float) {
        bar.kw = kw
        value.text = String.format(java.util.Locale.getDefault(), "%.1f kW", kw)
        under.text = String.format(java.util.Locale.getDefault(), "%.0f V   ·   %.1f A", 352f - kw * 0.15f, kw * 1000f / 350f)
    }

    private val step = object : Runnable {
        override fun run() {
            val t = (android.os.SystemClock.uptimeMillis() - start) / 1000f
            val pk = bar.peakKw
            // Pied au plancher, palier, coup de pied (record), puis on relâche
            val kw = when {
                t < 1.2f -> 12f + (pk * 0.86f - 12f) * (t / 1.2f)
                t < 2.4f -> pk * 0.86f
                t < 3.0f -> pk * 0.86f + (pk * 0.95f - pk * 0.86f) * ((t - 2.4f) / 0.6f)
                t < 4.0f -> pk * 0.95f - (pk * 0.95f - 30f) * ((t - 3.0f) / 1.0f)
                else -> 30f
            }
            show(kw)
            fx.update(kw, pk * 0.8f, pk)
            if (t < 4.5f) handler.postDelayed(this, 100)
        }
    }

    /** Joue la démonstration avec le style choisi. */
    fun play(style: Int) {
        handler.removeCallbacks(step)
        fx.stop()
        fx.style = style
        start = android.os.SystemClock.uptimeMillis()
        step.run()
    }

    override fun onDetachedFromWindow() { handler.removeCallbacks(step); fx.stop(); super.onDetachedFromWindow() }
}
