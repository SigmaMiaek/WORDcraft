package pl.wordcraft.core;

import java.util.Locale;

public final class DownloadFormat {
    private static final Locale PL = Locale.forLanguageTag("pl-PL");
    private DownloadFormat() {}
    public static String bytes(long value) {
        if (value >= 1_000_000_000L) return String.format(PL, "%.2f GB", value / 1_000_000_000.0);
        if (value >= 1_000_000L) return String.format(PL, "%.1f MB", value / 1_000_000.0);
        if (value >= 1000L) return String.format(PL, "%.0f kB", value / 1000.0);
        return Math.max(0, value) + " B";
    }
    public static String speed(double value) {
        if (!Double.isFinite(value) || value <= 0) return "— MB/s";
        if (value >= 1_000_000) return String.format(PL, "%.1f MB/s", value / 1_000_000);
        return String.format(PL, "%.0f kB/s", value / 1000);
    }
    public static String eta(long seconds) {
        if (seconds < 0) return "obliczanie…";
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
