package app.mg4dash.shortcuts

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.mg4dash.R
import app.mg4dash.pages.PhoneActivity
import app.mg4dash.system.Prefs
import kotlin.concurrent.thread

/**
 * Contacts favoris du widget Téléphone (2, 3, 4, 6 ou 8 ; 3 par défaut), choisis dans le répertoire du
 * téléphone. Les cases au-delà du nombre affiché gardent leur contact pour le jour où on le remonte.
 * Nom et numéro sont gardés dans les préférences : ils restent appelables même quand le
 * téléphone est déconnecté (PBAP efface alors le répertoire de la voiture). Le dernier
 * répertoire lu est aussi gardé, pour pouvoir choisir un contact sans attendre la synchro.
 */
object PhoneFavorites {
    /** Nombres proposés : 2 et 3 sur une ligne, 4, 6 et 8 sur deux lignes. */
    val COUNTS = listOf(2, 3, 4, 6, 8)

    fun count(prefs: Prefs): Int = prefs.raw.getInt("fav_phone_count", 3).takeIf { it in COUNTS } ?: 3

    /** Colonnes de la grille pour [n] raccourcis (une ligne jusqu'à 3, deux lignes au-delà). */
    fun columns(n: Int): Int = if (n <= 3) n else n / 2
    private const val SEP = '\u001F'
    private const val CACHE = "phone_book_cache"
    private val main = Handler(Looper.getMainLooper())

    data class Fav(val name: String, val number: String)

    fun get(prefs: Prefs, i: Int): Fav? =
        prefs.raw.getString("fav_phone_$i", null)?.split(SEP)?.takeIf { it.size == 2 }?.let { Fav(it[0], it[1]) }

    private fun set(prefs: Prefs, i: Int, f: Fav?) {
        prefs.raw.edit().apply { if (f == null) remove("fav_phone_$i") else putString("fav_phone_$i", f.name + SEP + f.number) }.apply()
    }

    /** Mémorise le répertoire lu (appelé dès qu'un répertoire non vide est lu). */
    fun remember(prefs: Prefs, book: List<PhoneActivity.Entry>) {
        if (book.isEmpty()) return
        prefs.raw.edit().putString(CACHE, book.joinToString("\n") { it.name + SEP + it.number + SEP + it.detail }).apply()
    }

    private fun remembered(prefs: Prefs): List<PhoneActivity.Entry> =
        prefs.raw.getString(CACHE, null)?.lines()?.mapNotNull { l ->
            l.split(SEP).takeIf { it.size == 3 }?.let { PhoneActivity.Entry(it[0], it[1], it[2]) }
        } ?: emptyList()

    /** Choix pour une case : un contact du répertoire, ou vider la case. */
    fun edit(activity: Activity, i: Int, refresh: () -> Unit) {
        val prefs = Prefs(activity)
        val choices = arrayOf(activity.getString(R.string.sw_fav_pick), activity.getString(R.string.sc_clear),
            activity.getString(R.string.sw_fav_count))
        AlertDialog.Builder(activity)
            .setTitle(R.string.sw_phone)
            .setItems(choices) { _, w ->
                when (w) {
                    0 -> pick(activity) { e -> set(prefs, i, Fav(e.name, e.number)); refresh() }
                    1 -> { set(prefs, i, null); refresh() }
                    2 -> chooseCount(activity, refresh)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    /**
     * Nombre de raccourcis : un aperçu de chaque disposition (cases en miniature) avec son nombre ;
     * toucher une disposition l'applique. Les contacts déjà choisis sont gardés.
     */
    fun chooseCount(activity: Activity, refresh: () -> Unit) {
        val prefs = Prefs(activity)
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val current = count(prefs)
        var dialog: AlertDialog? = null
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        val accent = app.mg4dash.ui.Themes.color(activity, R.color.accent_action)
        val dim = app.mg4dash.ui.Themes.color(activity, R.color.text_title)
        for (n in COUNTS) {
            val sel = n == current
            val tile = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                setPadding(dp(12), dp(12), dp(12), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setStroke(dp(if (sel) 3 else 1), if (sel) accent else dim and 0x55FFFFFF)
                }
                isClickable = true
                setOnClickListener {
                    prefs.raw.edit().putInt("fav_phone_count", n).apply()
                    app.mg4dash.system.Log.i("Phone", "phone shortcuts: $n")
                    dialog?.dismiss(); refresh()
                }
            }
            // Aperçu : la grille de la disposition, cases arrondies
            val cols = columns(n); val rows = if (n <= 3) 1 else 2
            val grid = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            for (r in 0 until rows) {
                val line = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
                for (c in 0 until cols) line.addView(android.view.View(activity).apply {
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(4).toFloat(); setColor(if (sel) accent else dim)
                    }
                }, android.widget.LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                grid.addView(line, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
            }
            tile.addView(grid, android.widget.LinearLayout.LayoutParams(dp(112), dp(56)))
            tile.addView(android.widget.TextView(activity).apply {
                text = n.toString(); gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(if (sel) accent else app.mg4dash.ui.Themes.color(activity, R.color.text_value))
            }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            row.addView(tile, android.widget.LinearLayout.LayoutParams(-2, -2).apply { setMargins(dp(8), 0, dp(8), 0) })
        }
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.sw_fav_count)
            .setView(row)
            .setNegativeButton(R.string.cancel, null)
            .show().also { app.mg4dash.ui.Dialogs.fix(it) }
    }

    /**
     * Relance la synchro du répertoire, puis affiche la liste avec recherche : le répertoire de la
     * voiture s'il est rempli, sinon le dernier répertoire mémorisé ; si les deux sont vides, on
     * attend la synchro (jusqu'à 15 s).
     */
    private fun pick(activity: Activity, onPick: (PhoneActivity.Entry) -> Unit) {
        val prefs = Prefs(activity)
        PhoneActivity.sync(activity, manual = false)
        thread {
            var book = PhoneActivity.contacts(activity)
            if (book.isEmpty()) book = remembered(prefs)
            if (book.isEmpty()) {
                main.post { app.mg4dash.ui.Toasts.show(activity, R.string.ph_sync_started, long = true) }
                for (n in 0 until 15) {
                    Thread.sleep(1000)
                    book = PhoneActivity.contacts(activity)
                    if (book.isNotEmpty()) break
                }
            }
            remember(prefs, book)
            main.post {
                if (activity.isFinishing) return@post
                if (book.isEmpty()) { app.mg4dash.ui.Toasts.show(activity, R.string.ph_no_contacts, long = true); return@post }
                val labels = book.map { "${it.name}  ·  ${it.number}" }
                Shortcuts.searchList(activity, activity.getString(R.string.ph_contacts), labels) { w -> onPick(book[w]) }
            }
        }
    }
}
