package app.mg4dash.radar

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.system.DataUsage
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.ui.OptionsPopup
import app.mg4dash.ui.Rows
import app.mg4dash.ui.SourceMenu
import app.mg4dash.ui.SwitchView
import app.mg4dash.ui.Themes

/**
 * The whole alerter in the cards' options window (24/11, once a page with two tabs): Alertes on the left
 * (thresholds, display, each alert's sound), Données on the right (usual trips, while driving, storage) and the
 * OpenStreetMap licence. Same rows as the settings ([Rows]); the window scrolls when it is taller than the screen.
 */
class RadarPage(private val a: Activity, private val prefs: Prefs) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val sel get() = Themes.select(a)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private var correction: TextView? = null
    /** Repaints done by the preparation's progress and the storage figures. */
    private val refreshers = ArrayList<() -> Unit>()

    /** The content of the window: two columns in a scrolling area no taller than the screen. */
    fun window(): View {
        val cols = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        cols.addView(alerts(), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
        cols.addView(data(), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        val screen = a.resources.displayMetrics.heightPixels
        refresh()
        return object : ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(screen - dp(150), View.MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = true; addView(cols) }
    }

    /** The preparation's progress and the storage figures, again (live). */
    fun refresh() {
        refreshers.forEach { it() }
        refreshSpeedCorrection()
    }

    fun refreshSpeedCorrection() {
        correction?.text = RadarRepo.speedCorrection(a)?.let { value ->
            val format = java.text.NumberFormat.getPercentInstance(a.resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
            when {
                value <= -0.05f -> "−" + format.format(-value / 100f)
                value >= 0.05f -> "+" + format.format(value / 100f)
                else -> format.format(0f)
            }
        } ?: "—"
    }

    // ── Building blocks ──

    private fun text(s: String, sp: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col(color)); typeface = face
    }

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        parent.addView(Rows.header(a, title))
        return Rows.group(a).also { parent.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    private fun toggleRow(group: LinearLayout, title: String, sub: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = SwitchView(a).apply { on = get() }
        Rows.add(group, Rows.row(a, title, sub, sw) { set(!get()); sw.on = get() })
    }

    /** A choice: the value in the theme's colour, the whole row opens the menu. */
    private fun choiceRow(group: LinearLayout, title: String, sub: String?, labels: () -> List<String>, current: () -> Int, onPick: (Int) -> Unit): TextView {
        val v = Rows.value(a, labels()[current().coerceIn(0, labels().lastIndex)])
        Rows.add(group, Rows.row(a, title, sub, v) {
            SourceMenu.choose(a, v, labels(), current()) { onPick(it); v.text = labels()[current().coerceIn(0, labels().lastIndex)] + "  ▾" }
        })
        return v
    }

    private fun infoRow(group: LinearLayout, title: String, sub: String?, value: TextView) {
        value.apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(col(R.color.text_value)); gravity = Gravity.END }
        Rows.add(group, Rows.row(a, title, sub, value, null))
    }

    private fun repLabel(sec: Int) = when {
        sec == 0 -> a.getString(R.string.st_rad_rep_never)
        sec < 60 -> a.getString(R.string.rp_rep_s, sec)
        else -> a.getString(R.string.rp_rep_min, sec / 60)
    }

    // ── Alertes ──

    private fun alerts(): LinearLayout {
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val unit = Units.speedUnit(a)
        val modes = ThresholdMode.entries
        // Thresholds: the unit, then the light and strong margins (both bands in mixed mode)
        val th = section(column, a.getString(R.string.rp_thresholds))
        val seg = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; background = Rows.groupBackground(a).apply { setColor(col(R.color.button_idle)) }; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        val segChips = listOf(unit, "%", a.getString(R.string.rp_mixed)).map { l ->
            TextView(a).apply { text = l; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setPadding(dp(16), 0, dp(16), 0) }
        }
        val margins = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun paintSeg() = segChips.forEachIndexed { i, c ->
            val on = i == prefs.radarThresholdMode.ordinal
            c.setTextColor(if (on) sel else col(R.color.text_sub))
            c.background = if (on) GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor((sel and 0x00FFFFFF) or 0x24000000); setStroke(dp(2), sel) } else null
        }
        fun showMargins() {
            margins.removeAllViews()
            val mixed = prefs.radarThresholdMode == ThresholdMode.MIXED
            val bands = if (mixed) listOf(false, true) else listOf(prefs.radarThresholdMode == ThresholdMode.PERCENT)
            for (percent in bands) for (strong in listOf(false, true)) {
                val suffix = if (percent) "%" else if (mixed) "km/h" else unit
                val values = when {
                    strong && percent -> listOf(5, 8, 10, 15, 20)
                    strong -> listOf(3, 5, 6, 8, 10, 15, 20)
                    percent && mixed -> listOf(0, 2, 5)
                    percent -> listOf(0, 2, 5, 10)
                    else -> listOf(0, 1, 2, 3, 5)
                }
                val title = a.getString(if (strong) R.string.rp_strong_over else R.string.rp_light_over)
                val band = if (mixed) a.getString(if (percent) R.string.rp_from_100 else R.string.rp_under_100) else null
                choiceRow(margins, title, band, { values.map { "+$it $suffix" } }, {
                    val value = prefs.radarMargin(strong, percent, mixed)
                    values.indices.minBy { kotlin.math.abs(values[it] - value) }
                }) { prefs.setRadarMargin(strong, percent, mixed, values[it].toFloat()) }
            }
            // The margins' rows move into the group, after the unit's row
            while (th.childCount > 1) th.removeViewAt(th.childCount - 1)
            (0 until margins.childCount).map { margins.getChildAt(it) }.also { margins.removeAllViews() }.filter { it.tag == Rows.TAG_ROW }.forEach { Rows.add(th, it) }
            correctionRow(th)
        }
        segChips.forEachIndexed { i, c ->
            c.setOnClickListener { prefs.radarThresholdMode = modes[i]; paintSeg(); showMargins() }
            seg.addView(c, LinearLayout.LayoutParams(-2, dp(48)).apply { if (i > 0) leftMargin = dp(4) })
        }
        paintSeg()
        Rows.add(th, Rows.row(a, a.getString(R.string.rp_threshold_unit), null, seg, null))
        showMargins()
        // Display
        val show = section(column, a.getString(R.string.rp_display))
        toggleRow(show, a.getString(R.string.rp_visual), a.getString(R.string.rp_visual_sub), { prefs.radarVisual }) { prefs.radarVisual = it }
        toggleRow(show, a.getString(R.string.rp_banner), null, { prefs.radarBanner }) { prefs.radarBanner = it }
        // Each alert's sound: the sound on the row, its volume and repeat by touching the row
        val snd = section(column, a.getString(R.string.rp_sounds))
        val sounds = RadarAlert.Sound.values().toList()
        val soundNames = listOf(R.string.st_rad_snd_none, R.string.st_rad_snd_chime, R.string.st_rad_snd_beep, R.string.st_rad_snd_loud).map { a.getString(it) }
        listOf(
            Triple(RadarAlert.Kind.APPROACH, R.string.rp_kind_approach to R.string.rp_kind_approach_sub, col(R.color.accent_action)),
            Triple(RadarAlert.Kind.LIGHT, R.string.rp_kind_light to R.string.rp_kind_light_sub, col(R.color.accent_heat)),
            Triple(RadarAlert.Kind.STRONG, R.string.rp_kind_strong to R.string.rp_kind_strong_sub, col(R.color.accent_danger)),
            Triple(RadarAlert.Kind.ZONE, R.string.rp_kind_zone to R.string.rp_kind_zone_sub, 0xFFFF1744.toInt()),
        ).forEach { (k, names, color) ->
            val soundValue = Rows.value(a, soundNames[sounds.indexOf(prefs.radarSoundOf(k))])
            val vol = text("", 17f, R.color.text_sub).apply { gravity = Gravity.END }
            fun paint() {
                soundValue.text = soundNames[sounds.indexOf(prefs.radarSoundOf(k))] + "  ▾"
                val on = prefs.radarSoundOf(k) != RadarAlert.Sound.NONE
                vol.text = if (on) "${prefs.radarVolume(k)} %" + (if (k != RadarAlert.Kind.APPROACH) "\n" + repLabel(prefs.radarRepeat(k)) else "") else ""
            }
            val right = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            soundValue.setOnClickListener { v -> SourceMenu.choose(a, v, soundNames, sounds.indexOf(prefs.radarSoundOf(k))) { prefs.setRadarSoundOf(k, sounds[it]); paint() } }
            right.addView(soundValue)
            right.addView(vol, LinearLayout.LayoutParams(dp(150), -2).apply { leftMargin = dp(14) })
            right.addView(TextView(a).apply {
                text = "▶"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(sel)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), sel) }
                contentDescription = a.getString(R.string.rp_play)
                setOnClickListener { RadarAlert.play(a, k) }
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(14) })
            val row = Rows.row(a, a.getString(names.first), a.getString(names.second), right) {
                // Its volume (and how often it repeats), in a small window over this one
                val w = OptionsPopup(a, a.getString(names.first))
                    .slider(a.getString(R.string.rp_col_volume), 100, { prefs.radarVolume(k) }, { "$it %" }, { prefs.radarSoundOf(k) != RadarAlert.Sound.NONE }) {
                        prefs.setRadarVolume(k, it); RadarAlert.play(a, k)
                    }
                if (k != RadarAlert.Kind.APPROACH) w.choice(a.getString(R.string.rp_col_repeat), null, RadarAlert.REPEATS.map { repLabel(it) },
                    { RadarAlert.REPEATS.indexOf(prefs.radarRepeat(k)).coerceAtLeast(0) }) { prefs.setRadarRepeat(k, RadarAlert.REPEATS[it]) }
                w.show { paint() }
            }
            row.addView(View(a).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color) } },
                0, LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(14) })
            paint()
            Rows.add(snd, row)
        }
        column.addView(text(a.getString(R.string.rp_sounds_note), 15f, R.color.text_sub).apply { setPadding(dp(4), dp(10), dp(4), 0) })
        return column
    }

    private fun correctionRow(group: LinearLayout) {
        correction = TextView(a)
        infoRow(group, a.getString(R.string.rp_gps_correction), a.getString(R.string.rp_gps_correction_sub), correction!!)
        refreshSpeedCorrection()
    }

    // ── Données ──

    private fun data(): LinearLayout {
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        // Usual trips: the radius (and what it costs), the preparation's progress and its button
        val trips = section(column, a.getString(R.string.rp_trips))
        val radii = RadarRepo.PREPARE_RADII
        fun estimate(): String = RadarRepo.prepareEstimate(prefs.radarPrepareKm).let { e ->
            a.getString(R.string.st_rad_estimate, e.boxes, DataUsage.format(a, e.download), DataUsage.format(a, e.kept), e.minutes)
        }
        lateinit var radiusRow: LinearLayout
        val radiusValue = choiceRow(trips, a.getString(R.string.rp_radius), estimate(), { radii.map { "$it km" } },
            { radii.indexOf(prefs.radarPrepareKm).coerceAtLeast(0) }) { prefs.radarPrepareKm = radii[it]; refresh() }
        radiusRow = radiusValue.parent as LinearLayout
        refreshers += { ((radiusRow.getChildAt(0) as LinearLayout).getChildAt(1) as? TextView)?.text = estimate() }
        val state = text("", 19f, R.color.text_value, medium)
        val detail = text("", 14f, R.color.text_sub)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(sel); progressBackgroundTintList = ColorStateList.valueOf(col(R.color.button_idle))
        }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(state); texts.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        texts.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        lateinit var main: TextView
        main = Rows.actionButton(a, a.getString(R.string.st_rad_prepare)) {
            if (RadarRepo.prepare.value?.running == true) RadarRepo.cancelPrepare()
            else if (!RadarRepo.prepareTrips(a)) app.mg4dash.ui.Toasts.show(a, R.string.st_rad_prepare_no)
            refresh()
        }
        val prep = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = Rows.TAG_ROW; minimumHeight = dp(Rows.ROW_DP); setPadding(0, dp(12), 0, dp(12)) }
        prep.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        prep.addView(main, LinearLayout.LayoutParams(-2, dp(56)).apply { leftMargin = dp(20) })
        Rows.add(trips, prep)
        refreshers += {
            val p = RadarRepo.prepare.value
            val running = p?.running == true
            val busyLeft = p?.retryLeftS(System.currentTimeMillis())
            state.text = (if (busyLeft != null) a.getString(R.string.rp_busy,
                if (busyLeft >= 60) a.getString(R.string.rp_rep_min, ((busyLeft + 30) / 60).toInt()) else a.getString(R.string.rp_rep_s, busyLeft.toInt()))
            else a.getString(when (p?.phase) {
                RadarRepo.PrepPhase.RUNNING, RadarRepo.PrepPhase.RETRY -> R.string.rp_prep_running
                RadarRepo.PrepPhase.WAIT_WIFI -> R.string.rp_prep_wait
                RadarRepo.PrepPhase.PAUSED -> R.string.rp_prep_paused
                RadarRepo.PrepPhase.DONE -> R.string.rp_prep_done
                RadarRepo.PrepPhase.NO_WIFI, RadarRepo.PrepPhase.CANCELLED -> R.string.rp_prep_stopped
                null -> R.string.rp_prep_none
            })) + (p?.takeIf { it.total > 0 }?.let { "   ${it.done} / ${it.total}" } ?: "")
            // Nothing run in this session: what is stored (zones, and when they were prepared)
            if (p == null) state.text = RadarRepo.storedPreparation(a, RadarRepo.cacheSize(a).first, RadarRepo.preparedAt(a))
            bar.progress = p?.takeIf { it.total > 0 }?.let { it.done * 1000 / it.total } ?: 0
            detail.text = listOfNotNull(
                p?.let { a.getString(R.string.rp_prep_detail, DataUsage.format(a, it.bytes)) },
                p?.minutesLeft?.takeIf { running }?.let { m -> a.getString(R.string.rp_prep_left, Math.max(1, m)) },
                p?.takeIf { it.failed > 0 }?.let { a.getString(R.string.rp_prep_failed, it.failed) }).joinToString(" · ")
            detail.visibility = if (detail.text.isEmpty()) View.GONE else View.VISIBLE
            // One button: Stop while it runs, Prepare otherwise (preparing again goes on where it stopped)
            main.text = a.getString(if (running) R.string.rp_stop else R.string.st_rad_prepare)
        }
        // While driving
        val driving = section(column, a.getString(R.string.rp_driving))
        toggleRow(driving, a.getString(R.string.st_rad_wifi), a.getString(R.string.rp_wifi_sub), { prefs.radarWifiOnly }) { prefs.radarWifiOnly = it }
        val ages = RadarRepo.REFRESH_HOURS
        fun ageLabel(h: Int) = if (h < 24) a.getString(R.string.st_rad_age_hours, h) else a.getString(R.string.st_rad_age_days, h / 24)
        choiceRow(driving, a.getString(R.string.rp_refresh), a.getString(R.string.rp_refresh_sub), { ages.map { ageLabel(it) } },
            { ages.indexOf(prefs.radarRefreshHours).coerceAtLeast(0) }) { prefs.radarRefreshHours = ages[it] }
        // Storage
        val storage = section(column, a.getString(R.string.rp_storage))
        val space = TextView(a); val month = TextView(a)
        infoRow(storage, a.getString(R.string.rp_space), a.getString(R.string.rp_space_sub), space)
        infoRow(storage, a.getString(R.string.rp_month), a.getString(R.string.rp_month_sub), month)
        refreshers += {
            val (n, bytes) = RadarRepo.cacheSize(a)
            space.text = a.getString(R.string.rp_space_val, DataUsage.format(a, bytes), n)
            val s = DataUsage.last
            month.text = a.getString(R.string.rp_month_val, DataUsage.format(a, s?.of(DataUsage.Use.RADARS, true, false) ?: 0L),
                DataUsage.format(a, s?.of(DataUsage.Use.RADARS, false, true) ?: 0L))
        }
        val clear = Rows.actionButton(a, a.getString(R.string.opt_clear), danger = true) {
            app.mg4dash.ui.Windows.confirm(a, a.getString(R.string.st_rad_clear), a.getString(R.string.st_rad_clear_msg, DataUsage.format(a, RadarRepo.cacheSize(a).second)),
                a.getString(R.string.st_rad_clear_btn)) { RadarRepo.clearCache(a); space.postDelayed({ refresh() }, 500) }
        }
        Rows.add(storage, Rows.row(a, a.getString(R.string.st_rad_clear), null, clear, null))
        column.addView(text(a.getString(R.string.rp_osm_box), 14f, R.color.text_sub).apply { setPadding(dp(4), dp(12), dp(4), 0) })
        return column
    }
}

