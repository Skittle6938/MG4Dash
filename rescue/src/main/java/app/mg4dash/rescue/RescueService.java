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
 * Filet de sécurité de MG4Dash. Quand MG4Dash a remplacé l'accueil SAIC, seul lui peut le réactiver : s'il ne
 * démarre plus (code compilé abîmé, plantage au lancement), l'écran reste bloqué. Ce service, dans une appli à
 * part sans code commun avec MG4Dash :
 *  1. 90 s après le démarrage, regarde si MG4Dash est l'accueil et s'il a donné signe de vie pendant ce
 *     démarrage (réglage global mg4dash_alive, écrit par MG4Dash à son lancement) sans planter en boucle ;
 *  2. sinon, arrête MG4Dash, recompile son code, le relance et attend 60 s ;
 *  3. s'il ne démarre toujours pas, réactive l'accueil SAIC, le remet par défaut et l'ouvre.
 * Le résultat est laissé dans mg4dash_rescued pour que MG4Dash l'affiche à son prochain lancement.
 * Si MG4Dash a été désinstallé sans passer par le mode Entretien, l'accueil SAIC est aussi réactivé.
 */
public class RescueService extends Service {
    static final String TAG = "MG4Rescue";
    private static final String DASH = "app.mg4dash";
    private static final ComponentName DASH_HOME = new ComponentName(DASH, "app.mg4dash.MainActivity");
    private static final String KEY_ALIVE = "mg4dash_alive";      // « nombre de démarrages:elapsedRealtime »
    private static final String KEY_RESCUED = "mg4dash_rescued";  // « recompiled|stock:heure »
    private static final long FIRST_CHECK_MS = 90_000;
    private static final long RETRY_WAIT_MS = 60_000;
    private static final int CRASH_LIMIT = 3;

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

    private void run(long delay) throws InterruptedException {
        Thread.sleep(delay);
        PackageManager pm = getPackageManager();
        ComponentName stock = findStock(pm);
        boolean dashInstalled = installed(pm, DASH);
        boolean stockDisabled = stock != null && pm.getComponentEnabledSetting(stock) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        Log.i(TAG, "check: MG4Dash " + (dashInstalled ? "installed" : "absent") + ", SAIC home " + stock + (stockDisabled ? " disabled" : " enabled"));

        // MG4Dash désinstallé alors qu'il avait désactivé l'accueil SAIC : on le remet
        if (!dashInstalled) {
            if (stockDisabled) restoreStock(pm, stock, "MG4Dash not installed");
            return;
        }
        // MG4Dash n'est pas l'accueil (accueil SAIC ou autre launcher) : rien à faire
        if (!stockDisabled) return;

        if (healthy(-1, 0)) { Log.i(TAG, "MG4Dash is fine"); return; }

        // 2) Arrêt, recompilation, relance
        // MG4Dash étant l'accueil, Android le relance aussitôt après un arrêt : il tournait alors pendant la
        // recompilation, qui abîmait son code (essai du 31/10). Il est donc désactivé le temps de la recompiler.
        Log.w(TAG, "MG4Dash did not start: recompiling and relaunching it");
        try {
            pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            Thread.sleep(1000);
            forceStop(DASH);
            Thread.sleep(1000);
            Log.i(TAG, "MG4Dash disabled, running: " + running(DASH));
            recompile(DASH);
        } finally {
            pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        }
        setPreferred(pm, DASH_HOME);
        // Relance sur un processus neuf : son signe de vie prouve que le code recompilé se charge
        forceStop(DASH);
        long t0 = SystemClock.elapsedRealtime();
        long wall0 = System.currentTimeMillis();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(DASH_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Log.w(TAG, "relaunch failed: " + e.getMessage());
        }
        Thread.sleep(RETRY_WAIT_MS);
        if (healthy(t0, wall0)) {
            Log.i(TAG, "MG4Dash started after recompiling");
            mark("recompiled");
            return;
        }
        // 3) Accueil SAIC
        restoreStock(pm, stock, "MG4Dash still not starting");
    }

    /**
     * MG4Dash en bonne santé : signe de vie pendant ce démarrage (après [afterElapsed] si ≥ 0) et moins de
     * [CRASH_LIMIT] plantages depuis [sinceWall] (0 : depuis le démarrage de l'autoradio).
     */
    private boolean healthy(long afterElapsed, long sinceWall) {
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

    /** Plantages de MG4Dash depuis [since] (heure murale), d'après le journal des plantages d'Android. */
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

    /** Accueil SAIC de ce firmware (désactivé compris), hors assistant de premier démarrage. */
    private static ComponentName findStock(PackageManager pm) {
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

    /** Équivalent de « cmd package compile -m speed -f app.mg4dash ». */
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

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
