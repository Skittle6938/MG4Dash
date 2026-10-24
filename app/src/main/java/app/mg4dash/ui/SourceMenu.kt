package app.mg4dash.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.R
import app.mg4dash.media.MediaHub

/** Menu des sources audio sous le nom de la source (« Radio ▾ ») : la source active est en couleur. */
object SourceMenu {
    fun show(activity: Activity, anchor: View) {
        val d = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (16 * d).toInt())   // marge basse : le contour de la dernière ligne surlignée n'est plus coupé
            elevation = 12 * d
        }
        // Fenêtre non focalisable : sinon elle prend le focus et la voiture réaffiche sa barre latérale et
        // son bandeau (plein écran perdu). Un toucher à côté la ferme quand même.
        val popup = android.widget.PopupWindow(list, (300 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        val cur = MediaHub.currentSource()
        MediaHub.availableSources().forEach { pkg ->
            list.addView(TextView(activity).apply {
                text = MediaHub.sourceLabel(pkg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (pkg == cur) R.color.accent_action else R.color.text_value))
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = pkg == cur
                setOnClickListener { popup.dismiss(); MediaHub.switchTo(pkg) }
            }, LinearLayout.LayoutParams(-1, (60 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Hauteur mesurée explicitement : en ouverture vers le bas (carte musique en haut de l'écran),
        // la hauteur « wrap_content » coupait le bas de la dernière ligne.
        list.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        popup.height = list.measuredHeight + (6 * d).toInt()
        popup.showAsDropDown(anchor, 0, (4 * d).toInt())
    }

    /** Confirmation dans le style des menus, centrée sur [anchor] : [message], Annuler et [ok] (en couleur). */
    fun confirm(activity: Activity, anchor: View, message: String, ok: String, onOk: () -> Unit) {
        val d = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (20 * d).toInt())
            elevation = 12 * d
        }
        val popup = android.widget.PopupWindow(box, (520 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        box.addView(TextView(activity).apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Themes.color(activity, R.color.text_value))
        })
        val row = LinearLayout(activity).apply { gravity = Gravity.END }
        for ((label, action) in listOf(activity.getString(R.string.cancel) to false, ok to true)) {
            row.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (action) R.color.accent_action else R.color.text_value))
                setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = action
                setOnClickListener { popup.dismiss(); if (action) onOk() }
            }, LinearLayout.LayoutParams(-2, (60 * d).toInt()).apply { marginStart = (12 * d).toInt() })
        }
        box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (18 * d).toInt() })
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Centrée sur la vue touchée (la courbe), sans déborder de l'écran
        box.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val at = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val screenW = anchor.rootView.width
        val x = (at[0] + anchor.width / 2 - popup.width / 2).coerceIn(0, (screenW - popup.width).coerceAtLeast(0))
        val y = at[1] + anchor.height / 2 - box.measuredHeight / 2
        popup.showAtLocation(anchor.rootView, Gravity.TOP or Gravity.START, x, y)
    }

    /** Menu de choix générique sous [anchor] ; [current] est en couleur. */
    fun choose(activity: Activity, anchor: View, labels: List<String>, current: Int, onPick: (Int) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_panel)
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (16 * d).toInt())   // marge basse : le contour de la dernière ligne surlignée n'est plus coupé
            elevation = 12 * d
        }
        val popup = android.widget.PopupWindow(list, (340 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setOnDismissListener { Fullscreen.apply(activity) }
        }
        labels.forEachIndexed { i, label ->
            list.addView(TextView(activity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTextColor(Themes.color(activity, if (i == current) R.color.accent_action else R.color.text_value))
                setPadding((18 * d).toInt(), 0, (18 * d).toInt(), 0)
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_chip)
                isSelected = i == current
                setOnClickListener { popup.dismiss(); onPick(i) }
            }, LinearLayout.LayoutParams(-1, (60 * d).toInt()).apply { topMargin = (4 * d).toInt() })
        }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Hauteur mesurée explicitement : en ouverture vers le bas (carte musique en haut de l'écran),
        // la hauteur « wrap_content » coupait le bas de la dernière ligne.
        list.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        popup.height = list.measuredHeight + (6 * d).toInt()
        popup.showAsDropDown(anchor, 0, (4 * d).toInt())
    }

    /** Prépare un titre de carte comme sélecteur de source. */
    fun attach(activity: Activity, title: TextView) {
        val d = activity.resources.displayMetrics.density
        title.isClickable = true
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        title.setTextColor(Themes.color(activity, R.color.text_value))
        title.setPadding(0, (8 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        title.setOnClickListener { show(activity, it) }
    }
}
