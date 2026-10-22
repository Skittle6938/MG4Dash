package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.mg4dash.media.MediaHub

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
    /** Joystick droit (codes relevés sur SWI173, #33, et dans MG4Control) : 299 gauche, 300 droite, 301 OK. */
    private const val JOY_LEFT = 299
    private const val JOY_RIGHT = 300
    private const val JOY_OK = 301
    /** Settings.System : fonction du « * » gauche ; 3 = caméra de recul / 360 (CarHardKey SAIC). */
    private const val STAR_FUNCTION = "star_key_function"
    /** Même chose pour le « * » droit. */
    private const val RIGHT_STAR_FUNCTION = "right_star_key_function"
    private const val KEY_LEFT_STAR_ALT = 17
    private const val KEY_RIGHT_STAR = 286
    private const val KEY_RIGHT_STAR_ALT = 18
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
                // Diagnostic (#33) : chaque bouton du volant reçu, visible dans le journal des réglages
                Log.i(TAG, "bouton volant : code $code ${if (down) "appuyé" else "relâché"}${if (long) " (long)" else ""}")
                // SWI173 (#33) : le joystick droit n'agit pas sur la musique Bluetooth / DiAuto quand
                // MG4Dash est l'accueil ; gauche = précédent, droite = suivant, OK = lecture / pause.
                // Limité à SWI173 : ailleurs le joystick change déjà piste ou volume tout seul.
                if (Firmware.gen == Firmware.Gen.SWI173 && !down && !long && code in JOY_LEFT..JOY_OK &&
                    MediaHub.ownPlayerShown()) {
                    when (code) {
                        JOY_LEFT -> MediaHub.previous()
                        JOY_RIGHT -> MediaHub.next()
                        JOY_OK -> MediaHub.playPause()
                    }
                    return
                }
                if (down || long) return
                // Codes des « * » selon le firmware : 5 (gauche) sur le launcher SWI69 ; 17 (gauche) et
                // 286 ou 18 (droit) ailleurs, relevés sur SWI173 (#33) comme dans MG4Control
                val a9 = Firmware.gen in setOf(Firmware.Gen.SWI69, Firmware.Gen.SWI131, Firmware.Gen.SWI132)
                val setting = when {
                    code == KEY_LEFT_STAR && a9 -> STAR_FUNCTION
                    code == KEY_LEFT_STAR_ALT && !a9 -> STAR_FUNCTION
                    (code == KEY_RIGHT_STAR || code == KEY_RIGHT_STAR_ALT) && !a9 -> RIGHT_STAR_FUNCTION
                    else -> return
                }
                val fn = try { android.provider.Settings.System.getInt(app.contentResolver, setting, -1) } catch (_: Exception) { -1 }
                Log.i(TAG, "« * » ($code) relâché, $setting = $fn")
                if (fn != FUNCTION_AVM || Prefs(app).valetMode) return
                openAvm(app)
            }
        }
        try { app.registerReceiver(receiver, IntentFilter(ACTION)); Log.i(TAG, "écoute des boutons du volant") }
        catch (e: Exception) { Log.w(TAG, "écoute impossible : ${e.message}") }
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
