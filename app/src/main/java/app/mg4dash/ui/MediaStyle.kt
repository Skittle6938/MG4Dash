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
 * Presentation of the home media card, like the Android Auto player: the sharp cover
 * covers the card, extended by its edge colour when it does not have the card's shape;
 * title and artist on top, pill play button and progress bar in the image's
 * vivid colour. (The "Classic" presentation was removed.)
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
    private lateinit var shade: View
    private lateinit var texts: List<TextView>
    private lateinit var skips: List<ImageView>
    private var plain: Boolean? = null
    /** The card's panel, whose outline is only shown without an image (#36). */
    private var panel: LinearLayout? = null
    private lateinit var radioLine: TextView
    private lateinit var lowerSpace: View

    private fun detach(v: View) = v.apply { (parent as? ViewGroup)?.removeView(this) }

    /**
     * [card]: the panel; [head]: its title line (source ▾); [info]: the clickable area that
     * receives the player (it opens the source's app). Passed views are moved, not recreated.
     */
    private fun buildImmersive(card: LinearLayout, head: View, info: LinearLayout, title: TextView, artist: TextView,
                               prev: ImageView, next: ImageView) {
        listOf(title, artist, prev, play, next).forEach { detach(it) }
        progress?.let { detach(it) }
        info.removeAllViews()
        detach(head)
        // The whole panel becomes the player: the clickable area (opens the app) covers it
        card.setPadding(0, 0, 0, 0)
        panel = card
        for (i in 0 until card.childCount) if (card.getChildAt(i) !== info) card.getChildAt(i).visibility = View.GONE
        (info.layoutParams as LinearLayout.LayoutParams).apply { topMargin = 0; height = 0; weight = 1f }
        info.orientation = LinearLayout.HORIZONTAL
        // The player covers the whole panel, frame included; its slightly less rounded corners (19 dp vs 20)
        // also cover the frame's, which stuck out under a dark image
        frame = RoundedFrame(a, radiusDp = 19f)
        picture = ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_END }
        frame.addView(picture, FrameLayout.LayoutParams(-1, -1))
        fade = View(a)
        frame.addView(fade, FrameLayout.LayoutParams(0, -1).apply { gravity = Gravity.START })
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeFade() }
        shade = View(a).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x44000000, 0xC0000000.toInt()))
        }
        frame.addView(shade, FrameLayout.LayoutParams(-1, -1))
        texts = listOf(title, artist)
        skips = listOf(prev, next)
        val over = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(14), dp(24), dp(16)) }
        headTitle = (head as? LinearLayout)?.getChildAt(0) as? TextView
        headTitle?.setTextColor(Color.WHITE)
        over.addView(head, LinearLayout.LayoutParams(-1, -2))
        over.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        over.addView(title, LinearLayout.LayoutParams(-1, -2))
        over.addView(artist, LinearLayout.LayoutParams(-1, -2))
        radioLine = TextView(a).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; visibility = View.GONE }
        over.addView(radioLine, LinearLayout.LayoutParams(-1, -2))
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
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            v.setPadding(dp(18), dp(18), dp(18), dp(18))
            row.addView(v, LinearLayout.LayoutParams(dp(80), dp(80)).apply { leftMargin = dp(20) })
        }
        // Without an image (styleTexts), this second space centres the texts between the source and the buttons
        lowerSpace = View(a)
        over.addView(lowerSpace, LinearLayout.LayoutParams(1, 0, 1f))
        over.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        frame.addView(over, FrameLayout.LayoutParams(-1, -1))
        info.addView(frame, LinearLayout.LayoutParams(0, -1, 1f))
        update(null)
    }

    /** Fade set on the image's left edge (aligned right): the edge colour extends there with no break. */
    /**
     * Image aligned right at the card's height, never cropped on the sides: its black bands at the
     * top and bottom are only removed as long as the enlarged image fits in the card's width
     * (its shape changes with the layout). The average colour of its left edge extends it, with
     * a fade set on that edge.
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

    /**
     * Panel outline only without an image (FM radio): under a cover, its line stayed visible
     * in the corners on the car (#36). Checked at each update, as the panel style
     * (FadeOutline) is set after the card is created.
     */
    private fun showOutline(show: Boolean) {
        val p = panel ?: return
        if (show && p.background == null) {
            p.background = FadeOutline(d, Themes.color(a, R.color.text_sub), app.mg4dash.system.Prefs(a).panelFade)
        } else if (!show && p.background != null) {
            p.background = null; p.elevation = 0f
        }
    }

    /**
     * Text and buttons on the cover (white, shadowed) or, in [bare], directly on the card's
     * panel, in the theme's colours, with larger text (radio without an image).
     */
    private fun styleTexts(bare: Boolean) {
        if (plain == bare) return
        plain = bare
        val (title, artist) = texts
        val main = if (bare) Themes.color(a, R.color.text_value) else Color.WHITE
        val sub = if (bare) Themes.color(a, R.color.text_sub) else 0xFFE6E6E6.toInt()
        val shadow = if (bare) 0 else 0x99000000.toInt()
        title.setTextColor(main); title.textSize = if (bare) 48f else 30f; title.maxLines = 1; title.setShadowLayer(8f, 0f, 2f, shadow)
        artist.setTextColor(sub); artist.textSize = if (bare) 26f else 22f; artist.maxLines = if (bare) 2 else 1; artist.setShadowLayer(8f, 0f, 2f, shadow)
        headTitle?.setTextColor(if (bare) Themes.color(a, R.color.text_title) else Color.WHITE)
        skips.forEach { it.imageTintList = ColorStateList.valueOf(main) }
        shade.visibility = if (bare) View.GONE else View.VISIBLE
        lowerSpace.visibility = if (bare) View.VISIBLE else View.GONE
        // Same gap between lines: without the font margin, which adds up mostly under the large title
        listOf(title, artist, radioLine).forEach { it.includeFontPadding = !bare }
        listOf(artist, radioLine).forEach { (it.layoutParams as LinearLayout.LayoutParams).topMargin = if (bare) dp(LINE_GAP_DP) else 0 }
        progress?.progressBackgroundTintList = ColorStateList.valueOf(if (bare) Themes.color(a, R.color.gauge_off) else 0x55FFFFFF)
    }

    /** FM or AM radio, after [update]: smaller, greyed frequency, then the RDS text line. */
    fun radioLines(analog: Boolean, text: String) {
        if (!::frame.isInitialized) return
        val bare = plain == true
        val artist = texts[1]
        val sub = if (bare) Themes.color(a, R.color.text_sub) else 0xFFE6E6E6.toInt()
        artist.textSize = if (bare) (if (analog) 21f else 26f) else (if (analog) 18f else 22f)
        artist.setTextColor(if (analog && bare) Themes.color(a, R.color.text_disabled) else sub)
        artist.visibility = if (analog && artist.text.isNullOrBlank()) View.GONE else View.VISIBLE
        radioLine.put(text)
        radioLine.textSize = if (bare) 26f else 22f
        radioLine.setTextColor(sub)
        radioLine.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * New cover: image, extension and vivid colour recomputed (once per image).
     * [radio]: radio source; without an image (FM, station with no logo), only the text and buttons.
     */
    fun update(source: Bitmap?, radio: Boolean = false) {
        if (!::frame.isInitialized) return
        // All black image (station with no cover): treated as no image
        val art = source?.takeIf { !isBlank(it) }
        // Without an image, whatever the source (or nothing playing): text on the panel and faded outline,
        // like FM radio (#36), instead of a solid background with a music note
        showOutline(art == null)
        if (art == null) {
            styleTexts(true)
            lastArt = null; shownFor = 0
            picture.setImageDrawable(null)
            frame.setBackgroundColor(Color.TRANSPARENT)
            fade.visibility = View.GONE
            accent = Themes.color(a, R.color.accent_action)
            paintAccent()
            return
        }
        val wasBare = plain == true
        styleTexts(false)
        if (art != null && art === lastArt && !wasBare) return
        lastArt = art
        val edge: Int
        val light = Themes.isLight(a)
        // Without an image: white or black background according to the theme, discreet music note; the source title
        // turns dark on white (the bottom stays darkened for title and artist)
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
        // Image cropped to the card's size (placeFade, also called again at each layout)
        shownFor = 0
        placeFade()
        paintAccent()
    }

    private var paintedAccent: Int? = null

    private fun paintAccent() {
        if (paintedAccent == accent) return
        paintedAccent = accent
        play.background = GradientDrawable().apply { cornerRadius = dp(38).toFloat(); setColor(accent) }
        progress?.progressTintList = ColorStateList.valueOf(accent)
    }

    companion object {
        private const val LINE_GAP_DP = 10

        fun apply(a: Activity, b: ActivityMainBinding): MediaStyle? = try {
            MediaStyle(a, b.mediaPlay, b.mediaProgress).apply {
                b.mediaAlbum.visibility = View.GONE
                buildImmersive(b.mediaCard, b.mediaCard.getChildAt(0), b.mediaInfo, b.mediaTitle, b.mediaArtist, b.mediaPrev, b.mediaNext)
            }
        } catch (e: Exception) { app.mg4dash.system.Log.w("Media", "player style: ${e.message}"); null }

        /** Simple mode: same Immersive player, without a progress bar. */
        fun immersive(a: Activity, card: LinearLayout, head: View, info: LinearLayout, title: TextView, artist: TextView,
                      prev: ImageView, play: ImageView, next: ImageView): MediaStyle? = try {
            MediaStyle(a, play, null).apply { buildImmersive(card, head, info, title, artist, prev, next) }
        } catch (e: Exception) { app.mg4dash.system.Log.w("Media", "simple player: ${e.message}"); null }

        /**
         * Image without its black (or almost black) bands at the top and bottom, as on 16:9
         * radio covers put in a square. At most 35 % removed from each side.
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
                return bright * 20 <= n   // at most 5 % of light pixels
            }
            val max = h * 35 / 100
            var top = 0; while (top < max && dark(top)) top++
            var bottom = 0; while (bottom < max && dark(h - 1 - bottom)) bottom++
            if (top + bottom < h / 20) return b
            // No more than what keeps the enlarged image within the frame's width (never cropped on the sides)
            val minH = ((w.toLong() * frameH + frameW - 1) / frameW).toInt()
            val newH = maxOf(h - top - bottom, minH)
            if (newH >= h) return b
            val cut = h - newH
            val cutTop = minOf(top, Math.round(cut * top / (top + bottom).toFloat()))
            return Bitmap.createBitmap(b, 0, cutTop, w, newH)
        }

        /** Almost uniformly black image. */
        fun isBlank(b: Bitmap): Boolean {
            val step = maxOf(1, b.width / 24)
            var bright = 0; var n = 0
            for (x in 0 until b.width step step) for (y in 0 until b.height step step) {
                val c = b.getPixel(x, y); n++
                if (Color.red(c) > 24 || Color.green(c) > 24 || Color.blue(c) > 24) bright++
            }
            return n > 0 && bright * 50 < n   // less than 2 % of non-black pixels
        }

        /** Average colour of the image's left band (extension when it does not have the card's shape). */
        fun edgeColor(b: Bitmap): Int {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0L
            val w = maxOf(1, b.width / 12)
            for (x in 0 until w step 2) for (y in 0 until b.height step 3) {
                val c = b.getPixel(x, y); r += Color.red(c); g += Color.green(c); bl += Color.blue(c); n++
            }
            if (n == 0L) return 0xFF2A3140.toInt()
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }

        /** Dominant vivid colour: the hue (in 30° slices) weighing most in saturation × brightness. */
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
            // Saturated and light enough to stand out on the darkened image
            return Color.HSVToColor(floatArrayOf(sum[k][0] / w[k], maxOf(0.65f, sum[k][1] / w[k]), maxOf(0.85f, sum[k][2] / w[k])))
        }
    }
}
