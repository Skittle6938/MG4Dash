package app.mg4dash.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.AirlinkUpdater
import app.mg4dash.system.Log
import app.mg4dash.system.RadioDashUpdater
import app.mg4dash.system.Uninstaller

/**
 * Settings › System › Updates: uninstalling MG4Dash, MG4Radio or MG4Link (Marc, 19/11, discussion #111): one line per
 * installed app, what its uninstall does, its button; each one confirmed, MG4Dash's with a long press.
 */
object UninstallWindow {
    private const val TAG = "Uninstall"

    /** An app the window offers: its name, its package, whether it is installed, its version, what removing it does. */
    internal class App(val name: String, val pkg: String, val installed: Boolean, val version: String?, val what: Int)

    internal fun apps(a: Activity): List<App> = listOf(
        App("MG4Dash", a.packageName, true, app.mg4dash.BuildConfig.VERSION_NAME, R.string.un_dash_what),
        App("MG4Radio", RadioDashUpdater.PKG, RadioDashUpdater.installed(a), RadioDashUpdater.installedVersion(a), R.string.un_radio_what),
        App("MG4Link", AirlinkUpdater.PKG, AirlinkUpdater.installedVersion(a) != null, AirlinkUpdater.installedVersion(a), R.string.un_link_what),
    )

    fun show(a: Activity) {
        val kit = Kit(a)
        val box = kit.column().apply { setPadding(kit.dp(30), kit.dp(24), kit.dp(30), kit.dp(22)) }
        box.addView(kit.text(a.getString(R.string.un_title).uppercase(), 16f, R.color.text_title).apply { letterSpacing = 0.12f }, kit.lp(-1, -2, b = 8))
        val dialog = AlertDialog.Builder(a).setView(box).create()
        for (app in apps(a).filter { it.installed }) {
            val line = kit.row().apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(kit.column().apply {
                addView(kit.text(listOfNotNull(app.name, app.version).joinToString(" · "), 22f))
                addView(kit.text(a.getString(app.what), 17f, R.color.text_sub).apply { maxLines = 3; setLineSpacing(0f, 1.1f) }, kit.lp(-1, -2, t = 2))
            }, kit.lp(0, -2, 1f))
            line.addView(redTile(kit, a, a.getString(R.string.un_do)) {
                dialog.dismiss()
                if (app.pkg == a.packageName) confirmDash(a) else confirmApp(a, app)
            }, kit.lp(kit.dp(220), kit.dp(56), l = 20))
            box.addView(line, kit.lp(-1, -2, t = 12))
        }
        box.addView(kit.toggleTile(a.getString(R.string.st_close)) { dialog.dismiss() }.apply { textSize = 20f }, kit.lp(-1, kit.dp(56), t = 22))
        Dialogs.show(dialog)
        dialog.window?.setLayout(kit.dp(980), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun redTile(kit: Kit, a: Activity, label: String, onClick: () -> Unit): TextView =
        kit.toggleTile(label, onClick = onClick).apply { textSize = 19f; setTextColor(Themes.color(a, R.color.accent_danger)) }

    private fun confirmApp(a: Activity, app: App) {
        AlertDialog.Builder(a)
            .setTitle(a.getString(R.string.un_confirm_title, app.name))
            .setMessage(app.what)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.un_do) { _, _ -> run(a, app.name) { Uninstaller.removeApp(a.applicationContext, app.pkg) } }
            .create().also { Dialogs.show(it); Dialogs.fix(it) }
    }

    /** MG4Dash's own: its consequences, and a long press to go ahead. */
    private fun confirmDash(a: Activity) {
        val kit = Kit(a)
        val box = kit.column().apply { setPadding(kit.dp(30), kit.dp(24), kit.dp(30), kit.dp(22)) }
        box.addView(kit.text(a.getString(R.string.un_confirm_title, "MG4Dash").uppercase(), 16f, R.color.text_title).apply { letterSpacing = 0.12f }, kit.lp(-1, -2, b = 10))
        box.addView(kit.text(a.getString(R.string.un_dash_body), 20f).apply { setLineSpacing(0f, 1.15f) })
        box.addView(kit.text(a.getString(R.string.un_dash_note), 17f, R.color.text_sub).apply { setLineSpacing(0f, 1.1f) }, kit.lp(-1, -2, t = 10))
        val d = AlertDialog.Builder(a).setView(box).create()
        val buttons = kit.row()
        buttons.addView(kit.toggleTile(a.getString(R.string.cancel)) { d.dismiss() }.apply { textSize = 20f }, kit.lp(0, kit.dp(60), 1f))
        buttons.addView(redTile(kit, a, a.getString(R.string.un_hold)) { Toasts.show(a, R.string.un_hold) }.apply {
            setOnLongClickListener { d.dismiss(); run(a, "MG4Dash") { Uninstaller.uninstallDash(Uninstaller.CarOps(a.applicationContext), a.packageName).failed == null }; true }
        }, kit.lp(0, kit.dp(60), 1f, l = 16))
        box.addView(buttons, kit.lp(-1, -2, t = 22))
        Dialogs.show(d)
        d.window?.setLayout(kit.dp(900), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Runs [work] off the main thread, then tells how it went (for MG4Dash itself, its process goes before). */
    private fun run(a: Activity, name: String, work: () -> Boolean) {
        Toasts.show(a, a.getString(R.string.un_working))
        val app = a.applicationContext
        Thread {
            val ok = try { work() } catch (e: Exception) { Log.w(TAG, "$name: ${e.message}"); false }
            Log.i(TAG, "$name uninstall → $ok")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toasts.show(app, if (ok) app.getString(R.string.un_done, name) else app.getString(R.string.un_failed, name), long = true)
            }
        }.start()
    }

}
