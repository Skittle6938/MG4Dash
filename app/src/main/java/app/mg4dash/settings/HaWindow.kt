package app.mg4dash.settings

import android.app.Activity
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Rows
import app.mg4dash.ui.Windows

/**
 * Home Assistant's settings (24/11): a window like every other (title, round close button, a touch outside or
 * Home closes it), opened from the shortcuts card's options and the shortcuts' menu. The address and the token
 * typed with Dash's keyboard, a test, and the import from a USB stick.
 */
object HaWindow {
    fun show(a: Activity) {
        val prefs = Prefs(a)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(a).apply {
            text = a.getString(R.string.st_ha_desc); setTextColor(app.mg4dash.ui.Themes.color(a, R.color.text_sub))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Rows.SUB_SP)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * a.resources.displayMetrics.density).toInt() })

        // Connection: the address and the token, each typed by touching its row
        box.addView(Rows.header(a, a.getString(R.string.ha_connection)))
        val conn = Rows.group(a)
        val url = Rows.value(a, "")
        val token = Rows.value(a, "")
        fun refresh() {
            url.text = "${prefs.haUrl.ifBlank { "—" }}  ▾"
            token.text = "${a.getString(if (prefs.haToken.isBlank()) R.string.ha_token_off else R.string.ha_token_on)}  ▾"
        }
        refresh()
        Rows.add(conn, Rows.row(a, a.getString(R.string.st_ha_set_url), null, url) {
            app.mg4dash.ui.Ask.show(a, a.getString(R.string.st_ha_set_url), prefs.haUrl.ifBlank { "http://homeassistant.local:8123" }, false, false) {
                prefs.haUrl = it.trim().trimEnd('/'); refresh()
            }
        })
        Rows.add(conn, Rows.row(a, a.getString(R.string.st_ha_set_token), null, token) {
            app.mg4dash.ui.Ask.show(a, a.getString(R.string.st_ha_set_token), "", true, false) { prefs.haToken = it.trim(); refresh() }
        })
        Rows.add(conn, Rows.row(a, a.getString(R.string.ha_test_title), null, Rows.actionButton(a, a.getString(R.string.st_ha_test)) {
            app.mg4dash.shortcuts.Shortcuts.testHa(a) { ok -> app.mg4dash.ui.Toasts.show(a, if (ok) R.string.st_ha_ok else R.string.ha_error, long = true) }
        }, null))
        box.addView(conn)

        // #63: keyboard without "paste" (SWI68): address and token read from a text file on the USB stick
        box.addView(Rows.header(a, a.getString(R.string.ha_usb)))
        val usb = Rows.group(a)
        Rows.add(usb, Rows.row(a, a.getString(R.string.st_ha_import), a.getString(R.string.st_ha_import_desc),
            Rows.actionButton(a, a.getString(R.string.opt_import)) { import(a) { refresh() } }, null))
        box.addView(usb)

        Windows.show(a, a.getString(R.string.st_ha_settings), box, R.drawable.ic_ha, widthDp = 1100)
    }

    /**
     * Home Assistant address and token read from mg4dash_ha.txt at the root of a USB stick: the line that
     * starts with http is the address, the longest of the others the token.
     */
    fun import(a: Activity, done: () -> Unit) {
        val prefs = Prefs(a)
        val sm = a.getSystemService(Activity.STORAGE_SERVICE) as android.os.storage.StorageManager
        val file = sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .mapNotNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? java.io.File } catch (_: Throwable) { null } }
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.name.equals("mg4dash_ha.txt", ignoreCase = true) }
        if (file == null) { app.mg4dash.ui.Toasts.show(a, R.string.st_ha_import_none, long = true); return }
        val lines = try { file.readLines().map { it.trim().trimStart('﻿') }.filter { it.isNotEmpty() } } catch (_: Exception) { emptyList() }
        val url = lines.firstOrNull { it.startsWith("http", ignoreCase = true) }
        val token = lines.filter { it != url && !it.contains(' ') }.maxByOrNull { it.length }
        if (url != null) prefs.haUrl = url.trimEnd('/')
        if (token != null) prefs.haToken = token
        Log.i("Settings", "Home Assistant imported from ${file.name}: address ${url != null}, token ${token != null}")
        val found = listOfNotNull(url?.let { a.getString(R.string.st_ha_set_url) }, token?.let { a.getString(R.string.st_ha_set_token) })
        if (found.isEmpty()) { app.mg4dash.ui.Toasts.show(a, R.string.st_ha_import_none, long = true); return }
        app.mg4dash.ui.Toasts.show(a, a.getString(R.string.st_ha_import_done, found.joinToString(", ")), long = true)
        done()
    }
}
