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
import kotlin.math.atan2
import kotlin.math.cos
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
        /** Citrouille d'Halloween : seulement pendant [halloween]. */
        const val PUMPKIN = 5
        /** Araignée d'Halloween qui s'écrase sur la vitre : seulement pendant [halloween]. */
        const val SPIDER = 6
        /** Période d'Halloween : du 25 octobre au 2 novembre inclus. */
        fun halloween(): Boolean {
            val c = java.util.Calendar.getInstance()
            val m = c.get(java.util.Calendar.MONTH); val day = c.get(java.util.Calendar.DAY_OF_MONTH)
            return (m == java.util.Calendar.OCTOBER && day >= 25) || (m == java.util.Calendar.NOVEMBER && day <= 2)
        }
        /** Style joué pour un réglage : au hasard parmi les animations (la citrouille en plus à Halloween). */
        fun pick(setting: Int): Int = when {
            setting == RANDOM -> (listOf(ROCKET, LIGHTNING, FLAMES) +
                (if (halloween()) listOf(PUMPKIN, PUMPKIN, SPIDER, SPIDER) else emptyList())).random()
            (setting == PUMPKIN || setting == SPIDER) && !halloween() -> ROCKET
            else -> setting
        }
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
    /** Vue secouée pendant l'arrivée de la fusée (le contenu de l'écran ; l'animation, elle, reste stable). */
    var shake: View? = null

    private fun shakeBy(amp: Float) {
        val v = shake ?: return
        if (amp <= 0f) { if (v.translationX != 0f || v.translationY != 0f) { v.translationX = 0f; v.translationY = 0f }; return }
        v.translationX = (rnd.nextFloat() * 2f - 1f) * amp
        v.translationY = (rnd.nextFloat() * 2f - 1f) * amp * 0.7f
    }

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
        shakeBy(0f)
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
            PUMPKIN -> drawPumpkinScene(canvas, now, dt)
            SPIDER -> drawSpiderScene(canvas, now, dt)
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

    // ── Fusée : une grande fusée traverse tout l'écran en 3 s, ralentit au milieu, puis le pic s'affiche ──

    private class Puff(var x: Float, var y: Float, var vx: Float, var vy: Float, var r: Float, var age: Float, val life: Float, val spark: Boolean)
    private val puffs = ArrayList<Puff>()
    private var launchAt = 0L
    private val CROSS_MS = 3000f

    /** Avancement 0..1 de la traversée : rapide au début et à la fin, lent au milieu (vitesse 1 + 0,75·cos). */
    private fun crossing(u: Float) = u + 0.75f * sin(2 * Math.PI * u).toFloat() / (2 * Math.PI).toFloat()

    private fun crossPos(s: Float, out: FloatArray) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        out[0] = bounds.left - w * 0.3f + s * w * 1.6f
        out[1] = bounds.top + h * 0.62f - sin(Math.PI * s).toFloat() * h * 0.28f   // léger arc vers le haut
    }
    private val pa = FloatArray(2); private val pb = FloatArray(2)

    private fun drawRocketScene(c: Canvas, now: Long, dt: Float) {
        sizeText()
        val text = peakText()
        val (bw, bh) = badgeSize(text, 0f)
        if (launchAt == 0L) launchAt = now
        val u = (now - launchAt) / CROSS_MS
        val flying = u < 1f
        val L = bounds.height() * 0.62f
        var ang = 0f; var nx = 0f; var ny = 0f
        if (flying) {
            val sv = crossing(u)
            crossPos(sv, pa); crossPos((sv + 0.01f).coerceAtMost(1.05f), pb)
            ang = Math.toDegrees(atan2((pb[1] - pa[1]).toDouble(), (pb[0] - pa[0]).toDouble())).toFloat()
            // Centre de la fusée sur la trajectoire ; la tuyère est à L/2 derrière
            val rad = Math.toRadians(ang.toDouble())
            nx = pa[0] - (L * 0.5f * cos(rad)).toFloat(); ny = pa[1] - (L * 0.5f * sin(rad)).toFloat()
            // Fumée laissée sur place, qui gonfle et s'efface en 1,3 s (le texte dessous se relit vite)
            var n = 70f * dt
            while (n > 0f) {
                if (n >= 1f || rnd.nextFloat() < n) puffs.add(Puff(nx + (rnd.nextFloat() - 0.5f) * 30 * d, ny + (rnd.nextFloat() - 0.5f) * 26 * d,
                    0f, -12 * d, (14 + rnd.nextFloat() * 10) * d, 0f, 1.3f, false))
                n -= 1f
            }
            shakeBy(2f * d)
        } else { if (fly) { fly = false; badgeAt = now }; shakeBy(0f) }
        stepPuffs(dt)
        paint.shader = null
        for (p in puffs) {
            val k = p.age / p.life
            paint.color = 0xFFE1E3EA.toInt(); paint.alpha = (150 * (1 - k) * (1 - k)).toInt()
            c.drawCircle(p.x, p.y, p.r + k * 46 * d, paint)
        }
        // Badge du pic sous les chiffres : apparaît une fois la fusée passée
        val gx = badgeArea.centerX() - bw / 2f
        val appear = if (fly) 0f else ((now - badgeAt) / 250f).coerceIn(0f, 1f)
        if (appear > 0f) {
            val l = c.saveLayerAlpha(gx - 4 * d, badgeArea.centerY() - bh, gx + bw + 4 * d, badgeArea.centerY() + bh, (appear * 255).toInt())
            drawBadge(c, gx, badgeArea.centerY(), bw, bh, cAction, text, gx + bh * 0.45f)
            c.restoreToCount(l)
        }
        if (flying) {
            c.save(); c.rotate(ang, nx, ny)
            drawRocket(c, nx, ny, L, 0f, now, girth = 0.15f)
            c.restore()
        }
    }
    private var badgeAt = 0L
    private var fly = true

    private fun stepPuffs(dt: Float) {
        val it = puffs.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt; p.x += p.vx * dt; p.y += p.vy * dt; p.vy -= 8 * d * dt
            if (p.age >= p.life) it.remove()
        }
        while (puffs.size > 200) puffs.removeAt(0)
    }

    /** Fusée couchée vers la droite : [x] = tuyère, [y] = axe. */
    private fun drawRocket(c: Canvas, x: Float, y: Float, L: Float, boost: Float, now: Long, girth: Float = 0.21f) {
        val h = L * girth
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
        // Ailerons rouges, comme le nez
        paint.color = cDanger; paint.alpha = 255
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
        paint.color = cDanger; c.drawRect(x - L * 0.02f, y - h * 0.18f, x + L * 0.22f, y + h * 0.18f, paint)
    }

    // ── Citrouille d'Halloween (25 octobre → 2 novembre) : elle fonce à travers l'écran, fait un clin d'œil
    //    au milieu, laisse des flammes et fait fuir des chauves-souris ──

    private class Bat(var x: Float, var y: Float, val vx: Float, val vy: Float, var age: Float)
    private val bats = ArrayList<Bat>()
    private var batClock = 0f
    private val PUMPKIN_MS = 3200f

    private fun drawPumpkinScene(c: Canvas, now: Long, dt: Float) {
        sizeText()
        val text = peakText()
        val (bw, bh) = badgeSize(text, 0f)
        if (launchAt == 0L) launchAt = now
        val u = (now - launchAt) / PUMPKIN_MS
        val flying = u < 1f
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val size = h * 0.32f
        val t = (now - launchAt) / 1000f
        val s = u.coerceAtMost(1f) + 0.8f * sin(2 * Math.PI * u.coerceAtMost(1f)).toFloat() / (2 * Math.PI).toFloat()
        val speed = 1f + 0.8f * cos(2 * Math.PI * u).toFloat()
        val x = bounds.left - w * 0.2f + s * w * 1.4f
        val y = bounds.top + h * 0.56f - kotlin.math.abs(sin(t * 6.0)).toFloat() * 30 * d
        if (flying) {
            // Flammes derrière (le « moteur » à fond), qui partent vers l'arrière et s'éteignent
            var n = 80f * dt
            while (n > 0f) {
                if (n >= 1f || rnd.nextFloat() < n) puffs.add(Puff(x - size * 0.55f, y + (rnd.nextFloat() - 0.5f) * size * 0.5f,
                    -260 * d, -40 * d, (14 + rnd.nextFloat() * 9) * d, 0f, 0.9f, true))
                n -= 1f
            }
            // Chauves-souris qui s'enfuient
            batClock += dt
            if (batClock > 0.25f) { batClock = 0f; bats.add(Bat(x - 30 * d, y - 20 * d, -(80 + rnd.nextFloat() * 140) * d, -(140 + rnd.nextFloat() * 120) * d, 0f)) }
            shakeBy(1.5f * d)
        } else { if (fly) { fly = false; badgeAt = now }; shakeBy(0f) }
        stepPuffs(dt)
        paint.shader = null
        for (p in puffs) {
            val k = p.age / p.life
            paint.color = if (k < 0.35f) 0xFFFFD25A.toInt() else 0xFFFF8A1E.toInt()
            paint.alpha = (220 * (1 - k)).toInt()
            c.drawCircle(p.x, p.y, p.r * (1 - 0.5f * k), paint)
        }
        val bi = bats.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            b.age += dt; b.x += b.vx * dt; b.y += b.vy * dt
            if (b.age > 1.4f) { bi.remove(); continue }
            drawBat(c, b.x, b.y, 22 * d, ((now / 80) % 2).toInt() == 0, ((1 - b.age / 1.4f) * 255).toInt())
        }
        // Lignes de vitesse quand elle file
        if (flying && speed > 1f) {
            stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = 110; stroke.strokeWidth = 3 * d
            for (i in 0..4) {
                val yy = y - size * 0.55f + i * size * 0.27f
                c.drawLine(x - size * 0.6f - 160 * d * speed, yy, x - size * 0.6f - 20 * d, yy, stroke)
            }
        }
        // Badge du pic une fois la citrouille passée
        val gx = badgeArea.centerX() - bw / 2f
        val appear = if (fly) 0f else ((now - badgeAt) / 250f).coerceIn(0f, 1f)
        if (appear > 0f) {
            val l = c.saveLayerAlpha(gx - 4 * d, badgeArea.centerY() - bh, gx + bw + 4 * d, badgeArea.centerY() + bh, (appear * 255).toInt())
            drawBadge(c, gx, badgeArea.centerY(), bw, bh, cHeat, text, gx + bh * 0.45f)
            c.restoreToCount(l)
        }
        if (flying) {
            val wink = kotlin.math.abs(u - 0.5f) < 0.06f
            c.save()
            if (speed > 1.2f && !wink) c.rotate(-t * 260f, x, y)
            drawPumpkin(c, x, y, size, wink, 0.5f + 0.5f * sin(t * 9.0).toFloat(), 1f - 0.08f * sin(t * 12.0).toFloat())
            c.restore()
        }
    }

    // ── Araignée d'Halloween : une grosse araignée fonce vers le conducteur et s'écrase sur la vitre de
    //    l'écran (plaquée par l'accélération) : impact, vitre fêlée, araignée aplatie qui glisse puis s'efface ──

    private class Goo(val dx: Float, val dy: Float, val r: Float, val drip: Float)
    private val goo = ArrayList<Goo>()
    private val cracks = ArrayList<FloatArray>()
    private val SPIDER_IN_MS = 950f
    private val SPIDER_STAY_MS = 3600f

    private fun drawSpiderScene(c: Canvas, now: Long, dt: Float) {
        sizeText()
        val text = peakText()
        val (bw, bh) = badgeSize(text, 0f)
        if (launchAt == 0L) launchAt = now
        val ms = (now - launchAt).toFloat()
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val ix = bounds.left + w * 0.56f; val iy = bounds.top + h * 0.46f      // point d'impact
        val full = h * 0.62f                                                  // taille à l'impact
        if (ms < SPIDER_IN_MS) {
            // Arrivée : petite au loin, elle grossit de plus en plus vite en se rapprochant, pattes qui gigotent
            val u = ms / SPIDER_IN_MS
            val k = 0.06f + 0.94f * u * u * u
            val x = bounds.left + w * 0.72f + (ix - bounds.left - w * 0.72f) * u
            val y = bounds.top + h * 0.22f + (iy - bounds.top - h * 0.22f) * u
            drawSpider(c, x, y, full * k, wiggle = sin(ms / 45.0).toFloat(), squash = 0f, alpha = 255)
            shakeBy(0f)
            return
        }
        val since = ms - SPIDER_IN_MS
        if (fly) {
            fly = false; badgeAt = now
            // Éclaboussures et fêlures tirées une fois, à l'impact
            goo.clear(); cracks.clear()
            repeat(34) {
                val a = rnd.nextFloat() * 2 * Math.PI; val dist = full * (0.15f + rnd.nextFloat() * 0.8f)
                goo.add(Goo((cos(a) * dist).toFloat(), (sin(a) * dist * 0.8f).toFloat(), (4 + rnd.nextFloat() * 13) * d,
                    if (rnd.nextFloat() < 0.6f) (25 + rnd.nextFloat() * 90) * d else 0f))
            }
            repeat(11) {
                val a0 = rnd.nextFloat() * 2 * Math.PI
                val pts = ArrayList<Float>(); var px = 0f; var py = 0f; var a = a0
                pts.add(0f); pts.add(0f)
                val segs = 3 + rnd.nextInt(3); val len = full * (0.5f + rnd.nextFloat() * 0.9f) / segs
                repeat(segs) { a += (rnd.nextFloat() - 0.5f) * 0.6f; px += (cos(a) * len).toFloat(); py += (sin(a) * len).toFloat(); pts.add(px); pts.add(py) }
                cracks.add(pts.toFloatArray())
            }
        }
        // Secousse de l'impact, qui s'amortit
        shakeBy(if (since < 450f) 9 * d * (1f - since / 450f) else 0f)
        val fade = ((SPIDER_STAY_MS - since) / 600f).coerceIn(0f, 1f)
        val slide = (since / SPIDER_STAY_MS).let { it * it } * h * 0.18f    // l'araignée glisse lentement vers le bas
        val a = (255 * fade).toInt()
        if (a > 0) {
            // Vitre fêlée autour de l'impact
            stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = (170 * fade).toInt(); stroke.strokeWidth = 2 * d
            for (pts in cracks) {
                path.reset(); path.moveTo(ix + pts[0], iy + pts[1])
                var i = 2; while (i < pts.size) { path.lineTo(ix + pts[i], iy + pts[i + 1]); i += 2 }
                c.drawPath(path, stroke)
            }
            stroke.alpha = (110 * fade).toInt(); c.drawCircle(ix, iy, full * 0.18f, stroke)
            // Jus qui gicle à l'impact puis coule le long de la vitre : taches, et coulures qui s'allongent
            // avec une goutte au bout (jaune-vert translucide, plus foncé au centre)
            paint.shader = null
            val run = (since / SPIDER_STAY_MS).coerceAtMost(1f)
            for (g in goo) {
                val gx = ix + g.dx; val gy = iy + g.dy + slide * 0.3f
                paint.color = 0xFFB5A642.toInt(); paint.alpha = (190 * fade).toInt(); c.drawCircle(gx, gy, g.r, paint)
                paint.color = 0xFF6E7A1F.toInt(); paint.alpha = (150 * fade).toInt(); c.drawCircle(gx, gy, g.r * 0.55f, paint)
                if (g.drip > 0f) {
                    val len = g.drip * (0.25f + 2.4f * run)
                    paint.color = 0xFFB5A642.toInt(); paint.alpha = (170 * fade).toInt()
                    c.drawRect(gx - g.r * 0.3f, gy, gx + g.r * 0.3f, gy + len, paint)
                    c.drawCircle(gx, gy + len, g.r * 0.5f, paint)
                    paint.color = 0xFFFFF7C0.toInt(); paint.alpha = (110 * fade).toInt()
                    c.drawCircle(gx - g.r * 0.15f, gy + len - g.r * 0.15f, g.r * 0.15f, paint)
                }
            }
            // Grande flaque de jus sous le corps écrasé, qui s'étale un peu
            paint.color = 0xFF8C8A2E.toInt(); paint.alpha = (150 * fade).toInt()
            r.set(ix - full * 0.32f * (1 + run * 0.2f), iy - full * 0.05f + slide, ix + full * 0.32f * (1 + run * 0.2f), iy + full * 0.5f + slide * 1.2f)
            c.drawOval(r, paint)
            // Araignée plaquée, aplatie
            drawSpider(c, ix, iy + slide, full, wiggle = 0f, squash = 1f, alpha = a)
            // Flash blanc de l'impact
            if (since < 160f) { paint.color = 0xFFFFFFFF.toInt(); paint.alpha = (140 * (1f - since / 160f)).toInt(); c.drawRect(bounds, paint) }
        }
        // Badge du pic peu après l'impact
        val gx = badgeArea.centerX() - bw / 2f
        val appear = ((now - badgeAt - 500) / 250f).coerceIn(0f, 1f)
        if (appear > 0f) {
            val l = c.saveLayerAlpha(gx - 4 * d, badgeArea.centerY() - bh, gx + bw + 4 * d, badgeArea.centerY() + bh, (appear * 255).toInt())
            drawBadge(c, gx, badgeArea.centerY(), bw, bh, cHeat, text, gx + bh * 0.45f)
            c.restoreToCount(l)
        }
    }

    /**
     * Grosse mygale vue de face : abdomen et céphalothorax bruns couverts de poils, huit grosses pattes
     * poilues à trois segments, baguées de roux, pédipalpes et crochets. [squash] 0 → en vol (pattes
     * repliées qui gigotent), 1 → écrasée contre la vitre (pattes étalées, corps élargi et aplati).
     */
    private fun drawSpider(c: Canvas, cx: Float, cy: Float, size: Float, wiggle: Float, squash: Float, alpha: Int) {
        val s = size / 2f
        val hair = java.util.Random(42)   // poils au même endroit d'une image à l'autre
        paint.style = Paint.Style.FILL
        // Une patte : trois segments épais qui s'affinent, bagues claires aux articulations, poils dressés
        fun leg(x0: Float, y0: Float, pts: FloatArray, w0: Float) {
            var px = x0; var py = y0; var w = w0
            var k = 0
            while (k < pts.size) {
                val qx = pts[k]; val qy = pts[k + 1]
                stroke.shader = null; stroke.color = 0xFF2A1D16.toInt(); stroke.alpha = alpha; stroke.strokeWidth = w
                c.drawLine(px, py, qx, qy, stroke)
                // Reflet le long du segment
                stroke.color = 0xFF4A3426.toInt(); stroke.alpha = alpha / 2; stroke.strokeWidth = w * 0.35f
                c.drawLine(px, py - w * 0.15f, qx, qy - w * 0.15f, stroke)
                // Poils
                val dx = qx - px; val dy = qy - py; val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                val nx = -dy / len; val ny = dx / len
                stroke.color = 0xFF6B5240.toInt(); stroke.alpha = alpha; stroke.strokeWidth = 1.2f * d
                val n = (len / (3.5f * d)).toInt()
                for (h in 0 until n) {
                    val t = h / n.toFloat(); val bx = px + dx * t; val by = py + dy * t
                    val side = if (h % 2 == 0) 1f else -1f
                    val hl = w * (0.55f + hair.nextFloat() * 0.5f)
                    c.drawLine(bx + nx * side * w * 0.4f, by + ny * side * w * 0.4f,
                        bx + nx * side * (w * 0.4f + hl) + dx / len * hl * 0.4f, by + ny * side * (w * 0.4f + hl) + dy / len * hl * 0.4f, stroke)
                }
                // Bague rousse à l'articulation
                paint.shader = null; paint.color = 0xFF8A5A3A.toInt(); paint.alpha = alpha
                c.drawCircle(qx, qy, w * 0.55f, paint)
                px = qx; py = qy; w *= 0.72f; k += 2
            }
        }
        val spread = 1f + 0.2f * squash
        for (side in listOf(-1f, 1f)) for (i in 0 until 4) {
            // Angles de départ : deux pattes vers l'avant, deux vers l'arrière, de chaque côté
            val base = Math.toRadians((-62.0 + i * 38.0) + wiggle * 9.0 * (if ((i + (if (side > 0) 1 else 0)) % 2 == 0) 1 else -1))
            val bend = 0.55 - 0.3 * squash        // pattes repliées en vol, encore un peu pliées une fois écrasée
            val l1 = s * 0.55f * spread; val l2 = s * 0.5f * spread; val l3 = s * 0.42f * spread
            val ax = cx + side * s * 0.1f; val ay = cy - s * 0.12f
            val a1 = base - 0.35 * (1 - squash)
            val x1 = ax + side * (cos(a1) * l1).toFloat(); val y1 = ay + (sin(a1) * l1).toFloat() - s * 0.18f * (1 - squash)
            val a2 = base + bend
            val x2 = x1 + side * (cos(a2) * l2).toFloat(); val y2 = y1 + (sin(a2) * l2).toFloat()
            val a3 = base + bend * 1.6
            val x3 = x2 + side * (cos(a3) * l3).toFloat(); val y3 = y2 + (sin(a3) * l3).toFloat()
            leg(ax, ay, floatArrayOf(x1, y1, x2, y2, x3, y3), s * 0.12f)
        }
        val wide = 1f + 0.35f * squash; val flat = 1f - 0.25f * squash
        // Pédipalpes (petites pattes avant)
        for (side in listOf(-1f, 1f)) leg(cx + side * s * 0.08f, cy - s * 0.4f,
            floatArrayOf(cx + side * s * 0.2f, cy - s * 0.62f * flat, cx + side * s * 0.16f, cy - s * 0.78f * flat), s * 0.07f)
        // Corps : abdomen (arrière, en bas) et céphalothorax, bruns avec dégradé et fourrure
        fun furOval(l: Float, t: Float, rr: Float, b: Float, dark: Int, light: Int, hairs: Int) {
            r.set(l, t, rr, b)
            paint.shader = android.graphics.RadialGradient(r.centerX(), r.centerY() - r.height() * 0.15f, r.width() * 0.6f,
                light, dark, android.graphics.Shader.TileMode.CLAMP)
            paint.alpha = alpha; c.drawOval(r, paint); paint.shader = null
            stroke.shader = null; stroke.strokeWidth = 1.3f * d
            for (h in 0 until hairs) {
                val a = hair.nextFloat() * 2 * Math.PI; val rad = kotlin.math.sqrt(hair.nextFloat())
                val hx = r.centerX() + (cos(a) * r.width() / 2 * rad).toFloat(); val hy = r.centerY() + (sin(a) * r.height() / 2 * rad).toFloat()
                val hl = (3 + hair.nextFloat() * 5) * d * (0.6f + 0.6f * rad)
                stroke.color = if (hair.nextBoolean()) 0xFF7A5C46.toInt() else 0xFF1A120D.toInt(); stroke.alpha = alpha
                c.drawLine(hx, hy, hx + (cos(a) * hl).toFloat(), hy + (sin(a) * hl).toFloat(), stroke)
            }
            // Poils de bordure plus longs
            stroke.color = 0xFF8C6D55.toInt(); stroke.alpha = alpha
            for (h in 0 until hairs / 3) {
                val a = h / (hairs / 3f) * 2 * Math.PI
                val ex = r.centerX() + (cos(a) * r.width() / 2).toFloat(); val ey = r.centerY() + (sin(a) * r.height() / 2).toFloat()
                val hl = (6 + hair.nextFloat() * 6) * d
                c.drawLine(ex, ey, ex + (cos(a) * hl).toFloat(), ey + (sin(a) * hl).toFloat(), stroke)
            }
        }
        furOval(cx - s * 0.36f * wide, cy - s * 0.02f, cx + s * 0.36f * wide, cy + s * 0.78f * flat, 0xFF1C130E.toInt(), 0xFF5A4030.toInt(), 160)
        furOval(cx - s * 0.27f * wide, cy - s * 0.48f * flat, cx + s * 0.27f * wide, cy + s * 0.04f, 0xFF231811.toInt(), 0xFF6A4E3A.toInt(), 110)
        // Huit petits yeux groupés sur le dessus, brillants
        paint.shader = null
        for ((ex, ey) in listOf(-0.05f to -0.36f, 0.05f to -0.36f, -0.11f to -0.33f, 0.11f to -0.33f,
                -0.03f to -0.31f, 0.03f to -0.31f, -0.08f to -0.29f, 0.08f to -0.29f)) {
            val er = s * (if (kotlin.math.abs(ex) < 0.06f && ey < -0.34f) 0.028f else 0.018f)
            paint.color = 0xFF050505.toInt(); paint.alpha = alpha; c.drawCircle(cx + ex * s * wide, cy + ey * s * flat, er, paint)
            if (squash < 0.5f) { paint.color = 0xFFDDE6EE.toInt(); paint.alpha = alpha; c.drawCircle(cx + ex * s * wide - er * 0.3f, cy + ey * s * flat - er * 0.3f, er * 0.3f, paint) }
        }
        // Chélicères et crochets noirs brillants
        for (side in listOf(-1f, 1f)) {
            r.set(cx + side * s * 0.01f - s * 0.08f, cy - s * 0.5f * flat - s * 0.1f, cx + side * s * 0.01f + s * 0.08f, cy - s * 0.42f * flat + s * 0.02f)
            r.offset(side * s * 0.07f, 0f)
            paint.color = 0xFF1A120D.toInt(); paint.alpha = alpha; c.drawOval(r, paint)
            stroke.color = 0xFF0A0A0A.toInt(); stroke.alpha = alpha; stroke.strokeWidth = s * 0.03f
            c.drawLine(r.centerX(), r.top, r.centerX() - side * s * 0.04f, r.top - s * 0.06f, stroke)
        }
    }

    /** Citrouille-lanterne de face : côtes orange, tige, yeux et sourire lumineux ; [wink] ferme l'œil droit. */
    private fun drawPumpkin(c: Canvas, cx: Float, cy: Float, size: Float, wink: Boolean, glow: Float, squash: Float) {
        val rw = size * 0.62f; val rh = size * 0.52f * squash
        // Halo de bougie
        paint.shader = RadialGradient(cx, cy, size * 0.95f, intArrayOf(0x66FF9A1E, 0x00FF9A1E), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, size * 0.95f, paint); paint.shader = null
        for ((off, col) in listOf(-0.45f to 0xFFD66012.toInt(), 0.45f to 0xFFD66012.toInt(), -0.2f to 0xFFF07818.toInt(),
                0.2f to 0xFFF07818.toInt(), 0f to 0xFFFC8A1E.toInt())) {
            val ww = rw * (if (off != 0f) 0.62f else 0.55f)
            paint.color = col; paint.alpha = 255
            r.set(cx + off * rw - ww, cy - rh, cx + off * rw + ww, cy + rh); c.drawOval(r, paint)
        }
        paint.color = 0xFF466E28.toInt()
        path.reset(); path.moveTo(cx - 0.08f * size, cy - rh + 4); path.lineTo(cx + 0.1f * size, cy - rh + 4)
        path.lineTo(cx + 0.16f * size, cy - rh - 0.22f * size); path.lineTo(cx - 0.02f * size, cy - rh - 0.2f * size); path.close()
        c.drawPath(path, paint)
        val g = android.graphics.Color.rgb(255, 214 + (30 * glow).toInt().coerceIn(0, 41), 80)
        paint.color = g
        val ey = cy - rh * 0.18f
        path.reset()
        path.moveTo(cx - 0.36f * size, ey + 0.1f * size); path.lineTo(cx - 0.12f * size, ey + 0.1f * size); path.lineTo(cx - 0.24f * size, ey - 0.14f * size); path.close()
        if (!wink) { path.moveTo(cx + 0.12f * size, ey + 0.1f * size); path.lineTo(cx + 0.36f * size, ey + 0.1f * size); path.lineTo(cx + 0.24f * size, ey - 0.14f * size); path.close() }
        c.drawPath(path, paint)
        if (wink) { stroke.color = g; stroke.alpha = 255; stroke.strokeWidth = 0.04f * size; c.drawLine(cx + 0.12f * size, ey + 0.02f * size, cx + 0.36f * size, ey + 0.02f * size, stroke) }
        // Sourire en zigzag
        val my = cy + rh * 0.32f
        path.reset(); path.moveTo(cx - 0.42f * size, my - 0.06f * size)
        for (i in 0 until 7) path.lineTo(cx - 0.42f * size + (i + 0.5f) * 0.12f * size, my + (if (i % 2 == 0) 0.06f else -0.02f) * size)
        path.lineTo(cx + 0.42f * size, my - 0.06f * size); path.lineTo(cx + 0.3f * size, my + 0.16f * size)
        path.lineTo(cx, my + 0.22f * size); path.lineTo(cx - 0.3f * size, my + 0.16f * size); path.close()
        c.drawPath(path, paint)
    }

    private fun drawBat(c: Canvas, cx: Float, cy: Float, s: Float, flap: Boolean, alpha: Int) {
        paint.shader = null; paint.color = 0xFF1E1828.toInt(); paint.alpha = alpha
        val up = if (flap) -0.5f else 0.35f
        path.reset()
        for (sg in intArrayOf(-1, 1)) {
            path.moveTo(cx, cy); path.lineTo(cx + sg * s * 1.15f, cy + up * s); path.lineTo(cx + sg * s * 0.8f, cy + 0.2f * s)
            path.lineTo(cx + sg * s * 0.5f, cy + 0.05f * s); path.close()
        }
        c.drawPath(path, paint)
        r.set(cx - s * 0.22f, cy - s * 0.25f, cx + s * 0.22f, cy + s * 0.3f); c.drawOval(r, paint)
        paint.color = 0xFFFFDC5A.toInt(); paint.alpha = alpha
        c.drawCircle(cx - 0.08f * s, cy - 0.06f * s, 0.045f * s, paint); c.drawCircle(cx + 0.08f * s, cy - 0.06f * s, 0.045f * s, paint)
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
        puffs.clear(); flames.clear(); crackles.clear(); bolt.clear(); bats.clear(); goo.clear(); cracks.clear(); fly = true; heat = 0f; launchAt = 0L
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
