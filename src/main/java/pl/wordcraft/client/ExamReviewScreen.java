package pl.wordcraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.data.Question;

final class ExamReviewScreen extends WordcraftScreen {
    private static final int TOP = 47;
    private static final int ROW_HEIGHT = 49;
    private final WordcraftClient app;
    private final boolean errorsOnly;
    private final List<ExamReviewScreen.Entry> entries;
    private int page;
    private int pageSize = 1;

    ExamReviewScreen(WordcraftClient app, Screen parent, ExamSession.Result result, boolean errorsOnly) {
        super(errorsOnly ? "WORDcraft · Błędy z egzaminu" : "WORDcraft · Przegląd egzaminu", parent);
        this.app = app;
        this.errorsOnly = errorsOnly;
        List<ExamReviewScreen.Entry> selected = new ArrayList<>();

        for (int i = 0; i < result.answers().size(); i++) {
            ExamSession.AnswerResult answer = result.answers().get(i);
            if (!errorsOnly || answer.presented() && !answer.correct()) {
                selected.add(new ExamReviewScreen.Entry(i + 1, answer));
            }
        }

        this.entries = List.copyOf(selected);
    }

    @Override
    protected void init() {
        int area = Math.min(660, this.width - 24);
        int left = (this.width - area) / 2;
        this.pageSize = Math.max(1, (this.height - 62 - 47) / 49);
        this.page = Math.max(0, Math.min(this.page, this.pageCount() - 1));
        int end = Math.min(this.entries.size(), (this.page + 1) * this.pageSize);

        for (int i = this.page * this.pageSize; i < end; i++) {
            ExamReviewScreen.Entry entry = this.entries.get(i);
            int y = 47 + (i - this.page * this.pageSize) * 49;
            this.themedButton("Zobacz", left + area - 65, y + 12, 57, () -> this.open(entry.answer()), WordcraftScreen.Tone.SECONDARY).active = this.app
                .questionById(entry.answer().questionId())
                .isPresent();
        }

        this.button("←", left, this.height - 53, 46, () -> {
            this.page--;
            this.rebuildWidgets();
        }).active = this.page > 0;
        this.button("→", left + area - 46, this.height - 53, 46, () -> {
            this.page++;
            this.rebuildWidgets();
        }).active = this.page + 1 < this.pageCount();
        this.button("Wróć do wyniku", left, this.height - 27, area, this::onClose);
    }

    private void open(ExamSession.AnswerResult answer) {
        Optional<Question> question = this.app.questionById(answer.questionId());
        if (question.isPresent()) {
            this.minecraft.setScreen(QuestionScreen.review(this.app, this, question.get(), answer));
        } else {
            this.app.message(this, "Tego pytania nie ma w bieżącej bazie. Zachowano identyfikator i wynik odpowiedzi.");
        }
    }

    private int pageCount() {
        return Math.max(1, (this.entries.size() + this.pageSize - 1) / this.pageSize);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.backdrop(
            g,
            this.errorsOnly ? "Wybierz pytanie, aby zobaczyć swoją odpowiedź i wyjaśnienie" : "Kolejność z egzaminu · wybierz pytanie, aby zobaczyć szczegóły"
        );
        int area = Math.min(660, this.width - 24);
        int left = (this.width - area) / 2;
        int end = Math.min(this.entries.size(), (this.page + 1) * this.pageSize);

        for (int i = this.page * this.pageSize; i < end; i++) {
            ExamReviewScreen.Entry entry = this.entries.get(i);
            ExamSession.AnswerResult answer = entry.answer();
            Question question = this.app.questionById(answer.questionId()).orElse(null);
            int y = 47 + (i - this.page * this.pageSize) * 49;
            int accent = !answer.presented() ? -5653041 : (answer.correct() ? -9117498 : -1009005);
            this.card(g, left, y, area, 44);
            g.fill(left + 1, y + 1, left + 3, y + 49 - 6, accent);
            int textWidth = Math.max(40, area - 83);
            String status = !answer.presented()
                ? "Nieprzedstawione"
                : (answer.correct() ? "Poprawnie" : (answer.selectedIndex() < 0 ? "Brak odpowiedzi" : "Błędnie"));
            String heading = entry.number() + ". #" + answer.questionId() + " · " + status;
            String summary = question == null ? "Pytanie niedostępne w bieżącej bazie" : question.text();
            String choices = answer.presented()
                ? "Twoja: " + answerLabel(question, answer.selectedIndex()) + " · poprawna: " + answerLabel(question, answer.correctIndex())
                : "Nie zaliczono tego pytania do błędów.";
            g.enableScissor(left + 6, y + 3, left + 6 + textWidth, y + 49 - 7);
            g.drawString(this.font, this.font.plainSubstrByWidth(heading, textWidth), left + 8, y + 5, accent, false);
            g.drawString(this.font, this.font.plainSubstrByWidth(summary, textWidth), left + 8, y + 18, -1182209, false);
            g.drawString(this.font, this.font.plainSubstrByWidth(choices, textWidth), left + 8, y + 31, -5653041, false);
            g.disableScissor();
        }

        if (this.entries.isEmpty()) {
            this.card(g, left, 47, area, 64);
            this.itemIcon(g, Items.EMERALD, left + 10, 62);
            this.paragraph(g, this.errorsOnly ? "Brak błędnych odpowiedzi w tym egzaminie." : "Brak zapisanych pytań.", left + 35, 62, area - 46, -9117498);
        }

        String label = "Strona " + (this.page + 1) + "/" + this.pageCount();
        g.drawString(this.font, label, this.width / 2 - this.font.width(label) / 2, this.height - 47, -5653041, false);
        super.render(g, mx, my, delta);
    }

    private static String answerLabel(Question question, int index) {
        if (index < 0) {
            return "brak";
        } else if (question != null && question.basic()) {
            return index == 0 ? "TAK" : "NIE";
        } else {
            return index < 3 ? Character.toString((char)(65 + index)) : Integer.toString(index + 1);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (vertical == 0.0) {
            return false;
        }

        int next = Math.max(0, Math.min(this.pageCount() - 1, this.page + (vertical < 0.0 ? 1 : -1)));
        if (next != this.page) {
            this.page = next;
            this.rebuildWidgets();
        }

        return true;
    }

    private record Entry(int number, ExamSession.AnswerResult answer) {
    }
}
