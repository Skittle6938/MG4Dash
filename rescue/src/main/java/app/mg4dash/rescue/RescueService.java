package app.mg4dash.rescue;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.DropBoxManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * MG4Dash safety net. When MG4Dash has replaced SAIC home, only it can re-enable it: if it no longer
 * starts (damaged compiled code, crash at launch), the screen stays stuck. This service, in a separate app
 * with no code shared with MG4Dash:
 *  1. 90 s after boot, checks whether MG4Dash is home and gave a sign of life during this
 *     boot (global setting mg4dash_alive, written by MG4Dash at launch) without crash looping;
 *  2. otherwise, stops MG4Dash, recompiles its code, relaunches it and waits 60 s;
 *  3. if it still does not start, re-enables SAIC home, makes it the default again and opens it.
 * The result is left in mg4dash_rescued for MG4Dash to show at its next launch.
 * If MG4Dash was uninstalled without going through Service mode, SAIC home is also re-enabled.
 */
public class RescueService extends Service {
    static final String TAG = "MG4Rescue";
    private static final String DASH = "app.mg4dash";
    private static final ComponentName DASH_HOME = new ComponentName(DASH, "app.mg4dash.MainActivity");
    private static final String KEY_ALIVE = "mg4dash_alive";      // "boot count:elapsedRealtime"
    private static final String KEY_RESCUED = "mg4dash_rescued";  // "recompiled|stock:time"
    private static final long FIRST_CHECK_MS = 90_000;
    static final long RETRY_WAIT_MS = 60_000;
    static final int CRASH_LIMIT = 3;

