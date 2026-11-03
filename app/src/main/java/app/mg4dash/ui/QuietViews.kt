package app.mg4dash.ui

import android.text.TextUtils
import android.widget.ImageView
import android.widget.TextView
import app.mg4dash.R

/*
 * Android redraws a view rewritten identically, and redoes the screen layout when its width follows
 * the text. Home rereads its values every second: without these guards, it redrew with nothing changing.
 */

/** Text, only if it changed (text styles are not compared). */
fun TextView.put(value: CharSequence?) {
    if (!TextUtils.equals(text, value ?: "")) text = value
}

/** Image, only if it changed; to use for all writes to this view. */
fun ImageView.putImage(res: Int) {
    if (getTag(R.id.tag_shown_image) == res) return
    setTag(R.id.tag_shown_image, res)
    setImageResource(res)
}

/** Tint, only if it changed; to use for all writes to this view. */
fun ImageView.putTint(color: Int) {
    if (getTag(R.id.tag_shown_tint) == color) return
    setTag(R.id.tag_shown_tint, color)
    setColorFilter(color)
}
