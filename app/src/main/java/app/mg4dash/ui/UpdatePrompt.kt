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
 * Au démarrage, propose la dernière release non bêta si elle est plus récente que la version
 * installée : « Ne plus notifier cette release », « Plus tard » ou « Mettre à jour ».
 * Une seule vérification par démarrage de l'appli, dès qu'un réseau est disponible ; les erreurs
 * sont silencieuses. Le popup est une vue posée dans la fenêtre de l'accueil (pas un Dialog) :
 * l'activité garde le focus, donc le mode immersif reste actif et ni la barre latérale ni le
 * bandeau SAIC ne réapparaissent.
 * L'accueil est reconstruit quand le thème passe de clair à sombre : la proposition (et le
 * téléchargement en cours) survit et réapparaît dans la nouvelle fenêtre tant qu'on n'a pas répondu.
 */
object UpdatePrompt {
    private const val TAG = "Update"
    private var checked = false
    /** Release proposée et pas encore écartée ; null une fois répondu « Plus tard » / « Ne plus notifier ». */
    private var pending: Updater.Release? = null
    /** Texte d'état du téléchargement en cours (null : pas lancé), et la fenêtre qui l'affiche. */
    private var progress: String? = null
    private var shownIn: java.lang.ref.WeakReference<Activity>? = null
    private var onProgress: ((String?) -> Unit)? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun maybeShow(activity: Activity) {
        pending?.let { r ->
            if (shownIn?.get() !== activity && activity is LifecycleOwner) show(activity, r)
            return
        }
        if (checked || !StatusMonitor.hasInternet(activity)) return
        checked = true
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
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

    private fun show(activity: Activity, r: Updater.Release) {
        shownIn = java.lang.ref.WeakReference(activity)
        val kit = Kit(activity)
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Voile sombre plein écran : capte les touchers pour que l'accueil reste inactif derrière
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
        val notes = Updater.plainNotes(r.notes)
        if (notes.isNotEmpty()) {
            panel.addView(ScrollView(activity).apply {
                addView(kit.text(notes, 18f, R.color.text_sub).apply { maxLines = Int.MAX_VALUE; setLineSpacing(0f, 1.15f) })
            }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = kit.dp(4) })
        }

        fun close() { pending = null; onProgress = null; root.removeView(scrim); Fullscreen.apply(activity) }
        fun button(label: Int, colorRes: Int, onClick: () -> Unit) = Button(activity).apply {
            setText(label); isAllCaps = false; setTextColor(kit.color(colorRes)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setBackgroundResource(R.drawable.bg_button); minHeight = kit.dp(56); setPadding(kit.dp(20), 0, kit.dp(20), 0)
            stateListAnimator = null; elevation = 0f   // pas de relief ni d'ombre (style Material), comme les autres boutons
            setOnClickListener { onClick() }
        }
        val buttons = kit.row()
        lateinit var skip: Button
        lateinit var later: Button
        lateinit var now: Button
        skip = button(R.string.upd_skip, R.color.text_sub) { Prefs(activity).updateSkipped = r.version; close() }
        later = button(R.string.upd_later, R.color.text_value) { close() }
        // État du téléchargement affiché dans la fenêtre courante (il continue si l'accueil est reconstruit)
        fun render(text: String?) {
            val busy = text != null && text != activity.getString(R.string.st_update_error)
            if (text != null) state.text = text
            skip.isEnabled = !busy; later.isEnabled = !busy; now.isEnabled = !busy
        }
        now = button(R.string.upd_now, R.color.accent_action) {
            val app = activity.applicationContext
            fun report(t: String) { progress = t; onProgress?.invoke(t) }
            if (!Updater.begin()) { report(app.getString(R.string.st_update_busy)); return@button }
            report(app.getString(R.string.st_update_downloading, 0))
            scope.launch {
                try {
                    val apk = withContext(Dispatchers.IO) {
                        Updater.download(app, r) { pct -> scope.launch { report(app.getString(R.string.st_update_downloading, pct)) } }
                    }
                    report(app.getString(R.string.st_update_installing))
                    withContext(Dispatchers.IO) { Updater.install(app, apk) }
                } catch (e: Exception) {
                    Log.w(TAG, "failed: ${e.message}")
                    Updater.end()
                    report(app.getString(R.string.st_update_error))
                }
            }
        }
        onProgress = { render(it) }
        listOf(skip, later, now).forEachIndexed { i, v -> buttons.addView(v, kit.lp(0, -1, 1f, l = if (i > 0) 10 else 0)) }
        panel.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = kit.dp(16) })

        // Hauteur bornée avec des notes : elles défilent au lieu de pousser les boutons hors de l'écran
        val h = if (notes.isEmpty()) -2 else minOf(kit.dp(560), (activity.resources.displayMetrics.heightPixels * 0.85f).toInt())
        scrim.addView(panel, FrameLayout.LayoutParams(kit.dp(900), h, Gravity.CENTER))
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        render(progress)
    }
}
