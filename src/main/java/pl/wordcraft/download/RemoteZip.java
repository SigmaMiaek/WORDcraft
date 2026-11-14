package pl.wordcraft.download;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import pl.wordcraft.core.BulkTransfers;

public final class RemoteZip {
    private static final Gson JSON = new Gson();
    private static final long MAX_FILE = 268435456L;
    private static final int MAX_DIRECTORY = 16777216;
    private static final int CHUNK = 2097152;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final Charset CP437 = Charset.forName("IBM437");
    private final URI uri;
    private final Path cache;
    private final RemoteZip.Progress progress;
    private final HttpClient http;
    private RemoteZip.Index current;

    public RemoteZip(URI uri, Path cacheDirectory, RemoteZip.Progress progress) {
        if (uri != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null && uri.getUserInfo() == null) {
            this.uri = uri;
            this.cache = cacheDirectory.toAbsolutePath().normalize().resolve("index-" + hash(uri.toString()) + ".json");
            this.progress = progress == null ? (bytes, total, text) -> {} : progress;
            this.http = OfficialHttp.client(Duration.ofSeconds(15L));
        } else {
            throw new IllegalArgumentException("Invalid archive URL");
        }
    }

    public synchronized Optional<RemoteZip.Index> cachedIndex() {
        if (this.current != null) {
            return Optional.of(this.current);
        }

        try {
            if (Files.isRegularFile(this.cache, LinkOption.NOFOLLOW_LINKS) && Files.size(this.cache) <= 33554432L) {
                RemoteZip.Index candidate = JSON.fromJson(Files.readString(this.cache), RemoteZip.Index.class);
                this.validateIndex(candidate);
                this.current = candidate;
                return Optional.of(candidate);
            } else {
                return Optional.empty();
            }
        } catch (IOException | RuntimeException ex) {
            return Optional.empty();
        }
    }

