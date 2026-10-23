package app.mg4dash.pages

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.car.VehicleState
import app.mg4dash.system.Apps
import app.mg4dash.system.Log
import app.mg4dash.ui.Dock
import app.mg4dash.ui.PageActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page Téléphone : appels récents, contacts avec recherche, clavier.
 * Les données viennent du téléphone connecté en Bluetooth (PBAP → ContactsProvider et journal
 * d'appels, comme l'appli SAIC) ; l'appel passe par le compte HFP de Telecom, et l'écran d'appel
 * en cours reste celui de l'appli SAIC (son InCallService).
 */
class PhoneActivity : PageActivity() {
    override val pageTitle get() = getString(R.string.title_phone)
    override val page = Dock.Page.PHONE

    data class Entry(val name: String, val number: String, val detail: String, val type: Int = 0)

    companion object {
        /** Appels récents du téléphone connecté (journal synchronisé par PBAP), du plus récent au plus ancien. */
        fun recentCalls(ctx: Context, max: Int = 100): List<Entry> {
            if (BuildConfig.MOCK) return mockCalls()
            return try {
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                    null, null, CallLog.Calls.DATE + " DESC",
                )?.use { c ->
                    val out = ArrayList<Entry>()
                    while (c.moveToNext() && out.size < max) {
                        val number = c.getString(1) ?: continue
                        val name = c.getString(0)?.takeIf { it.isNotBlank() } ?: number
                        val ago = DateUtils.getRelativeTimeSpanString(c.getLong(3), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
                        out.add(Entry(name, number, ago, c.getInt(2)))
                    }
                    out
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w("Téléphone", "journal d'appels : ${e.message}"); emptyList()
            }
        }

        /** Nom du téléphone connecté en mains libres (HFP), ou null. */
        fun connectedPhone(): String? {
            if (BuildConfig.MOCK) return "Pixel 8"
            return try {
                BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.firstOrNull { d ->
                    try { d.javaClass.getMethod("isConnected").invoke(d) as Boolean } catch (_: Throwable) { false }
                }?.name
            } catch (e: Exception) { null }
        }

        /** Appel par le compte Bluetooth mains libres (HFP), comme l'appli SAIC. */
        fun placeCall(ctx: Context, number: String) {
            val n = number.trim()
            if (n.isEmpty()) return
            if (BuildConfig.MOCK) { app.mg4dash.ui.Toasts.show(ctx, "☎ $n"); return }
            try {
                val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                val hfp = tm.callCapablePhoneAccounts.firstOrNull { it.componentName.flattenToShortString().contains("HfpClientConnectionService") }
                if (hfp == null) { app.mg4dash.ui.Toasts.show(ctx, R.string.ph_no_phone, long = true); return }
                val extras = Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, hfp) }
                tm.placeCall(Uri.fromParts("tel", n, null), extras)
                Log.i("Téléphone", "appel lancé")
            } catch (e: Exception) {
                Log.w("Téléphone", "appel : ${e.message}")
                app.mg4dash.ui.Toasts.show(ctx, R.string.ph_call_failed, long = true)
            }
        }

        private fun mockCalls() = listOf(
            Entry("Claire Martin", "+33 6 12 34 56 78", "12 min", CallLog.Calls.INCOMING_TYPE),
            Entry("Garage MG", "+33 1 23 45 67 89", "1 h", CallLog.Calls.OUTGOING_TYPE),
            Entry("+33 7 98 76 54 32", "+33 7 98 76 54 32", "3 h", CallLog.Calls.MISSED_TYPE),
            Entry("Paul Durand", "+33 6 55 44 33 22", "Yesterday", CallLog.Calls.OUTGOING_TYPE),
            Entry("Claire Martin", "+33 6 12 34 56 78", "Yesterday", CallLog.Calls.INCOMING_TYPE),
            Entry("Sophie Leroy", "+33 6 11 22 33 44", "2 days", CallLog.Calls.MISSED_TYPE),
        )

