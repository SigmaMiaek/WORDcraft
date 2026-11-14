package pl.wordcraft.core;

import java.util.List;

public final class WordcraftConfig {
    public static final List<Integer> INTERVALS = List.of(5, 10, 15, 20, 30);
    public boolean enabled = true;
    public int intervalMinutes = 15;
    public WordcraftConfig.Mode mode = WordcraftConfig.Mode.FREE;
    public boolean suppressCombat = true;

    public void normalize() {
        if (!INTERVALS.contains(this.intervalMinutes)) {
            this.intervalMinutes = 15;
        }

        if (this.mode == null) {
            this.mode = WordcraftConfig.Mode.FREE;
        }
    }

    public void cycleInterval() {
        this.normalize();
        this.intervalMinutes = INTERVALS.get((INTERVALS.indexOf(this.intervalMinutes) + 1) % INTERVALS.size());
    }

    public enum Mode {
        FREE,
        EXAM;
    }
}