    public synchronized RemoteZip.Index index() throws IOException {
        RemoteZip.Range probe = this.range(0L, 1, null);
        RemoteZip.Index old = this.cachedIndex().orElse(null);
        if (old != null && old.archiveSize == probe.size && hasIdentity(old) && same(old.etag, probe.etag) && same(old.lastModified, probe.lastModified)) {
            return old;
        }

        long tailOffset = Math.max(0L, probe.size - 65557L);
        RemoteZip.Index identity = new RemoteZip.Index(this.uri, probe.size, probe.etag, probe.lastModified, List.of());
        byte[] tail = this.range(tailOffset, Math.toIntExact(probe.size - tailOffset), identity).bytes;
        int end = -1;

        for (int i = tail.length - 22; i >= 0; i--) {
            if (u32(tail, i) == 101010256L && i + 22L + u16(tail, i + 20) == tail.length) {
                end = i;
                break;
            }
        }

        if (end < 0) {
            throw invalid("Missing ZIP end record");
        }

        if (u16(tail, end + 4) == 0 && u16(tail, end + 6) == 0) {
            long count = u16(tail, end + 10);
            long directoryLength = u32(tail, end + 12);
            long directoryOffset = u32(tail, end + 16);
            if (u16(tail, end + 8) != count) {
                throw invalid("Split ZIP directory");
            }

            long directoryLimit = tailOffset + end;
            if (count == 65535L || directoryLength == 4294967295L || directoryOffset == 4294967295L) {
                int locator = end - 20;
                if (locator < 0 || u32(tail, locator) != 117853008L || u32(tail, locator + 4) != 0L || u32(tail, locator + 16) != 1L) {
                    throw invalid("Missing or split ZIP64 locator");
                }

                long offset = i64(tail, locator + 8);
                if (offset > tailOffset + locator - 56L) {
                    throw invalid("Invalid ZIP64 offset");
                }

                byte[] record = this.range(offset, 56, identity).bytes;
                if (u32(record, 0) != 101075792L
                    || i64(record, 4) < 44L
                    || i64(record, 4) > 1048576L
                    || safeAdd(offset, safeAdd(i64(record, 4), 12L)) != tailOffset + locator
                    || u32(record, 16) != 0L
                    || u32(record, 20) != 0L
                    || i64(record, 24) != i64(record, 32)) {
                    throw invalid("Invalid or split ZIP64 end record");
                }

                count = i64(record, 32);
                directoryLength = i64(record, 40);
                directoryOffset = i64(record, 48);
                directoryLimit = offset;
            }

            if (count >= 0L
                && count <= 200000L
                && directoryLength >= 0L
                && directoryLength <= 16777216L
                && safeAdd(directoryOffset, directoryLength) <= directoryLimit) {
                this.progress.update(0L, directoryLength, "Pobieranie spisu multimediów");
                byte[] directory = directoryLength == 0L ? new byte[0] : this.range(directoryOffset, Math.toIntExact(directoryLength), identity).bytes;
                List<RemoteZip.Entry> entries = parseDirectory(directory, count, directoryOffset);
                RemoteZip.Index result = new RemoteZip.Index(this.uri, probe.size, probe.etag, probe.lastModified, entries);
                this.validateIndex(result);
                secureParents(this.cache);
                Path temporary = this.cache.resolveSibling(this.cache.getFileName() + ".tmp");
                rejectSymlink(temporary);
                Files.writeString(temporary, JSON.toJson(result), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                publish(temporary, this.cache);
                this.current = result;
                this.progress.update(directoryLength, directoryLength, "Spis multimediów gotowy");
                return result;
            } else {
                throw invalid("Oversized ZIP directory");
            }
        } else {
            throw invalid("Split archives are unsupported");
        }
    }

    public Path extract(RemoteZip.Entry entry, Path target) throws IOException {
        RemoteZip.Index archive = this.cachedIndex().orElseGet(() -> null);
        if (archive == null) {
            archive = this.index();
        }

        if (!archive.entries.contains(entry)) {
            throw invalid("Entry is not part of the selected archive");
        }

        validateEntry(entry);
        if (entry.name.endsWith("/")) {
            throw invalid("Cannot extract a directory");
        }

        Path destination = target.toAbsolutePath().normalize();
        secureParents(destination);
        rejectSymlink(destination);
        if (verified(destination, entry)) {
            return destination;
        }

        interrupted();
        byte[] header = this.range(entry.localHeaderOffset, 30, archive).bytes;
        if (u32(header, 0) == 67324752L && u16(header, 8) == entry.method && u16(header, 6) == entry.flags) {
            int nameLength = u16(header, 26);
            int extraLength = u16(header, 28);
            byte[] name = this.range(safeAdd(entry.localHeaderOffset, 30L), nameLength, archive).bytes;
            if (!decode(name, entry.flags).equals(entry.name)) {
                throw invalid("Local ZIP filename disagrees with directory");
            }

            long payload = safeAdd(entry.localHeaderOffset, 30L + nameLength + extraLength);
            if (safeAdd(payload, entry.compressedSize) > archive.archiveSize) {
                throw invalid("ZIP member outside archive");
            }

            Path part = destination.resolveSibling(destination.getFileName() + ".download.part");
            Path stamp = destination.resolveSibling(destination.getFileName() + ".download.identity");
            Path inflated = destination.resolveSibling(destination.getFileName() + ".verify.part");
            rejectSymlink(part);
            rejectSymlink(stamp);
            rejectSymlink(inflated);
            String fingerprint = hash(JSON.toJson(archive.uri) + archive.archiveSize + archive.etag + archive.lastModified + JSON.toJson(entry));
            boolean resume = Files.isRegularFile(stamp, LinkOption.NOFOLLOW_LINKS)
                && Files.size(stamp) < 1024L
                && Files.readString(stamp).equals(fingerprint)
                && Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)
                && Files.size(part) <= entry.compressedSize;
            if (!resume) {
                Files.deleteIfExists(part);
                Files.writeString(stamp, fingerprint, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            long completed = resume ? Files.size(part) : 0L;

            try (OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                while (completed < entry.compressedSize) {
                    interrupted();
                    this.progress.update(completed, entry.compressedSize, "Pobieranie: " + basename(entry.name));
                    int length = (int)Math.min(2097152L, entry.compressedSize - completed);
                    byte[] bytes = this.range(payload + completed, length, archive).bytes;
                    out.write(bytes);
                    out.flush();
                    completed += length;
                }
            }

            try {
                this.inflate(part, inflated, entry);
                publish(inflated, destination);
                Files.deleteIfExists(part);
                Files.deleteIfExists(stamp);
                this.progress.update(entry.compressedSize, entry.compressedSize, "Gotowe: " + basename(entry.name));
                return destination;
            } catch (IOException ex) {
                Files.deleteIfExists(inflated);
                if (!(ex instanceof InterruptedIOException)) {
                    Files.deleteIfExists(part);
                    Files.deleteIfExists(stamp);
                }

                throw ex;
            }
        } else {
            throw invalid("Local ZIP header disagrees with directory");
        }
    }

    private void inflate(Path part, Path output, RemoteZip.Entry entry) throws IOException {
        Inflater inflater = entry.method == 8 ? new Inflater(true) : null;

        try (
            InputStream raw = Files.newInputStream(part);
            InputStream in = inflater == null ? raw : new InflaterInputStream(raw, inflater, 65536);
            OutputStream out = Files.newOutputStream(output, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        ) {
            CRC32 crc = new CRC32();
            long count = 0L;
            byte[] buffer = new byte[65536];

            int read;
            while ((read = in.read(buffer)) != -1) {
                interrupted();
                count = safeAdd(count, read);
                if (count > entry.size || count > 268435456L) {
                    throw invalid("Inflated member exceeds declared size");
                }

                crc.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }

            if (count != entry.size
                || crc.getValue() != entry.crc32
                || inflater != null && (!inflater.finished() || inflater.getBytesRead() != entry.compressedSize)) {
                throw invalid("ZIP member CRC or size mismatch");
            }
        } finally {
            if (inflater != null) {
                inflater.end();
            }
        }
    }

    private RemoteZip.Range range(long start, int length, RemoteZip.Index expected) throws IOException {
        interrupted();
        if (start >= 0L && length >= 1 && length <= 16777216) {
            long end = safeAdd(start, length - 1L);
            Builder builder = HttpRequest.newBuilder(this.uri)
                .timeout(Duration.ofSeconds(60L))
                .header("Range", "bytes=" + start + "-" + end)
                .header("Accept-Encoding", "identity");
            if (expected != null && expected.etag != null && !expected.etag.startsWith("W/")) {
                builder.header("If-Range", expected.etag);
            } else if (expected != null && expected.lastModified != null) {
                builder.header("If-Range", expected.lastModified);
            }

            HttpResponse<InputStream> response;
            try {
                response = this.http.send(builder.GET().build(), BodyHandlers.ofInputStream());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Download cancelled");
            }

            try (InputStream input = BulkTransfers.wrap(response.body())) {
                if (response.statusCode() != 206) {
                    throw invalid("Server does not support requested byte ranges (HTTP " + response.statusCode() + ")");
                }

                Matcher match = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
                if (!match.matches()) {
                    throw invalid("Missing Content-Range");
                }

                long actualStart;
                long actualEnd;
                long total;
                try {
                    actualStart = Long.parseLong(match.group(1));
                    actualEnd = Long.parseLong(match.group(2));
                    total = Long.parseLong(match.group(3));
                } catch (NumberFormatException ex) {
                    throw invalid("Invalid Content-Range");
                }

                if (actualStart != start || actualEnd != end || total <= end) {
                    throw invalid("Unexpected Content-Range");
                }

                String etag = response.headers().firstValue("ETag").orElse(null);
                String modified = response.headers().firstValue("Last-Modified").orElse(null);
                if (response.headers().firstValue("Content-Encoding").filter(v -> !v.equalsIgnoreCase("identity")).isPresent()) {
                    throw invalid("Encoded byte ranges are unsupported");
                }

                if (expected != null && (total != expected.archiveSize || !same(expected.etag, etag) || !same(expected.lastModified, modified))) {
                    throw invalid("Remote archive changed; refresh its directory");
                }

                long declared = response.headers().firstValueAsLong("Content-Length").orElse(length);
                if (declared != length) {
                    throw invalid("Unexpected range length");
                }

                FutureTask<byte[]> task = new FutureTask<>(() -> {
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length == length && input.read() == -1) {
                        return bytes;
                    } else {
                        throw invalid("Truncated or oversized range response");
                    }
                });
                Thread worker = Thread.ofVirtual().name("wordcraft-http-body").start(task);

                try {
                    return new RemoteZip.Range(task.get(60L, TimeUnit.SECONDS), total, etag, modified);
                } catch (InterruptedException ex) {
                    task.cancel(true);
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Download cancelled");
                } catch (TimeoutException ex) {
                    task.cancel(true);
                    throw new SocketTimeoutException("Range download timed out");
                } catch (ExecutionException ex) {
                    if (ex.getCause() instanceof IOException ioe) {
                        throw ioe;
                    } else {
                        throw new IOException("Cannot read range", ex.getCause());
                    }
                } finally {
                    if (!task.isDone()) {
                        task.cancel(true);
                        worker.interrupt();
                    }
                }
            }
        } else {
            throw invalid("Invalid byte range");
        }
    }

    private static List<RemoteZip.Entry> parseDirectory(byte[] bytes, long count, long directoryOffset) throws IOException {
        List<RemoteZip.Entry> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        int position = 0;
        long number = 0L;

        while (number < count) {
            if (position + 46L <= bytes.length && u32(bytes, position) == 33639248L) {
                int nameLength = u16(bytes, position + 28);
                int extraLength = u16(bytes, position + 30);
                int commentLength = u16(bytes, position + 32);
                long next = position + 46L + nameLength + extraLength + commentLength;
                if (next <= bytes.length && nameLength != 0) {
                    int flags = u16(bytes, position + 8);
                    int method = u16(bytes, position + 10);
                    String name = decode(Arrays.copyOfRange(bytes, position + 46, position + 46 + nameLength), flags);
                    long size = u32(bytes, position + 24);
                    long compressed = u32(bytes, position + 20);
                    long offset = u32(bytes, position + 42);
                    long disk = u16(bytes, position + 34);
                    int extra = position + 46 + nameLength;
                    int extraEnd = extra + extraLength;
                    boolean found64 = false;

                    while (extra < extraEnd) {
                        if (extra + 4 > extraEnd) {
                            throw invalid("Malformed ZIP extra field");
                        }

                        int tag = u16(bytes, extra);
                        int length = u16(bytes, extra + 2);
                        int field = extra + 4;
                        if ((long)field + length > extraEnd) {
                            throw invalid("Truncated ZIP extra field");
                        }

                        if (tag == 1) {
                            if (found64) {
                                throw invalid("Duplicate ZIP64 extra field");
                            }

                            found64 = true;
                            int limit = field + length;
                            if (size == 4294967295L) {
                                require(field, 8, limit);
                                size = i64(bytes, field);
                                field += 8;
                            }

                            if (compressed == 4294967295L) {
                                require(field, 8, limit);
                                compressed = i64(bytes, field);
                                field += 8;
                            }

                            if (offset == 4294967295L) {
                                require(field, 8, limit);
                                offset = i64(bytes, field);
                                field += 8;
                            }

                            if (disk == 65535L) {
                                require(field, 4, limit);
                                disk = u32(bytes, field);
                            }
                        }

                        extra += 4 + length;
                    }

                    if (disk == 0L && (found64 || size != 4294967295L && compressed != 4294967295L && offset != 4294967295L)) {
                        RemoteZip.Entry entry = new RemoteZip.Entry(
                            name, compressed, size, u32(bytes, position + 16), method, flags, offset, u32(bytes, position + 38)
                        );
                        validateEntry(entry);
                        if (safeAdd(offset, safeAdd(30L, compressed)) <= directoryOffset && names.add(name)) {
                            result.add(entry);
                            position = (int)next;
                            number++;
                            continue;
                        }

                        throw invalid("Invalid or duplicate ZIP member");
                    }

                    throw invalid("Missing ZIP64 member offsets or split member");
                }

                throw invalid("Truncated ZIP directory");
            }

            throw invalid("Malformed ZIP directory");
        }

        if (position != bytes.length) {
            throw invalid("Unexpected data after ZIP directory");
        } else {
            return result;
        }
    }

    private void validateIndex(RemoteZip.Index index) throws IOException {
        if (index != null && this.uri.equals(index.uri) && index.archiveSize >= 22L && index.entries != null && index.entries.size() <= 200000) {
            Set<String> names = new HashSet<>();

            for (RemoteZip.Entry entry : index.entries) {
                validateEntry(entry);
                if (!names.add(entry.name) || safeAdd(entry.localHeaderOffset, safeAdd(30L, entry.compressedSize)) > index.archiveSize) {
                    throw invalid("Invalid cached ZIP member");
                }
            }
        } else {
            throw invalid("Invalid cached ZIP index");
        }
    }

    private static void validateEntry(RemoteZip.Entry e) throws IOException {
        if (e != null
            && e.name != null
            && !e.name.isEmpty()
            && e.name.indexOf(0) < 0
            && e.name.indexOf(92) < 0
            && !e.name.startsWith("/")
            && !e.name.matches("^[A-Za-z]:.*")
            && e.compressedSize >= 0L
            && e.size >= 0L
            && e.compressedSize <= 268435456L
            && e.size <= 268435456L
            && e.localHeaderOffset >= 0L
            && e.crc32 >= 0L
            && e.crc32 <= 4294967295L
            && (e.method == 0 || e.method == 8)
            && (e.flags & 8257) == 0
            && (e.externalAttributes >>> 16 & 61440L) != 40960L) {
            for (String segment : e.name.split("/", -1)) {
                if (segment.equals(".") || segment.equals("..")) {
                    throw invalid("Unsafe ZIP member path");
                }
            }

            if (e.method == 0 && e.size != e.compressedSize) {
                throw invalid("Invalid stored member size");
            }
        } else {
            throw invalid("Unsafe, encrypted or unsupported ZIP member");
        }
    }

    private static boolean verified(Path file, RemoteZip.Entry entry) throws IOException {
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == entry.size) {
            CRC32 crc = new CRC32();

            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];

                int n;
                while ((n = in.read(buffer)) != -1) {
                    interrupted();
                    crc.update(buffer, 0, n);
                }
            }

            return crc.getValue() == entry.crc32;
        } else {
            return false;
        }
    }

    private static void secureParents(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) {
            throw invalid("Missing target directory");
        }

        for (Path p = parent; p != null; p = p.getParent()) {
            rejectSymlink(p);
        }

        Files.createDirectories(parent);
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw invalid("Symbolic link target is forbidden");
        }
    }

    private static void publish(Path from, Path to) throws IOException {
        rejectSymlink(to);

        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Download cancelled");
        }
    }

    private static String decode(byte[] name, int flags) throws IOException {
        try {
            return ((flags & 2048) != 0 ? StandardCharsets.UTF_8 : CP437)
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(name))
                .toString();
        } catch (CharacterCodingException ex) {
            throw invalid("Invalid ZIP filename encoding");
        }
    }

    private static boolean hasIdentity(RemoteZip.Index i) {
        return i.etag != null || i.lastModified != null;
    }

    private static boolean same(String a, String b) {
        return Objects.equals(a, b);
    }

    private static String basename(String name) {
        return name.substring(name.lastIndexOf(47) + 1);
    }

    private static int u16(byte[] b, int i) {
        return b[i] & 0xFF | (b[i + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] b, int i) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
    }

    private static long i64(byte[] b, int i) throws IOException {
        long value = ByteBuffer.wrap(b, i, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
        if (value < 0L) {
            throw invalid("ZIP64 value exceeds supported size");
        } else {
            return value;
        }
    }

    private static long safeAdd(long a, long b) throws IOException {
        if (a >= 0L && b >= 0L && a <= Long.MAX_VALUE - b) {
            return a + b;
        } else {
            throw invalid("ZIP offset overflow");
        }
    }

    private static void require(int start, int length, int limit) throws IOException {
        if ((long)start + length > limit) {
            throw invalid("Truncated ZIP64 extra");
        }
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record Entry(String name, long compressedSize, long size, long crc32, int method, int flags, long localHeaderOffset, long externalAttributes) {
    }

    public record Index(URI uri, long archiveSize, String etag, String lastModified, List<RemoteZip.Entry> entries) {
        public Index {
            entries = List.copyOf(entries);
        }
    }

    public interface Progress {
        void update(long var1, long var3, String var5);
    }

    private record Range(byte[] bytes, long size, String etag, String lastModified) {
    }
}
