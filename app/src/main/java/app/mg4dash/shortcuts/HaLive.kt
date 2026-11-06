package app.mg4dash.shortcuts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.ui.Themes
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Live state of the Home Assistant shortcuts (#97): the icon and its colour follow the state, with the
 * state's name under the label, as in Home Assistant. Open, on or unlocked: orange (a garage is drawn
 * open); closed or off: the usual blue; unavailable or Home Assistant out of reach: grey.
 *
 * The state shown is the action's own entity when it has one worth showing (cover, lock, switch, light…),
 * or another entity chosen in the shortcut's menu (e.g. the door sensor of a garage opened by a script).
 * States are read while a shortcut is on screen: at once, then every [PERIOD_MS], and soon after an action.
 */
object HaLive {
    private const val TAG = "HaLive"
    private const val PERIOD_MS = 5000L
    private const val IDLE_MS = 2000L
    private val main = Handler(Looper.getMainLooper())

    enum class Kind { ACTIVE, MOVING, INACTIVE, UNAVAILABLE, OFFLINE }
    data class State(val kind: Kind, val text: Int)

    /** Domains whose own state is shown without any setting. */
    private val STATEFUL = setOf("cover", "lock", "switch", "light", "input_boolean", "fan", "binary_sensor", "valve")
    /** Domains offered for "Displayed state". */
    val STATE_DOMAINS = listOf("cover", "binary_sensor", "lock", "switch", "light", "input_boolean", "fan", "valve")
    private val DOOR_CLASSES = setOf("door", "garage_door", "opening", "window")

    private const val NONE = "-"

    /** Entity whose state is shown for slot [i], or null for none. */
    fun entityFor(prefs: Prefs, i: Int, slot: Shortcuts.Slot.Ha): String? {
        val chosen = prefs.raw.getString(key(i), null)
        return when {
            chosen == NONE -> null
            !chosen.isNullOrBlank() -> chosen
            slot.entityId.substringBefore('.') in STATEFUL -> slot.entityId
            else -> null
        }
    }

    /** Stored choice: null = automatic, [NONE] = no state, or an entity id. */
    fun choice(prefs: Prefs, i: Int): String? = prefs.raw.getString(key(i), null)
    fun setChoice(prefs: Prefs, i: Int, value: String?) { prefs.raw.edit().putString(key(i), value).apply() }
    fun setNone(prefs: Prefs, i: Int) = setChoice(prefs, i, NONE)
    fun isNone(value: String?) = value == NONE
    private fun key(i: Int) = "sc_state_$i"

    /** Maps Home Assistant's state of [entity] to what the tile shows. */
    internal fun parse(entity: String, state: String, deviceClass: String?): State {
        val domain = entity.substringBefore('.')
        return when (state) {
            "unavailable", "unknown", "" -> State(Kind.UNAVAILABLE, R.string.ha_unavailable)
            else -> when (domain) {
                "cover", "valve" -> when (state) {
                    "open" -> State(Kind.ACTIVE, R.string.ha_open)
                    "opening" -> State(Kind.MOVING, R.string.ha_opening)
                    "closing" -> State(Kind.MOVING, R.string.ha_closing)
                    "closed" -> State(Kind.INACTIVE, R.string.ha_closed)
                    else -> State(Kind.UNAVAILABLE, R.string.ha_unavailable)
                }
                "lock" -> when (state) {
                    "locked" -> State(Kind.INACTIVE, R.string.ha_locked)
                    "unlocked", "open" -> State(Kind.ACTIVE, R.string.ha_unlocked)
                    "locking" -> State(Kind.MOVING, R.string.ha_locking)
                    "unlocking", "opening" -> State(Kind.MOVING, R.string.ha_unlocking)
                    else -> State(Kind.UNAVAILABLE, R.string.ha_unavailable)
                }
                "binary_sensor" -> {
                    val door = deviceClass in DOOR_CLASSES
                    val lock = deviceClass == "lock"
                    when (state) {
                        "on" -> State(Kind.ACTIVE, if (door) R.string.ha_open else if (lock) R.string.ha_unlocked else R.string.ha_active)
                        "off" -> State(Kind.INACTIVE, if (door) R.string.ha_closed else if (lock) R.string.ha_locked else R.string.ha_inactive)
                        else -> State(Kind.UNAVAILABLE, R.string.ha_unavailable)
                    }
                }
                else -> when (state) {
                    "on" -> State(Kind.ACTIVE, R.string.ha_on)
                    "off" -> State(Kind.INACTIVE, R.string.ha_off)
                    else -> State(Kind.UNAVAILABLE, R.string.ha_unavailable)
                }
            }
        }
    }

