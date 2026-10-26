package app.mg4dash.system

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Heure affichée par MG4Dash : 24 h (par défaut) ou 12 h sans AM / PM (réglage Apparence, #18). */
object Clock {
    fun hm(context: Context, date: Date = Date()): String =
        SimpleDateFormat(if (Prefs(context).clock12) "h:mm" else "HH:mm", Locale.getDefault()).format(date)

    /** Heure de la prévision météo (« 14h » ou « 14:00 ») passée en 12 h sans AM / PM si le réglage le demande. */
    fun hourLabel(context: Context, label: String): String {
        if (!Prefs(context).clock12) return label
        val m = Regex("^([0-9]{1,2})(h|:00)$").find(label) ?: return label
        val h = m.groupValues[1].toInt() % 12
        return (if (h == 0) 12 else h).toString() + m.groupValues[2]
    }
}