    private volatile boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (running) return START_NOT_STICKY;
        running = true;
        long delay = intent != null ? intent.getLongExtra("delay_ms", FIRST_CHECK_MS) : FIRST_CHECK_MS;
        new Thread(() -> {
            try {
                run(delay);
            } catch (Throwable t) {
                Log.e(TAG, "check failed", t);
            } finally {
                running = false;
                stopForeground(true);
                stopSelf();
            }
        }, "rescue").start();
        return START_NOT_STICKY;
    }

    private void foreground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("rescue", "MG4Dash Rescue", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "rescue")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("MG4Dash Rescue").build();
        startForeground(1, n);
    }

    /** The check itself (on its own thread); package-private for the tests, which run it without the waits. */
    void run(long delay) throws InterruptedException {
        pause(delay);
        PackageManager pm = getPackageManager();
        ComponentName stock = findStock(pm);
        boolean dashInstalled = installed(pm, DASH);
        boolean stockDisabled = stock != null && pm.getComponentEnabledSetting(stock) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        Log.i(TAG, "check: MG4Dash " + (dashInstalled ? "installed" : "absent") + ", SAIC home " + stock + (stockDisabled ? " disabled" : " enabled"));

        // MG4Dash uninstalled while it had disabled SAIC home: restore it
        if (!dashInstalled) {
            if (stockDisabled) restoreStock(pm, stock, "MG4Dash not installed");
            return;
        }
        // MG4Dash is not home (SAIC home or another launcher): nothing to do
        if (!stockDisabled) return;

        if (healthy(-1, 0)) { Log.i(TAG, "MG4Dash is fine"); return; }

        // 2) Stop, recompile, relaunch
        // As MG4Dash is home, Android relaunches it right after a stop: it then ran during the
        // recompilation, which damaged its code (trial of 31/10). So it is disabled while recompiling.
        Log.w(TAG, "MG4Dash did not start: recompiling and relaunching it");
        try {
            pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            pause(1000);
            forceStop(DASH);
            pause(1000);
            Log.i(TAG, "MG4Dash disabled, running: " + running(DASH));
            recompile(DASH);
        } finally {
            pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        }
        setPreferred(pm, DASH_HOME);
        // Relaunch on a fresh process: its sign of life proves the recompiled code loads
        forceStop(DASH);
        long t0 = SystemClock.elapsedRealtime();
        long wall0 = System.currentTimeMillis();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(DASH_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "relaunch failed: " + e.getMessage());
        }
        pause(RETRY_WAIT_MS);
        if (healthy(t0, wall0)) {
            Log.i(TAG, "MG4Dash started after recompiling");
            mark("recompiled");
            return;
        }
        // 3) SAIC home
        restoreStock(pm, stock, "MG4Dash still not starting");
    }

    /**
     * MG4Dash healthy: sign of life during this boot (after [afterElapsed] if ≥ 0) and fewer than
     * [CRASH_LIMIT] crashes since [sinceWall] (0: since the head unit booted).
     */
    boolean healthy(long afterElapsed, long sinceWall) {
        boolean alive = false;
        String v = Settings.Global.getString(getContentResolver(), KEY_ALIVE);
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (v != null) {
            String[] p = v.split(":");
            try {
                alive = p.length == 2 && Integer.parseInt(p[0]) == boot && Long.parseLong(p[1]) > afterElapsed;
            } catch (NumberFormatException ignored) { }
        }
        long since = sinceWall > 0 ? sinceWall : System.currentTimeMillis() - SystemClock.elapsedRealtime();
        int crashes = crashes(since);
        Log.i(TAG, "MG4Dash alive " + alive + " (" + v + ", boot " + boot + "), crashes " + crashes);
        return alive && crashes < CRASH_LIMIT;
    }

    /** MG4Dash crashes since [since] (wall clock), from Android's crash log. */
    private int crashes(long since) {
        int n = 0;
        try {
            DropBoxManager db = getSystemService(DropBoxManager.class);
            for (String tag : new String[] { "system_app_crash", "data_app_crash" }) {
                long t = since;
                DropBoxManager.Entry e;
                while ((e = db.getNextEntry(tag, t)) != null) {
                    t = e.getTimeMillis();
                    String text = e.getText(512);
                    if (text != null && text.contains("Process: " + DASH + "\n")) n++;
                    e.close();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "crash log: " + e.getMessage());
        }
        return n;
    }

    private void restoreStock(PackageManager pm, ComponentName stock, String why) {
        Log.w(TAG, why + ": restoring SAIC home " + stock);
        forceStop(DASH);
        pm.setComponentEnabledSetting(stock, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        setPreferred(pm, stock);
        mark("stock");
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(stock).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "opening SAIC home: " + e.getMessage());
        }
    }

    private void mark(String what) {
        try {
            Settings.Global.putString(getContentResolver(), KEY_RESCUED, what + ":" + System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "mark: " + e.getMessage());
        }
    }

    /** SAIC home of this firmware (disabled included), excluding the first boot wizard. */
    static ComponentName findStock(PackageManager pm) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ComponentName best = null;
        int bestRank = 9;
        for (ResolveInfo ri : pm.queryIntentActivities(home, PackageManager.MATCH_DISABLED_COMPONENTS)) {
            String pkg = ri.activityInfo.packageName;
            if (!pkg.startsWith("com.saicmotor") || pkg.contains("setupwizard")) continue;
            int rank = pkg.equals("com.saicmotor.launcher") ? 0 : pkg.equals("com.saicmotor.hmi.launcher") ? 1 : 2;
            if (rank < bestRank) { bestRank = rank; best = new ComponentName(pkg, ri.activityInfo.name); }
        }
        return best;
    }

    @SuppressWarnings("deprecation")
    private static void setPreferred(PackageManager pm, ComponentName target) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ComponentName> set = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) set.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        if (!set.contains(target)) set.add(target);
        try {
            for (ComponentName c : set) pm.clearPackagePreferredActivities(c.getPackageName());
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_HOME);
            f.addCategory(Intent.CATEGORY_DEFAULT);
            pm.addPreferredActivity(f, IntentFilter.MATCH_CATEGORY_EMPTY, set.toArray(new ComponentName[0]), target);
        } catch (SecurityException e) {
            Log.w(TAG, "HOME preference refused: " + e.getMessage());
        }
    }

    private boolean running(String pkg) {
        ActivityManager am = getSystemService(ActivityManager.class);
        List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
        if (list != null) for (ActivityManager.RunningAppProcessInfo p : list) if (pkg.equals(p.processName)) return true;
        return false;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void forceStop(String pkg) {
        try {
            ActivityManager am = getSystemService(ActivityManager.class);
            Method m = am.getClass().getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
        } catch (Exception e) {
            Log.w(TAG, "force stop: " + e.getMessage());
        }
    }

    /** Equivalent of "cmd package compile -m speed -f app.mg4dash". */
    private static void recompile(String pkg) {
        try {
            Object pm = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null);
            for (Method m : pm.getClass().getMethods()) {
                if (m.getName().equals("performDexOptMode") && m.getParameterTypes().length == 6) {
                    Object ok = m.invoke(pm, pkg, false, "speed", true, true, null);
                    Log.i(TAG, "recompile: " + ok);
                    return;
                }
            }
            Log.w(TAG, "recompile: method not found");
        } catch (Throwable t) {
            Log.w(TAG, "recompile failed: " + (t.getCause() != null ? t.getCause() : t));
        }
    }

    /** Waits [ms]: the boot, the stop, MG4Dash's relaunch. Replaced in the tests. */
    void pause(long ms) throws InterruptedException { Thread.sleep(ms); }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
