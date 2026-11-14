package pl.wordcraft.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.core.LearningStats;
import pl.wordcraft.data.Question;

final class StatsScreen extends WordcraftScreen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.forLanguageTag("pl-PL"));
    private static final int TOP = 76;
    private static final int ROW_HEIGHT = 43;
    private final WordcraftClient app;
    private StatsScreen.View view = StatsScreen.View.ERRORS;
    private int page;
    private int pageSize = 1;
    private List<StatsScreen.Row> rows = List.of();

    StatsScreen(WordcraftClient app, Screen parent) {
        super("WORDcraft · Postępy", parent);
        this.app = app;
    }

    @Override
    protected void init() {
        int area = Math.min(600, this.width - 24);
        int left = (this.width - area) / 2;
        int third = (area - 8) / 3;
        this.themedButton(
            "Błędy (" + this.app.stats().errors().size() + ")",
            left,
            45,
            third,
            () -> this.switchView(StatsScreen.View.ERRORS),
            this.view == StatsScreen.View.ERRORS ? WordcraftScreen.Tone.SELECTED : WordcraftScreen.Tone.QUIET,
            Items.REDSTONE
        );
        this.themedButton(
            "Powtórki (" + this.app.stats().reviewIds().size() + ")",
            left + third + 4,
            45,
            third,
            () -> this.switchView(StatsScreen.View.REVIEW),
            this.view == StatsScreen.View.REVIEW ? WordcraftScreen.Tone.SELECTED : WordcraftScreen.Tone.QUIET,
            Items.BOOK
        );
        this.themedButton(
            "Egzaminy (" + this.app.stats().examHistory().size() + ")",
            left + 2 * (third + 4),
            45,
            area - 2 * (third + 4),
            () -> this.switchView(StatsScreen.View.EXAMS),
            this.view == StatsScreen.View.EXAMS ? WordcraftScreen.Tone.AMBER : WordcraftScreen.Tone.QUIET,
            Items.GOLD_INGOT
        );
        this.rows = this.snapshotRows();
        this.pageSize = Math.max(1, (this.height - 62 - 76) / 43);
        int pages = this.pageCount();
        this.page = Math.max(0, Math.min(this.page, pages - 1));
        Set<String> usableIds = new HashSet<>();

        for (Question question : this.app.usable()) {
            usableIds.add(question.id());
        }

        int end = Math.min(this.rows.size(), (this.page + 1) * this.pageSize);

        for (int i = this.page * this.pageSize; i < end; i++) {
            StatsScreen.Row row = this.rows.get(i);
            int y = 76 + (i - this.page * this.pageSize) * 43 + 9;
            int openWidth = 57;
            int markWidth = this.view == StatsScreen.View.EXAMS ? 0 : 51;
            this.themedButton(
                    "Otwórz",
                    left + area - openWidth - markWidth - (markWidth > 0 ? 4 : 0),
                    y,
                    openWidth,
                    () -> this.openRow(row),
                    WordcraftScreen.Tone.SECONDARY
                )
                .active = row.exam() != null || row.answer() != null && this.app.questionById(row.id()).isPresent() || usableIds.contains(row.id());
            if (this.view != StatsScreen.View.EXAMS) {
                boolean marked = this.app.stats().isMarkedForReview(row.id());
                this.themedButton(marked ? "Usuń ☆" : "Dodaj ☆", left + area - markWidth, y, markWidth, () -> {
                    this.app.stats().toggleReview(row.id());
                    this.app.save();
                    this.rebuildWidgets();
                }, marked ? WordcraftScreen.Tone.SELECTED : WordcraftScreen.Tone.QUIET);
            }
        }

        this.button("←", left, this.height - 53, 46, () -> {
            this.page--;
            this.rebuildWidgets();
        }).active = this.page > 0;
        this.button("→", left + area - 46, this.height - 53, 46, () -> {
            this.page++;
            this.rebuildWidgets();
        }).active = this.page + 1 < pages;
        int footerHalf = (area - 4) / 2;
        this.themedButton(
                "Losuj powtórkę",
                left,
                this.height - 27,
                footerHalf,
                () -> this.app.requestQuestion(this, false, true),
                WordcraftScreen.Tone.PRIMARY,
                Items.BOOK
            )
            .active = !this.app.stats().reviewIds().isEmpty();
        this.button("Wróć", left + footerHalf + 4, this.height - 27, area - footerHalf - 4, this::onClose);
    }

    private void switchView(StatsScreen.View next) {
        this.view = next;
        this.page = 0;
        this.rebuildWidgets();
    }

    private List<StatsScreen.Row> snapshotRows() {
        List<StatsScreen.Row> result = new ArrayList<>();
        if (this.view == StatsScreen.View.ERRORS) {
            List<LearningStats.ErrorEntry> errors = this.app.stats().errors();

            for (int i = errors.size() - 1; i >= 0; i--) {
                LearningStats.ErrorEntry error = errors.get(i);
                Optional<Question> question = this.app.questionById(error.questionId());
                String answer = error.selectedIndex() < 0 ? "brak odpowiedzi" : answerLabel(question.orElse(null), error.selectedIndex());
                String correct = answerLabel(question.orElse(null), error.correctIndex());
                result.add(
                    new StatsScreen.Row(
                        error.questionId(),
                        "#" + error.questionId() + " · " + date(error.timestamp()),
                        "Wybrano: " + answer + " · poprawna: " + correct,
                        new ExamSession.AnswerResult(error.questionId(), error.selectedIndex(), error.correctIndex(), 0, false, true),
                        null
                    )
                );
            }
        } else if (this.view == StatsScreen.View.REVIEW) {
            for (String id : this.app.stats().reviewIds()) {
                Optional<Question> question = this.app.questionById(id);
                LearningStats.QuestionProgress progress = this.app.stats().questionStats(id);
                result.add(
                    new StatsScreen.Row(
                        id,
                        "#" + id + " · błędy " + progress.wrong() + "/" + progress.attempts(),
                        question.map(Question::text).orElse("Pytanie nie jest w obecnie zaimportowanej bazie.")
                    )
                );
            }
        } else {
            List<LearningStats.ExamEntry> exams = this.app.stats().examHistory();

            for (int i = exams.size() - 1; i >= 0; i--) {
                LearningStats.ExamEntry entry = exams.get(i);
                ExamSession.Result exam = entry.result();
                String state = exam.aborted() ? "Przerwano" : (exam.passed() ? "Zaliczono" : "Niezaliczono");
                long seconds = Math.max(0L, exam.elapsedMillis() / 1000L);
                result.add(
                    new StatsScreen.Row(
                        "",
                        date(entry.timestamp()) + " · " + exam.score() + "/" + exam.maxScore() + " pkt",
                        state + " · czas " + seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L),
                        null,
                        exam
                    )
                );
            }
        }

        return List.copyOf(result);
    }

    private void openRow(StatsScreen.Row row) {
        if (row.exam() != null) {
            this.minecraft.gui.setScreen(new ExamResultsScreen(this.app, this, row.exam(), ""));
        } else {
            Optional<Question> question = row.answer() != null
                ? this.app.questionById(row.id())
                : this.app.usable().stream().filter(q -> q.id().equals(row.id())).findFirst();
            if (question.isPresent()) {
                this.minecraft
                    .gui
                    .setScreen(
                        row.answer() == null
                            ? QuestionScreen.practice(this.app, this, question.get(), false)
                            : QuestionScreen.review(this.app, this, question.get(), row.answer())
                    );
            } else {
                this.app.message(this, "To pytanie lub jego multimedia nie są dostępne w bieżącym imporcie.");
            }
        }
    }

    private static String answerLabel(Question question, int index) {
        if (index < 0) {
            return "brak";
        } else if (question == null) {
            return "nr " + (index + 1);
        } else if (question != null && question.basic()) {
            return index == 0 ? "TAK" : "NIE";
        } else {
            return index < 3 ? Character.toString((char)(65 + index)) : Integer.toString(index + 1);
        }
    }

    private static String date(long timestamp) {
        return DATE.format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()));
    }

    private int pageCount() {
        return Math.max(1, (this.rows.size() + this.pageSize - 1) / this.pageSize);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        String percent = String.format(Locale.forLanguageTag("pl-PL"), "%.1f%%", this.app.stats().correctPercent());
        this.backdrop(g, "Rozwiązano: " + this.app.stats().solvedCount() + " · poprawne: " + percent);
        int area = Math.min(600, this.width - 24);
        int left = (this.width - area) / 2;
        int end = Math.min(this.rows.size(), (this.page + 1) * this.pageSize);

        for (int i = this.page * this.pageSize; i < end; i++) {
            StatsScreen.Row row = this.rows.get(i);
            int y = 76 + (i - this.page * this.pageSize) * 43;
            this.card(g, left, y - 3, area, 38);
            g.fill(left + 1, y - 2, left + 3, y + 43 - 9, this.view == StatsScreen.View.EXAMS ? -800132 : -7487489);
            int textWidth = area - (this.view == StatsScreen.View.EXAMS ? 72 : 122);
            g.enableScissor(left + 4, y, left + 4 + Math.max(40, textWidth), y + 43 - 6);
            g.text(this.font, this.font.plainSubstrByWidth(row.heading(), Math.max(40, textWidth)), left + 7, y + 2, -1182209, false);
            g.text(this.font, this.font.plainSubstrByWidth(row.detail(), Math.max(40, textWidth)), left + 7, y + 16, -5653041, false);
            g.disableScissor();
        }

        if (this.rows.isEmpty()) {
            String message = this.view == StatsScreen.View.ERRORS
                ? "Brak zapisanych błędów."
                : (
                    this.view == StatsScreen.View.REVIEW
                        ? "Brak pytań do powtórki. Dodaj je po rozwiązaniu pytania."
                        : "Brak próbnych egzaminów. Uruchom egzamin w ustawieniach."
                );
            this.card(g, left, 73, area, Math.max(48, Math.min(this.height - 76 - 64, 88)));
            this.itemIcon(g, this.view == StatsScreen.View.EXAMS ? Items.GOLD_INGOT : Items.BOOK, left + 9, 86);
            this.paragraph(g, message, left + 33, 86, area - 43, -5653041);
        }

        String label = "Strona " + (this.page + 1) + "/" + this.pageCount();
        g.text(this.font, label, this.width / 2 - this.font.width(label) / 2, this.height - 47, -5653041, false);
        super.extractRenderState(g, mx, my, delta);
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

    private record Row(String id, String heading, String detail, ExamSession.AnswerResult answer, ExamSession.Result exam) {
        Row(String id, String heading, String detail) {
            this(id, heading, detail, null, null);
        }
    }

    private enum View {
        ERRORS,
        REVIEW,
        EXAMS;
    }
}
