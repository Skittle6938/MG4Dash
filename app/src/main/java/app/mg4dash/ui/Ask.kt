package app.mg4dash.ui

import android.app.Activity
import android.util.TypedValue
import android.widget.TextView
import app.mg4dash.R

/** A text (or number) asked in a window, with the car's keyboard and a Paste button when the clipboard has text. */
object Ask {
    private fun dp(a: Activity, v: Int) = (v * a.resources.displayMetrics.density).toInt()

    fun show(a: Activity, title: String, initial: String, secret: Boolean, number: Boolean = false, onOk: (String) -> Unit) {
        val input = android.widget.EditText(a).apply {
            setText(initial); setSingleLine()
            setTextColor(Themes.color(a, R.color.text_value))
            inputType = if (number) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else android.text.InputType.TYPE_CLASS_TEXT or
                (if (secret) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_URI)
            setSelection(text.length)
        }
        val box = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(a, 24), dp(a, 8), dp(a, 24), 0); addView(input)
        }
        // Some keyboards (SWI68) have no "paste" (#63): dedicated button if the clipboard contains text
        val clip = (a.getSystemService(Activity.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(a)?.toString()?.trim()
        if (!clip.isNullOrEmpty()) box.addView(TextView(a).apply {
            text = a.getString(R.string.st_paste); setTextColor(Themes.color(a, R.color.accent_action)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setPadding(0, dp(a, 12), 0, dp(a, 4))
            setOnClickListener { input.setText(clip); input.setSelection(input.text.length) }
        })
        box.setPadding(0, 0, 0, 0)
        lateinit var d: android.app.AlertDialog
        fun ok() { d.dismiss(); onOk(input.text.toString()) }
        d = Windows.show(a, title, box, widthDp = 900, buttons = listOf(Rows.actionButton(a, a.getString(android.R.string.ok)) { ok() }))
        CarKeyboard.attachInDialog(input, d, box, onDone = { ok() })
    }
}
