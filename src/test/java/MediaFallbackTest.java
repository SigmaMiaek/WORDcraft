import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import pl.wordcraft.core.*;
import pl.wordcraft.data.Question;

public final class MediaFallbackTest {
    private static final Random FIRST = new Random(0) {
        @Override public int nextInt(int bound) { return 0; }
    };

    private static Question q(int id, boolean basic, int points, String media) {
        return new Question("" + id, "Test question", basic ? List.of("Yes", "No")
                : List.of("A", "B", "C"), 0, basic, points, media, "");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ExamSession exam(Question... questions) throws Exception {
        var constructor = ExamSession.class.getDeclaredConstructor(List.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(List.of(questions), System.nanoTime());
    }

    public static void main(String[] args) throws Exception {
        Question failed = q(1, true, 3, "failed.wmv");
        Question reserved = q(2, true, 3, "");
        Question replacement = q(3, true, 3, "replacement.wmv");
        Question sameMedia = q(4, true, 3, "FAILED.WMV");
        var bank = List.of(failed, reserved, q(5, false, 3, ""), q(6, true, 1, ""), sameMedia, replacement);
        MediaFallback fallback = new MediaFallback();
        ExamSession session = exam(failed, reserved);
        var selected = fallback.choose(bank, failed, session.questions(), true, true, ignored -> null, FIRST).orElseThrow();
        check(selected.equals(replacement), "Replacement violates type, score, duplicate or failed-media constraints");
        var originalSnapshot = session.questions();
        check(session.replaceCurrentQuestion(selected, System.nanoTime()), "Exam replacement rejected");
        check(session.questionIndex() == 0 && session.questions().size() == 2, "Question count/index changed");
        check(originalSnapshot.getFirst().equals(failed), "Immutable exam snapshot was mutated");
        check(!session.replaceCurrentQuestion(q(7, false, 3, ""), System.nanoTime()), "Wrong exam type accepted");
        check(!session.replaceCurrentQuestion(q(8, true, 1, ""), System.nanoTime()), "Wrong exam score accepted");
        session.markMediaReady(System.nanoTime());
        check(session.selectAnswer(0, System.nanoTime()) && session.submit(System.nanoTime()), "Replacement answer failed");
        session.startAnswering(System.nanoTime());
        session.selectAnswer(0, System.nanoTime());
        session.submit(System.nanoTime());
        var result = session.result().orElseThrow();
        check(result.score() == 6 && result.answers().size() == 2, "Failed download changed exam scoring");
        check(result.answers().getFirst().questionId().equals(replacement.id()), "Failed question recorded as answered");
        check(!session.replaceCurrentQuestion(failed, System.nanoTime()), "Finished exam changed");
        System.out.println("PASS: exam replacement preserves quotas, index, history and scoring");

        var practiceConstructor = PracticeSession.class.getDeclaredConstructor(List.class);
        practiceConstructor.setAccessible(true);
        PracticeSession practice = practiceConstructor.newInstance(List.of(failed, reserved, q(9, false, 2, "")));
        check(practice.replaceCurrentQuestion(replacement), "Practice replacement rejected");
        check(practice.questionIndex() == 0 && practice.size() == 3, "Practice advanced past an unanswered question");
        check(!practice.replaceCurrentQuestion(reserved), "Duplicate practice question accepted");
        check(!practice.replaceCurrentQuestion(q(10, false, 3, "")), "Practice type changed");
        System.out.println("PASS: automatic practice keeps the current slot and series length");

        Path cache = Files.createTempFile("wordcraft-fallback-test-", ".jpg");
        try {
            List<Question> offlineBank = new ArrayList<>();
            for (int i = 20; i < 28; i++) offlineBank.add(q(i, true, 3, "remote-" + i + ".wmv"));
            Question cached = q(30, true, 3, "cached.jpg");
            Question textOnly = q(31, true, 3, "");
            offlineBank.add(cached);
            offlineBank.add(textOnly);
            Function<Question, Path> local = question -> question.equals(cached) ? cache : null;
            fallback = new MediaFallback();
            Question current = failed;
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 3; i++) {
                current = fallback.choose(offlineBank, current, List.of(), false, false, local, FIRST).orElseThrow();
                check(seen.add(current.id()) && current.media().startsWith("remote-"), "Remote retry repeated a failure");
            }
            current = fallback.choose(offlineBank, current, List.of(), false, false, local, FIRST).orElseThrow();
            check(current.equals(cached), "Offline recovery did not choose local content after 3 remote alternatives");
            current = fallback.choose(offlineBank, current, List.of(), false, false, local, FIRST).orElseThrow();
            check(current.equals(textOnly), "Corrupt cached content did not fall back to a text-only question");
            System.out.println("PASS: network outage falls back to cached content, then text-only questions");
            fallback = new MediaFallback();
            current = failed;
            int attempts = 0;
            while (true) {
                var next = fallback.choose(offlineBank.subList(0, 8), current, List.of(), false, false, ignored -> null, FIRST);
                if (next.isEmpty()) break;
                current = next.get();
                check(++attempts <= 3, "Unbounded retry loop");
            }
            check(attempts == 3, "Unexpected remote recovery budget");
            System.out.println("PASS: all downloads failing terminates after a bounded number of alternatives");
        } finally {
            Files.deleteIfExists(cache);
        }

        CompletableFuture<Path> lateFailure = new CompletableFuture<>();
        ExamMediaPlan plan = new ExamMediaPlan(List.of(failed, reserved),
                question -> question.equals(failed) ? lateFailure : CompletableFuture.completedFuture(Path.of("cached")));
        plan.prepareFirst();
        check(plan.replaceQuestion(failed, replacement), "Prefetch plan replacement failed");
        lateFailure.completeExceptionally(new IOException("simulated timeout"));
        check(plan.failures() == 0, "Stale failure remains attached to replacement question");
        plan.prepareFirst().join();
        check(plan.allReady() && plan.readyQuestions() == 2 && plan.totalQuestions() == 2, "Prefetch counters do not match new questions");
        plan.stop();
        System.out.println("PASS: stale async failures do not corrupt the replaced exam prefetch plan");

        MediaFallback randomFallback = new MediaFallback();
        Set<String> choices = new HashSet<>();
        for (int seed = 0; seed < 100; seed++) {
            randomFallback = new MediaFallback();
            choices.add(randomFallback.choose(List.of(replacement, q(40, true, 3, ""), q(41, true, 3, "")),
                    failed, List.of(), false, false, ignored -> null, new Random(seed)).orElseThrow().id());
        }
        check(choices.size() > 1, "Replacement selection is not random");
        System.out.println("ALL MEDIA FALLBACK TESTS PASSED");
    }
}
