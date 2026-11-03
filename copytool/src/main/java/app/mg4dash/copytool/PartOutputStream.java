package app.mg4dash.copytool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes a single stream to several size-limited files name.001, name.002…: the GitHub site refuses
 * files over 25 MB. They are joined back in order (cat name.* > name).
 */
final class PartOutputStream extends OutputStream {
    private final File dir;
    private final String name;
    private final long partSize;
    private final List<File> parts = new ArrayList<>();
    private FileOutputStream current;
    private long written;

    PartOutputStream(File dir, String name, long partSize) {
        this.dir = dir;
        this.name = name;
        this.partSize = partSize;
    }

    List<File> parts() { return parts; }

    private void next() throws IOException {
        closeCurrent();
        File f = new File(dir, String.format(Locale.ROOT, "%s.%03d", name, parts.size() + 1));
        current = new FileOutputStream(f);
        parts.add(f);
        written = 0;
    }

    @Override public void write(int b) throws IOException {
        write(new byte[] { (byte) b }, 0, 1);
    }

    @Override public void write(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            if (current == null || written >= partSize) next();
            int n = (int) Math.min(len, partSize - written);
            current.write(b, off, n);
            written += n;
            off += n;
            len -= n;
        }
    }

    private void closeCurrent() throws IOException {
        if (current == null) return;
        current.flush();
        current.getFD().sync();   // USB stick: the data must be there when the app announces the end
        current.close();
        current = null;
    }

    @Override public void close() throws IOException { closeCurrent(); }
}
