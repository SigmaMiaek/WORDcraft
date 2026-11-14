package pl.wordcraft.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Function;

/** Sequential coordinator; the shared media loader retains priority for queued quiz requests. */
public final class BulkDownloader implements AutoCloseable {
    public enum State {
        IDLE, SCANNING, RUNNING, PAUSING, PAUSED, COMPLETE, PARTIAL, CLOSED;
        public boolean busy() { return this == SCANNING || this == RUNNING || this == PAUSING; }
    }
    public record Snapshot(State state, int ready, int total, int failures,
            long readyDiskBytes, long totalDiskBytes, long receivedBytes, long remainingBytes,
            double bytesPerSecond, long etaSeconds, double fraction, String message, long changedAtNanos) {}
    private record Sample(long time, long bytes) {}
    private final Function<MediaAsset, CompletableFuture<Path>> loader;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WORDcraft-bulk-media");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private State state = State.IDLE;
    private Job job;
    private boolean pauseRequested;
    private String message = "";
    private long changedAt = System.nanoTime();
    private static final class Job {
        final List<MediaAsset> assets;
        final Map<String, Long> estimates = new LinkedHashMap<>();
        final ArrayDeque<Sample> samples = new ArrayDeque<>();
        long diskTotal, diskReady, remaining, received, currentReceived, lastRead;
        int ready, failures, consecutiveFailures;
        MediaAsset current;
        Job(List<MediaAsset> assets) {
            this.assets = assets;
            for (MediaAsset asset : assets) diskTotal = Math.addExact(diskTotal, asset.diskBytes());
            samples.add(new Sample(System.nanoTime(), 0));
        }
    }
    public BulkDownloader(Function<MediaAsset, CompletableFuture<Path>> loader) {
        this.loader = Objects.requireNonNull(loader);
    }
    public synchronized boolean start(List<MediaAsset> assets) {
        if (state.busy() || state == State.CLOSED) return false;
        Map<Path, MediaAsset> unique = new LinkedHashMap<>();
        for (MediaAsset asset : assets) unique.putIfAbsent(asset.target(), asset);
        if (unique.isEmpty()) return false;
        Job next = new Job(List.copyOf(unique.values()));
        job = next;
        pauseRequested = false;
        change(State.SCANNING, "Sprawdzanie plików na dysku…");
        worker.execute(() -> run(next));
        return true;
    }
    public synchronized void pause() {
        if (state.busy()) {
            pauseRequested = true;
            change(State.PAUSING, "Kończenie bieżącego pliku…");
        }
    }
    private void run(Job current) {
        try {
            long missingDisk = 0, temporary = 0;
            for (MediaAsset asset : current.assets) {
                boolean local = Files.isRegularFile(asset.target()) && Files.size(asset.target()) == asset.diskBytes();
                synchronized (this) {
                    if (state == State.CLOSED || job != current) return;
                    long estimate = local ? 0 : asset.downloadBytes();
                    current.estimates.put(asset.name(), estimate);
                    current.remaining += estimate;
                    if (!local) missingDisk = Math.addExact(missingDisk, asset.diskBytes());
                    temporary = Math.max(temporary, asset.diskBytes() + asset.downloadBytes());
                }
            }
            Path parent = current.assets.getFirst().target().getParent();
            if (Files.isDirectory(parent) && Files.getFileStore(parent).getUsableSpace() < missingDisk + temporary) {
                synchronized (this) {
                    if (state != State.CLOSED && job == current) change(State.PAUSED, "Za mało wolnego miejsca na materiały i pliki tymczasowe.");
                }
                return;
            }
            synchronized (this) {
                if (state == State.CLOSED || job != current) return;
                if (pauseRequested) { change(State.PAUSED, "Pobieranie wstrzymane."); return; }
                current.samples.clear();
                current.samples.add(new Sample(System.nanoTime(), 0));
                change(State.RUNNING, "Pobieranie w tle. Możesz grać.");
            }
            for (MediaAsset asset : current.assets) {
                synchronized (this) {
                    if (state == State.CLOSED || job != current) return;
                    if (pauseRequested) { change(State.PAUSED, "Pobieranie wstrzymane. Pobrane pliki pozostają na dysku."); return; }
                    current.current = asset;
                    current.currentReceived = 0;
                }
                boolean success = false;
                try {
                    Path result = loader.apply(asset).get();
                    if (result == null || !Files.isRegularFile(result) || Files.size(result) != asset.diskBytes()) {
                        throw new IOException("Downloaded media size mismatch");
                    }
                    success = true;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    synchronized (this) {
                        if (state != State.CLOSED) change(State.PAUSED, "Pobieranie wstrzymane.");
                    }
                    return;
                } catch (ExecutionException | IOException | RuntimeException failure) {
                    // Per-file failures are counted; a short outage must not retry thousands of files.
                }
                synchronized (this) {
                    if (state == State.CLOSED || job != current) return;
                    if (success) {
                        current.ready++;
                        current.diskReady += asset.diskBytes();
                        current.remaining = Math.max(0, current.remaining - current.estimates.getOrDefault(asset.name(), 0L));
                        current.consecutiveFailures = 0;
                    } else {
                        current.failures++;
                        if (++current.consecutiveFailures >= 3) {
                            pauseRequested = true;
                            message = "Wstrzymano po 3 kolejnych błędach pobierania. Możesz wznowić pobieranie.";
                        }
                    }
                    current.current = null;
                    current.currentReceived = 0;
                    if (pauseRequested) {
                        change(State.PAUSED, current.consecutiveFailures >= 3 ? message : "Pobieranie wstrzymane. Pobrane pliki pozostają na dysku.");
                        return;
                    }
                }
            }
            synchronized (this) {
                if (state != State.CLOSED && job == current) change(current.failures == 0 ? State.COMPLETE : State.PARTIAL,
                        current.failures == 0 ? "Wszystkie materiały gotowe." : "Niektórych plików nie pobrano. Uruchom ponownie, aby spróbować ponownie.");
            }
        } catch (IOException | RuntimeException failure) {
            synchronized (this) {
                if (state != State.CLOSED && job == current) change(State.PAUSED, "Nie można odczytać folderu materiałów. Pobieranie wstrzymane.");
            }
        }
    }
    public synchronized void recordBytes(String name, int count) {
        Job current = job;
        if (count <= 0 || current == null || current.current == null || !state.busy()
                || !current.current.name().equals(name)) return;
        if (current.estimates.getOrDefault(name, 0L) == 0) {
            // A same-size cached file can still fail CRC and require repair.
            current.estimates.put(name, current.current.downloadBytes());
            current.remaining += current.current.downloadBytes();
        }
        long now = System.nanoTime();
        current.received += count;
        current.currentReceived += count;
        current.lastRead = now;
        if (now - current.samples.getLast().time() >= 250_000_000L) current.samples.add(new Sample(now, current.received));
        while (current.samples.size() > 1 && now - current.samples.getFirst().time() > 10_000_000_000L) current.samples.removeFirst();
    }
    public synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (job == null) return new Snapshot(state, 0, 0, 0, 0, 0, 0, 0, 0, -1, 0, message, changedAt);
        Job current = job;
        double rate = 0;
        if (state.busy() && current.lastRead > 0 && now - current.lastRead < 5_000_000_000L) {
            Sample baseline = current.samples.getFirst();
            double elapsed = (now - baseline.time()) / 1_000_000_000.0;
            if (elapsed >= 0.25) rate = (current.received - baseline.bytes()) / elapsed;
        }
        long remaining = Math.max(0, current.remaining - current.currentReceived);
        long eta = state == State.COMPLETE ? 0 : current.failures == 0 && rate > 0 && remaining > 0 ? (long)Math.ceil(remaining / rate) : -1;
        double progressBytes = current.diskReady;
        if (current.current != null && current.current.downloadBytes() > 0) {
            progressBytes += current.current.diskBytes() * Math.min(1, (double)current.currentReceived / current.current.downloadBytes());
        }
        double fraction = current.diskTotal > 0 ? progressBytes / current.diskTotal : current.assets.isEmpty() ? 0 : (double)current.ready / current.assets.size();
        return new Snapshot(state, current.ready, current.assets.size(), current.failures, current.diskReady,
                current.diskTotal, current.received, remaining, rate, eta, Math.max(0, Math.min(1, fraction)), message, changedAt);
    }
    private void change(State next, String text) {
        state = next;
        message = text;
        changedAt = System.nanoTime();
    }
    @Override public synchronized void close() {
        pauseRequested = true;
        change(State.CLOSED, "");
        worker.shutdownNow();
    }
}
