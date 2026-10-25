package app.mg4dash.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import app.mg4dash.BuildConfig
import app.mg4dash.system.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Guidage de l'appli Android Auto « DiAuto » (com.drivehub.diauto.mg4, basée sur Headunit Revived).
 *
 * Contrairement à l'Android Auto d'origine de la voiture, elle reçoit du téléphone la prochaine
 * manœuvre et la diffuse environ chaque seconde (action NAVIGATION_UPDATE, extras relevés dans
 * son code : NavigationUpdateIntent). La diffusion est réservée à une permission de l'appli ;
 * MG4Dash, en uid système, la reçoit sans rien modifier. Sans nouvelle pendant 8 s, le guidage
 * est considéré comme terminé.
 */
object NavRepo {
    private const val TAG = "Guidance"
    const val ACTION = "com.andrerinas.headunitrevived.NAVIGATION_UPDATE"
    private const val TIMEOUT_MS = 8_000L

    /** Types de manœuvre (NextTurnEnum d'Android Auto). */
    object Turn {
        const val DEPART = 1; const val NAME_CHANGE = 2; const val SLIGHT = 3; const val TURN = 4
        const val SHARP = 5; const val U_TURN = 6; const val ON_RAMP = 7; const val OFF_RAMP = 8
        const val FORK = 9; const val MERGE = 10; const val ROUNDABOUT_ENTER = 11
        const val ROUNDABOUT_EXIT = 12; const val ROUNDABOUT = 13; const val STRAIGHT = 14
        const val DESTINATION = 19
    }

    data class Nav(
        val distanceMeters: Int?,
        val timeSeconds: Int?,
        val road: String,
        val eventType: Int,
        val actionText: String,
        val side: Int?,          // 1 gauche, 2 droite, 3 non précisé
        val exitNumber: Int?,
        val angle: Int?,
        val totalMeters: Int?,
        val totalSeconds: Long?,
        val arrival: String,
        val at: Long = System.currentTimeMillis(),
    )

    private val _nav = MutableStateFlow<Nav?>(null)
    val nav: StateFlow<Nav?> = _nav
    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { if (_nav.value != null) { _nav.value = null; Log.i(TAG, "guidance ended") } }

    fun start(context: Context) {
        if (BuildConfig.MOCK) return
        try {
            context.applicationContext.registerReceiver(receiver, IntentFilter(ACTION))
        } catch (e: Exception) { Log.w(TAG, "guidance listener failed: ${e.message}") }
    }

    /** Pour les captures d'écran du PC. */
    fun setForTest(n: Nav?) { _nav.value = n }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val x = intent.extras ?: return
            fun int(k: String) = (x.get(k) as? Number)?.toInt()
            fun str(k: String) = (x.get(k) as? CharSequence)?.toString()?.trim().orEmpty()
            val n = Nav(
                distanceMeters = int("distance_meters"), timeSeconds = int("time_seconds"),
                road = str("road"), eventType = int("next_event_type") ?: 0, actionText = str("action_text"),
                side = int("turn_side"), exitNumber = int("turn_number"), angle = int("turn_angle"),
                totalMeters = int("total_distance_meters"), totalSeconds = (x.get("total_time_seconds") as? Number)?.toLong(),
                arrival = str("estimated_arrival"),
            )
            if (_nav.value == null) Log.i(TAG, "guidance received (manoeuvre ${n.eventType})")
            _nav.value = n
            main.removeCallbacks(expire)
            main.postDelayed(expire, TIMEOUT_MS)
        }
    }
}
