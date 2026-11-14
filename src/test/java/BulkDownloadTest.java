import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import pl.wordcraft.core.*;

public final class BulkDownloadTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        check(condition.getAsBoolean(), "Timed out waiting for test state");
    }
    private static MediaAsset asset(Path dir, String name, int bytes) {
        return new MediaAsset(name, dir.resolve(name), bytes, bytes);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wordcraft-bulk-test-");
        try {
            MediaAsset first = asset(root, "first.wmv", 1_000_000);
            MediaAsset cached = asset(root, "cached.jpg", 20);
            Files.write(cached.target(), new byte[20]);
            CompletableFuture<Path> pending = new CompletableFuture<>();
            AtomicInteger calls = new AtomicInteger();
            try (BulkDownloader job = new BulkDownloader(item -> {
                calls.incrementAndGet();
                return item.equals(first) ? pending : CompletableFuture.completedFuture(item.target());
            })) {
                BulkTransfers.attach(job);
                long start = System.nanoTime();
                check(job.start(List.of(first, cached, first)), "Start failed");
                check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1), "Start blocked the caller");
                waitFor(() -> calls.get() == 1);
                check(!job.start(List.of(first)), "Duplicate download job started");
                try (BulkTransfers.Scope scope = BulkTransfers.scope(first.name());
                     InputStream input = BulkTransfers.wrap(new ByteArrayInputStream(new byte[200_000]))) {
                    input.readNBytes(100_000);
                    Thread.sleep(300);
                    input.readNBytes(100_000);
                }
                var snapshot = job.snapshot();
                check(snapshot.total() == 2 && snapshot.receivedBytes() == 200_000, "Counting cached/duplicate files or actual bytes incorrectly");
                check(snapshot.bytesPerSecond() > 0 && snapshot.etaSeconds() > 0, "Speed/ETA unavailable during transfer");
                check(snapshot.remainingBytes() == 800_000, "Cached file included in network estimate");
                check(snapshot.fraction() > 0 && snapshot.fraction() < 1, "Byte-weighted progress is not advancing");
                Files.write(first.target(), new byte[1_000_000]);
                pending.complete(first.target());
                waitFor(() -> job.snapshot().state() == BulkDownloader.State.COMPLETE);
                check(calls.get() == 2 && job.snapshot().ready() == 2 && job.snapshot().totalDiskBytes() == 1_000_020,
                        "Final counters or total disk size incorrect");
                check(job.snapshot().fraction() == 1 && job.snapshot().etaSeconds() == 0, "Completion state incorrect");
                System.out.println("PASS: nonblocking start, sequential/deduplicated files, cached bytes, live speed, ETA and completion");
            }
            CompletableFuture<Path> shared = new CompletableFuture<>();
            AtomicInteger starts = new AtomicInteger();
            try (BulkDownloader job = new BulkDownloader(item -> { starts.incrementAndGet(); return shared; })) {
                job.start(List.of(first, cached));
                waitFor(() -> starts.get() == 1);
                job.pause();
                check(!shared.isCancelled() && job.snapshot().state() == BulkDownloader.State.PAUSING,
                        "Pause cancelled the shared quiz request");
                shared.complete(first.target());
                waitFor(() -> job.snapshot().state() == BulkDownloader.State.PAUSED);
                check(starts.get() == 1, "Pause started another file");
                check(job.start(List.of(cached)), "Resume failed");
                // Shared completion intentionally has a different file size; must be treated as an error.
                waitFor(() -> !job.snapshot().state().busy());
                System.out.println("PASS: pause finishes the active shared request and prevents another download");
            }
            AtomicInteger failures = new AtomicInteger();
            try (BulkDownloader job = new BulkDownloader(item -> {
                failures.incrementAndGet();
                return CompletableFuture.failedFuture(new IOException("simulated outage"));
            })) {
                job.start(List.of(asset(root,"a",1),asset(root,"b",1),asset(root,"c",1),asset(root,"d",1)));
                waitFor(() -> job.snapshot().state() == BulkDownloader.State.PAUSED);
                check(failures.get() == 3 && job.snapshot().failures() == 3, "Outage did not auto-pause after 3 failures");
                check(job.snapshot().ready() == 0, "Failed files counted as downloaded");
                System.out.println("PASS: repeated failures auto-pause without spinning through the entire catalogue");
            }
            CompletableFuture<Path> shutdownRequest = new CompletableFuture<>();
            BulkDownloader closing = new BulkDownloader(item -> shutdownRequest);
            closing.start(List.of(first));
            waitFor(() -> closing.snapshot().state() == BulkDownloader.State.RUNNING);
            closing.close();
            check(closing.snapshot().state() == BulkDownloader.State.CLOSED && !shutdownRequest.isCancelled(), "Shutdown damaged a shared request");
            check(!closing.start(List.of(first)), "Closed manager restarted");
            check(DownloadFormat.eta(7812).equals("2:10:12"), "ETA format incorrect");
            check(DownloadFormat.speed(1_200_000).equals("1,2 MB/s"), "Speed format/units incorrect");
            check(DownloadFormat.bytes(1_230_000_000).equals("1,23 GB"), "Disk size format incorrect");
            check(DownloadFormat.eta(-1).equals("obliczanie…"), "Unknown ETA is fabricated");
            System.out.println("PASS: shutdown, exact ETA/speed/disk units and unknown-rate handling");
            System.out.println("ALL BULK DOWNLOADER TESTS PASSED");
        } finally {
            BulkTransfers.attach(null);
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
