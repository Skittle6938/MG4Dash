package app.mg4dash.rescue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Au démarrage de l'autoradio : lance la vérification (le service attend lui-même que tout soit monté). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(RescueService.TAG, "received " + action);
        // CHECK : même vérification à la demande (essai par ADB), avec un délai en extra « delay_ms »
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !"app.mg4dash.rescue.CHECK".equals(action)) return;
        try {
            Intent i = new Intent(context, RescueService.class);
            if (intent.hasExtra("delay_ms")) i.putExtra("delay_ms", intent.getLongExtra("delay_ms", 90_000));
            context.startForegroundService(i);
        } catch (Exception e) {
            Log.w(RescueService.TAG, "start failed: " + e.getMessage());
        }
    }
}
