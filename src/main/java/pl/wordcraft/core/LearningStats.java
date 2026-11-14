package pl.wordcraft.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import pl.wordcraft.data.Question;

public final class LearningStats {
    private int schemaVersion = 2;
    private Map<String, LearningStats.QuestionProgress> questions = new LinkedHashMap<>();
    private Set<String> review = new LinkedHashSet<>();
    private Set<String> manualReview = new LinkedHashSet<>();
    private List<LearningStats.ErrorEntry> errors = new ArrayList<>();
    private List<LearningStats.ExamEntry> exams = new ArrayList<>();

    public synchronized void record(Question question, int selectedIndex) {
        if (selectedIndex >= -1 && selectedIndex < question.answers().size()) {
            this.record(question.id(), selectedIndex, question.correctIndex(), Instant.now().toEpochMilli());
        } else {
            throw new IllegalArgumentException("Niepoprawny indeks odpowiedzi");
        }
    }

    private void record(String id, int selected, int correct, long timestamp) {
        LearningStats.QuestionProgress old = this.questionStats(id);
        boolean ok = selected == correct;
        int streak = ok ? Math.min(3, old.consecutiveCorrect + 1) : 0;
        this.questions.put(id, new LearningStats.QuestionProgress(old.attempts + 1L, old.correct + (ok ? 1 : 0), old.wrong + (ok ? 0 : 1), streak));
        if (!ok) {
            this.review.add(id);
            this.errors.add(new LearningStats.ErrorEntry(id, selected, correct, timestamp));
            trim(this.errors, 500);
        } else if (streak == 3 && !this.manualReview.contains(id)) {
            this.review.remove(id);
        }
    }

    public synchronized void addExamResult(ExamSession.Result result) {
        long now = Instant.now().toEpochMilli();
        this.exams.add(new LearningStats.ExamEntry(now, Objects.requireNonNull(result)));
        trim(this.exams, 100);

        for (ExamSession.AnswerResult answer : result.answers()) {
            if (answer.presented()) {
                this.record(answer.questionId(), answer.selectedIndex(), answer.correctIndex(), now);
            }
        }
    }

    public synchronized long solvedCount() {
        return this.questions.values().stream().mapToLong(LearningStats.QuestionProgress::attempts).sum();
    }

    public synchronized long correctCount() {
        return this.questions.values().stream().mapToLong(LearningStats.QuestionProgress::correct).sum();
    }

    public synchronized double correctPercent() {
        return this.solvedCount() == 0L ? 0.0 : 100.0 * this.correctCount() / this.solvedCount();
    }

    public synchronized LearningStats.QuestionProgress questionStats(String id) {
        return this.questions.getOrDefault(id, new LearningStats.QuestionProgress(0L, 0L, 0L));
    }

    public synchronized Set<String> reviewIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.review));
    }

    public synchronized boolean isMarkedForReview(String id) {
        return this.review.contains(id);
    }

    public synchronized boolean needsErrorReview(String id) {
        LearningStats.QuestionProgress progress = this.questionStats(id);
        return progress.wrong > 0L && progress.consecutiveCorrect < 3;
    }

    public synchronized boolean isManuallyMarkedForReview(String id) {
        return this.manualReview.contains(id);
    }

    public synchronized List<LearningStats.ErrorEntry> errors() {
        return List.copyOf(this.errors);
    }

    public synchronized List<LearningStats.ExamEntry> examHistory() {
        return List.copyOf(this.exams);
    }

    public synchronized void toggleReview(String id) {
        this.setReview(id, !this.review.contains(id));
    }

    public synchronized void setReview(String id, boolean value) {
        if (value) {
            this.review.add(id);
            this.manualReview.add(id);
        } else {
            this.review.remove(id);
            this.manualReview.remove(id);
        }
    }

    void normalize() {
        if (this.questions == null) {
            this.questions = new LinkedHashMap<>();
        }

        if (this.review == null) {
            this.review = new LinkedHashSet<>();
        }

        if (this.manualReview == null) {
            this.manualReview = new LinkedHashSet<>();
        }

        if (this.errors == null) {
            this.errors = new ArrayList<>();
        }

        if (this.exams == null) {
            this.exams = new ArrayList<>();
        }

        this.questions
            .entrySet()
            .removeIf(
                e -> e.getKey() == null
                    || e.getValue() == null
                    || e.getValue().attempts < 0L
                    || e.getValue().correct < 0L
                    || e.getValue().wrong < 0L
                    || e.getValue().correct + e.getValue().wrong != e.getValue().attempts
            );
        this.questions
            .replaceAll(
                (id, progress) -> new LearningStats.QuestionProgress(
                    progress.attempts,
                    progress.correct,
                    progress.wrong,
                    (int)Math.min(Math.min(3L, progress.correct), Math.max(0, progress.consecutiveCorrect))
                )
            );
        this.review.remove(null);
        if (this.schemaVersion < 2) {
            this.manualReview.addAll(this.review);
        }

        this.manualReview.retainAll(this.review);
        this.manualReview.remove(null);
        this.review.removeIf(id -> !this.manualReview.contains(id) && !this.needsErrorReview(id));
        this.errors.removeIf(Objects::isNull);
        this.exams.removeIf(e -> e == null || e.result() == null);
        trim(this.errors, 500);
        trim(this.exams, 100);
        this.schemaVersion = 2;
    }

    private static <T> void trim(List<T> values, int max) {
        if (values.size() > max) {
            values.subList(0, values.size() - max).clear();
        }
    }

    public record ErrorEntry(String questionId, int selectedIndex, int correctIndex, long timestamp) {
    }

    public record ExamEntry(long timestamp, ExamSession.Result result) {
    }

    public record QuestionProgress(long attempts, long correct, long wrong, int consecutiveCorrect) {
        public QuestionProgress(long attempts, long correct, long wrong) {
            this(attempts, correct, wrong, 0);
        }
    }
}
