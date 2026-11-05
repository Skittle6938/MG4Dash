package app.mg4dash.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.R
import app.mg4dash.system.Prefs

/**
 * MG4Dash's own keyboard (#93): full width, large keys, the theme's colours. Android's keyboard does not fill
 * this screen. It only serves MG4Dash's text fields, through [attach]. Keys show capitals and type lower case
 * letters unless shift is on; a long press on a letter offers its accents in the top row.
 */
class CarKeyboard(
    context: Context,
    language: String,
    private val numeric: Boolean = false,
    private val onText: (String) -> Unit = {},
    private val onBackspace: () -> Unit = {},
    private val onClear: () -> Unit = {},
    private val onDone: () -> Unit = {},
) : LinearLayout(context) {
    private enum class Shift { OFF, ONCE, LOCK }

    private val letters = lettersFor(language)
    private var symbols = false
    private var shift = Shift.OFF
    private var accentsOf: Char? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        // Opaque under the translucent panel: the page must not show through the keys
        background = LayerDrawable(arrayOf(
            GradientDrawable().apply { setColor(Themes.color(context, R.color.bg_bottom)); cornerRadius = resources.getDimension(R.dimen.radius_panel) },
            ContextCompat.getDrawable(context, R.drawable.bg_panel)!!))
        setPadding(dp(12), dp(12), dp(12), dp(4))
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        when {
            numeric -> {
                addRow { "1234567890".forEach { char(it) } }
                addRow { char('-'); char('.'); backspace(1f); done(1f) }
            }
            symbols -> {
                addRow { "1234567890".forEach { char(it) } }
                addRow { "-/:;()€&@\"".forEach { char(it) } }
                addRow { "%_+=?!*#'".forEach { char(it) }; backspace(1.5f) }
                bottomRow("ABC")
            }
            else -> {
                addRow { accentsOf?.let { accentKeys(it) } ?: letters[0].forEach { letter(it) } }
                addRow { letters[1].forEach { letter(it) } }
                addRow { shiftKey(); letters[2].forEach { letter(it) }; backspace(1.5f) }
                bottomRow("123")
            }
        }
    }

    private fun bottomRow(layerLabel: String) = addRow {
        key(layerLabel, 1.5f, sp = 22f) { symbols = !symbols; accentsOf = null; rebuild() }
        char(',')
        key("", 6f) { onText(" ") }
        char('.')
        done(1.5f)
    }

    private fun addRow(fill: LinearLayout.() -> Unit) {
        addView(LinearLayout(context).apply { orientation = HORIZONTAL; fill() }, LayoutParams(-1, dp(KEY_DP)).apply { bottomMargin = dp(GAP_DP) })
    }

    private fun LinearLayout.char(c: Char) = key(c.toString(), 1f) { onText(c.toString()) }

    private fun LinearLayout.letter(c: Char) {
        val v = key(c.uppercaseChar().toString(), 1f) { type(c) }
        if (accents(c).isNotEmpty()) v.setOnLongClickListener { accentsOf = c; rebuild(); true }
    }

    /** The row of a long pressed letter: its accents, then a key to go back to the letters. */
    private fun LinearLayout.accentKeys(c: Char) {
        val choices = accents(c)
        choices.forEach { a -> key(a.uppercase(), 1f) { accentsOf = null; type(a); rebuild() } }
        repeat(ROW_KEYS - 1 - choices.length) { addKey(View(context).apply { isClickable = true }, 1f).background = null }
        key("✕", 1f) { accentsOf = null; rebuild() }
    }

    private fun type(c: Char) {
        onText(if (shift == Shift.OFF) c.toString() else c.uppercase())
        if (shift == Shift.ONCE) { shift = Shift.OFF; rebuild() }
    }

    private fun LinearLayout.shiftKey() {
        val on = shift != Shift.OFF
        key(if (shift == Shift.LOCK) "⇪" else "⇧", 1.5f, sp = 32f, accent = on) {
            shift = if (shift == Shift.OFF) Shift.ONCE else Shift.OFF
            rebuild()
        }.setOnLongClickListener { shift = Shift.LOCK; rebuild(); true }
    }

    private fun LinearLayout.backspace(weight: Float) {
        addKey(ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace); scaleType = ImageView.ScaleType.CENTER
            setColorFilter(Themes.color(context, R.color.text_value))
            contentDescription = "⌫"
            setOnClickListener { onBackspace() }
            // As on the phone page's keypad
            setOnLongClickListener { onClear(); true }
        }, weight)
    }

    private fun LinearLayout.done(weight: Float) = key("OK", weight, sp = 22f, accent = true) { onDone() }

    private fun LinearLayout.key(label: String, weight: Float, sp: Float = 26f, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label; gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Themes.color(context, if (accent) R.color.accent_action else R.color.text_value))
            setOnClickListener { onClick() }
        }.also { addKey(it, weight) }

    private fun <T : View> LinearLayout.addKey(v: T, weight: Float): T {
        v.setBackgroundResource(R.drawable.bg_button)
        addView(v, LayoutParams(0, -1, weight).apply { if (childCount > 0) leftMargin = dp(GAP_DP) })
        return v
    }

    companion object {
        private const val KEY_DP = 66
        private const val GAP_DP = 8
        private const val ROW_KEYS = 10
        /** Height of the letters layer: four rows and the padding. */
        const val HEIGHT_DP = 4 * (KEY_DP + GAP_DP) + 16

        internal fun lettersFor(language: String): List<String> = when (language) {
            "fr" -> listOf("azertyuiop", "qsdfghjklm", "wxcvbn'")
            "de" -> listOf("qwertzuiop", "asdfghjkl", "yxcvbnm")
            else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        }

        internal fun accents(c: Char): String = when (c) {
            'a' -> "àâäáãæ"
            'c' -> "çć"
            'e' -> "éèêëę"
            'g' -> "ğ"
            'i' -> "îïíìı"
            'n' -> "ñ"
            'o' -> "ôöóòõœ"
            's' -> "ßş"
            'u' -> "ùûüú"
            'y' -> "ÿ"
            else -> ""
        }

        /** Replaces Android's keyboard on a page's field, if the option is on (the default). [onVisible] lets the page make room. */
        fun attach(field: EditText, onVisible: (Boolean) -> Unit = {}) {
            if (!Prefs(field.context).ownKeyboard) return
            field.showSoftInputOnFocus = false
            val popup = Popup(field, onVisible)
            field.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) field.post { popup.show() }; false }
        }

        /**
         * Same for a dialog's field. A popup does not sit where asked over a dialog, so the keyboard goes inside
         * the dialog, which takes the screen's width: two thirds of [box] on the driver's side, its content in the rest.
         * Call it once the dialog is shown. OK runs [onDone], or only closes the keyboard without it.
         */
        fun attachInDialog(field: EditText, dialog: Dialog, box: LinearLayout, onDone: (() -> Unit)? = null) {
            val context = field.context
            if (!Prefs(context).ownKeyboard) return
            field.showSoftInputOnFocus = false
            var keyboard: CarKeyboard? = null
            fun visible(on: Boolean) { keyboard?.visibility = if (on) View.VISIBLE else View.GONE }
            keyboard = forField(field) { if (onDone != null) onDone() else visible(false) }
            val content = LinearLayout(context).apply { orientation = VERTICAL }
            (0 until box.childCount).map { box.getChildAt(it) }.also { box.removeAllViews() }.forEach { content.addView(it) }
            box.orientation = HORIZONTAL
            val gap = (16 * context.resources.displayMetrics.density).toInt()
            val keyboardParams = LayoutParams(0, -2, 2f).apply { gravity = Gravity.BOTTOM }
            if (Dock.rightSide(context)) {
                box.addView(content, LayoutParams(0, -1, 1f).apply { rightMargin = gap })
                box.addView(keyboard, keyboardParams)
            } else {
                box.addView(keyboard, keyboardParams)
                box.addView(content, LayoutParams(0, -1, 1f).apply { leftMargin = gap })
            }
            field.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) visible(true); false }
            dialog.window?.setLayout(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            // The keyboard needs the height
            Dialogs.hideBars(dialog)
            field.requestFocus()
            visible(true)
        }

        private fun forField(field: EditText, onDone: () -> Unit) = CarKeyboard(
            field.context, field.context.resources.configuration.locales[0].language,
            numeric = field.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER,
            onText = { s ->
                val start = maxOf(field.selectionStart, 0); val end = maxOf(field.selectionEnd, 0)
                field.text.replace(minOf(start, end), maxOf(start, end), s)
            },
            onBackspace = {
                val start = maxOf(field.selectionStart, 0); val end = maxOf(field.selectionEnd, 0)
                if (start != end) field.text.delete(minOf(start, end), maxOf(start, end))
                else if (start > 0) field.text.delete(start - 1, start)
            },
            onClear = { field.text.clear() },
            onDone = onDone,
        )
    }

    private class Popup(private val field: EditText, private val onVisible: (Boolean) -> Unit) {
        private val context = field.context
        private var window: PopupWindow? = null
        private val focusWatch = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (!focused) hide() }
        private val detachWatch = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { hide() }
        }

        fun show() {
            if (window != null || !field.isAttachedToWindow) return
            val density = context.resources.displayMetrics.density
            // The real width: the one Android gives apps is narrower than this screen
            val real = android.util.DisplayMetrics()
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(real)
            val end = real.widthPixels * 2 / 3
            // Two thirds of the screen on the driver's side, along the vertical bar or from the screen's margin
            val side = (Dock.keyboardMarginDp(context) * density).toInt()
            val right = Dock.rightSide(context)
            val keyboard = forField(field) { hide() }
            window = PopupWindow(keyboard, end - side, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                isFocusable = false; isOutsideTouchable = false; isClippingEnabled = false
                setBackgroundDrawable(ColorDrawable(0))
                showAtLocation(field, Gravity.BOTTOM or Gravity.LEFT, if (right) real.widthPixels - end else side, (10 * density).toInt())
            }
            field.viewTreeObserver.addOnWindowFocusChangeListener(focusWatch)
            field.addOnAttachStateChangeListener(detachWatch)
            field.requestFocus()
            onVisible(true)
        }

        fun hide() {
            val w = window ?: return
            window = null
            if (field.viewTreeObserver.isAlive) field.viewTreeObserver.removeOnWindowFocusChangeListener(focusWatch)
            field.removeOnAttachStateChangeListener(detachWatch)
            w.dismiss()
            onVisible(false)
        }
    }
}