    /** Draws [state] on a tile: icon, its colour and the state's name. */
    fun style(context: Context, slot: Shortcuts.Slot.Ha, state: State?, icon: ImageView, text: TextView) {
        val base = Shortcuts.haIcon(slot.entityId, slot.label)
        if (state == null) {
            icon.setImageResource(base); icon.setColorFilter(Themes.color(context, R.color.accent_action))
            text.visibility = View.INVISIBLE
            return
        }
        val (iconColor, textColor) = when (state.kind) {
            Kind.ACTIVE, Kind.MOVING -> R.color.accent_heat to R.color.accent_heat
            Kind.INACTIVE -> R.color.accent_action to R.color.text_sub
            Kind.UNAVAILABLE, Kind.OFFLINE -> R.color.text_title to R.color.text_title
        }
        val open = state.kind == Kind.ACTIVE || state.kind == Kind.MOVING
        icon.setImageResource(if (open && base == R.drawable.ic_garage) R.drawable.ic_garage_open else base)
        icon.setColorFilter(Themes.color(context, iconColor))
        text.setText(state.text)
        text.setTextColor(Themes.color(context, textColor))
        text.visibility = View.VISIBLE
    }

    private class Watch(val entity: String, view: View, val apply: (State?) -> Unit) {
        val view = WeakReference(view)
        var seenAttached = false
    }

    private val watches = mutableListOf<Watch>()
    private val cache = HashMap<String, State>()
    private var app: Context? = null
    private var running = false
    private var fetching = false
    private val tick = Runnable { poll() }

    /** Follows [entity] for the tile [view]: [apply] gets the last known state at once, then each new one. */
    fun watch(context: Context, entity: String, view: View, apply: (State?) -> Unit) {
        app = context.applicationContext
        apply(cache[entity])
        watches.add(Watch(entity, view, apply))
        if (!running) { running = true; main.post(tick) }
    }

    /** Tests and screenshots: a known state, as if Home Assistant had answered. */
    internal fun seedForTest(entity: String, state: String, deviceClass: String? = null) { cache[entity] = parse(entity, state, deviceClass) }

    /** After an action: read the states again soon, to show "Opening…" then the result. */
    fun refreshSoon() {
        if (!running) return
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1500)
    }

    private fun poll() {
        watches.removeAll { w ->
            val v = w.view.get()
            if (v != null && v.isAttachedToWindow) w.seenAttached = true
            v == null || (w.seenAttached && !v.isAttachedToWindow)
        }
        if (watches.isEmpty()) { running = false; return }
        val context = app ?: return
        val prefs = Prefs(context)
        if (prefs.haUrl.isBlank() || prefs.haToken.isBlank()) { main.postDelayed(tick, PERIOD_MS * 6); return }
        // Only the tiles really on screen: not behind the map's page, nor while another app is in front
        val shown = watches.filter { w -> w.view.get()?.let { it.isShown && it.windowVisibility == View.VISIBLE } == true }
            .map { it.entity }.toSet()
        if (shown.isEmpty() || fetching) { main.postDelayed(tick, IDLE_MS); return }
        fetching = true
        thread {
            val result = HashMap<String, State>()
            for (e in shown) {
                result[e] = try {
                    val o = JSONObject(Shortcuts.request(prefs, "GET", "/api/states/" + URLEncoder.encode(e, "UTF-8"), null))
                    parse(e, o.optString("state"), o.optJSONObject("attributes")?.optString("device_class"))
                } catch (ex: IllegalStateException) {
                    // HTTP error, e.g. 404 when the entity no longer exists
                    State(Kind.UNAVAILABLE, R.string.ha_unavailable)
                } catch (ex: Exception) {
                    Log.w(TAG, "$e: ${ex.message}")
                    State(Kind.OFFLINE, R.string.ha_offline)
                }
            }
            main.post {
                fetching = false
                cache.putAll(result)
                watches.forEach { w -> result[w.entity]?.let { s -> if (w.view.get() != null) w.apply(s) } }
                main.removeCallbacks(tick)
                main.postDelayed(tick, PERIOD_MS)
            }
        }
    }
}
