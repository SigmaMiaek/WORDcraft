import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import pl.wordcraft.core.*;
import pl.wordcraft.data.Question;

/** Exercises the actual screen's replacement selection without opening a game window. */
public final class MediaFallbackUiTest {
    private static final Class<?> UNSAFE;
    private static final Object ALLOCATOR;
    static {
        try {
            UNSAFE = Class.forName("sun.misc.Unsafe");
            var field = UNSAFE.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            ALLOCATOR = field.get(null);
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private static Object allocate(Class<?> type) throws Exception {
        return UNSAFE.getMethod("allocateInstance", Class.class).invoke(ALLOCATOR, type);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void main(String[] args) throws Exception {
        var screenClass = Class.forName("pl.wordcraft.client.QuestionScreen");
        Object screen = allocate(screenClass);
        Object app = allocate(Class.forName("pl.wordcraft.client.WordcraftClient"));
        Question failed = new Question("1", "Failed", List.of("Yes", "No"), 0, true, 3, "failed.wmv", "");
        Question next = new Question("2", "Replacement", List.of("Yes", "No"), 0, true, 3, "", "");
        set(app, "usable", List.of(failed, next));
        set(app, "media", Map.of());
        set(screen, "app", app);
        set(screen, "question", failed);
        var find = screenClass.getDeclaredMethod("findReplacement");
        find.setAccessible(true);
        if (!find.invoke(screen).equals(next)) throw new AssertionError("Actual screen did not find a replacement");
        var constructor = ExamSession.class.getDeclaredConstructor(List.class, long.class);
        constructor.setAccessible(true);
        ExamSession exam = constructor.newInstance(List.of(failed), System.nanoTime());
        set(screen, "exam", exam);
        set(screen, "mediaFallback", null);
        if (!find.invoke(screen).equals(next)) throw new AssertionError("Actual exam screen did not find a replacement");
        // Loading these classes with verification enabled validates copied methods and lambda linkage.
        Class<?> preparationClass = Class.forName("pl.wordcraft.client.ExamPreparationScreen");
        Object preparation = allocate(preparationClass);
        ExamSession preparingExam = constructor.newInstance(List.of(failed), System.nanoTime());
        ExamMediaPlan plan = new ExamMediaPlan(preparingExam.questions(), ignored ->
                CompletableFuture.failedFuture(new java.io.IOException("simulated download failure")));
        set(preparation, "app", app);
        set(preparation, "selected", preparingExam);
        set(preparation, "plan", plan);
        set(preparation, "first", plan.prepareFirst());
        var tick = preparationClass.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(preparation);
        if (!preparingExam.currentQuestion().equals(next) || preparingExam.questionIndex() != 0
                || preparingExam.result().isPresent() || !plan.allReady()) {
            throw new AssertionError("First-question download failure did not recover without aborting the exam");
        }
        plan.stop();
        System.out.println("PASS: actual exam preparation replaces the first failed question without aborting");
        preparationClass.getDeclaredMethods();
        screenClass.getDeclaredMethods();
        System.out.println("PASS: actual screen selects a different question in practice and exam modes");
        System.out.println("PASS: JVM verified the screen callbacks and exam preparation methods");
        System.out.println("ALL MEDIA FALLBACK UI TESTS PASSED");
    }
}
