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
 * MG4Dash safety net. When MG4Dash is home, a MG4Dash that no longer starts (damaged compiled code, crash at launch)
 * leaves the screen stuck. This service, in a separate app with no code shared with MG4Dash:
 *  1. 90 s after boot (and after each MG4Dash install, update or crash of code that does not load), checks whether
 *     MG4Dash is home and gave a sign of life (global setting mg4dash_alive, written by MG4Dash once its home screen is
 *     drawn) without crash looping;
 *  2. otherwise, stops MG4Dash, recompiles its code, relaunches it and waits 60 s (once per install and boot);
 *  3. if it still does not start, re-enables SAIC home, makes it the default again and opens it.
 * After its own update, MG4Dash hands over to it: a clean recompilation, a relaunch, the same watch.
 * The result is left in mg4dash_rescued for MG4Dash to show at its next launch.
 * If MG4Dash was uninstalled without going through Service mode, SAIC home is also re-enabled.
 * Whatever happens, a check never ends with MG4Dash disabled (a marker covers a power cut during a repair).
 */
public class RescueService extends Service {
    static final String TAG = "MG4Rescue";
    private static final String DASH = "app.mg4dash";
    private static final ComponentName DASH_HOME = new ComponentName(DASH, "app.mg4dash.MainActivity");
    private static final String KEY_ALIVE = "mg4dash_alive";      // "boot count:elapsedRealtime"
    private static final String KEY_RESCUED = "mg4dash_rescued";  // "recompiled|stock:time"
    /** "1" while MG4Dash is the home the driver chose, "0" when it is not (written by MG4Dash). */
    static final String KEY_HOME = "mg4dash_home";
    /** Set while MG4Dash is disabled for its recompilation: a power cut then leaves it to undo. */
    static final String KEY_DISABLED = "mg4dash_rescue_disabled";
    /** "lastUpdateTime:boot" of MG4Dash's last recompilation by the rescue: one per install and boot. */
    static final String KEY_RECOMPILED = "mg4dash_rescue_recompiled";
    /** The rescue's answer to INIT: "versionCode:time". */
    static final String KEY_OK = "mg4dash_rescue_ok";
    private static final long FIRST_CHECK_MS = 90_000;
    static final long RETRY_WAIT_MS = 60_000;
    static final int CRASH_LIMIT = 3;

    /** Why a check runs: the head unit's boot (or on demand), MG4Dash updated by itself, installed, or crashed. */
    static final String MODE_BOOT = "boot", MODE_UPDATED = "updated", MODE_INSTALLED = "installed", MODE_CRASH = "crash";

