package app.mg4dash.shortcuts

import app.mg4dash.ui.Toasts as ToastsUi
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Kit
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Raccourcis de l'accueil : 4 × 2 cases, chacune vide, une appli ou une action
 * Home Assistant. Toucher = lancer ; appui long = modifier.
 * Stockage : "sc_<n>" = "app|<composant>" ou "ha|<entity_id>|<libellé>".
 */
object Shortcuts {
    const val COLS = 4
    const val ROWS = 2
    const val PAGES = 1
    private const val TAG = "Shortcuts"
    private val main = Handler(Looper.getMainLooper())

    sealed class Slot {
        object Empty : Slot()
        data class App(val component: ComponentName) : Slot()
        data class Ha(val entityId: String, val label: String) : Slot()
    }

    fun get(prefs: Prefs, i: Int): Slot {
        val v = prefs.raw.getString("sc_$i", null) ?: return Slot.Empty
        val p = v.split('|')
        return when (p[0]) {
            "app" -> ComponentName.unflattenFromString(p.getOrElse(1) { "" })?.let { Slot.App(it) } ?: Slot.Empty
            "ha" -> if (p.size >= 3) Slot.Ha(p[1], p.drop(2).joinToString("|")) else Slot.Empty
            else -> Slot.Empty
        }
    }

    fun set(prefs: Prefs, i: Int, s: Slot) {
        val v = when (s) {
            Slot.Empty -> null
            is Slot.App -> "app|" + s.component.flattenToString()
            is Slot.Ha -> "ha|${s.entityId}|${s.label}"
        }
        prefs.raw.edit().putString("sc_$i", v).apply()
    }

    // ─── Vue ───

    /** Construit (ou reconstruit) la grille dans [holder] ; [page] = page affichée. */
    /** [rows] : 2 sur l'accueil Complet, 3 dans le style Carte (cases 8 à 11 en plus). */
    fun render(activity: Activity, holder: LinearLayout, page: Int, rows: Int = ROWS, onPage: (Int) -> Unit) {
        val kit = Kit(activity)
        val prefs = Prefs(activity)
        holder.removeAllViews()
        val head = kit.row()
        head.addView(kit.title(activity.getString(R.string.title_shortcuts)).apply {
            if (app.mg4dash.system.Prefs(activity).hideTitles) visibility = android.view.View.INVISIBLE   // option « Masquer les titres »
        }, kit.lp(0, -2, 1f, l = Kit.FOG_SAFE_DP - 20))
        holder.addView(head, kit.lp(-1, kit.dp(48)))
        val grid = kit.column()
        for (r in 0 until rows) {
            val row = kit.row()
            for (c in 0 until COLS) {
                val i = page * COLS * rows + r * COLS + c
                row.addView(cell(activity, kit, prefs, i) { render(activity, holder, page, rows, onPage) },
                    kit.lp(0, -1, 1f, l = if (c > 0) 10 else 0))
            }
            grid.addView(row, kit.lp(-1, 0, 1f, t = if (r > 0) 10 else 0))
        }
        holder.addView(grid, kit.lp(-1, 0, 1f, t = 4))
    }

