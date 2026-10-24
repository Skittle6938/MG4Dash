package app.mg4dash.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.databinding.ActivityMainBinding

/**
 * Présentation de la carte média de l'accueil (réglage « Lecteur ») :
 *  - Immersif (par défaut) : comme le lecteur d'Android Auto, la pochette nette couvre la carte,
 *    prolongée par la couleur de son bord quand elle n'a pas le format de la carte ; titre et artiste
 *    par-dessus, bouton lecture en pilule et barre de progression à la couleur vive de l'image ;
 *  - Classique : pochette carrée à gauche, boutons en pavés à la largeur de la colonne (rien ne
 *    dépasse, ils étaient coupés à droite quand la pochette prenait toute la hauteur).
 */
class MediaStyle private constructor(
    private val a: Activity, private val play: ImageView, private val progress: android.widget.ProgressBar?,
) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private lateinit var frame: FrameLayout
    private lateinit var picture: ImageView
    private lateinit var fade: View
    private var lastArt: Bitmap? = null
    private var headTitle: TextView? = null
    private var accent = 0

    private fun detach(v: View) = v.apply { (parent as? ViewGroup)?.removeView(this) }

    /**
     * [card] : le panneau ; [head] : sa ligne de titre (source ▾) ; [info] : la zone cliquable qui
     * reçoit le lecteur (elle ouvre l'appli de la source). Les vues passées sont déplacées, pas recréées.
     */
    private fun buildImmersive(card: LinearLayout, head: View, info: LinearLayout, title: TextView, artist: TextView,
                               prev: ImageView, next: ImageView) {
        listOf(title, artist, prev, play, next).forEach { detach(it) }
        progress?.let { detach(it) }
        info.removeAllViews()
        detach(head)
        // Le panneau entier devient le lecteur : la zone cliquable (ouvre l'appli) le couvre
        card.setPadding(0, 0, 0, 0)
        for (i in 0 until card.childCount) if (card.getChildAt(i) !== info) card.getChildAt(i).visibility = View.GONE
        (info.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; height = 0; weight = 1f }
        info.orientation = LinearLayout.HORIZONTAL
        frame = RoundedFrame(a)
        picture = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_END }
        frame.addView(picture, FrameLayout.LayoutParams(-1, -1))
        fade = View(a)
        frame.addView(fade, FrameLayout.LayoutParams(0, -1).apply { gravity = Gravity.START })
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeFade() }
        frame.addView(View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x44000000, 0xC0000000.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val over = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(16)) }
        headTitle = (head as? LinearLayout)?.getChildAt(0) as? TextView
        headTitle?.setTextColor(Color.WHITE)
        over.addView(head, LinearLayout.LayoutParams(-1, -2))
        over.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        title.setTextColor(Color.WHITE); title.textSize = 30f; title.maxLines = 1; title.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        artist.setTextColor(0xFFE6E6E6.toInt()); artist.textSize = 22f; artist.maxLines = 1; artist.setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
        over.addView(title, LinearLayout.LayoutParams(-1, -2))
        over.addView(artist, LinearLayout.LayoutParams(-1, -2))
        progress?.let {
            over.addView(it, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })
            it.progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
        }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        play.setPadding(dp(24), dp(20), dp(24), dp(20))
        play.clearColorFilter()
        play.imageTintList = ColorStateList.valueOf(0xFF1A1A1A.toInt())
        row.addView(play, LinearLayout.LayoutParams(dp(160), dp(76)))
        row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f))
        listOf(prev, next).forEach { v ->
            v.background = null
            v.clearColorFilter()
            v.imageTintList = ColorStateList.valueOf(Color.WHITE)
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            v.setPadding(dp(18), dp(18), dp(18), dp(18))
            row.addView(v, LinearLayout.LayoutParams(dp(80), dp(80)).apply { leftMargin = dp(20) })
        }
        over.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        frame.addView(over, FrameLayout.LayoutParams(-1, -1))
        info.addView(frame, LinearLayout.LayoutParams(0, -1, 1f))
        update(null)
    }

    private fun buildClassic(b: ActivityMainBinding) {
        val texts = b.mediaInfo.getChildAt(1) as? LinearLayout ?: return
        val old = b.mediaPlay.parent as? LinearLayout ?: return
        texts.removeView(old)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val light = Themes.isLight(a)
        fun pad(v: View, w: Float, h: Int, bg: Int, r: Int) {
            detach(v)
            v.background = GradientDrawable().apply { cornerRadius = dp(r).toFloat(); setColor(bg) }
            row.addView(v, LinearLayout.LayoutParams(0, dp(h), w).apply { leftMargin = dp(5); rightMargin = dp(5) })
        }
        val soft = if (light) 0x14000000 else 0x22FFFFFF
        pad(b.mediaPrev, 1f, 70, soft, 18)
        pad(b.mediaPlay, 1.25f, 82, Themes.color(a, R.color.accent_action), 22)
        pad(b.mediaNext, 1f, 70, soft, 18)
        texts.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    /** Fondu posé sur le bord gauche de l'image (calée à droite) : la couleur du bord s'y prolonge sans cassure. */
    /**
     * Image calée à droite à la hauteur de la carte, jamais rognée sur les côtés : ses bandes noires du
     * haut et du bas ne sont retirées que tant que l'image agrandie tient dans la largeur de la carte
     * (sa forme change selon la disposition). La couleur moyenne de son bord gauche la prolonge, avec
     * un fondu posé sur ce bord.
     */
    private fun placeFade() {
        val w = frame.width; val h = frame.height
        val art = lastArt
        if (w == 0 || h == 0 || art == null) { fade.visibility = View.GONE; return }
        if (shownFor != w * 10000 + h) {
            shownFor = w * 10000 + h
            val img = cropBars(art, w, h)
            picture.setImageBitmap(img)
            val edge = edgeColor(img)
            frame.setBackgroundColor(edge)
            fade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(edge, edge and 0x00FFFFFF))
        }
        val d = picture.drawable ?: return
        if (picture.scaleType != ImageView.ScaleType.FIT_END) picture.scaleType = ImageView.ScaleType.FIT_END
        val imgW = minOf(w, (h.toLong() * d.intrinsicWidth / d.intrinsicHeight.coerceAtLeast(1)).toInt())
        val lp = fade.layoutParams as FrameLayout.LayoutParams
        val fw = imgW * 2 / 5
        if (lp.leftMargin != w - imgW || lp.width != fw) {
            lp.leftMargin = w - imgW; lp.width = fw
            frame.post { fade.layoutParams = lp }
        }
        fade.visibility = if (imgW < w) View.VISIBLE else View.GONE
    }
    private var shownFor = 0

    /** Nouvelle pochette : image, prolongement et couleur vive recalculés (une fois par image). */
    fun update(source: Bitmap?) {
        if (!::frame.isInitialized) return
        // Image toute noire (station sans pochette) : traitée comme une absence d'image
        val art = source?.takeIf { !isBlank(it) }
        if (art != null && art === lastArt) return
        lastArt = art
        val edge: Int
        val light = Themes.isLight(a)
        // Sans image : fond blanc ou noir selon le thème, note de musique discrète ; le titre de la source
        // passe en sombre sur le blanc (le bas reste assombri pour le titre et l'artiste)
        headTitle?.setTextColor(if (art == null && light) Themes.color(a, R.color.text_value) else Color.WHITE)
        if (art == null) {
            picture.setImageResource(R.drawable.ic_music)
            picture.scaleType = ImageView.ScaleType.CENTER
            picture.imageTintList = ColorStateList.valueOf(if (light) 0x33000000 else 0x33FFFFFF)
            edge = if (light) 0xFFF2F4F8.toInt() else 0xFF0B0E14.toInt()
            accent = Themes.color(a, R.color.accent_action)
        } else {
            picture.imageTintList = null
            picture.scaleType = ImageView.ScaleType.FIT_END
            edge = edgeColor(art)
            accent = vividColor(art) ?: Themes.color(a, R.color.accent_action)
        }
        frame.setBackgroundColor(edge)
        fade.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(edge, edge and 0x00FFFFFF))
        // Image recadrée à la taille de la carte (placeFade, aussi rappelé à chaque mise en page)
        shownFor = 0
        placeFade()
        play.background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(accent) }
        progress?.progressTintList = ColorStateList.valueOf(accent)
    }

    companion object {
        /** 0 = Immersif (défaut), 1 = Classique. */
        fun apply(a: Activity, b: ActivityMainBinding, style: Int): MediaStyle? = MediaStyle(a, b.mediaPlay, b.mediaProgress).apply {
            try {
                if (style == 0) {
                    b.mediaAlbum.visibility = View.GONE
                    buildImmersive(b.mediaCard, b.mediaCard.getChildAt(0), b.mediaInfo, b.mediaTitle, b.mediaArtist, b.mediaPrev, b.mediaNext)
                } else buildClassic(b)
            } catch (e: Exception) { android.util.Log.w("MG4L.Media", "présentation du lecteur : ${e.message}") }
        }.takeIf { style == 0 }

        /** Mode épuré : même lecteur Immersif, sans barre de progression. */
        fun immersive(a: Activity, card: LinearLayout, head: View, info: LinearLayout, title: TextView, artist: TextView,
                      prev: ImageView, play: ImageView, next: ImageView): MediaStyle? = try {
            MediaStyle(a, play, null).apply { buildImmersive(card, head, info, title, artist, prev, next) }
        } catch (e: Exception) { android.util.Log.w("MG4L.Media", "lecteur épuré : ${e.message}"); null }

        /**
         * Image sans ses bandes noires (ou presque noires) en haut et en bas, comme sur les pochettes
         * de radio au format 16:9 mises dans un carré. Au plus 35 % retirés de chaque côté.
         */
        fun cropBars(b: Bitmap, frameW: Int, frameH: Int): Bitmap {
            val h = b.height; val w = b.width
            if (h < 20 || w < 20) return b
            val step = maxOf(1, w / 60)
            fun dark(y: Int): Boolean {
                var n = 0; var bright = 0
                for (x in 0 until w step step) {
                    val c = b.getPixel(x, y); n++
                    if (Color.red(c) > 28 || Color.green(c) > 28 || Color.blue(c) > 28) bright++
                }
                return bright * 20 <= n   // au plus 5 % de pixels clairs
            }
            val max = h * 35 / 100
            var top = 0; while (top < max && dark(top)) top++
            var bottom = 0; while (bottom < max && dark(h - 1 - bottom)) bottom++
            if (top + bottom < h / 20) return b
            // Pas plus que ce qui garde l'image agrandie dans la largeur du cadre (jamais rognée sur les côtés)
            val minH = ((w.toLong() * frameH + frameW - 1) / frameW).toInt()
            val newH = maxOf(h - top - bottom, minH)
            if (newH >= h) return b
            val cut = h - newH
            val cutTop = minOf(top, Math.round(cut * top / (top + bottom).toFloat()))
            return Bitmap.createBitmap(b, 0, cutTop, w, newH)
        }

        /** Image presque uniformément noire. */
        fun isBlank(b: Bitmap): Boolean {
            val step = maxOf(1, b.width / 24)
            var bright = 0; var n = 0
            for (x in 0 until b.width step step) for (y in 0 until b.height step step) {
                val c = b.getPixel(x, y); n++
                if (Color.red(c) > 24 || Color.green(c) > 24 || Color.blue(c) > 24) bright++
            }
            return n > 0 && bright * 50 < n   // moins de 2 % de pixels non noirs
        }

        /** Couleur moyenne de la bande gauche de l'image (prolongement quand elle n'a pas le format de la carte). */
        fun edgeColor(b: Bitmap): Int {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            val w = maxOf(1, b.width / 12)
            for (x in 0 until w step 2) for (y in 0 until b.height step 3) {
                val c = b.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
            }
            if (n == 0L) return 0xFF2A3140.toInt()
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }

        /** Couleur vive dominante : la teinte (par tranches de 30°) qui pèse le plus en saturation × luminosité. */
        fun vividColor(b: Bitmap): Int? {
            val w = FloatArray(12); val hsv = FloatArray(3)
            val sum = Array(12) { FloatArray(3) }
            val step = maxOf(1, b.width / 80)
            for (x in 0 until b.width step step) for (y in 0 until b.height step step) {
                Color.colorToHSV(b.getPixel(x, y), hsv)
                if (hsv[1] < 0.35f || hsv[2] < 0.45f) continue
                val k = (hsv[0] / 30f).toInt() % 12; val wt = hsv[1] * hsv[2]
                w[k] += wt; sum[k][0] += hsv[0] * wt; sum[k][1] += hsv[1] * wt; sum[k][2] += hsv[2] * wt
            }
            val k = w.indices.maxByOrNull { w[it] } ?: return null
            if (w[k] == 0f) return null
            // Assez saturée et claire pour ressortir sur l'image assombrie
            return Color.HSVToColor(floatArrayOf(sum[k][0] / w[k], maxOf(0.65f, sum[k][1] / w[k]), maxOf(0.85f, sum[k][2] / w[k])))
        }
    }
}
