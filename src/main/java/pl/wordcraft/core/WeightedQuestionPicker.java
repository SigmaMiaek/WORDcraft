package pl.wordcraft.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.random.RandomGenerator;
import pl.wordcraft.data.Question;

public final class WeightedQuestionPicker {
    private static final double ERROR_SHARE = 0.7;
    private static final int ERROR_RECENT_LIMIT = 2;
    private final int recentLimit;
    private final Deque<String> recent = new ArrayDeque<>();

    public WeightedQuestionPicker() {
        this(30);
    }

    public WeightedQuestionPicker(int recentLimit) {
        if (recentLimit < 0) {
            throw new IllegalArgumentException("recentLimit < 0");
        }

        this.recentLimit = recentLimit;
    }

    public Optional<Question> pick(List<Question> bank, LearningStats stats, RandomGenerator random) {
        Objects.requireNonNull(random);
        LinkedHashMap<String, Question> unique = new LinkedHashMap<>();

        for (Question question : bank) {
            unique.putIfAbsent(question.id(), question);
        }

        if (unique.isEmpty()) {
            return Optional.empty();
        }

        Set<String> recentErrors = this.mostRecent(Math.min(this.recentLimit, 2));
        Set<String> recentNormal = new HashSet<>(this.recent);
        List<Question> errors = new ArrayList<>();
        List<Question> normal = new ArrayList<>();

        for (Question question : unique.values()) {
            if (stats.needsErrorReview(question.id())) {
                if (!recentErrors.contains(question.id())) {
                    errors.add(question);
                }
            } else if (!recentNormal.contains(question.id())) {
                normal.add(question);
            }
        }

        List<Question> candidates;
        if (!errors.isEmpty() && !normal.isEmpty()) {
            candidates = random.nextDouble() < 0.7 ? errors : normal;
        } else if (!errors.isEmpty()) {
            candidates = errors;
        } else if (!normal.isEmpty()) {
            candidates = normal;
        } else {
            String previous = this.recent.peekLast();
            List<Question> fallback = unique.values().stream().filter(qx -> unique.size() == 1 || !qx.id().equals(previous)).toList();
            List<Question> fallbackNormal = fallback.stream().filter(qx -> !stats.needsErrorReview(qx.id())).toList();
            candidates = fallbackNormal.isEmpty() ? fallback : fallbackNormal;
        }

        double total = 0.0;

        for (Question q : candidates) {
            total += weight(q.id(), stats);
        }

        double needle = random.nextDouble(total);
        Question selected = candidates.getLast();

        for (Question q : candidates) {
            needle -= weight(q.id(), stats);
            if (needle < 0.0) {
                selected = q;
                break;
            }
        }

        if (this.recentLimit > 0) {
            this.recent.addLast(selected.id());

            while (this.recent.size() > this.recentLimit) {
                this.recent.removeFirst();
            }
        }

        return Optional.of(selected);
    }

    public List<String> recentIds() {
        return List.copyOf(this.recent);
    }

    private Set<String> mostRecent(int count) {
        Set<String> result = new HashSet<>();
        Iterator<String> iterator = this.recent.descendingIterator();

        for (int i = 0; i < count && iterator.hasNext(); i++) {
            result.add(iterator.next());
        }

        return result;
    }

    private static double weight(String id, LearningStats stats) {
        LearningStats.QuestionProgress p = stats.questionStats(id);
        double urgent = stats.needsErrorReview(id) ? 3 - p.consecutiveCorrect() : 0.0;
        return 1.0 + urgent + (stats.isManuallyMarkedForReview(id) ? 3 : 0);
    }
}