    private fun cell(activity: Activity, kit: Kit, prefs: Prefs, i: Int, refresh: () -> Unit): View {
        val slot = get(prefs, i)
        val cell = kit.column().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dock_item)
            isClickable = true; isFocusable = true
        }
        val icon = ImageView(activity)
        val label = kit.text("", 20f, R.color.text_value).apply { gravity = Gravity.CENTER; maxLines = 1 }
        when (slot) {
            Slot.Empty -> {
                icon.setImageResource(R.drawable.ic_plus); icon.setColorFilter(kit.color(R.color.text_title))
                label.text = ""
            }
            is Slot.App -> {
                val d = appIcon(activity, slot.component)
                if (d != null) icon.setImageDrawable(d) else { icon.setImageResource(R.drawable.ic_apps); icon.setColorFilter(kit.color(R.color.text_sub)) }
                label.text = appLabel(activity, slot.component)
            }
            is Slot.Ha -> {
                icon.setImageResource(haIcon(slot.entityId, slot.label)); icon.setColorFilter(kit.color(R.color.accent_action))
                label.text = slot.label
            }
        }
        cell.addView(icon, LinearLayout.LayoutParams(kit.dp(64), kit.dp(64)))
        cell.addView(label, kit.lp(-1, -2, t = 6))
        cell.setOnClickListener {
            when (slot) {
                Slot.Empty -> edit(activity, i, refresh)
                is Slot.App -> launch(activity, slot.component)
                is Slot.Ha -> runHa(activity, slot)
            }
        }
        cell.setOnLongClickListener { edit(activity, i, refresh); true }
        return cell
    }

    fun haIcon(entity: String, label: String): Int {
        val s = (entity + " " + label).lowercase()
        return when {
            "garage" in s -> R.drawable.ic_garage
            "gate" in s || "portail" in s || "portal" in s || "cancello" in s || "poort" in s -> R.drawable.ic_gate
            else -> R.drawable.ic_ha
        }
    }

    // ─── Applis ───

    fun appIcon(c: Context, cn: ComponentName): Drawable? =
        try { c.packageManager.getActivityIcon(cn) } catch (_: Exception) { null }

    fun appLabel(c: Context, cn: ComponentName): String = try {
        c.packageManager.getActivityInfo(cn, 0).loadLabel(c.packageManager).toString()
    } catch (_: Exception) { cn.packageName.substringAfterLast('.') }

    /** Caméra 360 : le launcher d'origine ne lance pas l'activité, il démarre le service AVM (#33). */
    private val AVM_SERVICES = mapOf(
        "com.saicmotor.avm" to "com.saicmotor.avm.service.SaicMotorAvmService",
        "com.saicmotor.hmi.aroundview" to "com.saicmotor.hmi.aroundview.AvmService",
    )

    fun launch(activity: Activity, cn: ComponentName) {
        AVM_SERVICES[cn.packageName]?.let { svc ->
            try {
                activity.startService(Intent("com.saicmotor.avm.START_ENTER").setComponent(ComponentName(cn.packageName, svc)))
                Log.i(TAG, "360 camera: service $svc started")
                return
            } catch (e: Exception) { Log.w(TAG, "360 camera: service $svc: ${e.message}") }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) {
            Log.w(TAG, "opening ${cn.flattenToShortString()}: ${e.message}")
            app.mg4dash.ui.Toasts.show(activity, activity.getString(R.string.app_unavailable, appLabel(activity, cn)))
        }
    }

    /** Liste des applis lançables, avec icônes ; [extraFirst] = entrée ajoutée en tête (ex. « Android Auto d'origine »). */
    fun pickApp(activity: Activity, title: String, extraFirst: String? = null, onPick: (ComponentName?) -> Unit) {
        val pm = activity.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != activity.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        val kit = Kit(activity)
        val count = apps.size + (if (extraFirst != null) 1 else 0)
        val adapter = object : BaseAdapter() {
            override fun getCount() = count
            override fun getItem(p: Int) = p
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
                val row = kit.row().apply { setPadding(kit.dp(20), kit.dp(10), kit.dp(20), kit.dp(10)) }
                val icon = ImageView(activity)
                val label = kit.text("", 20f)
                if (extraFirst != null && p == 0) {
                    icon.setImageResource(R.drawable.ic_navigation); icon.setColorFilter(kit.color(R.color.accent_action))
                    label.text = extraFirst
                } else {
                    val ri = apps[p - (if (extraFirst != null) 1 else 0)]
                    icon.setImageDrawable(ri.loadIcon(pm)); label.text = ri.loadLabel(pm)
                }
                row.addView(icon, LinearLayout.LayoutParams(kit.dp(48), kit.dp(48)))
                row.addView(label, kit.lp(0, -2, 1f, l = 18))
                return row
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, p ->
                if (extraFirst != null && p == 0) onPick(null)
                else apps[p - (if (extraFirst != null) 1 else 0)].activityInfo.let { onPick(ComponentName(it.packageName, it.name)) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── Modification d'une case ───

    fun edit(activity: Activity, i: Int, refresh: () -> Unit) {
        val prefs = Prefs(activity)
        val choices = arrayOf(
            activity.getString(R.string.sc_app),
            activity.getString(R.string.sc_ha),
            activity.getString(R.string.sc_clear),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.sc_edit)
            .setItems(choices) { _, w ->
                when (w) {
                    0 -> pickApp(activity, choices[0]) { cn -> if (cn != null) { set(prefs, i, Slot.App(cn)); refresh() } }
                    1 -> pickHa(activity) { id, label -> set(prefs, i, Slot.Ha(id, label)); refresh() }
                    2 -> { set(prefs, i, Slot.Empty); refresh() }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ─── Home Assistant ───

    private val HA_DOMAINS = listOf("script", "automation", "scene", "button", "input_button", "cover", "switch", "light", "lock", "input_boolean")

    /** Liste les entités actionnables de Home Assistant et laisse choisir. */
    private fun pickHa(activity: Activity, onPick: (String, String) -> Unit) {
        val prefs = Prefs(activity)
        if (prefs.haUrl.isBlank() || prefs.haToken.isBlank()) {
            app.mg4dash.ui.Toasts.show(activity, R.string.ha_not_configured, long = true); return
        }
        thread {
            val result = try {
                val arr = JSONArray(request(prefs, "GET", "/api/states", null))
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { it.getString("entity_id") to (it.optJSONObject("attributes")?.optString("friendly_name") ?: "") }
                    .filter { it.first.substringBefore('.') in HA_DOMAINS }
                    .sortedWith(compareBy({ HA_DOMAINS.indexOf(it.first.substringBefore('.')) }, { it.second.lowercase() }))
            } catch (e: Exception) {
                Log.w(TAG, "Home Assistant: ${e.message}"); null
            }
            main.post {
                if (activity.isFinishing) return@post
                if (result == null) { app.mg4dash.ui.Toasts.show(activity, R.string.ha_error, long = true); return@post }
                val labels = result.map { (id, name) -> if (name.isNotBlank()) "$name  ·  $id" else id }
                searchList(activity, activity.getString(R.string.sc_ha), labels) { w ->
                    result[w].let { (id, name) -> onPick(id, name.ifBlank { id.substringAfter('.') }) }
                }
            }
        }
    }

    /**
     * Liste avec champ de recherche filtrant au fil de la frappe (sans tenir compte des accents
     * ni de la casse ; plusieurs mots = tous doivent apparaître). Renvoie l'index d'origine.
     */
    fun searchList(activity: Activity, title: String, labels: List<String>, onPick: (Int) -> Unit) {
        val kit = Kit(activity)
        fun norm(t: String) = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase()
        val keys = labels.map { norm(it) }
        var shown = labels.indices.toList()
        val adapter = android.widget.ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, labels.toMutableList())
        val list = android.widget.ListView(activity).apply { this.adapter = adapter }
        val search = android.widget.EditText(activity).apply {
            hint = activity.getString(R.string.sc_search)
            setSingleLine()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }
        val box = kit.column().apply {
            setPadding(kit.dp(20), kit.dp(8), kit.dp(20), 0)
            addView(search, LinearLayout.LayoutParams(-1, -2))
            addView(list, LinearLayout.LayoutParams(-1, kit.dp(420)))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title).setView(box)
            .setNegativeButton(R.string.cancel, null)
            .create()
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                val words = norm(e?.toString() ?: "").split(' ').filter { it.isNotBlank() }
                shown = labels.indices.filter { i -> words.all { it in keys[i] } }
                adapter.clear(); adapter.addAll(shown.map { labels[it] })
            }
        })
        list.setOnItemClickListener { _, _, pos, _ -> dialog.dismiss(); onPick(shown[pos]) }
        dialog.show()
    }

    /** Service appelé selon le domaine de l'entité. */
    private fun service(entity: String): String = when (entity.substringBefore('.')) {
        "script" -> "script/turn_on"
        "automation" -> "automation/trigger"
        "scene" -> "scene/turn_on"
        "button" -> "button/press"
        "input_button" -> "input_button/press"
        "cover" -> "cover/toggle"
        "lock" -> "lock/unlock"
        else -> "homeassistant/toggle"
    }

    fun runHa(activity: Activity, slot: Slot.Ha) {
        val prefs = Prefs(activity)
        val app = activity.applicationContext
        thread {
            val ok = try {
                request(prefs, "POST", "/api/services/" + service(slot.entityId), JSONObject().put("entity_id", slot.entityId).toString())
                true
            } catch (e: Exception) {
                Log.w(TAG, "action ${slot.entityId}: ${e.message}"); false
            }
            main.post {
                ToastsUi.show(app, app.getString(if (ok) R.string.ha_sent else R.string.ha_error_action, slot.label))
            }
        }
    }

    /** Vérifie l'adresse et le jeton (réglages). */
    fun testHa(context: Context, onResult: (Boolean) -> Unit) {
        val prefs = Prefs(context)
        thread {
            val ok = try { request(prefs, "GET", "/api/", null); true } catch (e: Exception) { Log.w(TAG, "test: ${e.message}"); false }
            main.post { onResult(ok) }
        }
    }

    private fun request(prefs: Prefs, method: String, path: String, body: String?): String {
        val c = URL(prefs.haUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 6000; c.readTimeout = 10000
            c.setRequestProperty("Authorization", "Bearer " + prefs.haToken)
            c.setRequestProperty("Content-Type", "application/json")
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
