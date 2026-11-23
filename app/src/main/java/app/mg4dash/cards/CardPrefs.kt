package app.mg4dash.cards

import android.content.Context
import app.mg4dash.system.Prefs
import app.mg4dash.ui.HomeSlots

/**
 * A card's own settings, stored as "card_<id>_<name>" in MG4Dash's settings: they follow the profile and go in the
 * settings backup by themselves (prefix "card_" in Profiles.PERSONAL_PREFIXES). The cards that existed before keep
 * their old keys (nothing lost for the drivers); only the new ones use this.
 */
class CardPrefs(context: Context, card: HomeSlots.Card) {
    private val sp = Prefs(context).raw
    private val prefix = "card_${card.id}_"

    fun key(name: String) = prefix + name

    fun bool(name: String, default: Boolean) = sp.getBoolean(key(name), default)
    fun setBool(name: String, v: Boolean) = sp.edit().putBoolean(key(name), v).apply()

    fun int(name: String, default: Int) = sp.getInt(key(name), default)
    fun setInt(name: String, v: Int) = sp.edit().putInt(key(name), v).apply()

    fun string(name: String, default: String?) = sp.getString(key(name), default)
    fun setString(name: String, v: String?) = sp.edit().putString(key(name), v).apply()
}
