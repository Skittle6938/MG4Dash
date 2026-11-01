package app.mg4dash.copytool;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Copie sur la clé USB le launcher et le SystemUI d'origine (dossiers entiers, oat/ compris), plus les lignes
 * du journal sur les boutons du volant. Ne modifie rien dans la voiture, et ne touche pas à internet.
 */
public final class MainActivity extends Activity {
    private static final String[] PACKAGES = { "com.saicmotor.launcher", "com.android.systemui" };
    private static final String ZIP_NAME = "swi173_files.zip";
    private static final long PART_SIZE = 20L * 1024 * 1024;
    private static final int MATCH_FACTORY_ONLY = 0x00200000;
    /** Seules ces lignes du journal sont gardées : les boutons du volant, rien de personnel. */
    private static final Pattern LOG_LINES = Pattern.compile("key receive|processKeyEvent|mCurrentController|SwcManager|StarKey");
    private static final int MAX_LOG_LINES = 20000;

    private TextView status;
    private Button copy;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);
        TextView help = new TextView(this);
        help.setTextColor(Color.WHITE);
        help.setTextSize(20);
        help.setText("Copies the original launcher and system bar apps to a USB stick. It only reads, nothing changes in the car.\n\n"
            + "1. Plug in a USB stick with at least 300 MB free.\n"
            + "2. With music playing, press the right joystick (left, right, OK) and the * button a few times.\n"
            + "3. Tap Copy and wait for the end message (a minute or two).");
        copy = new Button(this);
        copy.setText("Copy to USB stick");
        copy.setTextSize(22);
        copy.setOnClickListener(v -> start());
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(20);
        status.setGravity(Gravity.START);
        status.setPadding(0, pad / 2, 0, 0);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        root.addView(help);
        root.addView(copy);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void say(String text) { runOnUiThread(() -> status.setText(text)); }

    private void start() {
        copy.setEnabled(false);
        say("Copying…");
        new Thread(() -> {
            String end;
            try { end = run(); }
            catch (Exception e) { end = "Failed: " + e.getMessage() + "\n\nCheck the USB stick and try again."; }
            String text = end;
            runOnUiThread(() -> { status.setText(text); copy.setEnabled(true); });
        }).start();
    }

    private File usbRoot() {
        StorageManager sm = (StorageManager) getSystemService(STORAGE_SERVICE);
        for (StorageVolume v : sm.getStorageVolumes()) {
            if (!v.isRemovable() || !Environment.MEDIA_MOUNTED.equals(v.getState())) continue;
            try {
                File f = (File) v.getClass().getMethod("getPathFile").invoke(v);
                if (f != null) return f;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /** Racine de la clé si on peut y écrire (refusé sur certains firmwares), sinon le dossier propre à l'appli sur la clé. */
    private File outputDir(File usb) throws IOException {
        File root = new File(usb, "mg4_files");
        if ((root.mkdirs() || root.isDirectory()) && canWrite(root)) return root;
        for (File f : getExternalFilesDirs(null)) {
            if (f == null || !f.getAbsolutePath().startsWith(usb.getAbsolutePath())) continue;
            File own = new File(f, "mg4_files");
            if ((own.mkdirs() || own.isDirectory()) && canWrite(own)) return own;
        }
        throw new IOException("cannot write to the USB stick");
    }

    private boolean canWrite(File dir) {
        File t = new File(dir, ".test");
        try (FileOutputStream o = new FileOutputStream(t)) { o.write(1); }
        catch (IOException e) { return false; }
        return t.delete();
    }

    private String run() throws IOException {
        File usb = usbRoot();
        if (usb == null) return "No USB stick found. Plug one in and tap Copy again.";
        File out = outputDir(usb);
        File[] old = out.listFiles((d, n) -> n.startsWith(ZIP_NAME + "."));
        if (old != null) for (File f : old) f.delete();

        StringBuilder report = new StringBuilder();
        info(report);
        int files = 0;
        PartOutputStream parts = new PartOutputStream(out, ZIP_NAME, PART_SIZE);
        try (ZipOutputStream zip = new ZipOutputStream(parts)) {
            for (String pkg : PACKAGES) {
                say("Copying " + pkg + "…");
                files += addPackage(zip, pkg, report);
            }
            say("Saving the log…");
            zip.putNextEntry(new ZipEntry("buttons.txt"));
            zip.write(buttonLog(report).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("report.txt"));
            zip.write(report.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        if (files == 0) return "Nothing could be read. Please tell us on GitHub.";
        String where = out.getAbsolutePath().substring(usb.getAbsolutePath().length());
        boolean own = !where.equals("/mg4_files");
        return "Done: " + files + " files.\n\nOn the USB stick, folder " + where + ": " + parts.parts().size() + " file(s) named " + ZIP_NAME + ".001…\n"
            + "Upload all of them to the private repository."
            + (own ? "\n\nKeep this app installed until you have copied them: uninstalling it erases this folder." : "");
    }

    private int addPackage(ZipOutputStream zip, String pkg, StringBuilder report) throws IOException {
        PackageManager pm = getPackageManager();
        ApplicationInfo ai;
        try { ai = pm.getApplicationInfo(pkg, 0); }
        catch (PackageManager.NameNotFoundException e) {
            report.append(pkg).append(": not installed\n");
            return 0;
        }
        File current = new File(ai.sourceDir).getParentFile();
        report.append('\n').append(pkg).append(": ").append(ai.sourceDir).append('\n');
        int n = ZipFolder.addDir(zip, current, pkg + "/current", report);
        n += addDalvikCache(zip, current, pkg + "/dalvik-cache", report);
        // Mise à jour installée dans /data/app : on prend aussi la version d'usine, sur /system
        if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            try {
                File factory = new File(pm.getPackageInfo(pkg, MATCH_FACTORY_ONLY).applicationInfo.sourceDir).getParentFile();
                if (!factory.equals(current)) {
                    report.append(pkg).append(" factory: ").append(factory).append('\n');
                    n += ZipFolder.addDir(zip, factory, pkg + "/factory", report);
                    n += addDalvikCache(zip, factory, pkg + "/dalvik-cache", report);
                }
            } catch (PackageManager.NameNotFoundException e) {
                report.append(pkg).append(" factory: ").append(e.getMessage()).append('\n');
            }
        }
        return n;
    }

    /** Code compilé d'une appli système rangé hors de son dossier (arm64/system@priv-app@Nom@Nom.apk@classes.*), si lisible. */
    private int addDalvikCache(ZipOutputStream zip, File appDir, String prefix, StringBuilder report) throws IOException {
        String marker = "@" + appDir.getName() + "@";
        int n = 0;
        for (String abi : new String[] { "arm64", "arm" }) {
            File[] files = new File("/data/dalvik-cache/" + abi).listFiles((d, name) -> name.contains(marker));
            if (files == null) continue;
            for (File f : files) if (ZipFolder.addFile(zip, f, prefix + "/" + abi + "/" + f.getName(), report)) n++;
        }
        return n;
    }

    private String buttonLog(StringBuilder report) {
        StringBuilder log = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int kept = 0;
                while ((line = r.readLine()) != null) {
                    if (kept < MAX_LOG_LINES && LOG_LINES.matcher(line).find()) { log.append(line).append('\n'); kept++; }
                }
                report.append("\nlog lines kept: ").append(kept).append('\n');
            }
        } catch (IOException e) {
            report.append("\nlog: ").append(e.getMessage()).append('\n');
        } finally {
            if (p != null) p.destroy();
        }
        return log.toString();
    }

    private void info(StringBuilder report) {
        report.append("firmware: ").append(prop("ro.build.mt2712.version")).append('\n');
        report.append("android: ").append(Build.VERSION.RELEASE).append(" sdk ").append(Build.VERSION.SDK_INT).append('\n');
        PackageManager pm = getPackageManager();
        for (String pkg : new String[] { "com.saicmotor.launcher", "com.android.systemui", "app.mg4dash" }) {
            try {
                PackageInfo pi = pm.getPackageInfo(pkg, 0);
                report.append(pkg).append(": ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
            } catch (PackageManager.NameNotFoundException e) {
                report.append(pkg).append(": not installed\n");
            }
        }
        List<ResolveInfo> homes = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        for (ResolveInfo h : homes) report.append("home candidate: ").append(h.activityInfo.packageName).append('/').append(h.activityInfo.name).append('\n');
        ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        if (home != null) report.append("current home: ").append(home.activityInfo.packageName).append('\n');
        for (String key : new String[] { "star_key_function", "right_star_key_function" }) {
            report.append(key).append(" = ").append(Settings.System.getString(getContentResolver(), key)).append('\n');
        }
    }

    private static String prop(String name) {
        try {
            return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name);
        } catch (Throwable t) { return "?"; }
    }
}
