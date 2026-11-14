import pl.wordcraft.core.*;

/** Deterministic timing tests: no sleeps, rendering loop, network or filesystem needed. */
public final class DownloadDisplayTest {
    private static BulkDownloader.Snapshot value(BulkDownloader.State state, int ready,
            double speed, long remaining, long eta, long change) {
        return new BulkDownloader.Snapshot(state, ready, 100, 0, ready * 100L, 10000,
                ready * 100L, remaining, speed, eta, ready / 100.0, "status", change);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        DownloadDisplay display = new DownloadDisplay();
        var first = value(BulkDownloader.State.RUNNING, 1, 1_000_000, 9_000_000, 9, 10);
        var shown = display.sample(first, 0);
        for (long now = 10_000_000; now < 1_000_000_000L; now += 10_000_000) {
            var incoming = value(BulkDownloader.State.RUNNING, 50, 50_000_000, 1_000_000, 1, 10);
            check(display.sample(incoming, now) == shown, "Display changed more than once per second");
        }
        var next = display.sample(value(BulkDownloader.State.RUNNING, 5, 9_000_000, 4_000_000, 1, 10), 1_000_000_000L);
        check(next.ready() == 5 && next.fraction() == 0.05, "Count/bar did not update at one second");
        check(next.bytesPerSecond() > 1_000_000 && next.bytesPerSecond() < 3_000_000, "Speed jumped instead of being smoothed");
        check(next.etaSeconds() == (long)Math.ceil(next.remainingBytes() / next.bytesPerSecond()), "ETA does not use smoothed speed");
        check(display.sample(first, 1_100_000_000L) == next, "Another view bypassed the display cadence");
        System.out.println("PASS: frame-rate calls are sampled at 1 Hz; count/bar stay stable and speed/ETA are smoothed");

        var paused = display.sample(value(BulkDownloader.State.PAUSED, 5, 0, 4_000_000, -1, 20), 1_150_000_000L);
        check(paused.state() == BulkDownloader.State.PAUSED && paused.bytesPerSecond() == 0 && paused.etaSeconds() == -1,
                "Pause/control transition was delayed or stale");
        var restarted = display.sample(value(BulkDownloader.State.RUNNING, 0, 0, 10_000_000, -1, 30), 1_200_000_000L);
        check(restarted.ready() == 0 && restarted.bytesPerSecond() == 0, "New job inherited previous progress or rate");
        var completed = display.sample(value(BulkDownloader.State.COMPLETE, 100, 0, 0, 0, 40), 1_250_000_000L);
        check(completed.ready() == 100 && completed.fraction() == 1 && completed.etaSeconds() == 0, "Completion did not display immediately");
        System.out.println("PASS: pause, restart and completion remain immediate without stale statistics");

        DownloadDisplay other = new DownloadDisplay();
        other.sample(value(BulkDownloader.State.RUNNING, 2, 3_000_000, 7_000_000, 3, 50), 0);
        var idleNetwork = other.sample(value(BulkDownloader.State.RUNNING, 2, 0, 7_000_000, -1, 50), 1_000_000_000L);
        check(idleNetwork.bytesPerSecond() == 0 && idleNetwork.etaSeconds() == -1, "Stalled transfer displays invented activity");
        check(DownloadFormat.speed(1_200_000).equals("1,2 MB/s") && DownloadFormat.eta(7812).equals("2:10:12"), "Formatting changed");
        System.out.println("PASS: stalled transfer retains honest unknown ETA and existing units");
        System.out.println("ALL DOWNLOAD DISPLAY TESTS PASSED");
    }
}
