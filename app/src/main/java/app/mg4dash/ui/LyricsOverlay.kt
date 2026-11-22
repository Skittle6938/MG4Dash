package app.mg4dash.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import app.mg4dash.MainActivity
import app.mg4dash.system.Prefs

/** Manually enabled, non-focusable lyrics pill; text is fed by LyricsCard's existing ticker. */
object LyricsOverlay {
    @Volatile var active = false; private set
    private var app: Context? = null
    private var wm: WindowManager? = null
    private var pill: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var current = ""; private var next = ""
    private fun dp(v: Int) = ((app?.resources?.displayMetrics?.density ?: 1f) * v).toInt()

    fun toggle(context: Context): Boolean {
        if (active) { stop(); return false }
        if (!Settings.canDrawOverlays(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = android.net.Uri.parse("package:${context.packageName}"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent); return false
        }
        app = context.applicationContext; wm = app!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return start()
    }
    fun start(): Boolean {
        val c = app ?: return false
        if (active) return true
        val p = Prefs(c)
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(7), dp(9))
            background = GradientDrawable().apply { setColor(0xE60D121D.toInt()); cornerRadius = dp(18).toFloat(); setStroke(dp(1), 0x26FFFFFF) }
        }
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val activeLine = TextView(c).apply { setTextColor(-1); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); typeface = Typeface.DEFAULT_BOLD; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; text = current }
        val nextLine = TextView(c).apply { setTextColor(-1); alpha = .55f; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; text = next }
        texts.addView(activeLine); texts.addView(nextLine)
        root.addView(texts, LinearLayout.LayoutParams(dp(340), -2))
        root.addView(TextView(c).apply { text = "×"; gravity = Gravity.CENTER; setTextColor(-1); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f); setPadding(dp(10), 0, dp(10), 0); setOnClickListener { stop() } })
        val lp = WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (p.lyricsOverlayX >= 0) p.lyricsOverlayX else 80
            y = if (p.lyricsOverlayY >= 0) p.lyricsOverlayY else dp(40)
            title = "MG4Dash lyrics"
        }
        root.setOnTouchListener(object : View.OnTouchListener {
            var sx=0f; var sy=0f; var ox=0; var oy=0; var dragged=false
            override fun onTouch(v: View, e: MotionEvent): Boolean = when(e.action) {
                MotionEvent.ACTION_DOWN -> { sx=e.rawX; sy=e.rawY; ox=lp.x; oy=lp.y; dragged=false; true }
                MotionEvent.ACTION_MOVE -> { val dx=(e.rawX-sx).toInt(); val dy=(e.rawY-sy).toInt(); if (kotlin.math.abs(dx)+kotlin.math.abs(dy)>dp(6)) dragged=true; lp.x=ox+dx; lp.y=oy+dy; try { wm?.updateViewLayout(root,lp) } catch (_:Exception){}; true }
                MotionEvent.ACTION_UP -> { if (dragged) { p.lyricsOverlayX=lp.x; p.lyricsOverlayY=lp.y } else c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); true }
                else -> true
            }
        })
        return try { wm?.addView(root,lp); pill=root; params=lp; activeLine.tag="active"; nextLine.tag="next"; active=true; true } catch (_:Exception) { false }
    }
    fun update(activeText: String, nextText: String) { current=activeText; next=nextText; val r=pill as? LinearLayout ?: return; (r.getChildAt(0) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.text=activeText; (it.getChildAt(1) as? TextView)?.text=nextText } }
    fun stop() { val v=pill; pill=null; active=false; try { if(v!=null) wm?.removeViewImmediate(v) } catch (_:Exception){}; params=null }
}
