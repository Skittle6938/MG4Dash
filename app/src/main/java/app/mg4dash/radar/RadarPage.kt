package app.mg4dash.radar

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.mg4dash.R
import app.mg4dash.system.DataUsage
import app.mg4dash.system.Prefs
import app.mg4dash.system.Units
import app.mg4dash.ui.Themes

/**
 * The alerter's settings page (Marc's mockups, 17/11): three tabs on the left, Alertes (thresholds, banner, the sound of
 * each alert), Données (prepared trips with their progress, while driving, storage), Voiture (ISA) (the car's limit read,
 * OSM's limit sent); the OpenStreetMap licence under the tabs. Built in code in MG4Dash's style (panels, outlined chips).
 */
class RadarPage(private val a: Activity, private val prefs: Prefs) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun col(res: Int) = Themes.color(a, res)
    private val accent get() = col(R.color.accent_action)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val tabs = ArrayList<Pair<View, View>>()
    private var dataSub: TextView? = null
    /** Repaints done by the preparation's progress and the storage figures. */
    private val refreshers = ArrayList<() -> Unit>()

    enum class Tab { ALERTS, DATA, CAR }

    /** The page's body: tabs on the left, the content on the right. [start]: the tab shown first. */
    fun build(start: Tab = Tab.ALERTS): View {
        val body = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val side = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(side, LinearLayout.LayoutParams(dp(300), -1).apply { rightMargin = dp(16) })
        val pages = FrameLayout(a)
        body.addView(pages, LinearLayout.LayoutParams(0, -1, 1f))
        fun page(content: View) = ScrollView(a).apply { isVerticalScrollBarEnabled = true; addView(content) }.also { pages.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        val alerts = page(alertsTab()); val data = page(dataTab()); val car = page(carTab())
        listOf(Triple(Tab.ALERTS, R.string.rp_tab_alerts, R.string.rp_tab_alerts_sub), Triple(Tab.DATA, R.string.rp_tab_data, R.string.rp_tab_data_sub),
            Triple(Tab.CAR, R.string.rp_tab_car, R.string.rp_tab_car_sub)).forEachIndexed { i, (t, title, sub) ->
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(22), dp(14), dp(18), dp(14)); setBackgroundResource(R.drawable.bg_chip); isClickable = true
            }
            card.addView(text(a.getString(title), 21f, R.color.text_value, medium))
            val s = text(a.getString(sub), 15f, R.color.text_sub)
            card.addView(s, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            if (t == Tab.DATA) dataSub = s
            side.addView(card, LinearLayout.LayoutParams(-1, dp(104)).apply { if (i > 0) topMargin = dp(12) })
            tabs += card to listOf(alerts, data, car)[i]
            card.setOnClickListener { show(i) }
        }
        side.addView(View(a), LinearLayout.LayoutParams(-1, 0, 1f))
        // The licence, in a dashed box under the tabs
        side.addView(text(a.getString(R.string.rp_osm_box), 14f, R.color.text_sub).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setStroke(dp(1), col(R.color.text_sub), dp(6).toFloat(), dp(4).toFloat()) }
        }, LinearLayout.LayoutParams(-1, -2))
        show(start.ordinal)
        refresh()
        return body
    }

    private fun show(i: Int) = tabs.forEachIndexed { k, (card, page) -> card.isSelected = k == i; page.visibility = if (k == i) View.VISIBLE else View.GONE }

    /** The preparation's progress and the storage figures, again (live). */
    fun refresh() {
        val p = RadarRepo.prepare.value
        dataSub?.text = if (p?.running == true) "● " + a.getString(R.string.rp_tab_data_prep, p.done, p.total) else a.getString(R.string.rp_tab_data_sub)
        dataSub?.setTextColor(if (p?.running == true) accent else col(R.color.text_sub))
        refreshers.forEach { it() }
    }

    // ── Building blocks ──

    private fun text(s: String, sp: Float, color: Int, face: Typeface = Typeface.DEFAULT) = TextView(a).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(col(color)); typeface = face
    }

    private fun panel(title: String): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.bg_panel); elevation = dp(6).toFloat()
        setPadding(dp(22), dp(16), dp(22), dp(18))
        addView(text(title.uppercase(), 16f, R.color.text_title, medium).apply { letterSpacing = 0.12f })
    }

    private fun chip(label: String, onClick: (View) -> Unit) = TextView(a).apply {
        text = label; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = medium
        setBackgroundResource(R.drawable.bg_chip); setTextColor(ContextCompat.getColorStateList(a, R.color.chip_text))
        setPadding(dp(18), 0, dp(18), 0); setOnClickListener { onClick(it) }
    }

    /** A drop-down: the value shown, the choices in the same menu as the settings'. */
    private fun dropdown(labels: () -> List<String>, current: () -> Int, onPick: (Int) -> Unit): TextView {
        lateinit var v: TextView
        fun paint() { v.text = labels()[current().coerceIn(0, labels().lastIndex)] + "  ▾" }
        v = chip("") { view -> app.mg4dash.ui.SourceMenu.choose(a, view, labels(), current()) { onPick(it); paint() } }.apply { isSelected = true }
        paint()
        return v
    }

    /** OUI / NON. */
    private fun toggle(get: () -> Boolean, set: (Boolean) -> Unit): TextView {
        lateinit var v: TextView
        fun paint() { v.text = a.getString(if (get()) R.string.yes else R.string.no); v.isSelected = get() }
        v = chip("") { set(!get()); paint() }
        paint()
        return v
    }

    /** Segmented choice: the chosen one outlined. */
    private fun segmented(labels: List<String>, current: () -> Int, onPick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        fun paint() = chips.forEachIndexed { i, c -> c.isSelected = i == current() }
        labels.forEachIndexed { i, l ->
            val c = chip(l) { onPick(i); paint() }
            chips += c
            row.addView(c, LinearLayout.LayoutParams(-2, dp(52)).apply { if (i > 0) leftMargin = dp(6) })
        }
        paint()
        return row
    }

    /** A setting line: title and grey subtitle on the left, the control on the right. */
    private fun line(parent: LinearLayout, title: String, sub: String?, control: View, controlWidth: Int = -2): LinearLayout {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 18f, R.color.text_value))
        sub?.let { texts.addView(text(it, 14f, R.color.text_sub), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) }) }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
        row.addView(control, LinearLayout.LayoutParams(if (controlWidth > 0) dp(controlWidth) else -2, dp(52)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        return row
    }

    private fun dot(color: Int, size: Int = 14) = View(a).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { rightMargin = dp(8) }
    }

    private fun repLabel(sec: Int) = when {
        sec == 0 -> a.getString(R.string.st_rad_rep_never)
        sec < 60 -> a.getString(R.string.rp_rep_s, sec)
        else -> a.getString(R.string.rp_rep_min, sec / 60)
    }

    // ── Alertes ──

    private fun alertsTab(): View {
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val unit = Units.speedUnit(a)
        // Thresholds, banner and colours on the card
        val th = panel(a.getString(R.string.rp_thresholds))
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun lows() = if (prefs.radarPercent) listOf(0f, 2f, 5f, 10f) else listOf(0f, 1f, 2f, 3f, 5f)
        fun highs() = if (prefs.radarPercent) listOf(5f, 8f, 10f, 15f, 20f) else listOf(3f, 5f, 6f, 8f, 10f, 15f, 20f)
        fun label(v: Float) = "+" + (if (v % 1f == 0f) v.toInt().toString() else v.toString()) + " " + (if (prefs.radarPercent) "%" else unit)
        fun index(list: List<Float>, v: Float) = list.indices.minByOrNull { Math.abs(list[it] - v) } ?: 0
        val low = dropdown({ lows().map { label(it) } }, { index(lows(), prefs.radarLow) }) { prefs.radarLow = lows()[it] }
        val high = dropdown({ highs().map { label(it) } }, { index(highs(), prefs.radarHigh) }) { prefs.radarHigh = highs()[it] }
        fun repaintThresholds() { low.text = label(lows()[index(lows(), prefs.radarLow)]) + "  ▾"; high.text = label(highs()[index(highs(), prefs.radarHigh)]) + "  ▾" }
        row.addView(text(a.getString(R.string.rp_in), 18f, R.color.text_value), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(segmented(listOf(unit, "%"), { if (prefs.radarPercent) 1 else 0 }) { prefs.radarPercent = it == 1; repaintThresholds() })
        row.addView(View(a), LinearLayout.LayoutParams(dp(28), 1))
        row.addView(dot(col(R.color.accent_heat)))
        row.addView(text(a.getString(R.string.rp_light_over), 18f, R.color.text_value), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(low, LinearLayout.LayoutParams(-2, dp(52)))
        row.addView(View(a), LinearLayout.LayoutParams(dp(28), 1))
        row.addView(dot(col(R.color.accent_danger)))
        row.addView(text(a.getString(R.string.rp_strong_over), 18f, R.color.text_value), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
        row.addView(high, LinearLayout.LayoutParams(-2, dp(52)))
        th.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val two = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val right = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        two.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(16) })
        two.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        line(left, a.getString(R.string.rp_visual), null, toggle({ prefs.radarVisual }) { prefs.radarVisual = it }, 130)
        line(right, a.getString(R.string.rp_banner), null, toggle({ prefs.radarBanner }) { prefs.radarBanner = it }, 130)
        th.addView(two, LinearLayout.LayoutParams(-1, -2))
        col.addView(th, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        col.addView(soundsPanel(), LinearLayout.LayoutParams(-1, -2))
        return col
    }

    private fun soundsPanel(): LinearLayout {
        val p = panel(a.getString(R.string.rp_sounds))
        val sounds = RadarAlert.Sound.values().toList()
        val soundNames = listOf(R.string.st_rad_snd_none, R.string.st_rad_snd_chime, R.string.st_rad_snd_beep, R.string.st_rad_snd_loud).map { a.getString(it) }
        val reps = RadarAlert.REPEATS
        // Column widths: alert, sound, volume, repeat, play
        // The volume wide, for an easy setting (Marc, 17/11)
        val w = intArrayOf(0, 190, 460, 160, 60)
        fun header() = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(R.string.rp_col_alert, R.string.rp_col_sound, R.string.rp_col_volume, R.string.rp_col_repeat).forEachIndexed { i, s ->
                addView(text(a.getString(s).uppercase(), 13f, R.color.text_sub, medium).apply { letterSpacing = 0.1f },
                    if (i == 0) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(dp(w[i]), -2).apply { leftMargin = dp(12) })
            }
            addView(View(a), LinearLayout.LayoutParams(dp(w[4]), 1).apply { leftMargin = dp(12) })
        }
        p.addView(header(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        listOf(
            Triple(RadarAlert.Kind.APPROACH, R.string.rp_kind_approach to R.string.rp_kind_approach_sub, accent),
            Triple(RadarAlert.Kind.LIGHT, R.string.rp_kind_light to R.string.rp_kind_light_sub, col(R.color.accent_heat)),
            Triple(RadarAlert.Kind.STRONG, R.string.rp_kind_strong to R.string.rp_kind_strong_sub, col(R.color.accent_danger)),
            Triple(RadarAlert.Kind.ZONE, R.string.rp_kind_zone to R.string.rp_kind_zone_sub, 0xFFFF1744.toInt()),
        ).forEach { (k, names, color) ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            name.addView(View(a).apply { background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(color) } },
                LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(12) })
            val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(text(a.getString(names.first), 18f, R.color.text_value))
            texts.addView(text(a.getString(names.second), 14f, R.color.text_sub))
            name.addView(texts)
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            // Volume: slider with its %, dimmed when the sound is none
            val volBox = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val pct = text("", 16f, R.color.text_value)
            val seek = SeekBar(a).apply {
                max = 100; progress = prefs.radarVolume(k)
                progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { pct.text = "$v %"; if (fromUser) prefs.setRadarVolume(k, v) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            pct.text = "${prefs.radarVolume(k)} %"
            // A tall touch area: the whole row's height, not just the thin bar
            seek.minimumHeight = dp(56)
            volBox.addView(seek, LinearLayout.LayoutParams(0, dp(56), 1f))
            volBox.addView(pct, LinearLayout.LayoutParams(dp(60), -2))
            fun dim() { val on = prefs.radarSoundOf(k) != RadarAlert.Sound.NONE; volBox.alpha = if (on) 1f else 0.35f; seek.isEnabled = on }
            val sound = dropdown({ soundNames }, { sounds.indexOf(prefs.radarSoundOf(k)) }) { prefs.setRadarSoundOf(k, sounds[it]); dim() }
            dim()
            row.addView(sound, LinearLayout.LayoutParams(dp(w[1]), dp(52)).apply { leftMargin = dp(12) })
            row.addView(volBox, LinearLayout.LayoutParams(dp(w[2]), -2).apply { leftMargin = dp(12) })
            if (k == RadarAlert.Kind.APPROACH) row.addView(text("—", 18f, R.color.text_sub).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(w[3]), -2).apply { leftMargin = dp(12) })
            else row.addView(dropdown({ reps.map { repLabel(it) } }, { reps.indexOf(prefs.radarRepeat(k)).coerceAtLeast(0) }) { prefs.setRadarRepeat(k, reps[it]) },
                LinearLayout.LayoutParams(dp(w[3]), dp(52)).apply { leftMargin = dp(12) })
            row.addView(TextView(a).apply {
                text = "▶"; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(accent)
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(2), accent) }
                contentDescription = a.getString(R.string.rp_play)
                setOnClickListener { RadarAlert.play(a, k) }
            }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { leftMargin = dp(12 + (w[4] - 52)) })
            p.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        }
        p.addView(text(a.getString(R.string.rp_sounds_note), 14f, R.color.text_sub), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        return p
    }

    // ── Données ──

    private fun dataTab(): View {
        val two = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val trips = panel(a.getString(R.string.rp_trips))
        val driving = panel(a.getString(R.string.rp_driving))
        two.addView(trips, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        two.addView(driving, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })

        // Prepared trips
        val radii = RadarRepo.PREPARE_RADII
        val estimate = text("", 15f, R.color.text_sub)
        fun showEstimate() {
            val e = RadarRepo.prepareEstimate(prefs.radarPrepareKm)
            estimate.text = a.getString(R.string.st_rad_estimate, e.boxes, DataUsage.format(a, e.download), DataUsage.format(a, e.kept), e.minutes)
        }
        line(trips, a.getString(R.string.rp_radius), a.getString(R.string.rp_radius_sub),
            segmented(radii.map { "$it km" }, { radii.indexOf(prefs.radarPrepareKm).coerceAtLeast(0) }) { prefs.radarPrepareKm = radii[it]; showEstimate() })
        trips.addView(estimate, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        showEstimate()
        // The progress box
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(14), dp(18), dp(16))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor((accent and 0x00FFFFFF) or 0x1A000000); setStroke(dp(2), accent) }
        }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val state = text("", 18f, R.color.text_value, medium)
        val count = text("", 20f, R.color.text_value, Typeface.create("sans-serif", Typeface.BOLD)).apply { setTextColor(accent); gravity = Gravity.END }
        head.addView(state, LinearLayout.LayoutParams(0, -2, 1f)); head.addView(count)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(accent); progressBackgroundTintList = ColorStateList.valueOf(col(R.color.text_sub))
        }
        val foot = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val detail = text("", 14f, R.color.text_sub); val failed = text("", 14f, R.color.text_sub).apply { gravity = Gravity.END }
        foot.addView(detail, LinearLayout.LayoutParams(0, -2, 1f)); foot.addView(failed)
        box.addView(head); box.addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(10) }); box.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        trips.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val buttons = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val main = chip("") {}
        val cancel = chip(a.getString(R.string.rp_cancel)) { RadarRepo.cancelPrepare(); refresh() }
        buttons.addView(main, LinearLayout.LayoutParams(0, dp(56), 1f))
        buttons.addView(cancel, LinearLayout.LayoutParams(0, dp(56), 1f).apply { leftMargin = dp(10) })
        trips.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        refreshers += {
            val p = RadarRepo.prepare.value
            val running = p?.running == true
            val busyLeft = p?.retryLeftS(System.currentTimeMillis())
            state.text = "● " + if (busyLeft != null) a.getString(R.string.rp_busy,
                if (busyLeft >= 60) a.getString(R.string.rp_rep_min, ((busyLeft + 30) / 60).toInt()) else a.getString(R.string.rp_rep_s, busyLeft.toInt()))
            else a.getString(when (p?.phase) {
                RadarRepo.PrepPhase.RUNNING, RadarRepo.PrepPhase.RETRY -> R.string.rp_prep_running
                RadarRepo.PrepPhase.WAIT_WIFI -> R.string.rp_prep_wait
                RadarRepo.PrepPhase.PAUSED -> R.string.rp_prep_paused
                RadarRepo.PrepPhase.DONE -> R.string.rp_prep_done
                RadarRepo.PrepPhase.NO_WIFI, RadarRepo.PrepPhase.CANCELLED -> R.string.rp_prep_stopped
                null -> R.string.rp_prep_none
            })
            count.text = p?.takeIf { it.total > 0 }?.let { "${it.done} / ${it.total} · ${it.done * 100 / it.total} %" } ?: ""
            bar.progress = p?.takeIf { it.total > 0 }?.let { it.done * 1000 / it.total } ?: 0
            detail.text = p?.let { a.getString(R.string.rp_prep_detail, DataUsage.format(a, it.bytes)) +
                (it.minutesLeft?.takeIf { _ -> running }?.let { m -> " · " + a.getString(R.string.rp_prep_left, Math.max(1, m)) } ?: "") } ?: ""
            failed.text = p?.takeIf { it.failed > 0 }?.let { a.getString(R.string.rp_prep_failed, it.failed) } ?: ""
            main.text = a.getString(when { !running -> R.string.st_rad_prepare; p?.phase == RadarRepo.PrepPhase.PAUSED -> R.string.rp_resume; else -> R.string.rp_pause })
            main.isSelected = true
            main.setOnClickListener {
                val q = RadarRepo.prepare.value
                when {
                    q?.running != true -> if (!RadarRepo.prepareTrips(a)) app.mg4dash.ui.Toasts.show(a, R.string.st_rad_prepare_no)
                    q.phase == RadarRepo.PrepPhase.PAUSED -> RadarRepo.pausePrepare(false)
                    else -> RadarRepo.pausePrepare(true)
                }
                refresh()
            }
            cancel.visibility = if (running) View.VISIBLE else View.GONE
        }

        // While driving
        line(driving, a.getString(R.string.st_rad_wifi), a.getString(R.string.rp_wifi_sub), toggle({ prefs.radarWifiOnly }) { prefs.radarWifiOnly = it }, 130)
        val ages = RadarRepo.REFRESH_HOURS
        fun ageLabel(h: Int) = if (h < 24) a.getString(R.string.st_rad_age_hours, h) else a.getString(R.string.st_rad_age_days, h / 24)
        line(driving, a.getString(R.string.rp_refresh), a.getString(R.string.rp_refresh_sub),
            dropdown({ ages.map { ageLabel(it) } }, { ages.indexOf(prefs.radarRefreshHours).coerceAtLeast(0) }) { prefs.radarRefreshHours = ages[it] }, 150)
        driving.addView(text(a.getString(R.string.rp_storage).uppercase(), 16f, R.color.text_title, medium).apply { letterSpacing = 0.12f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26) })
        val space = text("", 17f, R.color.text_value)
        val month = text("", 17f, R.color.text_value)
        line(driving, a.getString(R.string.rp_space), a.getString(R.string.rp_space_sub), space)
        line(driving, a.getString(R.string.rp_month), a.getString(R.string.rp_month_sub), month)
        refreshers += {
            val (n, bytes) = RadarRepo.cacheSize(a)
            space.text = a.getString(R.string.rp_space_val, DataUsage.format(a, bytes), n)
            val s = DataUsage.last
            month.text = a.getString(R.string.rp_month_val, DataUsage.format(a, s?.of(DataUsage.Use.RADARS, true, false) ?: 0L),
                DataUsage.format(a, s?.of(DataUsage.Use.RADARS, false, true) ?: 0L))
        }
        driving.addView(chip(a.getString(R.string.st_rad_clear)) {
            AlertDialog.Builder(a).setTitle(R.string.st_rad_clear)
                .setMessage(a.getString(R.string.st_rad_clear_msg, DataUsage.format(a, RadarRepo.cacheSize(a).second)))
                .setPositiveButton(R.string.st_rad_clear_btn) { _, _ -> RadarRepo.clearCache(a); space.postDelayed({ refresh() }, 500) }
                .setNegativeButton(R.string.cancel, null).show().also { app.mg4dash.ui.Dialogs.fix(it) }
        }.apply { setTextColor(col(R.color.accent_danger)) }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(18) })
        return two
    }

    // ── Voiture (ISA) ──

    private fun carTab(): View {
        val two = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        val read = panel(a.getString(R.string.rp_read))
        val send = panel(a.getString(R.string.rp_send))
        two.addView(read, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        two.addView(send, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        line(read, a.getString(R.string.rp_isa_use), a.getString(R.string.rp_isa_use_sub), toggle({ prefs.radarIsaFallback }) { prefs.radarIsaFallback = it }, 130)
        // Example: a sign with "ISA" under its number
        val ex = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ex.addView(object : View(a) {
            private val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val r = width / 2f; val cx = r; val cy = height / 2f
                pt.style = android.graphics.Paint.Style.FILL; pt.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, pt)
                pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = r * 0.2f; pt.color = 0xFFD32F2F.toInt(); c.drawCircle(cx, cy, r - pt.strokeWidth / 2, pt)
                pt.style = android.graphics.Paint.Style.FILL; pt.color = 0xFF111111.toInt(); pt.textAlign = android.graphics.Paint.Align.CENTER
                pt.typeface = Typeface.create("sans-serif", Typeface.BOLD); pt.textSize = r * 0.9f; c.drawText("70", cx, cy + pt.textSize * 0.3f, pt)
                pt.textSize = r * 0.26f; c.drawText(a.getString(R.string.rad_isa), cx, cy + r * 0.64f, pt)
            }
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { rightMargin = dp(18) })
        ex.addView(text(a.getString(R.string.rp_isa_example), 15f, R.color.text_sub), LinearLayout.LayoutParams(0, -2, 1f))
        read.addView(ex, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val logRow = line(read, a.getString(R.string.rp_isa_log), "", toggle({ prefs.radarIsaLog }) { prefs.radarIsaLog = it }, 130)
        val logSub = ((logRow.getChildAt(0) as LinearLayout).getChildAt(1) as TextView)
        refreshers += { logSub.text = a.getString(R.string.rp_isa_log_sub, DataUsage.format(a, IsaLog.file(a).let { if (it.exists()) it.length() else 0L })) }

        // Send: experimental
        (send.getChildAt(0) as TextView).let { title ->
            send.removeView(title)
            val h = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            h.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            h.addView(text(a.getString(R.string.rp_experimental).uppercase(), 13f, R.color.text_value, medium).apply {
                setTextColor(col(R.color.accent_heat)); setPadding(dp(10), dp(4), dp(10), dp(4)); letterSpacing = 0.08f
                background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setStroke(dp(1), col(R.color.accent_heat)) }
            })
            send.addView(h, 0)
        }
        line(send, a.getString(R.string.rp_send_row), a.getString(R.string.rp_send_sub), toggle({ prefs.radarIsaWrite }) { prefs.radarIsaWrite = it }, 130)
        send.addView(text(a.getString(R.string.rp_send_warn), 15f, R.color.text_value).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor((col(R.color.accent_heat) and 0x00FFFFFF) or 0x26000000); setStroke(dp(1), col(R.color.accent_heat)) }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val last = text("", 17f, R.color.text_value)
        line(send, a.getString(R.string.rp_last_send), null, last)
        refreshers += {
            last.text = IsaWrite.last?.let { (t, v, sent) ->
                android.text.format.DateFormat.getTimeFormat(a).format(java.util.Date(t)) + " · " +
                    (if (v == 0) a.getString(R.string.rp_cleared) else "$v km/h") + if (!sent) " (" + a.getString(R.string.rp_held) + ")" else ""
            } ?: a.getString(R.string.rp_never)
        }
        return two
    }
}
