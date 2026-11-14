package pl.wordcraft.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

final class ExamIntroScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private static final List<String> RULES = List.of(
        "32 pytania: 20 podstawowych (TAK/NIE) i 12 specjalistycznych (A/B/C). Jedna odpowiedź jest poprawna.",
        "Maksymalnie 74 punkty. Aby zaliczyć, zdobądź co najmniej 68 punktów. Błędna lub niewskazana odpowiedź daje 0 punktów.",
        "Podstawowe: 20 sekund na przeczytanie treści, następnie materiał i 15 sekund na odpowiedź. Film odtwarza się tylko raz, w całości; potem pozostaje ostatnia klatka. Nie ma powtórnego odtwarzania.",
        "Mod przygotuje pierwsze zadanie i od razu je otworzy. Reszta materiałów pobiera się w tle; wskaźnik pokazuje postęp. Oczekiwanie na materiał bieżącego pytania wstrzymuje zegary. Specjalistyczne: 50 sekund na przeczytanie i odpowiedź. Limit egzaminu: 25 minut bez technicznego oczekiwania; czytanie, oglądanie i odpowiadanie zużywają czas.",
        "Możesz zmieniać wybór przed zatwierdzeniem. Przejście dalej lub upływ czasu zatwierdza odpowiedź. Nie można wrócić do poprzedniego pytania. Po zakończeniu wybierz Przejrzyj błędy lub Wszystkie pytania; podgląd pokazuje Twoją i poprawną odpowiedź oraz dostępne, sprawdzone wyjaśnienia. Dla części pytań nie ma jeszcze uzasadnień.",
        "W multiplayer świat i inni gracze nadal działają. Egzamin nie zatrzymuje walki ani nie chroni postaci. Uruchom go w bezpiecznym miejscu. W singleplayer ekran może wstrzymać świat, ale zegar egzaminu nadal biegnie.",
        "Wyjście z rozpoczętego egzaminu zapisuje próbę jako przerwaną. WORDcraft to lokalna pomoc w nauce kategorii B; nie jest egzaminem państwowym. Pauza na przygotowanie materiału jest udogodnieniem tej aplikacji."
    );

    ExamIntroScreen(WordcraftClient app, Screen parent) {
        super("WORDcraft · Próbny egzamin", parent);
        this.app = app;
    }

    @Override
    protected void init() {
        int area = Math.min(520, this.width - 24);
        int left = (this.width - area) / 2;
        int half = (area - 4) / 2;
        this.themedButton(
                "Rozpocznij egzamin", left, this.height - 27, half, () -> this.app.startExam(this.parent), WordcraftScreen.Tone.AMBER, Items.GOLD_INGOT
            )
            .active = !this.app.usable().isEmpty();
        this.button("Wróć", left + half + 4, this.height - 27, area - half - 4, this::onClose);
        this.scrollControls(this.height - 38);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        this.backdrop(g, "Kat. B · 32 pytania · próg 68/74 · 25 minut");
        g.enableScissor(10, 42, this.width - 28, this.height - 38);
        int y = 45 - this.scroll;

        for (int i = 0; i < RULES.size(); i++) {
            int textWidth = this.width - 76;
            int lines = this.font.split(Component.literal(RULES.get(i)), Math.max(40, textWidth)).size();
            int cardHeight = lines * (9 + 2) + 14;
            this.card(g, 14, y, this.width - 46, cardHeight);
            g.fill(15, y + 1, 17, y + cardHeight - 1, i == 5 ? -800132 : -13547171);

            this.itemIcon(g, switch (i) {
                case 0 -> Items.BOOK;
                case 1 -> Items.GOLD_INGOT;
                case 2, 3 -> Items.CLOCK;
                case 4 -> Items.PAPER;
                case 5 -> Items.SHIELD;
                default -> Items.COMPASS;
            }, 21, y + 7);
            this.paragraph(g, RULES.get(i), 43, y + 7, textWidth, i == 5 ? -800132 : -1182209);
            y += cardHeight + 7;
        }

        if (this.app.usable().isEmpty()) {
            y = this.paragraph(
                    g,
                    "Mod przygotowuje oficjalną bazę automatycznie. Wróć do ustawień WORDcraft, aby zobaczyć postęp lub ponowić przygotowanie.",
                    14,
                    y,
                    this.width - 52,
                    -800132
                )
                + 8;
        }

        this.contentHeight = y + this.scroll - 42;
        g.disableScissor();
        this.scrollBar(g, this.height - 38);
        super.extractRenderState(g, mx, my, delta);
    }
}
