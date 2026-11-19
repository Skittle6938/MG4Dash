package app.mg4dash.rescue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.DropBoxManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * What starts a check: the head unit's boot (the service itself waits for everything to be up), an on-demand CHECK,
 * MG4Dash's install (UPDATED: sent by its updater, and by MG4Dash at its first start after any install), a crash of its
 * code that does not load (CRASH, told by MG4Dash), and the rescue's own alarm reading Android's crash log (SCAN).
 * Android's own notices (PACKAGE_REPLACED, DROPBOX_ENTRY_ADDED) are not delivered to it on the car (19/11). INIT, sent by MG4Dash after installing
 * the rescue, only takes it out of the "stopped" state and gets an answer. Each start alone: one failing never stops
 * the others, and the boot's own path is the one it always was.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String ACTION_INIT = "app.mg4dash.rescue.INIT";
    static final String ACTION_CHECK = "app.mg4dash.rescue.CHECK";
    static final String ACTION_UPDATED = "app.mg4dash.rescue.UPDATED";
    static final String ACTION_CRASH = "app.mg4dash.rescue.CRASH";
    /** The rescue's own alarm: Android's crash log read for a MG4Dash that does not load (its own notice refused). */
    static final String ACTION_SCAN = "app.mg4dash.rescue.SCAN";
    /** How often the crash log is read (inexact, no service kept alive). */
    static final long SCAN_EVERY_MS = 5 * 60_000L;
    /** The last crash log time read (in the rescue's own preferences). */
    private static final String PREF_SCANNED = "scanned_until";
    /** After MG4Dash's update: its own process gone, the install settled. */
    static final long UPDATED_DELAY_MS = 3_000;
    /** After a crash of code that does not load: Android's own relaunch tries first. */
    static final long CRASH_DELAY_MS = 10_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.i(RescueService.TAG, "received " + action);
        try {
            switch (action) {
                case ACTION_INIT:
                    RescueService.answerInit(context);
                    armScan(context);
                    return;
                case ACTION_SCAN:
                    scan(context);
                    return;
                case Intent.ACTION_BOOT_COMPLETED:
                    armScan(context);
                    // fall through: the boot's check, as always
                case ACTION_CHECK: {
                    // CHECK: same check on demand (trial through ADB), with a delay in the "delay_ms" extra
                    Intent i = new Intent(context, RescueService.class);
                    if (intent.hasExtra("delay_ms")) i.putExtra("delay_ms", intent.getLongExtra("delay_ms", 90_000));
                    context.startForegroundService(i);
                    return;
                }
                case ACTION_UPDATED:
                    start(context, RescueService.MODE_UPDATED, intent.getLongExtra("delay_ms", UPDATED_DELAY_MS));
                    return;
                case ACTION_CRASH:
                    start(context, RescueService.MODE_CRASH, CRASH_DELAY_MS);
                    return;
                default:
            }
        } catch (Exception e) {
            Log.w(RescueService.TAG, "start failed: " + e.getMessage());
        }
    }

    /**
     * Every [SCAN_EVERY_MS], inexact: Android does not deliver its crash log notices to the rescue (background
     * restrictions, seen on the car 19/11), and a MG4Dash whose very first class does not load cannot tell it either.
     */
    static void armScan(Context context) {
        try {
            android.app.AlarmManager am = context.getSystemService(android.app.AlarmManager.class);
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(context, 1,
                    new Intent(ACTION_SCAN).setClass(context, BootReceiver.class), android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(android.app.AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + SCAN_EVERY_MS, SCAN_EVERY_MS, pi);
        } catch (Exception e) {
            Log.w(RescueService.TAG, "scan alarm: " + e.getMessage());
        }
    }

    /** Crash log read since the last time: a MG4Dash whose code does not load starts a check. */
    static void scan(Context context) {
        android.content.SharedPreferences sp = context.getSharedPreferences("rescue", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long since = sp.getLong(PREF_SCANNED, now - SCAN_EVERY_MS);
        boolean found = false;
        DropBoxManager db = context.getSystemService(DropBoxManager.class);
        for (String tag : new String[] { "system_app_crash", "data_app_crash" }) {
            long t = since;
            DropBoxManager.Entry e;
            while (!found && (e = db.getNextEntry(tag, t)) != null) {
                t = e.getTimeMillis();
                String text = e.getText(4096);
                e.close();
                if (RescueService.isLoadCrash(text)) found = true;
            }
        }
        sp.edit().putLong(PREF_SCANNED, now).apply();
        if (!found) return;
        Log.w(RescueService.TAG, "crash log: MG4Dash's code does not load: check");
        start(context, RescueService.MODE_CRASH, 0);
    }

    private static Intent service(Context context, String mode, long delay) {
        return new Intent(context, RescueService.class).putExtra("mode", mode).putExtra("delay_ms", delay);
    }

    private static void start(Context context, String mode, long delay) {
        context.startForegroundService(service(context, mode, delay));
    }
}
