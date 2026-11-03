package app.mg4dash.system

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Time shown by MG4Dash: 24 h (default) or 12 h without AM / PM (Appearance setting, #18). */
object Clock {
    fun hm(context: Context, date: Date = Date()): String =
        SimpleDateFormat(if (Prefs(context).clock12) "h:mm" else "HH:mm", Locale.getDefault()).format(date)

    /** Weather forecast time ("14h" or "14:00") changed to 12 h without AM / PM if the setting asks for it. */
    fun hourLabel(context: Context, label: String): String {
        if (!Prefs(context).clock12) return label
        val m = Regex("^([0-9]{1,2})(h|:00)$").find(label) ?: return label
        val h = m.groupValues[1].toInt() % 12
        return (if (h == 0) 12 else h).toString() + m.groupValues[2]
    }
}
