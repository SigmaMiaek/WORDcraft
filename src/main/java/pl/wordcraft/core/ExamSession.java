package pl.wordcraft.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import pl.wordcraft.data.Question;

public final class ExamSession {
    public static final int QUESTION_COUNT = 32;
    public static final int MAX_SCORE = 74;
    public static final int PASS_SCORE = 68;
    public static final long EXAM_NANOS = TimeUnit.MINUTES.toNanos(25L);
    private static final long READ_NANOS = TimeUnit.SECONDS.toNanos(20L);
    private static final long BASIC_ANSWER_NANOS = TimeUnit.SECONDS.toNanos(15L);
    private static final long SPECIALIST_NANOS = TimeUnit.SECONDS.toNanos(50L);
    private List<Question> questions;
    private final List<ExamSession.AnswerResult> answers = new ArrayList<>();
    private final long started;
    private long lastNow;
    private long phaseStarted;
    private long mediaWaitNanos;
    private int index;
    private int selected = -1;
    private ExamSession.Phase phase;
    private ExamSession.Result result;

    private ExamSession(List<Question> questions, long nowNanos) {
        this.questions = List.copyOf(questions);
        this.started = this.lastNow = nowNanos;
        this.beginQuestion(nowNanos);
    }

    public static ExamSession create(List<Question> bank, RandomGenerator random, long nowNanos) {
        Objects.requireNonNull(bank);
        Objects.requireNonNull(random);
        Map<String, Question> unique = new LinkedHashMap<>();

        for (Question q : bank) {
            if (q.points() < 1 || q.points() > 3 || q.correctIndex() < 0 || q.correctIndex() >= q.answers().size() || q.answers().size() != (q.basic() ? 2 : 3)
                )
             {
                throw new IllegalArgumentException("Nieprawidłowe dane pytania: " + q.id());
            }

            if (unique.putIfAbsent(q.id(), q) != null) {
                throw new IllegalArgumentException("Powtórzony identyfikator pytania: " + q.id());
            }
        }

        List<Question> basic = samplePart(unique.values(), true, new int[]{4, 6, 10}, random);
        List<Question> specialist = samplePart(unique.values(), false, new int[]{2, 4, 6}, random);
        basic.addAll(specialist);
        return new ExamSession(basic, nowNanos);
    }

    private static List<Question> samplePart(Collection<Question> bank, boolean basic, int[] quotas, RandomGenerator random) {
        List<Question> result = new ArrayList<>();

        for (int points = 1; points <= 3; points++) {
            List<Question> pool = new ArrayList<>();

            for (Question q : bank) {
                if (q.basic() == basic && q.points() == points) {
                    pool.add(q);
                }
            }

            if (pool.size() < quotas[points - 1]) {
                throw new IllegalArgumentException(
                    "Baza nie wystarcza do egzaminu: "
                        + (basic ? "podstawowe" : "specjalistyczne")
                        + ", "
                        + points
                        + " pkt: "
                        + pool.size()
                        + "/"
                        + quotas[points - 1]
                );
            }

            shuffle(pool, random);
            result.addAll(pool.subList(0, quotas[points - 1]));
        }

        shuffle(result, random);
        return result;
    }

    private static void shuffle(List<Question> items, RandomGenerator random) {
        for (int i = items.size() - 1; i > 0; i--) {
            Collections.swap(items, i, random.nextInt(i + 1));
        }
    }

    public Question currentQuestion() {
        if (this.phase == ExamSession.Phase.FINISHED) {
            throw new IllegalStateException("Egzamin zakończony");
        } else {
            return this.questions.get(this.index);
        }
    }

    public ExamSession.Phase phase() {
        return this.phase;
    }

    public List<Question> questions() {
        return this.questions;
    }

    public ExamSession freshCopy(long nowNanos) {
        return new ExamSession(this.questions, nowNanos);
    }

    public int questionIndex() {
        return this.index;
    }

    public int selectedAnswer() {
        return this.selected;
    }

    public Optional<ExamSession.Result> result() {
        return Optional.ofNullable(this.result);
    }