        /** Répertoire du téléphone connecté (ContactsProvider, rempli par PBAP), trié par nom. */
        fun contacts(ctx: Context): List<Entry> {
            if (BuildConfig.MOCK) return mockContacts()
            return try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.LABEL),
                    null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC",
                )?.use { c ->
                    val out = ArrayList<Entry>()
                    val seen = HashSet<String>()
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        val number = c.getString(1) ?: continue
                        if (!seen.add(name + "|" + number.filter { it.isDigit() })) continue
                        val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                        out.add(Entry(name, number, "$label · $number"))
                    }
                    out
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w("Téléphone", "contacts : ${e.message}"); emptyList()
            }
        }

        /** Demande au téléphone connecté (PBAP) de renvoyer répertoire et journal d'appels. */
        fun sync(ctx: Context, manual: Boolean) {
            if (BuildConfig.MOCK) return
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            val ok = adapter.getProfileProxy(ctx, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    var started = 0
                    for (d in proxy.connectedDevices) {
                        for (m in listOf("downloadPhoneBook", "downloadCallLog")) {
                            try {
                                val res = proxy.javaClass.getMethod(m, android.bluetooth.BluetoothDevice::class.java).invoke(proxy, d)
                                Log.i("Téléphone", "$m → $res"); started++
                            } catch (e: Throwable) {
                                Log.w("Téléphone", "$m indisponible : ${(e.cause ?: e).message}")
                            }
                        }
                    }
                    if (manual) app.mg4dash.ui.Toasts.show(ctx, if (started > 0) R.string.ph_sync_started else R.string.ph_no_phone)
                    adapter.closeProfileProxy(profile, proxy)
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, 17 /* BluetoothProfile.PBAP_CLIENT */)
            if (!ok) Log.w("Téléphone", "profil PBAP indisponible")
        }

        private fun mockContacts() = listOf(
            Entry("Alice Bernard", "+33 6 01 02 03 04", "Mobile · +33 6 01 02 03 04"),
            Entry("Claire Martin", "+33 6 12 34 56 78", "Mobile · +33 6 12 34 56 78"),
            Entry("Garage MG", "+33 1 23 45 67 89", "Work · +33 1 23 45 67 89"),
            Entry("Paul Durand", "+33 6 55 44 33 22", "Mobile · +33 6 55 44 33 22"),
            Entry("Sophie Leroy", "+33 6 11 22 33 44", "Mobile · +33 6 11 22 33 44"),
            Entry("Thomas Petit", "+33 6 99 88 77 66", "Home · +33 6 99 88 77 66"),
        )
    }

    private lateinit var deviceLabel: TextView
    private lateinit var recentAdapter: EntryAdapter
    private lateinit var contactAdapter: EntryAdapter
    private lateinit var recentEmpty: TextView
    private lateinit var contactEmpty: TextView
    private lateinit var dialed: TextView
    private var contacts: List<Entry> = emptyList()

    override fun buildBody() {
        deviceLabel = kit.text("", 18f, R.color.text_sub)
        header.addView(deviceLabel, kit.lp(-2, -2, r = 16))
        header.addView(kit.text(getString(R.string.ph_sync), 19f, R.color.text_value).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { sync(this@PhoneActivity, manual = true) }
        }, kit.lp(-2, -2, r = 12))
        header.addView(kit.text(getString(R.string.ph_stock), 19f, R.color.text_sub).apply {
            setPadding(kit.dp(16), kit.dp(12), kit.dp(16), kit.dp(12))
            setBackgroundResource(R.drawable.bg_button)
            setOnClickListener { Apps.open(this@PhoneActivity, Apps.BT_PHONE, "SAIC") }
        })

        // ── Récents ──
        val recent = kit.panel(getString(R.string.ph_recent))
        recentAdapter = EntryAdapter(showType = true)
        recentEmpty = kit.text(getString(R.string.ph_no_phone), 18f, R.color.text_title).apply { maxLines = 3 }
        recent.addView(recentEmpty, kit.lp(-1, -2, t = 14))
        recent.addView(list(recentAdapter), kit.lp(-1, 0, 1f, t = 8))

        // ── Contacts ──
        val book = kit.panel(getString(R.string.ph_contacts))
        val search = EditText(this).apply {
            hint = getString(R.string.sc_search); setSingleLine()
            setTextColor(kit.color(R.color.text_value)); setHintTextColor(kit.color(R.color.text_title))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button)
            setPadding(kit.dp(16), 0, kit.dp(16), 0)
        }
        book.addView(search, kit.lp(-1, kit.dp(52), t = 12))
        contactAdapter = EntryAdapter(showType = false)
        contactEmpty = kit.text("", 18f, R.color.text_title).apply { maxLines = 3 }
        book.addView(contactEmpty, kit.lp(-1, -2, t = 10))
        book.addView(list(contactAdapter), kit.lp(-1, 0, 1f, t = 6))
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) { filterContacts(e?.toString() ?: "") }
        })

        // ── Clavier ──
        val pad = kit.panel(getString(R.string.ph_keypad))
        dialed = kit.text("", 40f, light = true).apply { gravity = Gravity.CENTER; ellipsize = android.text.TextUtils.TruncateAt.START }
        val dRow = kit.row()
        dRow.addView(dialed, kit.lp(0, kit.dp(64), 1f))
        dRow.addView(kit.roundButton(R.drawable.ic_backspace, 56) { dialed.text = dialed.text.dropLast(1) }.apply {
            setOnLongClickListener { dialed.text = ""; true }
        })
        pad.addView(dRow, kit.lp(-1, -2, t = 4))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        keys.chunked(3).forEach { rowKeys ->
            val r = kit.row()
            rowKeys.forEachIndexed { i, k ->
                r.addView(kit.toggleTile(k) { dialed.append(k) }.apply {
                    textSize = 28f
                    if (k == "0") setOnLongClickListener { dialed.append("+"); true }
                }, kit.lp(0, -1, 1f, l = if (i > 0) 10 else 0))
            }
            pad.addView(r, kit.lp(-1, 0, 1f, t = 10))
        }
        pad.addView(kit.text(getString(R.string.ph_call), 22f).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_call)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_phone, 0, 0, 0)
            compoundDrawablePadding = kit.dp(12)
            setPadding(kit.dp(24), 0, kit.dp(24), 0)
            setOnClickListener { call(dialed.text.toString()) }
        }, kit.lp(-1, kit.dp(64), t = 12))

        body.addView(recent, kit.lp(0, -1, 1f, r = 6))
        body.addView(book, kit.lp(0, -1, 1f, l = 6, r = 6))
        body.addView(pad, kit.lp(0, -1, 1f, l = 6))
    }

    override fun onResume() {
        super.onResume()
        load()
        contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
    }

    /** Le répertoire ou le journal changent (synchronisation en cours) : rechargement groupé. */
    private val reload = Runnable { load() }
    private val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            body.removeCallbacks(reload); body.postDelayed(reload, 1500)
        }
    }

    private var autoSyncDone = false

    /**
     * Demande au téléphone son répertoire et son journal d'appels (PBAP), comme le fait l'appli
     * téléphone SAIC (BluetoothPbapClient.downloadPhoneBook / downloadCallLog, méthodes SAIC).
     */
    override fun render(s: VehicleState) {}

    private fun list(adapter: EntryAdapter) = ListView(this).apply {
        this.adapter = adapter
        divider = null; dividerHeight = kit.dp(6)
        selector = android.graphics.drawable.ColorDrawable(0)
        setOnItemClickListener { _, _, pos, _ -> call(adapter.items[pos].number) }
    }

    // ── Données ──

    private fun load() {
        lifecycleScope.launch {
            val device = connectedPhone()
            val (calls, book) = withContext(Dispatchers.IO) { readCalls() to contacts(this@PhoneActivity) }
            deviceLabel.text = device ?: getString(R.string.ph_not_connected)
            contacts = book
            app.mg4dash.shortcuts.PhoneFavorites.remember(app.mg4dash.system.Prefs(this@PhoneActivity), book)
            recentAdapter.items = calls
            recentEmpty.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
            recentEmpty.text = getString(if (device == null) R.string.ph_no_phone else R.string.ph_no_calls)
            contactEmpty.text = getString(if (device == null) R.string.ph_no_phone else R.string.ph_no_contacts)
            filterContacts("")
            // Téléphone connecté mais rien de reçu : on lance la synchronisation une fois
            if (device != null && book.isEmpty() && calls.isEmpty() && !autoSyncDone) { autoSyncDone = true; sync(this@PhoneActivity, manual = false) }
        }
    }

    private fun filterContacts(q: String) {
        fun norm(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase()
        val words = norm(q).split(' ').filter { it.isNotBlank() }
        contactAdapter.items = if (words.isEmpty()) contacts else contacts.filter { e ->
            val key = norm(e.name) + " " + e.number.filter { it.isDigit() }
            words.all { it in key }
        }
        contactEmpty.visibility = if (contactAdapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun readCalls(): List<Entry> = recentCalls(this)

    // ── Appel ──

    private fun call(number: String) = placeCall(this, number)

    // ── Liste ──

    private inner class EntryAdapter(val showType: Boolean) : BaseAdapter() {
        var items: List<Entry> = emptyList()
            set(v) { field = v; notifyDataSetChanged() }
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val e = items[p]
            val row = kit.row().apply {
                setBackgroundResource(R.drawable.bg_button)
                setPadding(kit.dp(16), kit.dp(10), kit.dp(16), kit.dp(10))
                minimumHeight = kit.dp(72)
            }
            if (showType) {
                val (icon, color) = when (e.type) {
                    CallLog.Calls.INCOMING_TYPE -> R.drawable.ic_call_in to R.color.accent_cold
                    CallLog.Calls.OUTGOING_TYPE -> R.drawable.ic_call_out to R.color.accent_energy
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.ic_call_missed to R.color.accent_danger
                    else -> R.drawable.ic_phone to R.color.text_sub
                }
                row.addView(ImageView(this@PhoneActivity).apply { setImageResource(icon); setColorFilter(kit.color(color)) },
                    LinearLayout.LayoutParams(kit.dp(28), kit.dp(28)))
            }
            val col = kit.column()
            col.addView(kit.text(e.name, 20f).apply {
                if (showType && (e.type == CallLog.Calls.MISSED_TYPE || e.type == CallLog.Calls.REJECTED_TYPE)) setTextColor(kit.color(R.color.accent_danger))
            })
            col.addView(kit.text(e.detail, 16f, R.color.text_sub), kit.lp(-1, -2, t = 4))
            row.addView(col, kit.lp(0, -2, 1f, l = if (showType) 16 else 0))
            row.addView(ImageView(this@PhoneActivity).apply { setImageResource(R.drawable.ic_phone); setColorFilter(kit.color(R.color.accent_energy)) },
                LinearLayout.LayoutParams(kit.dp(26), kit.dp(26)))
            return row
        }
    }
}
