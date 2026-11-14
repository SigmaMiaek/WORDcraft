package pl.wordcraft.core;

/** UI-only sampling. The downloader and pause/start controls always retain live state. */
public final class DownloadDisplay {
    private static final long REFRESH_NANOS = 1_000_000_000L;
    private static final double SMOOTHING_SECONDS = 5.0;
    private BulkDownloader.Snapshot displayed;
    private long refreshedAt;
    private double smoothedSpeed;

    public synchronized BulkDownloader.Snapshot sample(BulkDownloader.Snapshot current, long now) {
        boolean changed = displayed == null || current.state() != displayed.state()
                || current.changedAtNanos() != displayed.changedAtNanos();
        if (!changed && now - refreshedAt < REFRESH_NANOS) return displayed;

        double speed = Math.max(0, current.bytesPerSecond());
        if (changed || smoothedSpeed <= 0 || speed <= 0 || !current.state().busy()) {
            smoothedSpeed = speed;
        } else {
            double elapsed = Math.max(0, now - refreshedAt) / 1_000_000_000.0;
            double alpha = 1 - Math.exp(-elapsed / SMOOTHING_SECONDS);
            smoothedSpeed += alpha * (speed - smoothedSpeed);
        }
        long eta = current.etaSeconds();
        if (eta >= 0 && current.state().busy() && smoothedSpeed > 0 && current.remainingBytes() > 0) {
            eta = (long)Math.ceil(current.remainingBytes() / smoothedSpeed);
        }
        displayed = new BulkDownloader.Snapshot(current.state(), current.ready(), current.total(), current.failures(),
                current.readyDiskBytes(), current.totalDiskBytes(), current.receivedBytes(), current.remainingBytes(),
                smoothedSpeed, eta, current.fraction(), current.message(), current.changedAtNanos());
        refreshedAt = now;
        return displayed;
    }
}
