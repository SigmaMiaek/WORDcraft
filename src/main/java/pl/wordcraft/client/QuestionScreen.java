package pl.wordcraft.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import pl.wordcraft.core.ExamMediaPlan;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.core.MediaFallback;
import pl.wordcraft.core.PracticeSession;
import pl.wordcraft.data.Question;
import pl.wordcraft.data.QuestionExplanations;
import pl.wordcraft.media.MediaPlayer;

public final class QuestionScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private final ExamSession exam;
    private final boolean automatic;
    private final PracticeSession practiceSession;
    private ExamMediaPlan examDownloads;
    private ExamSession.AnswerResult reviewedAnswer;
    private Question question;
    private MediaPlayer player;
    private boolean mediaPending;
    private boolean practiceVideoStarted;
    private int mediaGeneration;
    private final MediaTexture texture = new MediaTexture();
    private int selected = -1;
    private boolean submitted;
    private boolean recordedExam;
    private boolean abortRequested;
    private boolean deferred;
    private String mediaError = "";
    private final List<Button> choices = new ArrayList<>();
    private final List<QuestionScreen.AnswerCard> answerCards = new ArrayList<>();
    private AbcQuestionLayout.Layout abcLayout;
    private Button submit;
    private Button replay;
    private Button review;
    private Button abort;
    private MediaFallback mediaFallback;

    private QuestionScreen(WordcraftClient app, Screen parent, Question question, ExamSession exam, boolean automatic, PracticeSession practiceSession) {
        super(exam == null ? "WORDcraft · Nauka" : "WORDcraft · Egzamin", parent);
        this.app = app;
        this.question = question;
        this.exam = exam;
        this.automatic = automatic;
        this.practiceSession = practiceSession;
    }

    public static QuestionScreen practice(WordcraftClient app, Screen parent, Question q, boolean automatic) {
        return new QuestionScreen(app, parent, q, null, automatic, null);
    }

    public static QuestionScreen automatic(WordcraftClient app, Screen parent, PracticeSession session) {
        return new QuestionScreen(app, parent, session.currentQuestion(), null, true, session);
    }

    public static QuestionScreen exam(WordcraftClient app, Screen parent, ExamSession session) {
        return new QuestionScreen(app, parent, session.currentQuestion(), session, false, null);
    }

    public static QuestionScreen exam(WordcraftClient app, Screen parent, ExamSession session, ExamMediaPlan plan) {
        QuestionScreen screen = exam(app, parent, session);
        screen.examDownloads = plan;
        return screen;
    }

    public static QuestionScreen review(WordcraftClient app, Screen parent, Question question, ExamSession.AnswerResult answer) {
        QuestionScreen screen = practice(app, parent, question, false);
        screen.reviewedAnswer = Objects.requireNonNull(answer);
        screen.selected = answer.selectedIndex();
        screen.submitted = true;
        return screen;
    }

    public PracticeSession practiceSession() {
        return this.practiceSession;
    }

    public Question question() {
        return this.question;
    }

    public boolean isAutomaticPractice() {
        return this.automatic && this.exam == null;
    }

    public boolean unanswered() {
        return !this.submitted;
    }

    @Override
    protected void init() {
        this.choices.clear();
        this.answerCards.clear();
        this.abcLayout = null;
        if (!this.question.basic()) {
            this.abcLayout = this.layoutFor(this.question);

            for (int i = 0; i < this.question.answers().size(); i++) {
                this.answerCards.add(this.addRenderableWidget(new QuestionScreen.AnswerCard(i, this.abcLayout.answers().get(i))));
            }
        }

        int available = this.width - 28;
        int count = this.question.answers().size();
        int cw = (available - (count - 1) * 4) / count;

        for (int i = 0; i < count; i++) {
            int index = i;
            this.choices
                .add(this.themedButton(this.choiceLabel(i), 14 + i * (cw + 4), this.height - 70, cw, () -> this.choose(index), WordcraftScreen.Tone.SECONDARY));
        }

        int actionWidth = this.exam == null && !ExamSession.isVideo(this.question) ? (available - 4) / 2 : Math.max(90, (available - 8) / 3);
        this.submit = this.themedButton("Zatwierdź", 14, this.height - 46, actionWidth, this::submitAnswer, WordcraftScreen.Tone.PRIMARY);
        if (this.exam == null) {
            if (ExamSession.isVideo(this.question)) {
                this.replay = this.themedButton(
                    "Obejrzyj film", 18 + actionWidth, this.height - 46, actionWidth, this::startPracticeVideo, WordcraftScreen.Tone.AMBER
                );
                this.themedButton(
                    this.reviewedAnswer == null ? "Zamknij" : "Wróć",
                    22 + 2 * actionWidth,
                    this.height - 46,
                    Math.max(40, available - 2 * actionWidth - 8),
                    this::onClose,
                    WordcraftScreen.Tone.QUIET
                );
            } else {
                this.themedButton(
                    this.reviewedAnswer == null ? "Zamknij" : "Wróć",
                    18 + actionWidth,
                    this.height - 46,
                    available - actionWidth - 4,
                    this::onClose,
                    WordcraftScreen.Tone.QUIET
                );
            }

            this.review = this.themedButton(this.reviewLabel(), 14, this.height - 22, Math.min(174, available), () -> {
                this.app.stats().toggleReview(this.question.id());
                this.app.save();
                this.updateButtons();
            }, WordcraftScreen.Tone.QUIET);
        } else {
            this.abort = this.themedButton("Przerwij egzamin", 18 + actionWidth, this.height - 46, available - actionWidth - 4, () -> {
                if (this.abortRequested) {
                    this.exam.abort(System.nanoTime());
                    this.finishExam();
                } else {
                    this.abortRequested = true;
                    this.abort.setMessage(Component.literal("Potwierdź przerwanie"));
                    setTone(this.abort, WordcraftScreen.Tone.DANGER);
                }
            }, WordcraftScreen.Tone.QUIET);
        }

        if (this.question.basic()) {
            this.scrollControls(this.height - 78);
        }

        if (this.player == null && !this.mediaPending && !this.question.media().isBlank() && this.mediaError.isBlank()) {
            this.loadMedia();
        }

        this.updateButtons();
    }

    private AbcQuestionLayout.Layout layoutFor(Question candidate) {
        return AbcQuestionLayout.fit(
            this.width,
            this.height,
            abcPrompt(candidate),
            candidate.answers(),
            !candidate.media().isBlank(),
            false,
            (text, textWidth) -> this.font.split(Component.literal(text), Math.max(1, textWidth)).size(),
            9 + 1
        );
    }

    private static String abcPrompt(Question candidate) {
        return candidate.text().replaceAll("\\s+", " ").strip();
    }

    private String choiceLabel(int index) {
        return this.question.basic() ? this.question.answers().get(index) : String.valueOf((char)(65 + index));
    }

    private int correctAnswerIndex() {
        return this.reviewedAnswer != null && this.reviewedAnswer.correctIndex() >= 0 && this.reviewedAnswer.correctIndex() < this.question.answers().size()
            ? this.reviewedAnswer.correctIndex()
            : this.question.correctIndex();
    }

    private String choiceDescription(int index) {
        return this.question.basic() ? this.question.answers().get(index) : this.choiceLabel(index) + " — " + this.question.answers().get(index);
    }

    private String feedbackText() {
        if (this.reviewedAnswer != null && !this.reviewedAnswer.presented()) {
            return "To pytanie nie było przedstawione w tej próbie.";
        } else {
            return this.selected == this.correctAnswerIndex()
                ? "✓ Poprawna odpowiedź!"
                : (this.selected < 0 ? "Brak odpowiedzi. Poprawna: " : "Błędna odpowiedź. Poprawna: ") + this.choiceLabel(this.correctAnswerIndex());
        }
    }

    private String reviewLabel() {
        return this.app.stats().isMarkedForReview(this.question.id()) ? "✓ Na liście powtórek" : "+ Dodaj do powtórek";
    }

    private void startPracticeVideo() {
        if (this.player != null && this.player.isVideo() && this.player.isReady() && !this.player.isPlaying()) {
            this.practiceVideoStarted = true;
            if (this.player.isEnded()) {
                this.player.replay();
            } else {
                this.player.play();
            }

            this.updateButtons();
        }
    }

    @Override
    public void tick() {
        if (this.player != null && this.player.getError() != null && !this.player.getError().isBlank()) {
            this.mediaError = this.player.getError();
        }

        if (this.exam != null) {
            long now = System.nanoTime();
            this.exam.tick(now);
            if (this.exam.phase() == ExamSession.Phase.FINISHED) {
                this.finishExam();
                return;
            }

            if (!this.exam.currentQuestion().id().equals(this.question.id())) {
                this.changeExamQuestion();
                return;
            }

            if (!this.mediaError.isBlank()) {
                this.exam.abort(now);
                this.finishExam();
                return;
            }

            if (this.player != null && this.player.isReady() && this.exam.phase() == ExamSession.Phase.PREPARING) {
                this.exam.markMediaReady(now);
            }

            if (this.player != null
                && !this.question.basic()
                && this.player.isVideo()
                && this.exam.phase() == ExamSession.Phase.ANSWERING
                && this.player.state() == MediaPlayer.State.READY) {
                this.player.play();
            }

            if (this.exam.phase() == ExamSession.Phase.READY_TO_PLAY && this.exam.startMedia(now) && this.player != null) {
                this.player.play();
            }

            if (this.player != null && this.player.consumeEndedEvent()) {
                this.exam.mediaEnded(now);
            }

            this.selected = this.exam.selectedAnswer();
        }

        this.updateButtons();
    }

    private void changeExamQuestion() {
        this.stopMedia();
        this.question = this.exam.currentQuestion();
        this.selected = -1;
        this.submitted = false;
        this.abortRequested = false;
        this.mediaError = "";
        this.scroll = 0;
        this.practiceVideoStarted = false;
        this.replay = null;
        this.rebuildWidgets();
    }

    private void choose(int index) {
        if (this.canAnswer()) {
            if (this.exam == null) {
                this.selected = index;
            } else if (this.exam.selectAnswer(index, System.nanoTime())) {
                this.selected = index;
            }

            this.updateButtons();
        }
    }

    private void submitAnswer() {
        if (this.reviewedAnswer != null) {
            this.openExplanation();
        } else {
            if (this.exam != null) {
                long now = System.nanoTime();
                if (this.showReadingAction()) {
                    if (ExamSession.isVideo(this.question)) {
                        if (this.exam.startMedia(now) && this.player != null) {
                            this.player.play();
                        }
                    } else {
                        this.exam.startAnswering(now);
                    }
                } else if (this.exam.canAnswer() && this.exam.submit(now)) {
                    if (this.exam.phase() == ExamSession.Phase.FINISHED) {
                        this.finishExam();
                    } else {
                        this.changeExamQuestion();
                    }
                }
            } else if (this.submitted) {
                if (this.practiceSession != null) {
                    if (this.practiceSession.advance()) {
                        this.stopMedia();
                        this.question = this.practiceSession.currentQuestion();
                        this.selected = -1;
                        this.submitted = false;
                        this.mediaError = "";
                        this.scroll = 0;
                        this.practiceVideoStarted = false;
                        this.replay = null;
                        this.rebuildWidgets();
                    } else {
                        this.onClose();
                    }
                } else {
                    this.app.requestQuestion(this.parent, this.automatic, false);
                }
            } else if (this.canAnswer() && this.selected >= 0) {
                this.submitted = true;
                this.app.record(this.question, this.selected);
                this.scroll = 0;
            }

            this.updateButtons();
        }
    }

    private void updateButtons() {
        if (this.submit != null) {
            for (int i = 0; i < this.choices.size(); i++) {
                this.choices.get(i).active = this.canAnswer();
                this.choices.get(i).setMessage(Component.literal((i == this.selected ? "● " : "") + this.choiceLabel(i)));
                setTone(
                    this.choices.get(i),
                    this.submitted && i == this.correctAnswerIndex()
                        ? WordcraftScreen.Tone.PRIMARY
                        : (
                            this.submitted && i == this.selected
                                ? WordcraftScreen.Tone.DANGER
                                : (i == this.selected ? WordcraftScreen.Tone.SELECTED : WordcraftScreen.Tone.SECONDARY)
                        )
                );
            }

            for (QuestionScreen.AnswerCard answerCard : this.answerCards) {
                answerCard.visible = this.exam == null || this.exam.canAnswer();
                answerCard.active = this.canAnswer();
            }

            if (this.exam == null) {
                this.submit.active = this.reviewedAnswer != null || this.submitted || this.canAnswer() && this.selected >= 0;
                String next = this.practiceSession == null
                    ? "Następne pytanie"
                    : (
                        this.practiceSession.hasNext()
                            ? "Dalej (" + (this.practiceSession.questionIndex() + 2) + "/" + this.practiceSession.size() + ")"
                            : "Zakończ serię"
                    );
                this.submit.setMessage(Component.literal(this.reviewedAnswer != null ? "Dlaczego?" : (this.submitted ? next : "Zatwierdź")));
                if (this.replay != null) {
                    this.replay.active = this.player != null && this.player.isReady() && !this.player.isPlaying() && this.player.getError() == null;
                    this.replay
                        .setMessage(
                            Component.literal(
                                this.player != null && this.player.isPlaying()
                                    ? "Trwa film…"
                                    : (this.practiceVideoStarted ? "Obejrzyj ponownie" : "Obejrzyj film")
                            )
                        );
                }

                this.review.setMessage(Component.literal(this.reviewLabel()));
                setTone(this.review, this.app.stats().isMarkedForReview(this.question.id()) ? WordcraftScreen.Tone.SELECTED : WordcraftScreen.Tone.QUIET);
            } else {
                boolean reading = this.showReadingAction();
                this.submit.active = reading || this.exam.canAnswer();
                this.submit
                    .setMessage(
                        Component.literal(reading ? (ExamSession.isVideo(this.question) ? "Start filmu" : "Rozpocznij odpowiedź") : "Zatwierdź / dalej")
                    );
            }
        }
    }

    private String subtitle() {
        if (this.reviewedAnswer != null) {
            return "Omówienie · Twoja: "
                + (this.selected < 0 ? "brak" : this.choiceLabel(this.selected))
                + " · poprawna: "
                + this.choiceLabel(this.correctAnswerIndex());
        }

        if (this.exam == null) {
            return (this.practiceSession == null ? "Nauka swobodna" : "Seria " + (this.practiceSession.questionIndex() + 1) + "/" + this.practiceSession.size())
                + " · "
                + (this.question.basic() ? "podstawowe" : "specjalistyczne")
                + " · "
                + this.question.points()
                + " pkt";
        }

        long now = System.nanoTime();
        long remaining = this.exam.remainingExamMillis(now) / 1000L;
        return this.exam.questionIndex()
            + 1
            + "/32 · "
            + this.question.points()
            + " pkt · egzamin "
            + String.format(Locale.ROOT, "%02d:%02d", remaining / 60L, remaining % 60L)
            + " · "
            + this.phaseLabel(now);
    }

    private String phaseLabel(long now) {
        return switch (this.exam.phase()) {
            case PREPARING -> "materiał · czas wstrzymany";
            case READING -> "czytanie " + (this.exam.remainingPhaseMillis(now) + 999L) / 1000L + " s";
            case READY_TO_PLAY, PLAYING -> "film · jeden pokaz";
            case ANSWERING -> "odpowiedź " + (this.exam.remainingPhaseMillis(now) + 999L) / 1000L + " s";
            case FINISHED -> "koniec";
        };
    }

    private boolean showMedia() {
        return this.exam == null && ExamSession.isVideo(this.question) && !this.practiceVideoStarted
            ? false
            : this.exam == null || !this.question.basic() || this.exam.phase() == ExamSession.Phase.PLAYING || this.exam.phase() == ExamSession.Phase.ANSWERING;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        if (this.player != null) {
            this.texture.update(this.player.pollFrame());
        }

        if (!this.question.basic()) {
            this.renderAbc(g);
            this.renderFooter(g);
            super.extractRenderState(g, mx, my, delta);
        } else {
            this.backdrop(g, this.subtitle());
            int bottom = this.height - 78;
            int w = this.width - 52;
            int y = 46 - this.scroll;
            g.enableScissor(10, 42, this.width - 28, bottom);
            if (this.submitted) {
                boolean correct = this.selected == this.correctAnswerIndex();
                int feedbackHeight = this.font.split(Component.literal(this.feedbackText()), w - 16).size() * (9 + 2) + 12;
                g.fill(14, y, 14 + w, y + feedbackHeight, correct ? -15188684 : -12047822);
                g.fill(14, y, 17, y + feedbackHeight, correct ? -9117498 : -1013106);
                y = this.paragraph(g, this.feedbackText(), 23, y + 6, w - 16, correct ? -9117498 : -17731) + 13;
            }

            int questionHeight = this.font.split(Component.literal(this.question.text()), w - 16).size() * (9 + 2) + 16;
            this.card(g, 14, y, w, questionHeight);
            g.fill(14, y, 17, y + questionHeight, this.question.basic() ? -9117498 : -800132);
            y = this.paragraph(g, this.question.text(), 23, y + 8, w - 16, -1182209) + 16;
            if (!this.question.media().isBlank()) {
                if (!this.mediaError.isBlank()) {
                    y = this.paragraph(g, this.mediaError, 18, y, w - 8, -17731) + 10;
                } else if (this.mediaPending) {
                    y = this.paragraph(g, this.app.mediaStatus(), 18, y, w - 8, -5653041) + 10;
                } else if (!this.showMedia()) {
                    String prompt = this.exam == null
                        ? "Film czeka na Ciebie. Kliknij „Obejrzyj film”, gdy będziesz gotowy."
                        : "Przeczytaj pytanie, a potem rozpocznij film.";
                    int promptHeight = this.font.split(Component.literal(prompt), w - 20).size() * (9 + 2) + 16;
                    this.card(g, 14, y, w, promptHeight);
                    y = this.paragraph(g, prompt, 24, y + 8, w - 20, this.exam == null ? -800132 : -5653041) + 16;
                } else if (this.texture.available()) {
                    int mediaHeight = Math.max(48, Math.min(260, bottom - (y + this.scroll) - 8));
                    y += this.texture.draw(g, 14, y, w, mediaHeight) + 8;
                } else {
                    y = this.paragraph(g, this.mediaPending ? this.app.mediaStatus() : "Wczytywanie oryginalnego materiału…", 14, y, w, -6705218) + 8;
                }

                if (this.player != null && this.player.warning() != null && !this.player.warning().isBlank()) {
                    y = this.paragraph(g, this.player.warning(), 14, y, w, -11634) + 8;
                }
            }

            boolean answersVisible = this.exam == null || this.exam.canAnswer();
            if (answersVisible && !this.question.basic()) {
                for (int i = 0; i < this.question.answers().size(); i++) {
                    int color = this.submitted && i == this.correctAnswerIndex() ? -9117498 : (i == this.selected ? -7487489 : -1182209);
                    String answer = this.choiceLabel(i) + ". " + this.question.answers().get(i);
                    int answerHeight = this.font.split(Component.literal(answer), w - 16).size() * (9 + 2) + 12;
                    this.card(g, 14, y, w, answerHeight);
                    if (i == this.selected || this.submitted && i == this.correctAnswerIndex()) {
                        g.fill(14, y, 17, y + answerHeight, color);
                    }

                    y = this.paragraph(g, answer, 23, y + 6, w - 16, color) + 12;
                }
            }

            if (this.submitted) {
                String basis = this.question.legalBasis();
                if (basis != null && !basis.isBlank()) {
                    y = this.paragraph(g, basis, 18, y + 3, w - 8, -5653041);
                }
            }

            this.contentHeight = y + this.scroll - 42;
            g.disableScissor();
            this.scrollBar(g, bottom);
            this.renderFooter(g);
            super.extractRenderState(g, mx, my, delta);
        }
    }

    private void renderFooter(GuiGraphicsExtractor g) {
        g.fill(0, this.height - 76, this.width, this.height, -15919326);
        g.fill(14, this.height - 77, this.width - 14, this.height - 76, -13547171);
        if (this.exam != null) {
            String progress = this.examDownloads == null
                ? "Bez powrotu do poprzedniego pytania"
                : (
                    this.examDownloads.allReady()
                        ? "Materiały gotowe: 32/32"
                        : (this.exam.phase() == ExamSession.Phase.PREPARING ? "Czas wstrzymany · " : "Pobieranie w tle · ")
                            + this.examDownloads.readyQuestions()
                            + "/"
                            + this.examDownloads.totalQuestions()
                );
            if (this.examDownloads != null && this.examDownloads.failures() > 0) {
                progress = progress + " · ponowię brakujące";
            }

            g.text(
                this.font,
                this.font.plainSubstrByWidth(progress, this.width - 28),
                14,
                this.height - 17,
                this.examDownloads != null && !this.examDownloads.allReady() ? -800132 : -5653041
            );
        } else if (this.practiceSession != null) {
            int segmentWidth = Math.min(20, (this.width - 210) / this.practiceSession.size());
            int x = this.width - 18 - this.practiceSession.size() * (segmentWidth + 3);

            for (int i = 0; i < this.practiceSession.size(); i++) {
                g.fill(
                    x + i * (segmentWidth + 3),
                    this.height - 14,
                    x + i * (segmentWidth + 3) + segmentWidth,
                    this.height - 10,
                    i >= this.practiceSession.questionIndex() && (i != this.practiceSession.questionIndex() || !this.submitted)
                        ? (i == this.practiceSession.questionIndex() ? -7487489 : -13547171)
                        : -9117498
                );
            }
        }
    }

    private void renderAbc(GuiGraphicsExtractor g) {
        String status = this.subtitle();
        if (this.submitted && this.reviewedAnswer == null) {
            status = this.feedbackText() + " · " + status;
        }

        if (!this.abcLayout.fullTextFits()) {
            status = "Zwiększ okno lub zmniejsz skalę GUI, aby zobaczyć pełne odpowiedzi.";
        }

        this.backdrop(g, status);
        AbcQuestionLayout.Box prompt = this.abcLayout.prompt();
        this.card(g, prompt.x(), prompt.y(), prompt.width(), prompt.height());
        g.fill(prompt.x(), prompt.y(), prompt.x() + 3, prompt.y() + prompt.height(), -800132);
        g.enableScissor(prompt.x() + 4, prompt.y() + 1, prompt.right() - 1, prompt.bottom() - 1);
        this.scaledParagraph(g, abcPrompt(this.question), prompt.x() + 8, prompt.y() + 8, prompt.width() - 16, this.abcLayout.promptScale(), -1182209);
        g.disableScissor();
        AbcQuestionLayout.Box material = this.abcLayout.media();
        if (material.width() > 0) {
            this.card(g, material.x(), material.y(), material.width(), material.height());
            int x = material.x() + 4;
            int y = material.y() + 4;
            int w = material.width() - 8;
            int h = material.height() - 8;
            g.enableScissor(x, y, x + w, y + h);
            if (!this.mediaError.isBlank()) {
                this.scaledParagraph(g, this.mediaError, x + 4, y + 4, w - 8, 0.8F, -17731);
            } else if (this.mediaPending) {
                this.scaledParagraph(g, this.app.mediaStatus(), x + 4, y + 4, w - 8, 0.85F, -5653041);
            } else if (!this.showMedia()) {
                this.scaledParagraph(
                    g,
                    this.exam == null ? "Film czeka na Ciebie. Kliknij „Obejrzyj film”, gdy będziesz gotowy." : "Przeczytaj pytanie, a potem rozpocznij film.",
                    x + 4,
                    y + 4,
                    w - 8,
                    0.9F,
                    -800132
                );
            } else if (this.texture.available()) {
                this.texture.drawCentered(g, x, y, w, h);
            } else {
                this.scaledParagraph(g, "Wczytywanie materiału…", x + 4, y + 4, w - 8, 0.85F, -5653041);
            }

            if (this.player != null && this.player.warning() != null && !this.player.warning().isBlank()) {
                g.text(this.font, this.font.plainSubstrByWidth(this.player.warning(), w - 8), x + 4, y + h - 12, -800132, false);
            }

            g.disableScissor();
        }

        if (this.exam != null && !this.exam.canAnswer() && !this.abcLayout.answers().isEmpty()) {
            AbcQuestionLayout.Box first = this.abcLayout.answers().getFirst().box();
            this.scaledParagraph(g, "Odpowiedzi pojawią się po przygotowaniu materiału.", first.x() + 8, first.y() + 8, first.width() - 16, 0.9F, -5653041);
        }

        this.contentHeight = 0;
        this.scroll = 0;
    }

    private void scaledParagraph(GuiGraphicsExtractor g, String text, int x, int y, int w, float scale, int color) {
        List<FormattedCharSequence> lines = this.font.split(Component.literal(text), Math.max(1, (int)Math.floor(w / scale)));
        g.pose().pushMatrix();

        try {
            g.pose().translate(x, y);
            g.pose().scale(scale, scale);
            int lineY = 0;

            for (FormattedCharSequence line : lines) {
                g.text(this.font, line, 0, lineY, color, false);
                lineY += 9 + 1;
            }
        } finally {
            g.pose().popMatrix();
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        return this.question.basic() && super.mouseScrolled(x, y, horizontal, vertical);
    }

    private void finishExam() {
        if (!this.recordedExam) {
            this.recordedExam = true;
            ExamSession.Result result = this.exam.result().orElseThrow();
            this.app.recordExam(result);
            this.minecraft.gui.setScreen(new ExamResultsScreen(this.app, this.parent, result, this.mediaError));
        }
    }

    private void openExplanation() {
        List<String> lines = new ArrayList<>();
        lines.add("Pytanie " + this.question.id() + "\n" + this.question.text());
        lines.add("Twoja odpowiedź: " + (this.selected < 0 ? "Nie zaznaczono odpowiedzi." : this.choiceDescription(this.selected)));
        lines.add("Poprawna odpowiedź: " + this.choiceDescription(this.correctAnswerIndex()));
        if (this.reviewedAnswer != null && !this.reviewedAnswer.presented()) {
            lines.add("Tego pytania nie przedstawiono w zakończonej próbie; nie jest liczone jako popełniony błąd.");
        }

        if (this.reviewedAnswer != null && this.reviewedAnswer.correctIndex() != this.question.correctIndex()) {
            lines.add(
                "Klucz zapisany w tej próbie różni się od bieżącej bazy. Pokazano wynik z historii; omówienie nowszej wersji nie jest przypisywane do starej odpowiedzi."
            );
        } else {
            QuestionExplanations.Explanation explanation = QuestionExplanations.forQuestion(this.question, this.selected);
            lines.add((explanation.verified() ? "Sprawdzone omówienie" : "Dostępność omówienia") + "\n" + explanation.reason());
            if (!explanation.whySelectedIsWrong().isBlank()) {
                lines.add(explanation.whySelectedIsWrong());
            }

            for (QuestionExplanations.Source source : explanation.sources()) {
                lines.add(source.label() + "\n" + source.url());
            }
        }

        this.minecraft.gui.setScreen(new TextScreen("WORDcraft · Dlaczego ta odpowiedź?", lines, this));
    }

    public void stopMedia() {
        this.mediaGeneration++;
        this.mediaPending = false;
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }

        this.texture.close();
    }

    public void recordAbortedOnExit() {
        if (this.examDownloads != null) {
            this.examDownloads.stop();
        }

        if (this.exam != null && !this.recordedExam) {
            this.exam.abort(System.nanoTime());
            this.recordedExam = true;
            this.app.recordExam(this.exam.result().orElseThrow());
        }
    }

    public void deferForSafety() {
        this.deferred = true;
        this.stopMedia();
        this.minecraft.gui.setScreen(null);
    }

    @Override
    public void removed() {
        this.recordAbortedOnExit();
        this.stopMedia();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return this.exam == null;
    }

    @Override
    public void onClose() {
        if (this.exam != null && !this.recordedExam) {
            this.abortRequested = true;
            if (this.abort != null) {
                this.abort.setMessage(Component.literal("Potwierdź przerwanie"));
            }
        } else {
            this.stopMedia();
            super.onClose();
        }
    }

    private boolean canAnswer() {
        return this.mediaError.isBlank()
            && !this.mediaPending
            && !this.submitted
            && (this.exam == null ? this.player == null || this.player.isReady() : this.exam.canAnswer());
    }

    private boolean showReadingAction() {
        return this.exam.phase() == ExamSession.Phase.READING && this.selected < 0;
    }

    void useMediaFallback(MediaFallback var1) {
        this.mediaFallback = var1;
    }

    private Question findReplacement() {
        if (this.mediaFallback == null) {
            this.mediaFallback = new MediaFallback();
        }

        List<Question> var1 = this.exam != null ? this.exam.questions() : (this.practiceSession != null ? this.practiceSession.questions() : List.of());
        return this.mediaFallback
            .choose(
                this.app.usable(),
                this.question,
                var1,
                this.exam != null || this.practiceSession != null,
                this.exam != null,
                this.app::mediaPath,
                ThreadLocalRandom.current()
            )
            .orElse(null);
    }

    private void recoverMediaDownload() {
        if (this.reviewedAnswer != null) {
            this.mediaError = "Materiał do tego pytania jest chwilowo niedostępny.";
        } else {
            Question var1 = this.question;
            Question var2 = this.findReplacement();
            if (var2 == null) {
                this.stopMedia();
                this.app.message(this.parent, "Nie ma teraz dostępnego pytania zastępczego. Spróbuj ponownie później.");
            } else {
                if (this.exam != null) {
                    if (!this.exam.replaceCurrentQuestion(var2, System.nanoTime())) {
                        return;
                    }

                    if (this.examDownloads != null) {
                        this.examDownloads.replaceQuestion(var1, var2);
                    }
                } else if (this.practiceSession != null && !this.practiceSession.replaceCurrentQuestion(var2)) {
                    return;
                }

                this.stopMedia();
                this.question = var2;
                this.selected = -1;
                this.submitted = false;
                this.abortRequested = false;
                this.mediaError = "";
                this.scroll = 0;
                this.practiceVideoStarted = false;
                this.replay = null;
                if (var2.media().isBlank()) {
                    this.mediaFallback.succeeded();
                }

                this.rebuildWidgets();
            }
        }
    }

    private void loadMedia() {
        this.mediaPending = true;
        int var1 = ++this.mediaGeneration;

        CompletableFuture<Path> var2;
        try {
            var2 = this.examDownloads == null ? this.app.prepareMedia(this.question) : this.examDownloads.prepare(this.question);
        } catch (RuntimeException var4) {
            var2 = CompletableFuture.failedFuture(var4);
        }

        var2.whenComplete((var2x, var3) -> this.minecraft.execute(() -> {
            if (var1 == this.mediaGeneration && this.minecraft.gui.screen() == this) {
                this.mediaPending = false;
                if (var3 == null && var2x != null) {
                    if (this.mediaFallback != null) {
                        this.mediaFallback.succeeded();
                    }

                    this.player = new MediaPlayer();
                    this.player.load(var2x);
                } else {
                    this.recoverMediaDownload();
                }
            }
        }));
    }

    private final class AnswerCard extends Button {
        private final int index;
        private final AbcQuestionLayout.Answer layout;

        AnswerCard(int index, AbcQuestionLayout.Answer layout) {
            super(
                layout.box().x(),
                layout.box().y(),
                layout.box().width(),
                layout.box().height(),
                Component.literal((char)(65 + index) + ". " + QuestionScreen.this.question.answers().get(index)),
                b -> QuestionScreen.this.choose(index),
                DEFAULT_NARRATION
            );
            this.index = index;
            this.layout = layout;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor g, int mx, int my, float delta) {
            boolean correct = QuestionScreen.this.submitted && this.index == QuestionScreen.this.correctAnswerIndex();
            boolean wrong = QuestionScreen.this.submitted && this.index == QuestionScreen.this.selected && !correct;
            boolean chosen = this.index == QuestionScreen.this.selected;
            int accent = correct ? -9117498 : (wrong ? -1009005 : (chosen ? -7487489 : -13547171));
            int background = correct
                ? -14529472
                : (wrong ? -11916236 : (chosen ? -13940890 : (this.isHoveredOrFocused() && this.active ? -14336941 : -15194054)));
            g.fill(this.getX(), this.getY() + 2, this.getX() + this.getWidth(), this.getY() + this.getHeight() + 2, 1426063360);
            g.fill(this.getX(), this.getY(), this.getX() + this.getWidth(), this.getY() + this.getHeight(), background);
            g.outline(this.getX(), this.getY(), this.getWidth(), this.getHeight(), accent);
            g.fill(this.getX(), this.getY(), this.getX() + 3, this.getY() + this.getHeight(), !chosen && !correct && !wrong ? -7487489 : accent);
            g.enableScissor(this.getX() + 4, this.getY() + 1, this.getX() + this.getWidth() - 1, this.getY() + this.getHeight() - 1);
            g.text(
                QuestionScreen.this.font,
                String.valueOf((char)(65 + this.index)),
                this.getX() + 7,
                this.getY() + this.layout.padding(),
                correct ? -9117498 : (wrong ? -17731 : -7487489),
                false
            );
            QuestionScreen.this.scaledParagraph(
                g,
                QuestionScreen.this.question.answers().get(this.index),
                this.getX() + 24,
                this.getY() + this.layout.padding(),
                this.getWidth() - 30,
                this.layout.scale(),
                correct ? -9117498 : (wrong ? -17731 : -1182209)
            );
            g.disableScissor();
            if (!QuestionScreen.this.abcLayout.fullTextFits() && this.isHoveredOrFocused()) {
                g.setTooltipForNextFrame(QuestionScreen.this.font, this.getMessage(), mx, my);
            }
        }
    }
}
