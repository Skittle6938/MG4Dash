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
import android.net.Uri;
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
 * Rescue v4 (19/11, after a MG4Dash install left code that did not load and the rescue did nothing as SAIC home stayed
 * enabled): MG4Dash looked after whenever it is the home, a single crash of code that does not load is enough, the
 * update hand-over, a repair cut by a power loss undone, one recompilation per install and boot, the new starts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RescueV4Test {
    private static final String DASH = "app.mg4dash";
    private static final ComponentName DASH_HOME = new ComponentName(DASH, "app.mg4dash.MainActivity");
    private static final ComponentName SAIC_HOME = new ComponentName("com.saicmotor.launcher", "com.saicmotor.launcher.Launcher");
    private static final int BOOT = 7;

    /** The service with its waits skipped; [onRelaunch] plays MG4Dash after its relaunch; [recompileFails] throws. */
    public static class Checked extends RescueService {
        Runnable onRelaunch = () -> {};
        Runnable onRecompile = () -> {};
        int recompiles;
        final List<Long> pauses = new ArrayList<>();
        @Override void pause(long ms) {
            pauses.add(ms);
            if (ms == RETRY_WAIT_MS) {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(60));
                onRelaunch.run();
            }
        }
        @Override void recompile(String pkg) { recompiles++; onRecompile.run(); }
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

    private void install(String pkg) {
        PackageInfo p = new PackageInfo();
        p.packageName = pkg;
        p.lastUpdateTime = 1_000L;
        spm.installPackage(p);
    }

    private void home(ComponentName c) {
        try { pm.getPackageInfo(c.getPackageName(), 0); } catch (PackageManager.NameNotFoundException e) { install(c.getPackageName()); }
        spm.addActivityIfNotPresent(c);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        try { spm.addIntentFilterForActivity(c, f); } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Marc's car: SAIC home enabled (its hub stays), MG4Dash the home chosen. */
    private void dashHomeSaicEnabled(String said) {
        home(SAIC_HOME);
        home(DASH_HOME);
        if (said != null) Settings.Global.putString(cr, RescueService.KEY_HOME, said);
    }

    private void dashHomeSaicDisabled() {
        home(SAIC_HOME);
        home(DASH_HOME);
        pm.setComponentEnabledSetting(SAIC_HOME, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private void alive(int boot) { Settings.Global.putString(cr, "mg4dash_alive", boot + ":" + SystemClock.elapsedRealtime()); }

    private long last = Long.MIN_VALUE, lastBefore = Long.MAX_VALUE;

    /** A crash of MG4Dash, its code not loading ([load]) or not, after now ([after]) or a little before. */
    private void crash(boolean load, boolean after) {
        // A crash "after" comes a second after whatever was written before it (its sign of life)
        if (after) org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        long now = System.currentTimeMillis();
        long at = after ? (last = Math.max(now - 500, last + 1)) : (lastBefore = Math.min(now - 1000, lastBefore - 1));
        String text = "Process: " + DASH + "\nFlags: 0x0\n\n" + (load ? "java.lang.NoClassDefFoundError: Failed resolution of: Lapp/mg4dash/R$style;\n" : "java.lang.IllegalStateException: oops\n");
        shadowOf(service.getSystemService(DropBoxManager.class)).addData("system_app_crash", at, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Whether MG4Dash's background was woken (its WakeReceiver, explicitly). */
    private boolean woken() {
        for (Intent i : shadowOf(RuntimeEnvironment.getApplication()).getBroadcastIntents())
            if ("app.mg4dash.WAKE".equals(i.getAction()) && i.getComponent() != null && DASH.equals(i.getComponent().getPackageName())
                    && (i.getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0) return true;
        return false;
    }

    /** A crash of code not loading after MG4Dash's relaunch (from onRelaunch): after the relaunch's wall time. */
    private void crashAfterRelaunch() {
        // A second after its sign of life (the test's elapsed clock is its own, the wall clock the real one)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
        long at = last = Math.max(System.currentTimeMillis() + 1, last + 1);
        String text = "Process: " + DASH + "\nFlags: 0x0\n\njava.lang.NoClassDefFoundError: Failed resolution of: Lapp/mg4dash/R$style;\n";
        shadowOf(service.getSystemService(DropBoxManager.class)).addData("system_app_crash", at, text.getBytes(StandardCharsets.UTF_8));
    }

    private String rescued() { return Settings.Global.getString(cr, "mg4dash_rescued"); }
    private int dashEnabled() { return pm.getApplicationEnabledSetting(DASH); }
    private ComponentName opened() { Intent i = shadowOf(service).getNextStartedActivity(); return i == null ? null : i.getComponent(); }

    // ── When to act ──

    @Test public void saicEnabledDashHomeIsRepaired() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertEquals(1, service.recompiles);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
        assertEquals(DASH_HOME, opened());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
    }

    @Test public void saicEnabledDashNotHomeByChoiceNothingDone() throws Exception {
        // Maintenance mode or SAIC home chosen: MG4Dash said it is not home
        dashHomeSaicEnabled("0");
        service.run(0);
        assertEquals(0, service.recompiles);
        assertNull(rescued());
        assertNull(opened());
    }

    @Test public void dashPreferredHomeIsLookedAfter() throws Exception {
        dashHomeSaicEnabled(null);
        IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
        f.addCategory(Intent.CATEGORY_HOME);
        f.addCategory(Intent.CATEGORY_DEFAULT);
        pm.addPreferredActivity(f, IntentFilter.MATCH_CATEGORY_EMPTY, new ComponentName[] { SAIC_HOME, DASH_HOME }, DASH_HOME);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertEquals(1, service.recompiles);
    }

    // ── Health ──

    @Test public void oneCrashOfCodeNotLoadingIsEnough() throws Exception {
        // Its sign of life written, then the code that does not load: one crash, not three
        dashHomeSaicEnabled("1");
        alive(BOOT);
        crash(true, true);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(1, service.recompiles);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void anOrdinaryCrashAloneIsNotRepaired() throws Exception {
        dashHomeSaicEnabled("1");
        alive(BOOT);
        crash(false, true);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(0, service.recompiles);
        assertNull(rescued());
    }

    @Test public void loadCrashBeforeItsSignOfLifeIsOld() throws Exception {
        // It crashed, then started fine (Android's relaunch, a recompilation): nothing to do
        dashHomeSaicEnabled("1");
        crash(true, false);
        alive(BOOT);
        service.run(0);
        assertEquals(0, service.recompiles);
    }

    @Test public void slowStartIsNoFailure() throws Exception {
        // Its sign of life only 50 s after the relaunch: still in time
        dashHomeSaicDisabled();
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void stillCrashingAfterRecompileFallsBack() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> { alive(BOOT); crashAfterRelaunch(); };
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
        assertEquals(SAIC_HOME, opened());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
    }

    @Test public void oneRecompilePerInstallAndBoot() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertEquals(1, service.recompiles);
        // It fails again later in the same boot, same install: SAIC home, no second recompilation
        crash(true, true);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(1, service.recompiles);
        assertTrue(rescued(), rescued().startsWith("stock:"));
    }

    // ── Never left disabled ──

    @Test public void repairCutByPowerLossIsUndoneFirst() throws Exception {
        dashHomeSaicEnabled("1");
        alive(BOOT);
        pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        Settings.Global.putString(cr, RescueService.KEY_DISABLED, "1");
        final int[] atStart = { -1 };
        service.onRecompile = () -> {};
        // Checked before the first wait
        service.pauses.clear();
        service.run(5_000);
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
        assertNull(Settings.Global.getString(cr, RescueService.KEY_DISABLED));
        assertEquals(0, service.recompiles);
        assertEquals(atStart[0], -1);
    }

    @Test public void disabledDashIsNotUninstalled() throws Exception {
        dashHomeSaicDisabled();
        pm.setApplicationEnabledSetting(DASH, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        assertTrue(RescueService.installed(pm, DASH));
    }

    @Test public void recompileThrowingLeavesDashEnabled() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRecompile = () -> { throw new IllegalStateException("dexopt died"); };
        try { service.run(0); } catch (IllegalStateException expected) { }
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
        assertNull(Settings.Global.getString(cr, RescueService.KEY_DISABLED));
    }

    @Test public void relaunchFailingEndsHealthyOrWithSaicHome() throws Exception {
        // The relaunch refused (no activity): it gives no sign of life, so SAIC home
        dashHomeSaicEnabled("1");
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
    }

    // ── After MG4Dash's update ──

    @Test public void updatedThenStartsNormally() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertEquals(1, service.recompiles);
        assertNull("no repair to tell", rescued());
        assertEquals(DASH_HOME, opened());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
    }

    @Test public void updatedThenNotStartingFallsBack() throws Exception {
        dashHomeSaicEnabled("1");
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertTrue(rescued(), rescued().startsWith("stock:"));
    }

    @Test public void updatedInMaintenanceRecompiledNotRelaunched() throws Exception {
        // Maintenance mode (Marc, 19/11): MG4Dash does nothing but wait for its gesture: recompiled, its background
        // woken for the gesture, no screen of it opened
        dashHomeSaicEnabled("0");
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertEquals(1, service.recompiles);
        assertNull(rescued());
        assertNull("no screen opened", opened());
        assertTrue("gesture woken", woken());
    }

    // ── After an install by USB / ADB (PACKAGE_REPLACED): the same clean recompilation ──





    @Test public void oneRecompilePerInstall() throws Exception {
        // Its updater and its first start both tell the rescue of the same install: recompiled once, relaunched each time
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_UPDATED, -1);
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertEquals(1, service.recompiles);
    }

    @Test public void aNewInstallIsRecompiledAgain() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_UPDATED, -1);
        PackageInfo p = pm.getPackageInfo(DASH, 0);
        p.lastUpdateTime = 2_000L;
        spm.installPackage(p);
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertEquals(2, service.recompiles);
    }

    // ── Code repair whatever the home, home steps only for the home ──

    @Test public void loadCrashNotHomeRecompiledOnly() throws Exception {
        // MG4Dash's own crash fallback said "not home" while the rescue's repair was pending: code fixed, SAIC stays
        dashHomeSaicEnabled("0");
        alive(BOOT);
        crash(true, true);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(1, service.recompiles);
        assertNull("no relaunch", opened());
        assertTrue("background woken", woken());
        List<IntentFilter> filters = new ArrayList<>();
        List<ComponentName> names = new ArrayList<>();
        pm.getPreferredActivities(filters, names, null);
        assertTrue("no HOME preference set", names.isEmpty());
        assertTrue("SAIC home untouched", pm.getComponentEnabledSetting(SAIC_HOME) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        assertNull(rescued());
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, dashEnabled());
    }

    @Test public void loadCrashHomeRecompiledRelaunchedWatched() throws Exception {
        dashHomeSaicEnabled("1");
        alive(BOOT);
        crash(true, true);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(1, service.recompiles);
        assertEquals(DASH_HOME, opened());
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
        assertTrue("watched", service.pauses.contains(RescueService.RETRY_WAIT_MS));
        assertFalse("relaunched instead", woken());
    }

    // ── MG4Dash's own crash guard put SAIC home ("auto"): brought back once repaired ──

    private String homeWord() { return Settings.Global.getString(cr, RescueService.KEY_HOME); }

    @Test public void autoLoadCrashRepairedAndHomeAgain() throws Exception {
        dashHomeSaicEnabled("auto");
        crash(true, true);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertEquals(1, service.recompiles);
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
        assertEquals("1", homeWord());
        assertEquals(DASH_HOME, opened());
        List<IntentFilter> filters = new ArrayList<>();
        List<ComponentName> names = new ArrayList<>();
        pm.getPreferredActivities(filters, names, null);
        assertTrue("MG4Dash the preferred HOME", names.contains(DASH_HOME));
    }

    @Test public void autoEvenRunningIsBroughtBackAtBoot() throws Exception {
        // Its process runs (not as the home) and gave a sign of life: brought back as the home all the same
        dashHomeSaicEnabled("auto");
        alive(BOOT);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertEquals(1, service.recompiles);
        assertEquals("1", homeWord());
    }

    @Test public void autoStillCrashingStockAndNoRetry() throws Exception {
        dashHomeSaicEnabled("auto");
        service.onRelaunch = () -> { alive(BOOT); crashAfterRelaunch(); };
        service.run(0);
        assertTrue(rescued(), rescued().startsWith("stock:"));
        assertEquals("0", homeWord());
        // Next boot: not tried again (only a new install is)
        Settings.Global.putInt(cr, Settings.Global.BOOT_COUNT, BOOT + 1);
        service.run(0);
        assertEquals(1, service.recompiles);
    }

    @Test public void autoAtBootNoSignOfLifeOneAttempt() throws Exception {
        dashHomeSaicEnabled("auto");
        service.run(0);
        service.run(0);
        assertEquals(1, service.recompiles);
        assertEquals("0", homeWord());
    }

    @Test public void autoAfterInstallRelaunchedAsHomeAndToldSo() throws Exception {
        dashHomeSaicEnabled("auto");
        service.onRelaunch = () -> alive(BOOT);
        service.run(0, RescueService.MODE_UPDATED, -1);
        assertEquals(1, service.recompiles);
        assertEquals(DASH_HOME, opened());
        assertEquals("1", homeWord());
        assertTrue(rescued(), rescued().startsWith("recompiled:"));
    }

    @Test public void driversOwnChoiceUntouched() throws Exception {
        dashHomeSaicEnabled("0");
        service.run(0);
        crash(true, true);
        service.run(0, RescueService.MODE_CRASH, -1);
        assertNull(opened());
        assertEquals("0", homeWord());
    }

    // ── Busy mark, crash log alarm (after the car trials, 19/11) ──

    @Test public void busyWhileCheckingClearedAfter() throws Exception {
        dashHomeSaicEnabled("1");
        final String[] during = { null };
        service.onRecompile = () -> during[0] = Settings.Global.getString(cr, RescueService.KEY_BUSY);
        service.onRelaunch = () -> alive(BOOT);
        service.run(0);
        assertNotNull("busy during the check", during[0]);
        assertTrue(during[0], during[0].startsWith(BOOT + ":"));
        assertNull("cleared after", Settings.Global.getString(cr, RescueService.KEY_BUSY));
    }

    @Test public void busyClearedEvenWhenTheCheckThrows() throws Exception {
        dashHomeSaicEnabled("1");
        service.onRecompile = () -> { throw new IllegalStateException("dexopt died"); };
        try { service.run(0); } catch (IllegalStateException expected) { }
        assertNull(Settings.Global.getString(cr, RescueService.KEY_BUSY));
    }

    @Test public void crashLogScanFindsCodeNotLoading() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        DropBoxManager db = c.getSystemService(DropBoxManager.class);
        shadowOf(db).addData("system_app_crash", System.currentTimeMillis() - 1000,
                ("Process: " + DASH + "\n\njava.lang.ClassNotFoundException: app.mg4dash.LauncherApp\n").getBytes(StandardCharsets.UTF_8));
        BootReceiver.scan(c);
        Intent i = started();
        assertNotNull("check started", i);
        assertEquals(RescueService.MODE_CRASH, i.getStringExtra("mode"));
        // Already read: not again
        BootReceiver.scan(c);
        assertNull(started());
    }

    @Test public void crashLogScanIgnoresOthers() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        DropBoxManager db = c.getSystemService(DropBoxManager.class);
        shadowOf(db).addData("system_app_crash", System.currentTimeMillis() - 1000, "Process: app.mg4dash\n\njava.lang.NullPointerException\n".getBytes(StandardCharsets.UTF_8));
        shadowOf(db).addData("data_app_crash", System.currentTimeMillis() - 900, "Process: com.other\n\njava.lang.ClassNotFoundException\n".getBytes(StandardCharsets.UTF_8));
        BootReceiver.scan(c);
        assertNull(started());
    }

    @Test public void bootAndInitArmTheScan() {
        android.content.Context c = RuntimeEnvironment.getApplication();
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        new BootReceiver().onReceive(c, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertNotNull("armed at boot", shadowOf(am).getNextScheduledAlarm());
        // The boot's own check still starts
        assertNotNull(started());
        new BootReceiver().onReceive(c, new Intent(BootReceiver.ACTION_INIT));
        assertNotNull("armed at init", shadowOf(am).getNextScheduledAlarm());
    }

    // ── Starts ──

    private Intent started() { return shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService(); }

    @Test public void updatedStartsTheHandOver() {
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent(BootReceiver.ACTION_UPDATED));
        Intent i = started();
        assertEquals(RescueService.MODE_UPDATED, i.getStringExtra("mode"));
    }



    @Test public void crashToldByDashStartsACheck() {
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent(BootReceiver.ACTION_CRASH));
        assertEquals(RescueService.MODE_CRASH, started().getStringExtra("mode"));
    }

    @Test public void initIsAnswered() {
        new BootReceiver().onReceive(RuntimeEnvironment.getApplication(), new Intent(BootReceiver.ACTION_INIT));
        assertNotNull(Settings.Global.getString(RuntimeEnvironment.getApplication().getContentResolver(), RescueService.KEY_OK));
        assertNull(started());
    }

    @Test public void loadCrashText() {
        assertTrue(RescueService.isLoadCrash("Process: app.mg4dash\nx\njava.lang.NoClassDefFoundError: Failed resolution of: Lapp/mg4dash/R$style;"));
        assertFalse(RescueService.isLoadCrash("Process: app.mg4dash.rescue\njava.lang.ClassNotFoundException"));
        assertFalse(RescueService.isLoadCrash("Process: app.mg4dash\njava.lang.NullPointerException"));
    }
}
