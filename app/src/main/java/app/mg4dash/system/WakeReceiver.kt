package app.mg4dash.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * MG4Dash woken by MG4Dash Rescue (or ADB) after a repair that did not relaunch it, MG4Dash not being the home (19/11): a
 * force-stopped app stays "stopped", without its shortcuts strip, its maintenance gesture (the way back to it) or even
 * the boot. Receiving this starts the process, whose start brings those back; nothing else here, neither the home nor
 * the maintenance mode is touched. Only the system uid and the shell may send it (WRITE_SECURE_SETTINGS).
 */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Log.i("Rescue", "woken by the rescue: background started")
    }

    companion object {
        const val ACTION = "app.mg4dash.WAKE"
    }
}
