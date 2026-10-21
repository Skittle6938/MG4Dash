package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Bouton « * » du volant réglé sur la caméra 360 (issue #33). Le service du launcher SAIC reçoit
 * bien le bouton, mais il ouvre la caméra en passant par son propre écran d'accueil, absent quand
 * MG4Dash le remplace : rien ne se passe. On écoute la même diffusion que lui
 * (com.saic.keyevent.hardkey.report, code 5 = « * » gauche) et on démarre le service caméra comme
 * il le ferait. Les autres fonctions du bouton (mode de conduite, régénération…) restent à SAIC.
 */
object StarKey {
    private const val TAG = "StarKey"
    private const val ACTION = "com.saic.keyevent.hardkey.report"
    private const val KEY_LEFT_STAR = 5
    /** Settings.System : fonction du « * » gauche ; 3 = caméra de recul / 360 (CarHardKey SAIC). */
    private const val STAR_FUNCTION = "star_key_function"
    private const val FUNCTION_AVM = 3

    private val AVM = listOf(
        "com.saicmotor.avm" to "com.saicmotor.avm.service.SaicMotorAvmService",
        "com.saicmotor.hmi.aroundview" to "com.saicmotor.hmi.aroundview.AvmService",
    )

    fun start(context: Context) {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val code = i.getIntExtra("android.intent.extra.hardkey.keycode", -1)
                val down = i.getBooleanExtra("android.intent.extra.hardkey.down", false)
                val long = i.getBooleanExtra("android.intent.extra.hardkey.longpress", false)
                if (code != KEY_LEFT_STAR || down || long) return
                val fn = try { android.provider.Settings.System.getInt(app.contentResolver, STAR_FUNCTION, -1) } catch (_: Exception) { -1 }
                Log.i(TAG, "« * » gauche relâché, fonction $fn")
                if (fn != FUNCTION_AVM || Prefs(app).valetMode) return
                openAvm(app)
            }
        }
        try { app.registerReceiver(receiver, IntentFilter(ACTION)) } catch (e: Exception) { Log.w(TAG, "écoute impossible : ${e.message}") }
    }

    /** Démarre le service de la caméra 360 installée (START_ENTER, comme le launcher SAIC). */
    fun openAvm(context: Context): Boolean {
        for ((pkg, svc) in AVM) {
            if (!Firmware.installed(context, pkg)) continue
            return try {
                context.startService(Intent("com.saicmotor.avm.START_ENTER").setComponent(ComponentName(pkg, svc)))
                Log.i(TAG, "caméra 360 : $svc démarré"); true
            } catch (e: Exception) { Log.w(TAG, "caméra 360 : $svc : ${e.message}"); false }
        }
        return false
    }
}
