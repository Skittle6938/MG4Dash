package app.mg4dash.ui

import android.app.Activity
import android.content.Context
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import java.io.File

/**
 * The web app's addresses (#98), the same menu from the card's long press and from the edit mode: each address
 * (a touch shows it, ✎ edits it, ✕ deletes it), Add this page (long press only: the page is open), Add an address,
 * and the list imported from or exported to a USB stick (mg4dash_url.txt, one address per line, "name | address"
 * read too). Changes are kept at once.
 */
object WebAddresses {
    private const val TAG = "WebApp"
    const val FILE = "mg4dash_url.txt"

    /**
     * Opens the menu. [head]: rows before the addresses (Back, Move / resize from the card); [page]: the page shown,
     * offered if it is not in the list; [onShow]: an address touched; [onChange]: the list or the address shown changed.
     */
    fun show(activity: Activity, head: List<CardMenu.Row> = emptyList(), page: String? = null,
             onShow: (String) -> Unit, onChange: () -> Unit = {}) {
        val prefs = Prefs(activity)
        val shown = prefs.webUrl
        fun again() = show(activity, head, page, onShow, onChange)
        val rows = head.toMutableList()
        prefs.webUrls.forEach { url ->
            rows += CardMenu.Row(if (url == shown) "◉" else "○", WebCard.name(url), url, url == shown,
                edit = {
                    CardsEditor.askUrl(activity, url) { v ->
                        if (v.isNotEmpty() && v != url) {
                            prefs.webUrls = replace(prefs.webUrls, url, v)
                            if (prefs.webUrl == url) prefs.webUrl = v
                            Log.i(TAG, "address ${WebCard.name(url)} → ${WebCard.name(v)}")
                            onChange()
                        }
                        again()
                    }
                },
                delete = {
                    prefs.webUrls = prefs.webUrls - url
                    if (prefs.webUrl == url) prefs.webUrl = prefs.webUrls.firstOrNull() ?: ""
                    Log.i(TAG, "address ${WebCard.name(url)} deleted")
                    onChange(); again()
                },
            ) { onShow(url) }
        }
        // The page shown now (after links followed in it), added to the list as it is, if it is not there yet
        WebCard.pageToAdd(prefs.webUrls, page)?.let { url ->
            rows += CardMenu.Row("＋", activity.getString(R.string.wc_add_page), url, false) {
                prefs.webUrls = prefs.webUrls + url
                Log.i(TAG, "menu: ${WebCard.name(url)} page added")
                Toasts.show(activity, R.string.wc_page_added)
                onChange()
            }
        }
        // Another address, added to the list and shown at once
        rows += CardMenu.Row("＋", activity.getString(R.string.wc_web_add), null, false) {
            CardsEditor.askUrl(activity, "") { url ->
                if (url.isEmpty()) return@askUrl
                prefs.webUrls = prefs.webUrls.filter { it != url } + url
                Log.i(TAG, "menu: ${WebCard.name(url)} added")
                onShow(url); onChange()
            }
        }
        rows += CardMenu.Row("⇩", activity.getString(R.string.wc_urls_import), activity.getString(R.string.wc_urls_import_desc), false) {
            importUsb(activity)?.let { added ->
                if (added.isNotEmpty() && prefs.webUrl.isBlank()) prefs.webUrl = added.first()
                Toasts.show(activity, activity.getString(R.string.wc_urls_imported, added.size), long = true)
                onChange(); again()
            }
        }
        rows += CardMenu.Row("⇧", activity.getString(R.string.wc_urls_export), FILE, false) { exportUsb(activity) }
        CardMenu.show(activity, rows)
    }

    /** [list] with [old] replaced by [new], in its place; [new] once. */
    internal fun replace(list: List<String>, old: String, new: String): List<String> =
        list.map { if (it == old) new else it }.distinct()

    /** The addresses of a file's lines: "address" or "name | address", blank lines and # comments left out. */
    internal fun parse(lines: List<String>): List<String> = lines.mapNotNull { raw ->
        val line = raw.trim().trimStart('﻿')
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        val url = line.substringAfterLast('|').trim()
        when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.contains('.') && !url.contains(' ') && !url.contains("://") -> "http://$url"
            else -> null
        }
    }.distinct()

    /** The file's text: one address per line, with its name for the reader. */
    internal fun format(urls: List<String>): String = urls.joinToString("\n", postfix = "\n") { "${WebCard.name(it)} | $it" }

    /** The addresses of [file] added to [current] (not twice): the new ones. */
    internal fun merge(current: List<String>, file: List<String>): List<String> = file.filter { it !in current }

    private fun stick(context: Context): File? {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        return sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
            .firstNotNullOfOrNull { v -> try { v.javaClass.getMethod("getPathFile").invoke(v) as? File } catch (_: Throwable) { null } }
    }

    /** MG4Dash's own folder on the stick (Android/data/app.mg4dash/files), always writable, if the root is not. */
    private fun ownFolder(context: Context, usb: File): File? =
        context.getExternalFilesDirs(null).firstOrNull { it != null && it.absolutePath.startsWith(usb.absolutePath) }

    /** The addresses read on the stick and added to the list (the new ones), or null with no file (a toast says so). */
    fun importUsb(context: Context): List<String>? {
        val usb = stick(context)
        val file = listOfNotNull(usb, usb?.let { ownFolder(context, it) })
            .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .firstOrNull { it.isFile && it.name.equals(FILE, ignoreCase = true) }
        if (file == null) { Toasts.show(context, R.string.wc_urls_import_none, long = true); return null }
        val read = try { parse(file.readLines()) } catch (e: Exception) { Log.w(TAG, "import: ${e.message}"); emptyList() }
        val prefs = Prefs(context)
        val added = merge(prefs.webUrls, read)
        prefs.webUrls = prefs.webUrls + added
        Log.i(TAG, "${read.size} addresses read from ${file.name}, ${added.size} new")
        return added
    }

    /** The list written to the stick's root (or MG4Dash's folder on it if the root is read only). */
    fun exportUsb(context: Context) {
        val usb = stick(context) ?: run { Toasts.show(context, R.string.wc_urls_no_usb, long = true); return }
        val text = format(Prefs(context).webUrls)
        val out = listOfNotNull(File(usb, FILE), ownFolder(context, usb)?.let { File(it.apply { mkdirs() }, FILE) }).firstOrNull { f ->
            try { f.writeText(text); true } catch (e: Exception) { Log.w(TAG, "export to ${f.absolutePath}: ${e.message}"); false }
        }
        if (out == null) { Toasts.show(context, R.string.wc_urls_no_usb, long = true); return }
        Log.i(TAG, "addresses exported to ${out.absolutePath}")
        Toasts.show(context, context.getString(R.string.wc_urls_exported, out.absolutePath.removePrefix(usb.absolutePath).trimStart('/')), long = true)
    }
}
