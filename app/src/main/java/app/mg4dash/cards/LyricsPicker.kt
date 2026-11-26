package app.mg4dash.cards

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.LyricsRepo
import app.mg4dash.media.MediaHub
import app.mg4dash.ui.Rows
import app.mg4dash.ui.Themes
import app.mg4dash.ui.Windows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * "Choose the lyrics" (26/11): every result LRCLIB has for the song, the closest first, the shown one ticked; a touch
 * loads that one at once and keeps it for the song. "Search again" at the bottom searches anew.
 */
internal object LyricsPicker {
    fun open(a: Activity, scope: CoroutineScope, m: MediaHub.NowPlaying, currentId: Long,
             onChosen: (LyricsRepo.Track) -> Unit, onSearchAgain: () -> Unit,
             results: suspend () -> List<LyricsRepo.Found>? = { LyricsRepo.search(m.artist, m.title, m.durationMs) }): AlertDialog {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        lateinit var dialog: AlertDialog
        var job: Job? = null
        val playing = a.getString(R.string.ly_choose_sub, 0, clock(m.durationMs)).substringAfter(" · ")

        fun note(text: String) {
            list.removeAllViews()
            list.addView(TextView(a).apply {
                this.text = text; gravity = Gravity.CENTER; setTextColor(Themes.color(a, R.color.text_sub))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setPadding(0, dp(36), 0, dp(36))
            }, LinearLayout.LayoutParams(-1, -2))
        }

        fun search() {
            note(a.getString(R.string.ly_searching))
            job?.cancel()
            job = scope.launch {
                val found = results()
                if (!dialog.isShowing) return@launch
                val sub = dialog.window?.decorView?.findViewWithTag<TextView>(Rows.TAG_SUB)
                when {
                    found == null -> note(a.getString(if (LyricsRepo.networkAllowed(a)) R.string.ly_offline else R.string.ly_wifi_only))
                    found.isEmpty() -> note(a.getString(R.string.ly_choose_none))
                    else -> {
                        list.removeAllViews()
                        found.forEachIndexed { i, f ->
                            list.addView(row(a, f, f.id != 0L && f.id == currentId) {
                                dialog.dismiss()
                                scope.launch { onChosen(LyricsRepo.choose(m.artist, m.title, f)) }
                            }, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(10) })
                        }
                    }
                }
                sub?.text = a.getString(R.string.ly_choose_sub, found?.size ?: 0, clock(m.durationMs))
            }
        }

        val again = Rows.actionButton(a, a.getString(R.string.ly_search_again)) { onSearchAgain(); search() }
        dialog = Windows.show(a, a.getString(R.string.ly_choose), list, sub = playing, widthDp = 1200, buttons = listOf(again),
            onDismiss = { job?.cancel() })
        search()
        return dialog
    }

    /** One result: the tick, its title, "artist · album", the kind of lyrics and the length. */
    private fun row(a: Activity, f: LyricsRepo.Found, selected: Boolean, onPick: () -> Unit): View {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val accent = Themes.select(a)
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(92)
            setPadding(dp(22), dp(14), dp(24), dp(14)); tag = Rows.TAG_ROW
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat(); setColor(Themes.color(a, R.color.button_idle))
                setStroke(dp(2).coerceAtLeast(1), if (selected) accent else Themes.color(a, R.color.panel_border))
            }
            isClickable = true; setOnClickListener { onPick() }
        }
        row.addView(View(a).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (selected) setColor(accent) else { setColor(0); setStroke(dp(2).coerceAtLeast(1), Themes.color(a, R.color.text_sub)) }
            }
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(20) })
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply {
            text = f.title; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Themes.color(a, R.color.text_value)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(a).apply {
            text = listOf(f.artist, f.album).filter { it.isNotBlank() }.joinToString(" · "); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(Themes.color(a, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val synced = f.synced
        row.addView(TextView(a).apply {
            text = a.getString(when { synced -> R.string.ly_tag_synced; f.instrumental -> R.string.ly_tag_instrumental; else -> R.string.ly_tag_plain })
            val tint = if (synced) accent else Themes.color(a, R.color.text_sub)
            setTextColor(tint); setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0); setStroke(dp(2).coerceAtLeast(1), tint) }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        row.addView(TextView(a).apply {
            text = clock(f.durationMs); gravity = Gravity.END; setTextColor(Themes.color(a, R.color.text_sub)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LinearLayout.LayoutParams(dp(76), -2).apply { marginStart = dp(12) })
        return row
    }

    /** 219000 → "3:39"; "–" when unknown. */
    internal fun clock(ms: Long): String {
        if (ms <= 0) return "–"
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
