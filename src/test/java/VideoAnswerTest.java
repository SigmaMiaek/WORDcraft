import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.data.Question;

public final class VideoAnswerTest {
    private static Question question(int id, boolean basic, String media) {
        return new Question("" + id, "Test question", basic ? List.of("Yes", "No")
                : List.of("A", "B", "C"), 0, basic, 3, media, "");
    }

    private static ExamSession session(Question... questions) throws Exception {
        var constructor = ExamSession.class.getDeclaredConstructor(List.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(List.of(questions), System.nanoTime());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Question video = question(1, true, "test.wmv");
        Question image = question(2, true, "test.jpg");
        for (var target : List.of(ExamSession.Phase.READING, ExamSession.Phase.READY_TO_PLAY,
                ExamSession.Phase.PLAYING, ExamSession.Phase.ANSWERING)) {
            ExamSession exam = session(video, image);
            long now = System.nanoTime();
            check(!exam.canAnswer(), "Download preparation must still block answers");
            check(!exam.selectAnswer(0, now), "Selection during preparation");
            check(!exam.submit(now), "Submission during preparation");
            exam.markMediaReady(now);
            if (target == ExamSession.Phase.READY_TO_PLAY) exam.tick(now + TimeUnit.SECONDS.toNanos(20));
            if (target == ExamSession.Phase.PLAYING || target == ExamSession.Phase.ANSWERING) {
                check(exam.startMedia(now), "Start media");
            }
            if (target == ExamSession.Phase.ANSWERING) exam.mediaEnded(now);
            check(exam.phase() == target, "Phase setup: " + target);
            check(exam.canAnswer(), "Answers blocked in " + target);
            check(!exam.selectAnswer(-1, now), "Invalid negative answer accepted");
            check(!exam.selectAnswer(2, now), "Out-of-range answer accepted");
            check(exam.selectAnswer(0, now), "Selection rejected in " + target);
            check(exam.submit(now), "Submission rejected in " + target);
            check(exam.questionIndex() == 1 && exam.currentQuestion().id().equals("2"), "Did not advance");
            check(exam.selectedAnswer() == -1, "Selection carried over to next question");
            check(exam.phase() == ExamSession.Phase.PREPARING, "Next question did not reset its phase");
            exam.markMediaReady(now);
            check(!exam.canAnswer(), "Image reading phase unexpectedly changed");
            check(exam.startAnswering(now), "Start answering image question");
            check(exam.selectAnswer(0, now) && exam.submit(now), "Final answer failed");
            var result = exam.result().orElseThrow();
            check(result.score() == 6 && result.answers().size() == 2, "Scoring changed");
            check(!exam.canAnswer(), "Answers allowed after finishing");
            System.out.println("PASS: select, submit, advance and score from " + target);
        }
        ExamSession ended = session(video, image);
        long now = System.nanoTime();
        ended.markMediaReady(now);
        ended.startMedia(now);
        ended.selectAnswer(0, now);
        ended.mediaEnded(now);
        check(ended.selectedAnswer() == 0, "Video ending cleared an early selection");
        ended.tick(now + TimeUnit.SECONDS.toNanos(15));
        check(ended.questionIndex() == 1, "Original answer deadline no longer advances");
        System.out.println("PASS: video completion preserves selection and original deadline");
        ExamSession longVideo = session(video, image);
        longVideo.markMediaReady(now);
        longVideo.startMedia(now);
        longVideo.tick(now + TimeUnit.SECONDS.toNanos(60));
        check(longVideo.questionIndex() == 0 && longVideo.canAnswer(), "Playback acquired a new deadline");
        check(longVideo.selectAnswer(0, now + TimeUnit.SECONDS.toNanos(60))
                && longVideo.submit(now + TimeUnit.SECONDS.toNanos(60)), "Cannot skip a long running video");
        System.out.println("PASS: long-running video can be answered without waiting for completion");
        ExamSession aborted = session(video);
        aborted.abort(System.nanoTime());
        check(!aborted.canAnswer() && aborted.result().orElseThrow().aborted(), "Abort behavior changed");
        System.out.println("ALL CORE VIDEO ANSWER TESTS PASSED");
    }
}
