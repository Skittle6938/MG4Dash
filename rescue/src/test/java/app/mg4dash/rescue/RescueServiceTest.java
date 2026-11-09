package app.mg4dash.rescue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.DropBoxManager;
import android.os.SystemClock;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPackageManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The safety net when MG4Dash no longer starts (damaged compiled code, crash at launch): nothing done while it is fine,
 * a recompilation and a relaunch when it gave no sign of life or crash loops, SAIC home back when that is not enough,
 * and SAIC home back if MG4Dash was uninstalled while it had disabled it. The waits are skipped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RescueServiceTest {
    private static final String DASH = "app.mg4dash";
    private static final ComponentName DASH_HOME = new ComponentName(DASH, "app.mg4dash.MainActivity");
    private static final ComponentName SAIC_HOME = new ComponentName("com.saicmotor.launcher", "com.saicmotor.launcher.Launcher");
    private static final int BOOT = 7;

    /** The service with its waits skipped; [onRelaunch] plays what MG4Dash does while the rescue waits after relaunching it. */
    public static class Checked extends RescueService {
        Runnable onRelaunch = () -> {};
        final List<Long> pauses = new ArrayList<>();
        @Override void pause(long ms) {
            pauses.add(ms);
            if (ms == RETRY_WAIT_MS) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
                onRelaunch.run();
            }
        }
    }

    private Checked service;
    private PackageManager pm;
    private ShadowPackageManager spm;
    private ContentResolver cr;

    @Before public void setUp() {
        service = Robolectric.buildService(Checked.class).create().get();
        pm = service.getPackageManager();
        spm = shadowOf(pm);
        cr = service.getContentResolver();
        Settings.Global.putInt(cr, Settings.Global.BOOT_COUNT, BOOT);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
    }

    // ── The car as the rescue finds it ──

    private void install(String pkg) {
        PackageInfo p = new PackageInfo();
        p.packageName = pkg;
        spm.installPackage(p);
    }

    private void home(ComponentName c) {
        if (!installed(c.getPackageName())) install(c.getPackageName());
        spm.addActivityIfNotPresent(c);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        try { spm.addIntentFilterForActivity(c, f); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean installed(String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    /** MG4Dash installed as home: SAIC home disabled, as MG4Dash leaves it. */
    private void dashIsHome() {
        home(SAIC_HOME);
        home(DASH_HOME);
        pm.setComponentEnabledSetting(SAIC_HOME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    /** MG4Dash's sign of life (Rescue.alive): boot count and elapsed time. */
    private void alive(int boot) {
        Settings.Global.putString(cr, "mg4dash_alive", boot + ":" + SystemClock.elapsedRealtime());
    }

    /** Last times given to a crash before / after the relaunch: each new one strictly further out. */
    private long lastBefore = Long.MAX_VALUE, lastAfter = Long.MIN_VALUE;

    /**
     * A crash of [process] in Android's crash log, before MG4Dash's relaunch, or after it with [afterRelaunch]
     * (called from onRelaunch). Each at its own time: the crash log refuses two entries at the same millisecond.
     */
    private void crash(String process, boolean afterRelaunch) {
        long now = System.currentTimeMillis();
        // Strictly decreasing before the relaunch, strictly increasing after it, even when the clock moves by
        // exactly 1 ms between two calls (now - 1000 - seq used to collide then)
        long at = afterRelaunch ? (lastAfter = Math.max(now + 1, lastAfter + 1)) : (lastBefore = Math.min(now - 1000, lastBefore - 1));
        String text = "Process: " + process + "\nFlags: 0x0\n\njava.lang.ClassNotFoundException: app.mg4dash.LauncherApp\n";
        shadowOf(service.getSystemService(DropBoxManager.class)).addData("system_app_crash", at, text.getBytes(StandardCharsets.UTF_8));
    }

    private void crash(String process) { crash(process, false); }

    private String rescued() { return Settings.Global.getString(cr, "mg4dash_rescued"); }
    private boolean saicHomeEnabled() { return pm.getComponentEnabledSetting(SAIC_HOME) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED; }
    private ComponentName opened() {
        Intent i = shadowOf(service).getNextStartedActivity();
        return i == null ? null : i.getComponent();
    }

    private ComponentName preferredHome() {
        List<IntentFilter> filters = new ArrayList<>();
        List<ComponentName> names = new ArrayList<>();
        pm.getPreferredActivities(filters, names, null);
        for (int i = 0; i < filters.size(); i++) if (filters.get(i).hasCategory(Intent.CATEGORY_HOME)) return names.get(i);
        return null;
    }

    // ── Nothing to do ──

    @Test public void dashFineNothingIsDone() throws Exception {
        dashIsHome();
        alive(BOOT);
        service.run(0);
        assertNull(rescued());
        assertFalse(saicHomeEnabled());
        assertNull(opened());
        assertEquals("MG4Dash never stopped", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getApplicationEnabledSetting(DASH));
    }

    @Test public void dashNotHomeIsLeftAlone() throws Exception {
        // SAIC home (or another launcher) in use: MG4Dash's state does not matter
        home(SAIC_HOME);
        home(DASH_HOME);
        service.run(0);
        assertNull(rescued());
        assertNull(opened());
    }

    @Test public void nothingInstalledNothingDone() throws Exception {
        home(SAIC_HOME);
        service.run(0);
        assertNull(rescued());
        assertTrue(saicHomeEnabled());
    }

    @Test public void twoCrashesAreStillFine() throws Exception {
        dashIsHome();
        alive(BOOT);
        crash(DASH);
        crash(DASH);
        service.run(0);
        assertNull(rescued());
    }

    @Test public void otherAppsCrashesDoNotCount() throws Exception {
        dashIsHome();
        alive(BOOT);
        for (int i = 0; i < 5; i++) { crash("app.mg4dash.rescue"); crash("com.saicmotor.launcher"); }
        crash(DASH + "web");
        service.run(0);
        assertNull(rescued());
    }

    // ── MG4Dash uninstalled ──

    @Test public void dashUninstalledBringsSaicHomeBack() throws Exception {
        home(SAIC_HOME);
        pm.setComponentEnabledSetting(SAIC_HOME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        service.run(0);
        assertTrue(saicHomeEnabled());
        assertEquals(SAIC_HOME, preferredHome());
        assertEquals(SAIC_HOME, opened());
        assertTrue(rescued(), rescued().startsWith("stock:"));
    }

    // ── MG4Dash does not start ──

    @Test public void noSignOfLifeRecompilesAndRelaunches() throws Exception {
        dashIsHome();
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
        // Enabled again after the recompilation, home again, relaunched; SAIC home still off
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getApplicationEnabledSetting(DASH));
        assertEquals(DASH_HOME, preferredHome());
        assertEquals(DASH_HOME, opened());
        assertFalse(saicHomeEnabled());
        assertTrue("waited for the relaunch", service.pauses.contains(RescueService.RETRY_WAIT_MS));
    }

    @Test public void signOfLifeFromAPreviousBootDoesNotCount() throws Exception {
        dashIsHome();
        alive(BOOT - 1);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void damagedSignOfLifeDoesNotCount() throws Exception {
        dashIsHome();
        Settings.Global.putString(cr, "mg4dash_alive", "garbage");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void crashLoopIsRescuedEvenWithASignOfLife() throws Exception {
        // It started, then crashed again and again (code damaged further in)
        dashIsHome();
        alive(BOOT);
        for (int i = 0; i < RescueService.CRASH_LIMIT; i++) crash(DASH);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        // The crashes before the relaunch no longer count after it
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void stillNotStartingBringsSaicHomeBack() throws Exception {
        dashIsHome();
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
        assertTrue(saicHomeEnabled());
        assertEquals(SAIC_HOME, preferredHome());
        // SAIC home opened last, after MG4Dash's relaunch (the most recent comes first)
        assertEquals(SAIC_HOME, opened());
        assertEquals(DASH_HOME, opened());
        // MG4Dash itself stays enabled (Service mode, reinstall)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, pm.getApplicationEnabledSetting(DASH));
    }

    @Test public void signOfLifeFromBeforeTheRelaunchDoesNotCount() throws Exception {
        // Alive written before the relaunch then crashing: the rescue wants a new sign of life
        dashIsHome();
        alive(BOOT);
        for (int i = 0; i < RescueService.CRASH_LIMIT; i++) crash(DASH);
        service.onRelaunch = () -> { for (int i = 0; i < RescueService.CRASH_LIMIT; i++) crash(DASH, true); };
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
    }

    @Test public void crashingAgainAfterTheRelaunchBringsSaicHomeBack() throws Exception {
        dashIsHome();
        service.onRelaunch = () -> { alive(BOOT); for (int i = 0; i < RescueService.CRASH_LIMIT; i++) crash(DASH, true); };
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
    }

    // ── SAIC home of each firmware ──

    @Test public void saicHomeOfTheFirmware() {
        home(new ComponentName("com.example.launcher", "com.example.Home"));
        home(new ComponentName("com.saicmotor.setupwizard", "com.saicmotor.setupwizard.Wizard"));
        home(new ComponentName("com.saicmotor.hmi.launcher", "com.saicmotor.hmi.launcher.Main"));
        assertEquals(new ComponentName("com.saicmotor.hmi.launcher", "com.saicmotor.hmi.launcher.Main"), RescueService.findStock(pm));
        // SWI69's own launcher wins, even disabled
        home(SAIC_HOME);
        pm.setComponentEnabledSetting(SAIC_HOME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
        assertEquals(SAIC_HOME, RescueService.findStock(pm));
    }

    @Test public void noSaicHome() {
        home(new ComponentName("com.example.launcher", "com.example.Home"));
        home(new ComponentName("com.saicmotor.setupwizard", "com.saicmotor.setupwizard.Wizard"));
        assertNull(RescueService.findStock(pm));
    }

    // ── Start ──

    @Test public void bootStartsTheCheck() {
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent(Intent.ACTION_BOOT_COMPLETED));
        Intent i = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(i);
        assertEquals(RescueService.class.getName(), i.getComponent().getClassName());
        assertFalse(i.hasExtra("delay_ms"));
    }

    @Test public void checkOnDemandKeepsItsDelay() {
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent("app.mg4dash.rescue.CHECK").putExtra("delay_ms", 5_000L));
        Intent i = shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertEquals(5_000L, i.getLongExtra("delay_ms", -1));
    }

    @Test public void wakeAfterInstallStartsNothing() {
        // MG4Dash's INIT only takes the app out of the "stopped" state
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent("app.mg4dash.rescue.INIT"));
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }
}
