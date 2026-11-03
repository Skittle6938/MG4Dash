package app.mg4dash.apps

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.mg4dash.R
import app.mg4dash.system.Log

/**
 * List of installed apps (long press on "Home" in the dock), with open apps at the top
 * (#70, #71): a cross closes the app, "Close all" closes all those that do not hold the car.
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var openSection: LinearLayout
    private lateinit var openRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.mg4dash.ui.Themes.apply(this)
        val pm = packageManager
        val apps: List<ResolveInfo> = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
        ).filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Top: fog light area, unless the vertical bar takes it
            setPadding(dp(24), dp(if (app.mg4dash.ui.Dock.vertical(this@AppsActivity)) 12 else 84), dp(24), dp(24))
        }

        // ── Ouvertes ──
        openSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(sectionTitle(R.string.apps_open), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(TextView(this).apply {
            setText(R.string.apps_close_all); gravity = Gravity.CENTER
            setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundResource(R.drawable.bg_chip); setPadding(dp(22), 0, dp(22), 0)
            setOnClickListener { closeAll() }
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { rightMargin = dp(6) })
        openSection.addView(head)
        openRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        openSection.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(openRow) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        openSection.addView(View(this).apply { setBackgroundColor(color(R.color.panel_border)) },
            LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(18); leftMargin = dp(6); rightMargin = dp(6) })
        root.addView(openSection)

        // ── All apps ──
        root.addView(sectionTitle(R.string.apps_all), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        // As many columns as the width left by the vertical bar allows
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density - if (app.mg4dash.ui.Dock.vertical(this)) 120 else 48
        val grid = GridLayout(this).apply { columnCount = (widthDp / 208).toInt().coerceIn(4, 8) }
        apps.forEach { ri ->
            val cell = tile(ri.loadIcon(pm), ri.loadLabel(pm)) {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(i)
                finish()
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = dp(196); height = dp(150)
                setMargins(dp(6), dp(6), dp(6), dp(6))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        // Same menu bar as the other pages (#79)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)) }
        page.addView(ScrollView(this).apply { addView(root) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dock = LinearLayout(this)
        page.addView(dock, LinearLayout.LayoutParams(-1, dp(84)).apply { topMargin = dp(8) })
        app.mg4dash.ui.Dock.mount(this, page, dock, app.mg4dash.ui.Dock.Page.APPS)
        setContentView(page)
        renderOpen()
    }

    override fun onResume() {
        super.onResume()
        app.mg4dash.ui.Fullscreen.apply(this)
        if (::openRow.isInitialized) renderOpen()
    }

    /** Open apps: running tasks, without MG4Dash, the SAIC launchers or the system UI. */
    private fun openPackages(): List<String> = try {
        @Suppress("DEPRECATION")
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getRunningTasks(30)
            .mapNotNull { it.baseActivity?.packageName ?: it.topActivity?.packageName }
            .distinct()
            .filter { it !in KEEP && packageManager.getLaunchIntentForPackage(it) != null }
    } catch (e: Exception) { Log.w(TAG, "running tasks: ${e.message}"); emptyList() }

    private fun renderOpen() {
        val pm = packageManager
        val pkgs = openPackages()
        openRow.removeAllViews()
        openSection.visibility = if (pkgs.isEmpty()) View.GONE else View.VISIBLE
        pkgs.forEach { pkg ->
            val ai = try { pm.getApplicationInfo(pkg, 0) } catch (_: Exception) { return@forEach }
            val f = FrameLayout(this)
            f.addView(tile(ai.loadIcon(pm), ai.loadLabel(pm)) {
                pm.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() }
            }, FrameLayout.LayoutParams(-1, -1))
            f.addView(TextView(this).apply {
                text = "✕"; gravity = Gravity.CENTER; setTextColor(color(R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setBackgroundResource(R.drawable.bg_chip)
                setOnClickListener { close(pkg); renderOpen() }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(4); rightMargin = dp(4) })
            openRow.addView(f, LinearLayout.LayoutParams(dp(196), dp(150)).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
    }

    /** Closes an app (force stop, allowed to MG4Dash as a system app). */
    private fun close(pkg: String) {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.javaClass.getMethod("forceStopPackage", String::class.java).invoke(am, pkg)
            Log.i(TAG, "closed $pkg")
        } catch (e: Exception) { Log.w(TAG, "close $pkg: ${(e.cause ?: e).message}") }
    }

    /** Close all: except Android Auto / CarPlay / DiAuto (the phone connection) and SAIC apps. */
    private fun closeAll() {
        openPackages().filter { !it.startsWith("com.allgo.") && !it.startsWith("com.saicmotor.") && !it.startsWith("com.drivehub.") }
            .forEach { close(it) }
        renderOpen()
    }

    private fun tile(icon: android.graphics.drawable.Drawable, label: CharSequence, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_dock_item)
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
            addView(ImageView(this@AppsActivity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(72), dp(72)))
            addView(TextView(this@AppsActivity).apply {
                text = label; maxLines = 2; gravity = Gravity.CENTER
                setTextColor(color(R.color.text_sub))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

    private fun sectionTitle(res: Int) = TextView(this).apply {
        setText(res); setTextColor(color(R.color.text_title)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        letterSpacing = 0.12f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        setPadding(dp(6), 0, 0, 0)
    }

    private fun color(res: Int) = app.mg4dash.ui.Themes.color(this, res)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Apps"
        private val KEEP = setOf("app.mg4dash", "com.saicmotor.launcher", app.mg4dash.system.Firmware.LEGACY_LAUNCHER,
            "com.android.systemui", "android", "com.android.settings")
    }
}
