package app.mg4dash.ui

import android.app.Activity
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.mg4dash.BuildConfig
import app.mg4dash.R
import app.mg4dash.system.Log
import app.mg4dash.system.Prefs
import app.mg4dash.system.StatusMonitor
import app.mg4dash.system.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * At startup, offers the latest non-beta release if it is newer than the installed
 * version: "Do not notify about this release", "Later" or "Update".
 * Checked at most once a week (once a day with betas), as soon as a network is available, unless
 * the automatic check is turned off; errors are silent. The versions found are kept for Settings. The popup is a view placed in the home window (not a Dialog):
 * the activity keeps focus, so immersive mode stays active and neither the side bar nor the
 * SAIC banner reappear.
 * Home is rebuilt when the theme goes from light to dark: the offer (and the
 * running download) survives and reappears in the new window until answered.
 */
object UpdatePrompt {
    private const val TAG = "Update"
    private var checked = false
    /** Release offered and not yet dismissed; null once answered "Later" / "Do not notify". */
    private var pending: Updater.Release? = null
    /** The window showing the offer, and what follows the update's state in it. */
    private var shownIn: java.lang.ref.WeakReference<Activity>? = null
    private var watch: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun maybeShow(activity: Activity) {
        pending?.let { r ->
            if (shownIn?.get() !== activity && activity is LifecycleOwner) show(activity, r)
            return
        }
        if (checked || !StatusMonitor.hasInternet(activity)) return
        // "Updates on Wi-Fi only" (4G data): the check waits for a return to home on Wi-Fi
        if (app.mg4dash.system.DataUsage.updatesBlocked(activity)) return
        val prefs = Prefs(activity)
        if (!prefs.updateAuto) return
        val every = if (prefs.updateBeta) DAY_MS else 7 * DAY_MS
        if (System.currentTimeMillis() - prefs.raw.getLong(CHECKED_AT, 0L) < every) return
        checked = true
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val mine = try { Updater.latest(prefs.updateBeta) } catch (e: Exception) { Log.w(TAG, "periodic check: ${e.message}"); return@withContext }
                val rd = try { app.mg4dash.system.RadioDashUpdater.latest(prefs.updateBeta) } catch (e: Exception) { null }
                remember(activity, mine?.version, rd?.version)
                Log.i(TAG, "periodic check: MG4Dash ${mine?.version}, Radio Dash ${rd?.version}")
            }
            val r = withContext(Dispatchers.IO) {
                try { Updater.latestStable() } catch (e: Exception) { Log.w(TAG, "startup check: ${e.message}"); null }
            } ?: return@launch
            if (!Updater.isNewer(r.version) || Prefs(activity).updateSkipped == r.version) return@launch
            if (activity.isFinishing || activity.isDestroyed) return@launch
            pending = r
            show(activity, r)
            Log.i(TAG, "release ${r.version} offered at startup")
        }
    }

    private const val CHECKED_AT = "upd_checked_at"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Latest versions found (null: not looked up this time), shown on the lines of Settings › System. */
    fun remember(context: android.content.Context, app: String? = null, rd: String? = null) {
        val e = Prefs(context).raw.edit().putLong(CHECKED_AT, System.currentTimeMillis())
        app?.let { e.putString("upd_new_app", it) }
        rd?.let { e.putString("upd_new_rd", it) }
        e.apply()
    }

    private fun show(activity: Activity, r: Updater.Release) {
        shownIn = java.lang.ref.WeakReference(activity)
        val kit = Kit(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Full screen dark veil: catches touches so home stays inactive behind
        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(Color.argb(0xB3, 0, 0, 0))
            isClickable = true; isFocusable = true
            elevation = kit.dp(24).toFloat()
        }
        val panel = kit.panel(activity.getString(R.string.upd_title))
        val state = kit.text(activity.getString(R.string.st_update_available, BuildConfig.VERSION_NAME, r.version), 22f).apply {
            maxLines = 2; setPadding(0, kit.dp(12), 0, kit.dp(8))
        }
        panel.addView(state)
        panel.addView(kit.text(Updater.dataWarning(activity, r), 18f, R.color.accent_heat).apply { maxLines = 3; setPadding(0, 0, 0, kit.dp(8)) })
        val notes = Updater.plainNotes(r.notes)
        if (notes.isNotEmpty()) {
            panel.addView(ScrollView(activity).apply {
                addView(kit.text(notes, 18f, R.color.text_sub).apply { maxLines = Int.MAX_VALUE; setLineSpacing(0f, 1.15f) })
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = kit.dp(4) })
        }

        fun close() { pending = null; watch?.cancel(); watch = null; root.removeView(scrim); Fullscreen.apply(activity) }
        fun button(label: Int, colorRes: Int, onClick: () -> Unit) = Button(activity).apply {
            setText(label); isAllCaps = false; setTextColor(kit.color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = kit.dp(56); setPadding(kit.dp(20), 0, kit.dp(20), 0)
            stateListAnimator = null; elevation = 0f   // no relief or shadow (Material style), like the other buttons
            setOnClickListener { onClick() }
        }
        val buttons = kit.row()
        lateinit var skip: Button
        lateinit var later: Button
        lateinit var now: Button
        skip = button(R.string.upd_skip, R.color.text_sub) { Prefs(activity).updateSkipped = r.version; close() }
        later = button(R.string.upd_later, R.color.text_value) { close() }
        // Progress bar under the status, while it downloads and installs
        val bar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = android.view.View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(kit.color(R.color.text_value))
        }
        panel.addView(bar, LinearLayout.LayoutParams(-1, kit.dp(8)).apply { bottomMargin = kit.dp(8) })
        // The update's state shown in the current window (it goes on if home is rebuilt)
        fun render(s: Updater.State) {
            Updater.stateText(activity, s)?.let { state.text = it }
            bar.visibility = if (s.busy) android.view.View.VISIBLE else android.view.View.GONE
            bar.isIndeterminate = s !is Updater.State.Downloading
            if (s is Updater.State.Downloading) bar.progress = s.pct
            skip.isEnabled = !s.busy; later.isEnabled = !s.busy; now.isEnabled = !s.busy
            if (s is Updater.State.Failed) now.setText(R.string.upd_try_again)
        }
        now = button(R.string.upd_now, R.color.accent_action) {
            if (!Updater.start(activity, (Updater.state.value as? Updater.State.Failed)?.release ?: r)) state.text = activity.getString(R.string.st_update_busy)
        }
        watch?.cancel()
        watch = scope.launch { Updater.state.collect { if (shownIn?.get() === activity) render(it) } }
        listOf(skip, later, now).forEachIndexed { i, v -> buttons.addView(v, kit.lp(0, -1, 1f, l = if (i > 0) 10 else 0)) }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) })

        // Bounded height with notes: they scroll instead of pushing the buttons off screen
        val h = if (notes.isEmpty()) -2 else minOf(kit.dp(560), (activity.resources.displayMetrics.heightPixels * 0.85f).toInt())
        scrim.addView(panel, FrameLayout.LayoutParams(kit.dp(900), h, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        render(Updater.state.value)
    }
}
