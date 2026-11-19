package app.mg4dash.system

import android.app.Activity
import android.os.Bundle
import app.mg4dash.R

/**
 * The only MG4Dash screen in maintenance mode (19/11): the offer to become the home again, nothing else. Opened when
 * any other MG4Dash screen is asked for meanwhile (MG4Link's "Open MG4Dash", a Radio Dash shortcut).
 */
class MaintenanceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        app.mg4dash.ui.Themes.apply(this)
        super.onCreate(savedInstanceState)
        if (!Maintenance.on(this)) { finish(); return }
        val d = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.st_valet)
            .setMessage(R.string.valet_back_msg)
            .setPositiveButton(R.string.valet_back_btn) { _, _ -> Maintenance.backToDash(this) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnDismissListener { finish() }
        app.mg4dash.ui.Dialogs.show(d); app.mg4dash.ui.Dialogs.fix(d)
    }
}