    private volatile boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foreground();
        if (running) return START_NOT_STICKY;
        running = true;
        long delay = intent != null ? intent.getLongExtra("delay_ms", FIRST_CHECK_MS) : FIRST_CHECK_MS;
        String m = intent != null ? intent.getStringExtra("mode") : null;
        String mode = m != null ? m : MODE_BOOT;
        long since = intent != null ? intent.getLongExtra("since_elapsed", -1) : -1;
        new Thread(() -> {
            try {
                run(delay, mode, since);
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
    void run(long delay) throws InterruptedException { run(delay, MODE_BOOT, -1); }

    /**
     * The check for [mode]: after [delay], with a sign of life wanted after [sinceElapsed] (-1: any during this boot).
     * Whatever happens, it never ends with MG4Dash disabled.
     */
    void run(long delay, String mode, long sinceElapsed) throws InterruptedException {
        // A repair cut by a power loss left MG4Dash disabled: first of all, enabled again
        undoInterruptedRepair();
        try {
            if (MODE_UPDATED.equals(mode)) afterUpdate(delay);
            else check(delay, mode, sinceElapsed);
        } finally {
            undoInterruptedRepair();
        }
    }

    private void check(long delay, String mode, long sinceElapsed) throws InterruptedException {
        pause(delay);
        PackageManager pm = getPackageManager();
        ComponentName stock = findStock(pm);
        boolean dashInstalled = installed(pm, DASH);
        boolean stockDisabled = stock != null && pm.getComponentEnabledSetting(stock) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        Log.i(TAG, "check (" + mode + "): MG4Dash " + (dashInstalled ? "installed" : "absent") + ", SAIC home " + stock + (stockDisabled ? " disabled" : " enabled"));

        // MG4Dash uninstalled while it had disabled SAIC home: restore it
        if (!dashInstalled) {
            if (stockDisabled) restoreStock(pm, stock, "MG4Dash not installed");
            return;
        }
        // MG4Dash is not home (SAIC home or another launcher): nothing to do
        if (!dashIsHome(pm, stockDisabled)) { Log.i(TAG, "MG4Dash is not home: nothing to do"); return; }

        if (healthy(sinceElapsed, 0)) { Log.i(TAG, "MG4Dash is fine"); return; }

        // 2) Stop, recompile, relaunch: once per install and boot, then SAIC home
        if (alreadyRecompiled(pm)) { restoreStock(pm, stock, "MG4Dash still not starting after its recompilation"); return; }
        Log.w(TAG, "MG4Dash did not start: recompiling and relaunching it");
        recompileStopped(pm);
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
     * MG4Dash updated itself (its updater hands over to the rescue): the install's compiled code is replaced by a clean
     * one while MG4Dash is stopped, then MG4Dash is relaunched and watched; SAIC home if, home, it does not start.
     */
    private void afterUpdate(long delay) throws InterruptedException {
        pause(delay);
        PackageManager pm = getPackageManager();
        if (!installed(pm, DASH)) { Log.i(TAG, "update: MG4Dash absent"); return; }
        ComponentName stock = findStock(pm);
        boolean stockDisabled = stock != null && pm.getComponentEnabledSetting(stock) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        boolean home = dashIsHome(pm, stockDisabled);
        Log.i(TAG, "update: clean recompilation of MG4Dash (home " + home + ")");
        recompileStopped(pm);
        if (home) setPreferred(pm, DASH_HOME);
        forceStop(DASH);
        long t0 = SystemClock.elapsedRealtime();
        long wall0 = System.currentTimeMillis();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(DASH_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (home) i.addCategory(Intent.CATEGORY_HOME);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "relaunch failed: " + e.getMessage());
        }
        pause(RETRY_WAIT_MS);
        if (healthy(t0, wall0)) { Log.i(TAG, "update: MG4Dash started"); return; }
        if (home) restoreStock(pm, stock, "MG4Dash not starting after its update");
        else Log.w(TAG, "update: MG4Dash not starting, it is not home: left as it is");
    }

    /**
     * MG4Dash is the home to look after: SAIC home disabled for it (as before), MG4Dash's own word that it is home
     * (Android forgets the preferred HOME after a crash loop), or MG4Dash the preferred HOME. Never when MG4Dash said it
     * is not home and SAIC home is enabled (the driver's choice, maintenance mode).
     */
    boolean dashIsHome(PackageManager pm, boolean stockDisabled) {
        if (stockDisabled) return true;
        String said = Settings.Global.getString(getContentResolver(), KEY_HOME);
        if ("0".equals(said)) return false;
        if ("1".equals(said)) return true;
        return DASH_HOME.equals(preferredHome(pm));
    }

    @SuppressWarnings("deprecation")
    private static ComponentName preferredHome(PackageManager pm) {
        try {
            List<IntentFilter> filters = new ArrayList<>();
            List<ComponentName> names = new ArrayList<>();
            pm.getPreferredActivities(filters, names, null);
            for (int i = 0; i < filters.size(); i++) if (filters.get(i).hasCategory(Intent.CATEGORY_HOME)) return names.get(i);
        } catch (Exception e) {
            Log.w(TAG, "preferred home: " + e.getMessage());
        }
        return null;
    }

    /** MG4Dash recompiled by the rescue since its last install, during this boot: a second time would change nothing. */
    private boolean alreadyRecompiled(PackageManager pm) {
        String v = Settings.Global.getString(getContentResolver(), KEY_RECOMPILED);
        return v != null && v.equals(recompileKey(pm));
    }

    private String recompileKey(PackageManager pm) {
        long updated = 0;
        try { updated = pm.getPackageInfo(DASH, PackageManager.MATCH_DISABLED_COMPONENTS).lastUpdateTime; } catch (Exception ignored) { }
        return updated + ":" + Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    /**
     * Recompiles MG4Dash while it cannot run: as MG4Dash is home, Android relaunches it right after a stop: it then ran
     * during the recompilation, which damaged its code (trial of 31/10). So it is disabled meanwhile, with a marker in
     * case the power goes, and enabled again whatever happens.
     */
    private void recompileStopped(PackageManager pm) throws InterruptedException {
        putGlobal(KEY_RECOMPILED, recompileKey(pm));
        putGlobal(KEY_DISABLED, "1");
        try {
            pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            pause(1000);
            forceStop(DASH);
            pause(1000);
            Log.i(TAG, "MG4Dash disabled, running: " + running(DASH));
            recompile(DASH);
        } finally {
            try {
                pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                putGlobal(KEY_DISABLED, null);
            } catch (Exception e) {
                Log.e(TAG, "enabling MG4Dash again: " + e.getMessage());
            }
        }
    }

    /** MG4Dash left disabled by a repair that did not finish (power cut): enabled again. */
    void undoInterruptedRepair() {
        try {
            if (Settings.Global.getString(getContentResolver(), KEY_DISABLED) == null) return;
            PackageManager pm = getPackageManager();
            if (installed(pm, DASH) && pm.getApplicationEnabledSetting(DASH) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
                Log.w(TAG, "MG4Dash was left disabled by an interrupted repair: enabled again");
            }
            putGlobal(KEY_DISABLED, null);
        } catch (Exception e) {
            Log.e(TAG, "undo interrupted repair: " + e.getMessage());
        }
    }

    private void putGlobal(String key, String value) {
        try { Settings.Global.putString(getContentResolver(), key, value); } catch (Exception e) { Log.w(TAG, key + ": " + e.getMessage()); }
    }

    /**
     * MG4Dash healthy: sign of life during this boot (after [afterElapsed] if ≥ 0), fewer than [CRASH_LIMIT] crashes
     * since [sinceWall] (0: since the head unit booted), and no crash of code that does not load since its sign of life.
     */
    boolean healthy(long afterElapsed, long sinceWall) {
        boolean alive = false;
        long aliveWall = -1;
        String v = Settings.Global.getString(getContentResolver(), KEY_ALIVE);
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (v != null) {
            String[] p = v.split(":");
            try {
                alive = p.length == 2 && Integer.parseInt(p[0]) == boot && Long.parseLong(p[1]) > afterElapsed;
                if (alive) aliveWall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - Long.parseLong(p[1]));
            } catch (NumberFormatException ignored) { }
        }
        long since = sinceWall > 0 ? sinceWall : System.currentTimeMillis() - SystemClock.elapsedRealtime();
        int crashes = crashes(since);
        boolean notLoading = alive && loadCrashAfter(Math.max(since, aliveWall));
        Log.i(TAG, "MG4Dash alive " + alive + " (" + v + ", boot " + boot + "), crashes " + crashes + (notLoading ? ", code not loading since" : ""));
        return alive && crashes < CRASH_LIMIT && !notLoading;
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

    /** Text of a crash where MG4Dash's code does not load (damaged compiled code). */
    static boolean isLoadCrash(String text) {
        return text != null && text.contains("Process: " + DASH + "\n")
                && (text.contains("ClassNotFoundException") || text.contains("NoClassDefFoundError") || text.contains("Failed resolution of"));
    }

    /** A crash of MG4Dash's code not loading after [since] (wall clock). */
    private boolean loadCrashAfter(long since) {
        try {
            DropBoxManager db = getSystemService(DropBoxManager.class);
            for (String tag : new String[] { "system_app_crash", "data_app_crash" }) {
                long t = since;
                DropBoxManager.Entry e;
                while ((e = db.getNextEntry(tag, t)) != null) {
                    t = e.getTimeMillis();
                    String text = e.getText(4096);
                    e.close();
                    if (isLoadCrash(text)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "crash log: " + e.getMessage());
        }
        return false;
    }

    private void restoreStock(PackageManager pm, ComponentName stock, String why) {
        Log.w(TAG, why + ": restoring SAIC home " + stock);
        forceStop(DASH);
        if (stock == null) { Log.e(TAG, "no SAIC home found"); return; }
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

    /** Installed, a disabled MG4Dash included (never taken for an uninstalled one). */
    static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
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

    /** Equivalent of "cmd package compile -m speed -f app.mg4dash"; replaced in the tests. */
    void recompile(String pkg) {
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

    /** Answer to MG4Dash's INIT: the rescue is installed and its code runs. */
    static void answerInit(Context c) {
        try {
            long v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
            Settings.Global.putString(c.getContentResolver(), KEY_OK, v + ":" + System.currentTimeMillis());
        } catch (Exception e) {
            Log.w(TAG, "init answer: " + e.getMessage());
        }
    }

    /** Waits [ms]: the boot, the stop, MG4Dash's relaunch. Replaced in the tests. */
    void pause(long ms) throws InterruptedException { Thread.sleep(ms); }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