    public void tick(long nowNanos) {
        long before = this.lastNow;
        long now = this.monotonic(nowNanos);
        if (this.phase != ExamSession.Phase.FINISHED) {
            if (this.phase == ExamSession.Phase.PREPARING) {
                this.mediaWaitNanos += now - before;
            } else if (now - this.started - this.mediaWaitNanos >= EXAM_NANOS) {
                this.finish(now, false);
            } else {
                if (this.phase == ExamSession.Phase.READING && now - this.phaseStarted >= READ_NANOS) {
                    long boundary = this.phaseStarted + READ_NANOS;
                    this.phase = isVideo(this.currentQuestion()) ? ExamSession.Phase.READY_TO_PLAY : ExamSession.Phase.ANSWERING;
                    this.phaseStarted = boundary;
                }

                if (this.phase == ExamSession.Phase.ANSWERING && now - this.phaseStarted >= this.answerDuration()) {
                    this.commitAnswer();
                    this.nextQuestion(now);
                }
            }
        }
    }

    public boolean markMediaReady(long nowNanos) {
        this.tick(nowNanos);
        if (this.phase != ExamSession.Phase.PREPARING) {
            return false;
        }

        this.phase = this.currentQuestion().basic() ? ExamSession.Phase.READING : ExamSession.Phase.ANSWERING;
        this.phaseStarted = this.lastNow;
        return true;
    }

    public boolean startAnswering(long nowNanos) {
        this.tick(nowNanos);
        if (this.phase == ExamSession.Phase.READING && !isVideo(this.currentQuestion())) {
            this.phase = ExamSession.Phase.ANSWERING;
            this.phaseStarted = this.lastNow;
            return true;
        } else {
            return false;
        }
    }

    public boolean startMedia(long nowNanos) {
        this.tick(nowNanos);
        if ((this.phase == ExamSession.Phase.READY_TO_PLAY || this.phase == ExamSession.Phase.READING) && isVideo(this.currentQuestion())) {
            this.phase = ExamSession.Phase.PLAYING;
            this.phaseStarted = this.lastNow;
            return true;
        } else {
            return false;
        }
    }

    public boolean mediaEnded(long nowNanos) {
        this.tick(nowNanos);
        if (this.phase != ExamSession.Phase.PLAYING) {
            return false;
        }

        this.phase = ExamSession.Phase.ANSWERING;
        this.phaseStarted = this.lastNow;
        return true;
    }

    public boolean selectAnswer(int answerIndex, long nowNanos) {
        int before = this.index;
        this.tick(nowNanos);
        if (this.index != before || !this.canAnswer()) {
            return false;
        } else if (answerIndex >= 0 && answerIndex < this.currentQuestion().answers().size()) {
            this.selected = answerIndex;
            return true;
        } else {
            return false;
        }
    }

    public boolean submit(long nowNanos) {
        int before = this.index;
        this.tick(nowNanos);
        if (this.index == before && this.canAnswer()) {
            this.commitAnswer();
            this.nextQuestion(this.lastNow);
            return true;
        } else {
            return false;
        }
    }

    public void abort(long nowNanos) {
        long before = this.lastNow;
        long now = this.monotonic(nowNanos);
        if (this.phase == ExamSession.Phase.PREPARING) {
            this.mediaWaitNanos += now - before;
        }

        if (this.phase != ExamSession.Phase.FINISHED) {
            this.finish(now, true);
        }
    }

    public long remainingPhaseMillis(long nowNanos) {
        long now = Math.max(this.lastNow, nowNanos);
        if (this.phase == ExamSession.Phase.READING) {
            return millisCeiling(READ_NANOS - (now - this.phaseStarted));
        } else {
            return this.phase == ExamSession.Phase.ANSWERING ? millisCeiling(this.answerDuration() - (now - this.phaseStarted)) : 0L;
        }
    }

    public long remainingExamMillis(long nowNanos) {
        long now = Math.max(this.lastNow, nowNanos);
        long waiting = this.mediaWaitNanos + (this.phase == ExamSession.Phase.PREPARING ? now - this.lastNow : 0L);
        return this.phase == ExamSession.Phase.FINISHED ? 0L : millisCeiling(EXAM_NANOS - (now - this.started - waiting));
    }

