package app.mg4dash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.mg4dash.system.Log

/** Starts the process early at boot: vehicle link and weather are ready before the display. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("Boot", "BOOT_COMPLETED received")
        }
    }
}
