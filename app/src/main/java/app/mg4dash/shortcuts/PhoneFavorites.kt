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
 * Trois contacts favoris du widget Téléphone, choisis dans le répertoire du téléphone.
 * Nom et numéro sont gardés dans les préférences : ils restent appelables même quand le
 * téléphone est déconnecté (PBAP efface alors le répertoire de la voiture). Le dernier
 * répertoire lu est aussi gardé, pour pouvoir choisir un contact sans attendre la synchro.
 */
object PhoneFavorites {
    const val COUNT = 3
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
        val choices = arrayOf(activity.getString(R.string.sw_fav_pick), activity.getString(R.string.sc_clear))
        AlertDialog.Builder(activity)
            .setTitle(R.string.sw_phone)
            .setItems(choices) { _, w ->
                when (w) {
                    0 -> pick(activity) { e -> set(prefs, i, Fav(e.name, e.number)); refresh() }
                    1 -> { set(prefs, i, null); refresh() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
