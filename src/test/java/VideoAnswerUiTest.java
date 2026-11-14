import java.lang.reflect.*;
import java.util.*;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.data.Question;
import pl.wordcraft.media.MediaPlayer;

/** Exercises the actual patched screen's eligibility and selection methods without a game window. */
public final class VideoAnswerUiTest {
    private static final Class<?> SCREEN = loadScreen();

    private static Class<?> loadScreen() {
        try { return Class.forName("pl.wordcraft.client.QuestionScreen"); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void set(Object screen, String name, Object value) throws Exception {
        Field field = SCREEN.getDeclaredField(name);
        field.setAccessible(true);
        field.set(screen, value);
    }

    private static Object call(Object screen, String name) throws Exception {
        Method method = SCREEN.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(screen);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field instance = unsafeClass.getDeclaredField("theUnsafe");
        instance.setAccessible(true);
        Object unsafe = instance.get(null);
        Object screen = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, SCREEN);
        set(screen, "mediaError", "");
        set(screen, "selected", -1);
        try (MediaPlayer player = new MediaPlayer()) {
            set(screen, "player", player);
            Field state = MediaPlayer.class.getDeclaredField("state");
            state.setAccessible(true);
            for (var value : List.of(MediaPlayer.State.READY, MediaPlayer.State.PLAYING, MediaPlayer.State.ENDED)) {
                state.set(player, value);
                check((boolean) call(screen, "canAnswer"), "Practice blocked in " + value);
                System.out.println("PASS: actual practice screen allows answers in " + value);
            }
            set(screen, "mediaPending", true);
            check(!(boolean) call(screen, "canAnswer"), "Download preparation gate lost");
            set(screen, "mediaPending", false);
            set(screen, "submitted", true);
            check(!(boolean) call(screen, "canAnswer"), "Submitted question can be changed");
            set(screen, "submitted", false);
            set(screen, "mediaError", "test error");
            check(!(boolean) call(screen, "canAnswer"), "Media error gate lost");
            set(screen, "mediaError", "");
            var constructor = ExamSession.class.getDeclaredConstructor(List.class, long.class);
            constructor.setAccessible(true);
            var question = new Question("1", "Test", List.of("Yes", "No"), 0, true, 3, "test.wmv", "");
            ExamSession exam = constructor.newInstance(List.of(question), System.nanoTime());
            exam.markMediaReady(System.nanoTime());
            set(screen, "exam", exam);
            check((boolean) call(screen, "canAnswer"), "Exam screen blocked before playback");
            check((boolean) call(screen, "showReadingAction"), "Start film button missing");
            Method choose = SCREEN.getDeclaredMethod("choose", int.class);
            choose.setAccessible(true);
            choose.invoke(screen, 0);
            check(exam.selectedAnswer() == 0, "Screen did not select an early answer");
            check(!(boolean) call(screen, "showReadingAction"), "Submit still starts film instead of confirming");
            exam.startMedia(System.nanoTime());
            check((boolean) call(screen, "canAnswer"), "Exam screen blocked during playback");
            choose.invoke(screen, 1);
            check(exam.selectedAnswer() == 1, "Screen did not select during playback");
            System.out.println("PASS: actual exam screen supports early selection and the submit action");
        }
        System.out.println("ALL UI VIDEO ANSWER TESTS PASSED");
    }
}
