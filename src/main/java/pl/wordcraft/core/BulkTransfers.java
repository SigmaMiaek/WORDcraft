package pl.wordcraft.core;

import java.io.InputStream;
import java.io.IOException;

/** Counts actual HTTP response body bytes only for the current bulk asset. */
public final class BulkTransfers {
    private static final ThreadLocal<String> MEDIA = new ThreadLocal<>();
    private static volatile BulkDownloader receiver;
    private BulkTransfers() {}
    public static void attach(BulkDownloader value) { receiver = value; }
    public static Scope scope(String media) {
        String before = MEDIA.get();
        MEDIA.set(media);
        return new Scope(before);
    }
    public static InputStream wrap(InputStream input) {
        BulkDownloader target = receiver;
        String name = MEDIA.get();
        if (target == null || name == null) return input;
        return new InputStream() {
            @Override public int read() throws IOException {
                int value = input.read();
                if (value >= 0) target.recordBytes(name, 1);
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = input.read(bytes, offset, length);
                target.recordBytes(name, count);
                return count;
            }
            @Override public void close() throws IOException { input.close(); }
            @Override public int available() throws IOException { return input.available(); }
            @Override public long skip(long amount) throws IOException { return input.skip(amount); }
            @Override public boolean markSupported() { return input.markSupported(); }
            @Override public void mark(int limit) { input.mark(limit); }
            @Override public void reset() throws IOException { input.reset(); }
        };
    }
    public static final class Scope implements AutoCloseable {
        private final String before;
        private Scope(String before) { this.before = before; }
        @Override public void close() {
            if (before == null) MEDIA.remove(); else MEDIA.set(before);
        }
    }
}
