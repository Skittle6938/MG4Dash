package app.mg4dash.copytool;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Ajoute des fichiers et des dossiers à un zip ; un fichier illisible est noté dans le rapport, sans arrêter la copie. */
final class ZipFolder {
    private ZipFolder() {}

    /** Nombre de fichiers ajoutés. Une erreur d'écriture (clé pleine, retirée) remonte et arrête tout. */
    static int addDir(ZipOutputStream zip, File dir, String prefix, StringBuilder report) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) {
            report.append("cannot list ").append(dir).append('\n');
            return 0;
        }
        Arrays.sort(files);
        int n = 0;
        for (File f : files) {
            String entry = prefix + "/" + f.getName();
            if (f.isDirectory()) n += addDir(zip, f, entry, report);
            else if (addFile(zip, f, entry, report)) n++;
        }
        return n;
    }

    static boolean addFile(ZipOutputStream zip, File f, String entry, StringBuilder report) throws IOException {
        FileInputStream in;
        try {
            in = new FileInputStream(f);
        } catch (IOException e) {
            report.append("cannot read ").append(f).append(": ").append(e.getMessage()).append('\n');
            return false;
        }
        try {
            zip.putNextEntry(new ZipEntry(entry));
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = read(in, buf, f, report)) > 0) zip.write(buf, 0, r);
            zip.closeEntry();
            report.append(f).append("  ").append(f.length()).append(" bytes\n");
            return true;
        } finally {
            in.close();
        }
    }

    /** Une erreur de lecture coupe ce fichier seulement ; celles de la clé passent par zip.write. */
    private static int read(FileInputStream in, byte[] buf, File f, StringBuilder report) {
        try {
            return in.read(buf);
        } catch (IOException e) {
            report.append("read stopped ").append(f).append(": ").append(e.getMessage()).append('\n');
            return -1;
        }
    }
}
