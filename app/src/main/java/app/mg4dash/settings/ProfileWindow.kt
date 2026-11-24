package app.mg4dash.settings

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Profiles
import app.mg4dash.ui.Themes

/**
 * Profiles in Settings (study v4, step 10): the "Profile: Main ▾" menu of the banner, on every tab: a profile to switch
 * to, New profile, Edit the profile, Delete the profile; and the window to name a profile, start it from this profile or
 * from the default settings, and pair it with a phone.
 */
object ProfileWindow {
    /** The banner's button, its name following the current profile. */
    fun button(activity: Activity, onSwitched: () -> Unit): TextView = TextView(activity).apply {
        val d = activity.resources.displayMetrics.density
        text = activity.getString(R.string.pf_menu, Profiles.current(activity).name) + "  ▾"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, app.mg4dash.ui.Rows.BUTTON_SP); gravity = Gravity.CENTER; maxLines = 1
        setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.chip_text))
        setBackgroundResource(R.drawable.bg_chip); setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
        setOnClickListener { menu(activity, it, onSwitched) }
    }

    /**
     * The profiles' window (24/11): a row per profile (the current one marked), new / edit / delete, and for every
     * profile the drive mode kept by each one, a switch right here.
     */
    fun menu(activity: Activity, @Suppress("UNUSED_PARAMETER") anchor: View, onSwitched: () -> Unit) {
        val list = Profiles.list(activity)
        val cur = list.indexOfFirst { it.id == Profiles.current(activity).id }
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        lateinit var dlg: android.app.AlertDialog
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val profiles = app.mg4dash.ui.Rows.group(activity)
        list.forEachIndexed { i, p ->
            val mark = TextView(activity).apply { text = if (i == cur) "✓" else ""; setTextColor(Themes.select(activity)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }
            app.mg4dash.ui.Rows.add(profiles, app.mg4dash.ui.Rows.row(activity, p.name, phoneName(p), mark) {
                dlg.dismiss(); if (i != cur && Profiles.switchTo(activity, p.id, "Settings")) onSwitched()
            })
        }
        fun action(title: String, run: () -> Unit) = app.mg4dash.ui.Rows.add(profiles, app.mg4dash.ui.Rows.row(activity, title, null, app.mg4dash.ui.Rows.chevron(activity)) { dlg.dismiss(); run() })
        action("+  " + activity.getString(R.string.pf_new)) { if (list.size >= Profiles.MAX) app.mg4dash.ui.Toasts.show(activity, R.string.pf_refused_full) else edit(activity, null, onSwitched) }
        action(activity.getString(R.string.pf_edit)) { edit(activity, Profiles.current(activity), onSwitched) }
        action(activity.getString(R.string.pf_delete)) {
            val p = Profiles.current(activity)
            if (list.size <= 1) app.mg4dash.ui.Toasts.show(activity, R.string.pf_refused_last)
            else app.mg4dash.ui.Windows.confirm(activity, activity.getString(R.string.pf_delete), activity.getString(R.string.pf_delete_confirm, p.name), activity.getString(R.string.pf_delete)) {
                Profiles.delete(activity, p.id); onSwitched()
            }
        }
        box.addView(profiles, LinearLayout.LayoutParams(-1, -2))
        box.addView(app.mg4dash.ui.Rows.header(activity, activity.getString(R.string.pf_all)))
        val prefs = app.mg4dash.system.Prefs(activity)
        val drive = app.mg4dash.ui.Rows.group(activity)
        val sw = app.mg4dash.ui.SwitchView(activity).apply { on = prefs.profilesDrive }
        app.mg4dash.ui.Rows.add(drive, app.mg4dash.ui.Rows.row(activity, activity.getString(R.string.dv_profiles_drive), activity.getString(R.string.dv_profiles_drive_desc), sw) {
            prefs.profilesDrive = !prefs.profilesDrive; sw.on = prefs.profilesDrive
        })
        box.addView(drive, LinearLayout.LayoutParams(-1, -2))
        dlg = app.mg4dash.ui.Windows.show(activity, activity.getString(R.string.pf_title), box, widthDp = 900)
    }

    /** The phone that switches to [p], if any, for its row. */
    private fun phoneName(p: Profiles.Profile): String? = p.phone?.let { ph -> pairedPhones().firstOrNull { it.first == ph }?.second ?: ph }

    /** Phones paired with the head unit: (address, name). */
    internal fun pairedPhones(): List<Pair<String, String>> = try {
        android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.map { it.address to (it.name ?: it.address) }?.sortedBy { it.second } ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** The window: [profile] edited, or a new one (null), switched to once created. */
    fun edit(activity: Activity, profile: Profiles.Profile?, onSwitched: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun label(res: Int) = TextView(activity).apply {
            text = activity.getString(res).uppercase(); letterSpacing = 0.1f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(Themes.color(activity, R.color.text_title)); setPadding(0, dp(16), 0, dp(8))
        }
        fun chip(text: String, onClick: () -> Unit) = TextView(activity).apply {
            this.text = text; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(androidx.core.content.ContextCompat.getColorStateList(activity, R.color.chip_text))
            setBackgroundResource(R.drawable.bg_chip); setPadding(dp(18), dp(8), dp(18), dp(8)); minHeight = dp(56); setOnClickListener { onClick() }
        }
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(28), dp(4), dp(28), 0) }
        box.addView(label(R.string.pf_name))
        val name = EditText(activity).apply {
            setText(profile?.name ?: ""); setSingleLine(); setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            filters = arrayOf(android.text.InputFilter.LengthFilter(Profiles.NAME_MAX))
            setTextColor(Themes.color(activity, R.color.text_value))
        }
        box.addView(name, LinearLayout.LayoutParams(-1, dp(60)))
        var fromCurrent = true
        if (profile == null) {
            box.addView(label(R.string.pf_start))
            val row = LinearLayout(activity)
            lateinit var a: TextView; lateinit var b: TextView
            fun paint() { a.isSelected = fromCurrent; b.isSelected = !fromCurrent }
            a = chip(activity.getString(R.string.pf_from_current)) { fromCurrent = true; paint() }
            b = chip(activity.getString(R.string.pf_from_defaults)) { fromCurrent = false; paint() }
            row.addView(a, LinearLayout.LayoutParams(0, -1, 1f)); row.addView(b, LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(10) })
            paint(); box.addView(row)
        }
        box.addView(label(R.string.pf_phone))
        val phones = pairedPhones()
        var phone = profile?.phone
        fun phoneText() = phone?.let { p -> phones.firstOrNull { it.first == p }?.second ?: p } ?: activity.getString(R.string.pf_phone_none)
        val phoneBtn = chip(phoneText() + "  ▾") {}
        phoneBtn.setOnClickListener { v ->
            val labels = listOf(activity.getString(R.string.pf_phone_none)) + phones.map { it.second }
            app.mg4dash.ui.SourceMenu.choose(activity, v, labels, phones.indexOfFirst { it.first == phone } + 1) { i ->
                phone = if (i == 0) null else phones[i - 1].first; phoneBtn.text = phoneText() + "  ▾"
            }
        }
        box.addView(phoneBtn, LinearLayout.LayoutParams(-1, dp(56)))
        box.addView(TextView(activity).apply {
            text = activity.getString(R.string.pf_phone_desc); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Themes.color(activity, R.color.text_title)); setPadding(0, dp(6), 0, 0)
        })
        // The drive mode by profile: in the driving card's options (24/11), where the mode and regeneration are changed
        val error = TextView(activity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Themes.color(activity, R.color.accent_heat)); setPadding(0, dp(8), 0, 0) }
        box.addView(error)
        lateinit var dialog: android.app.AlertDialog
        fun save() {
            val n = name.text.toString()
            val refused = if (profile == null) Profiles.create(activity, n, fromCurrent, phone).let { (p, r) ->
                if (p != null) Profiles.switchTo(activity, p.id, "created"); r
            } else Profiles.update(activity, profile.id, n, phone)
            if (refused != null) { error.text = activity.getString(refusalText(refused)); return }
            dialog.dismiss(); onSwitched()
        }
        box.setPadding(0, 0, 0, 0)
        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; addView(android.widget.ScrollView(activity).apply { addView(box) }, LinearLayout.LayoutParams(-1, -2)) }
        dialog = app.mg4dash.ui.Windows.show(activity, activity.getString(if (profile == null) R.string.pf_new else R.string.pf_edit), holder, widthDp = 900,
            buttons = listOf(app.mg4dash.ui.Rows.actionButton(activity, activity.getString(R.string.pf_save)) { save() }))
        app.mg4dash.ui.CarKeyboard.attachInDialog(name, dialog, box, onDone = { save() })
    }

    fun refusalText(r: Profiles.Refusal): Int = when (r) {
        Profiles.Refusal.EMPTY -> R.string.pf_refused_empty
        Profiles.Refusal.TOO_LONG -> R.string.pf_refused_long
        Profiles.Refusal.TAKEN -> R.string.pf_refused_taken
        Profiles.Refusal.FULL -> R.string.pf_refused_full
        Profiles.Refusal.LAST -> R.string.pf_refused_last
    }
}
