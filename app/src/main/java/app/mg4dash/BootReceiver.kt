package app.mg4dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mg4dash.system.Log

/** Démarre le processus tôt au boot : liaison véhicule et météo prêtes avant l'affichage. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("Boot", "BOOT_COMPLETED received")
        }
    }
}
