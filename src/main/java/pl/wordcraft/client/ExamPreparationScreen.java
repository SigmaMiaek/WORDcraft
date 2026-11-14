package pl.wordcraft.client;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import pl.wordcraft.core.ExamMediaPlan;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.core.MediaFallback;
import pl.wordcraft.data.Question;

final class ExamPreparationScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private final ExamSession selected;
    private final ExamMediaPlan plan;
    private CompletableFuture<Path> first;
    private boolean cancelled;
    private boolean handedOff;
    private MediaFallback mediaFallback;

    ExamPreparationScreen(WordcraftClient app, Screen parent, ExamSession selected) {
        super("WORDcraft · Przygotowanie egzaminu", parent);
        this.app = app;
        this.selected = selected;
        this.plan = new ExamMediaPlan(selected.questions(), app::prepareMedia);
        this.first = this.plan.prepareFirst();
    }

    @Override
    protected void init() {
        this.button("Anuluj", this.width / 2 - 60, this.height - 27, 120, this::onClose);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        this.backdrop(g, "Przygotowuję pierwsze pytanie");
        int area = Math.min(500, this.width - 28);
        int left = (this.width - area) / 2;
        this.card(g, left, 54, area, Math.max(115, this.height - 100));
        this.itemIcon(g, Items.CLOCK, left + 10, 64);
        g.text(this.font, "Za chwilę możesz zaczynać", left + 33, 68, -800132, false);
        int y = this.paragraph(
                g,
                "Egzamin ruszy po przygotowaniu pierwszego zadania. Zdjęcia i filmy do pozostałych pytań pobiorą się w tle podczas rozwiązywania.",
                left + 10,
                90,
                area - 20,
                -1182209
            )
            + 12;
        g.enableScissor(left + 9, y, left + area - 9, this.height - 38);
        this.paragraph(g, this.app.mediaStatus(), left + 10, y, area - 20, -5653041);
        g.disableScissor();
        super.extractRenderState(g, mx, my, delta);
    }

    @Override
    public void onClose() {
        this.cancelled = true;
        this.plan.stop();
        super.onClose();
    }

    @Override
    public void removed() {
        if (!this.handedOff) {
            this.cancelled = true;
            this.plan.stop();
        }
    }

    @Override
    public void tick() {
        if (!this.cancelled && this.first.isDone()) {
            if (this.first.isCompletedExceptionally()) {
                if (this.mediaFallback == null) {
                    this.mediaFallback = new MediaFallback();
                }

                Question var3 = this.selected.currentQuestion();
                Question var2 = this.mediaFallback
                    .choose(this.app.usable(), var3, this.selected.questions(), true, true, this.app::mediaPath, ThreadLocalRandom.current())
                    .orElse(null);
                if (var2 != null && this.selected.replaceCurrentQuestion(var2, System.nanoTime())) {
                    this.plan.replaceQuestion(var3, var2);
                    this.first = this.plan.prepareFirst();
                } else {
                    this.cancelled = true;
                    this.plan.stop();
                    this.app.message(this.parent, "Nie ma teraz dostępnego pytania zastępczego. Spróbuj ponownie później.");
                }
            } else {
                if (this.mediaFallback != null) {
                    this.mediaFallback.succeeded();
                }

                this.handedOff = true;
                this.cancelled = true;
                QuestionScreen var1 = QuestionScreen.exam(this.app, this.parent, this.selected.freshCopy(System.nanoTime()), this.plan);
                var1.useMediaFallback(this.mediaFallback);
                this.minecraft.gui.setScreen(var1);
                this.plan.startBackground();
            }
        }
    }
}
