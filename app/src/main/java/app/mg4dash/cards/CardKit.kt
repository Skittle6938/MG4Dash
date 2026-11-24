package app.mg4dash.cards

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.ui.Themes

/**
 * The cards' common look: margins, title, texts, divider, button and the "no data" message, in the theme's colours
 * only (light and dark, the accent chosen, Grand affichage's bigger text). Sizes in sp / dp of the normal display.
 *
 * Colours ([Themes.color]): R.color.text_value (values, main text), text_sub (labels, secondary), text_title (card
 * title), panel_border (dividers, outlines), button_idle (button background), accent_action (the accent).
 */
object CardKit {
    /** Inner margins of a card: 20 dp on the sides, 16 dp at the top and the bottom. */
    const val PAD_H = 20
    const val PAD_V = 16

    /** Text sizes (sp): card title, big value, main line, secondary line. */
    const val TITLE_SP = 17f
    const val BIG_SP = 44f
    const val BODY_SP = 22f
    const val SUB_SP = 18f

    private fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    /** The standard inner margins on [v]. */
    fun pad(v: View) = v.setPadding(dp(v.context, PAD_H), dp(v.context, PAD_V), dp(v.context, PAD_H), dp(v.context, PAD_V))

    /** The card's title, small capitals spaced out ("TRIPS"). */
    fun title(c: Context, text: String) = TextView(c).apply {
        this.text = text; setTextColor(Themes.color(c, R.color.text_title))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP); letterSpacing = 0.12f; maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** A line of text, one line high; [value]: the light font of the big numbers. */
    fun text(c: Context, sp: Float, colorRes: Int = R.color.text_value, value: Boolean = false) = TextView(c).apply {
        setTextColor(Themes.color(c, colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); maxLines = 1
        includeFontPadding = false
        if (value) typeface = Themes.valueFont(c)
    }

    /** A label on the left and its value on the right, on one line. */
    fun row(left: TextView, right: TextView) = LinearLayout(left.context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(right.apply { gravity = Gravity.END })
    }

    /** A thin line between two parts of a card (1 px high). */
    fun divider(c: Context) = View(c).apply { setBackgroundColor(Themes.color(c, R.color.panel_border)) }

    /** A round button's background; [on]: outlined in the text's colour (something running). */
    fun buttonBackground(c: Context, on: Boolean = false) = GradientDrawable().apply {
        val d = c.resources.displayMetrics.density
        cornerRadius = (24 * d).toInt().toFloat(); setColor(Themes.color(c, R.color.button_idle))
        setStroke((1.5f * d).toInt().coerceAtLeast(1), Themes.color(c, if (on) R.color.text_value else R.color.panel_border))
    }

    /** "No data yet" (or [text]): centred in the card, instead of an empty card. */
    fun empty(c: Context, text: String = c.getString(R.string.card_no_data)) = TextView(c).apply {
        this.text = text; gravity = Gravity.CENTER; setTextColor(Themes.color(c, R.color.text_sub))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SUB_SP + 1)
    }
}
