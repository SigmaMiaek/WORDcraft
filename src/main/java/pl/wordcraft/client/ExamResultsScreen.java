package pl.wordcraft.client;

import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.Items;
import pl.wordcraft.core.ExamSession;

final class ExamResultsScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private final ExamSession.Result result;
    private final String mediaError;
    private final int errors;
    private final int correct;
    private final int notPresented;

    ExamResultsScreen(WordcraftClient app, Screen parent, ExamSession.Result result, String mediaError) {
        super("WORDcraft · Wynik egzaminu", parent);
        this.app = app;
        this.result = result;
        this.mediaError = mediaError == null ? "" : mediaError;
        this.errors = (int)result.answers().stream().filter(a -> a.presented() && !a.correct()).count();
        this.correct = (int)result.answers().stream().filter(a -> a.presented() && a.correct()).count();
        this.notPresented = (int)result.answers().stream().filter(a -> !a.presented()).count();
    }

    @Override
    protected void init() {
        int area = Math.min(500, this.width - 24);
        int left = (this.width - area) / 2;
        int top = this.contentTop();
        this.themedButton(
                "Przejrzyj błędy (" + this.errors + ")",
                left,
                top + 108,
                area,
                () -> this.minecraft.setScreen(new ExamReviewScreen(this.app, this, this.result, true)),
                this.errors > 0 ? WordcraftScreen.Tone.DANGER : WordcraftScreen.Tone.QUIET,
                Items.REDSTONE
            )
            .active = this.errors > 0;
        this.themedButton(
                "Wszystkie pytania (" + this.result.answers().size() + ")",
                left,
                top + 132,
                area,
                () -> this.minecraft.setScreen(new ExamReviewScreen(this.app, this, this.result, false)),
                WordcraftScreen.Tone.SECONDARY,
                Items.BOOK
            )
            .active = !this.result.answers().isEmpty();
        if (this.mediaError.isBlank()) {
            this.button("Wróć", left, this.height - 26, area, this::onClose);
        } else {
            int half = (area - 4) / 2;
            this.themedButton(
                "Szczegóły przerwania",
                left,
                this.height - 26,
                half,
                () -> this.minecraft
                    .setScreen(
                        new TextScreen(
                            "WORDcraft · Przerwany egzamin", List.of(this.mediaError, "Zapisano odpowiedzi zatwierdzone przed przerwaniem egzaminu."), this
                        )
                    ),
                WordcraftScreen.Tone.AMBER
            );
            this.button("Wróć", left + half + 4, this.height - 26, area - half - 4, this::onClose);
        }
    }

    private int contentTop() {
        return Math.max(46, (this.height - 178) / 2);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.backdrop(g, "Wynik zapisany · przeglądanie odpowiedzi nie zmienia postępów");
        int area = Math.min(500, this.width - 24);
        int left = (this.width - area) / 2;
        int top = this.contentTop();
        int accent = this.result.aborted() ? -800132 : (this.result.passed() ? -9117498 : -1009005);
        this.card(g, left, top, area, 57);
        g.fill(left + 1, top + 1, left + 4, top + 56, accent);
        this.itemIcon(g, this.result.passed() ? Items.EMERALD : (this.result.aborted() ? Items.CLOCK : Items.REDSTONE), left + 11, top + 11);
        String state = this.result.aborted() ? "Egzamin przerwany" : (this.result.passed() ? "Egzamin zaliczony" : "Egzamin niezaliczony");
        g.drawString(this.font, state, left + 34, top + 10, accent, false);
        g.drawString(this.font, this.result.score() + " / " + this.result.maxScore() + " pkt · próg 68 pkt", left + 34, top + 25, -1182209, false);
        String note = this.notPresented > 0
            ? "Nieprzedstawione pytania: " + this.notPresented + " (poza listą błędów)"
            : "Możesz teraz sprawdzić odpowiedzi i ich wyjaśnienia.";
        g.drawString(this.font, this.font.plainSubstrByWidth(note, area - 24), left + 12, top + 42, -5653041, false);
        int tileWidth = (area - 8) / 3;
        this.metric(g, left, top + 64, tileWidth, "Poprawne", Integer.toString(this.correct), -9117498);
        this.metric(g, left + tileWidth + 4, top + 64, tileWidth, "Błędy / brak", Integer.toString(this.errors), -1009005);
        long seconds = Math.max(0L, this.result.elapsedMillis() / 1000L);
        this.metric(
            g,
            left + 2 * (tileWidth + 4),
            top + 64,
            area - 2 * (tileWidth + 4),
            "Czas",
            seconds / 60L + ":" + String.format(Locale.ROOT, "%02d", seconds % 60L),
            -7487489
        );
        super.render(g, mx, my, delta);
    }

    private void metric(GuiGraphics g, int x, int y, int w, String label, String value, int color) {
        this.card(g, x, y, w, 36);
        g.drawString(this.font, label, x + 8, y + 6, -5653041, false);
        g.drawString(this.font, value, x + 8, y + 20, color, false);
    }
}
