package app.mg4dash.system

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Heure affichée par MG4Dash : 24 h (par défaut) ou 12 h sans AM / PM (réglage Apparence, #18). */
object Clock {
    fun hm(context: Context, date: Date = Date()): String =
        SimpleDateFormat(if (Prefs(context).clock12) "h:mm" else "HH:mm", Locale.getDefault()).format(date)
}
