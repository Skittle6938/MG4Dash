package app.mg4dash.ui

import android.content.Context
import app.mg4dash.R
import app.mg4dash.energy.TripLog
import app.mg4dash.system.Units
import java.util.Locale

/** The texts of the trips (#102), shared by the Trips card, the Trips page and the Vehicle page's line. */
object TripText {
    /** "13.2 km", to the tenth. */
    fun dist(c: Context, km: Float) = Units.distText(c, km, 1)

    /** "18,344 km", the odometer. */
    fun odo(c: Context, km: Int) = String.format(Locale.getDefault(), "%,d %s", Math.round(Units.dist(c, km.toFloat())), Units.distUnit(c))

    /** "18,331 → 18,344 km". */
    fun odoRange(c: Context, from: Int, to: Int) =
        String.format(Locale.getDefault(), "%,d → %s", Math.round(Units.dist(c, from.toFloat())), odo(c, to))

    fun duration(c: Context, minutes: Long) = c.getString(R.string.tr_duration, minutes / 60, minutes % 60)

    fun time(c: Context, at: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(at))

    fun span(c: Context, t: TripLog.Trip) = "${time(c, t.start)} – ${time(c, t.end)}"

    /** "15.9 kWh/100 km", or "—" below half a km. */
    fun cons(c: Context, per100: Float?) = per100?.let { "${Units.consText(c, it)} ${Units.consUnit(c)}" } ?: "—"

    /** Short, without the unit: on the card's rows. */
    fun consShort(c: Context, per100: Float?) = per100?.let { Units.consText(c, it) } ?: "—"

    /** "0,52 €": an amount in the system's currency (#102), or "—" when unknown. */
    fun money(v: Float?): String = v?.let { m ->
        val loc = Locale.getDefault()
        java.text.NumberFormat.getCurrencyInstance(loc).apply {
            // A language without its country has no currency (¤): the euro, as on the cars sold in Europe
            if (loc.country.isEmpty()) currency = java.util.Currency.getInstance("EUR")
        }.format(m.toDouble())
    } ?: "—"

    /** The kWh price set, or null (no cost shown anywhere). */
    fun price(c: Context): Float? = app.mg4dash.system.Prefs(c).kwhPrice.takeIf { it > 0f }

    fun count(c: Context, n: Int) = if (n <= 1) c.getString(R.string.tr_one) else c.getString(R.string.tr_many, n)

    /** Today, yesterday, or the day (Sat 7 Nov). */
    fun day(c: Context, at: Long, now: Long): String {
        val today = TripLog.dayStart(now)
        return when {
            at >= today -> c.getString(R.string.tr_today)
            at >= TripLog.dayStart(today - 1) -> c.getString(R.string.tr_yesterday)
            else -> java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(java.util.Date(at))
        }
    }
}