    private void beginQuestion(long now) {
        this.selected = -1;
        this.phaseStarted = now;
        String media = this.questions.get(this.index).media();
        this.phase = media != null && !media.isBlank()
            ? ExamSession.Phase.PREPARING
            : (this.questions.get(this.index).basic() ? ExamSession.Phase.READING : ExamSession.Phase.ANSWERING);
    }

    private void commitAnswer() {
        Question q = this.questions.get(this.index);
        boolean correct = this.selected == q.correctIndex();
        this.answers.add(new ExamSession.AnswerResult(q.id(), this.selected, q.correctIndex(), correct ? q.points() : 0, correct, true));
    }

    private void nextQuestion(long now) {
        this.index++;
        if (this.index == this.questions.size()) {
            this.finish(now, false);
        } else {
            this.beginQuestion(now);
        }
    }

    private void finish(long now, boolean aborted) {
        if (!aborted && this.answers.size() <= this.index && this.index < this.questions.size()) {
            this.commitAnswer();
        }

        for (int i = this.answers.size(); i < this.questions.size(); i++) {
            Question q = this.questions.get(i);
            this.answers.add(new ExamSession.AnswerResult(q.id(), -1, q.correctIndex(), 0, false, false));
        }

        int score = this.answers.stream().mapToInt(ExamSession.AnswerResult::pointsAwarded).sum();
        this.result = new ExamSession.Result(
            score, 74, !aborted && score >= 68, aborted, TimeUnit.NANOSECONDS.toMillis(Math.max(0L, now - this.started - this.mediaWaitNanos)), this.answers
        );
        this.phase = ExamSession.Phase.FINISHED;
        this.index = this.questions.size();
        this.selected = -1;
    }

    private long answerDuration() {
        return this.currentQuestion().basic() ? BASIC_ANSWER_NANOS : SPECIALIST_NANOS;
    }

    private long monotonic(long now) {
        this.lastNow = Math.max(this.lastNow, now);
        return this.lastNow;
    }

    private static long millisCeiling(long nanos) {
        return nanos <= 0L ? 0L : (nanos + 999999L) / 1000000L;
    }

    public static boolean isVideo(Question q) {
        String name = q.media() == null ? "" : q.media().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(mp4|wmv|avi|mov|m4v|webm|mpeg|mpg|mkv)$");
    }

    public boolean canAnswer() {
        return this.phase == ExamSession.Phase.ANSWERING
            || (this.phase == ExamSession.Phase.READING || this.phase == ExamSession.Phase.READY_TO_PLAY || this.phase == ExamSession.Phase.PLAYING)
                && isVideo(this.currentQuestion());
    }

    public boolean replaceCurrentQuestion(Question var1, long var2) {
        this.tick(var2);
        if (this.phase != ExamSession.Phase.FINISHED && var1 != null) {
            Question var4 = this.currentQuestion();
            if (var1.basic() == var4.basic() && var1.points() == var4.points() && !var1.id().equals(var4.id())) {
                for (Question var5 : this.questions) {
                    if (var5.id().equals(var1.id())) {
                        return false;
                    }
                }

                ArrayList<Question> var7 = new ArrayList<>(this.questions);
                var7.set(this.index, var1);
                this.questions = List.copyOf(var7);
                this.beginQuestion(this.lastNow);
                return true;
            } else {
                return false;
            }
        } else {
            return false;
        }
    }

    public record AnswerResult(String questionId, int selectedIndex, int correctIndex, int pointsAwarded, boolean correct, boolean presented) {
    }

    public enum Phase {
        PREPARING,
        READING,
        READY_TO_PLAY,
        PLAYING,
        ANSWERING,
        FINISHED;
    }

    public record Result(int score, int maxScore, boolean passed, boolean aborted, long elapsedMillis, List<ExamSession.AnswerResult> answers) {
        public Result {
            answers = List.copyOf(answers);
        }
    }
}
