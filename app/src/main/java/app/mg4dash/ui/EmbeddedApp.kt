package app.mg4dash.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import app.mg4dash.system.Log

/**
 * Une autre appli (Waze, ABRP…) affichée dans le widget carte (#41, essai). Android 9 a un
 * composant caché, android.app.ActivityView (celui du launcher voiture d'AOSP) : il lance l'appli
 * dans un écran virtuel intégré à la vue et lui transmet les touchers. Réservé aux applis système,
 * ce qu'est MG4Dash ; appelé par réflexion, null s'il manque sur ce firmware.
 */
object EmbeddedApp {
    private const val TAG = "AppCarte"

    fun create(activity: Activity, cn: ComponentName): ViewGroup? = try {
        val cls = Class.forName("android.app.ActivityView")
        val view = cls.getConstructor(Context::class.java).newInstance(activity) as ViewGroup
        val start = cls.getMethod("startActivity", Intent::class.java)
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // L'écran virtuel n'existe qu'une fois la surface créée : on réessaie jusqu'à ce qu'il soit prêt
        fun tryStart(n: Int) {
            view.postDelayed({
                if (!view.isAttachedToWindow) return@postDelayed
                try {
                    start.invoke(view, intent)
                    Log.i(TAG, "${cn.flattenToShortString()} lancée dans le widget carte")
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    if (n < 20) tryStart(n + 1) else Log.w(TAG, "lancement impossible : ${e.cause?.message}")
                }
            }, 300)
        }
        // Taille changée (plein écran) : l'écran virtuel de l'appli suit la nouvelle taille, sinon
        // l'appli garde l'ancienne largeur et le reste du widget reste noir
        view.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) v.post { resize(v) }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = tryStart(0)
            override fun onViewDetachedFromWindow(v: View) = release(v)
        })
        Log.i(TAG, "ActivityView disponible")
        view
    } catch (e: Throwable) {
        Log.w(TAG, "ActivityView indisponible : ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /**
     * Plein écran : en touchant l'appli intégrée, c'est sa fenêtre qui prend la main, et comme elle
     * ne demande pas le mode immersif, les barres SAIC réapparaissaient. Le réglage système
     * « policy_control » force le mode immersif pour cette appli (et MG4Dash) ; retiré sans appli.
     */
    fun immersive(context: Context, pkg: String?) {
        val want = if (pkg != null && app.mg4dash.system.Prefs(context).fullscreen) "immersive.full=${context.packageName},$pkg" else null
        try {
            val cr = context.contentResolver
            val cur = android.provider.Settings.Global.getString(cr, "policy_control")
            // On ne touche qu'à une valeur posée par nous (ou vide)
            if (cur != null && cur.isNotEmpty() && !cur.startsWith("immersive.full=${context.packageName},")) return
            if (cur != want) {
                android.provider.Settings.Global.putString(cr, "policy_control", want)
                Log.i(TAG, "policy_control → ${want ?: "(vide)"}")
            }
        } catch (e: Throwable) { Log.w(TAG, "policy_control : ${e.message}") }
    }

    private fun resize(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        try {
            val f = v.javaClass.getDeclaredField("mVirtualDisplay").apply { isAccessible = true }
            val vd = f.get(v) as? android.hardware.display.VirtualDisplay ?: return
            val dpi = v.resources.displayMetrics.densityDpi
            vd.resize(v.width, v.height, dpi)
            Log.i(TAG, "écran virtuel redimensionné : ${v.width}×${v.height}")
        } catch (e: Throwable) { Log.w(TAG, "redimensionnement : ${e.message}") }
    }

    fun release(v: View) {
        try { v.javaClass.getMethod("release").invoke(v) } catch (_: Throwable) {}
    }
}
