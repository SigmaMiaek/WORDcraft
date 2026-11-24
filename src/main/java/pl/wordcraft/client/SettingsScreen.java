package pl.wordcraft.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import pl.wordcraft.client.ExamIntroScreen;
import pl.wordcraft.client.ManualImportScreen;
import pl.wordcraft.client.StatsScreen;
import pl.wordcraft.client.TextScreen;
import pl.wordcraft.client.WordcraftClient;
import pl.wordcraft.client.WordcraftScreen;
import pl.wordcraft.core.WordcraftConfig;

final class SettingsScreen
extends WordcraftScreen {
    private final WordcraftClient app;
    private Button automaticButton;
    private boolean preferences;
    private Button manualImport;
    private Button bulkButton;

    SettingsScreen(WordcraftClient app, Screen parent) {
        super("WORDcraft \u00b7 Nauka jazdy", parent);
        this.app = app;
    }

    protected void init() {
        this.manualImport = null;
        int area = Math.min(520, this.width - 24);
        int left = (this.width - area) / 2;
        int half = (area - 6) / 2;
        if (this.preferences) {
            this.themedButton("Mod: " + (this.app.config().enabled ? "w\u0142\u0105czony" : "wy\u0142\u0105czony"), left + 7, 62, half - 7, () -> {
                this.app.config().enabled = !this.app.config().enabled;
                this.app.save();
                this.rebuildWidgets();
            }, this.app.config().enabled ? WordcraftScreen.Tone.PRIMARY : WordcraftScreen.Tone.QUIET, Items.LEVER);
            this.themedButton("Co " + this.app.config().intervalMinutes + " min", left + half + 6, 62, half - 7, () -> {
                this.app.config().cycleInterval();
                this.app.save();
                this.rebuildWidgets();
            }, WordcraftScreen.Tone.SECONDARY, Items.CLOCK);
            this.themedButton("Tryb: " + (this.app.config().mode == WordcraftConfig.Mode.FREE ? "swobodny" : "egzaminacyjny"), left + 7, 86, half - 7, () -> {
                this.app.config().mode = this.app.config().mode == WordcraftConfig.Mode.FREE ? WordcraftConfig.Mode.EXAM : WordcraftConfig.Mode.FREE;
                this.app.save();
                this.rebuildWidgets();
            }, WordcraftScreen.Tone.QUIET);
            this.themedButton("Ochrona walki: " + (this.app.config().suppressCombat ? "TAK" : "NIE"), left + half + 6, 86, half - 7, () -> {
                this.app.config().suppressCombat = !this.app.config().suppressCombat;
                this.app.save();
                this.rebuildWidgets();
            }, WordcraftScreen.Tone.QUIET);
            this.automaticButton = this.themedButton(this.automaticLabel(), left + 7, 133, area - 14, this.app::prepareAutomatic, WordcraftScreen.Tone.SECONDARY, Items.COMPASS);
            this.themedButton("Raport bazy", left + 7, 157, half - 7, () -> this.minecraft.setScreen((Screen)new TextScreen("WORDcraft \u00b7 Baza pyta\u0144", this.app.audit().isEmpty() ? List.of(this.app.status()) : this.app.audit(), (Screen)this)), WordcraftScreen.Tone.QUIET, Items.PAPER);
            this.manualImport = this.themedButton("Import z plik\u00f3w\u2026", left + half + 6, 157, half - 7, () -> this.minecraft.setScreen((Screen)new ManualImportScreen(this.app, (Screen)this)), WordcraftScreen.Tone.QUIET, Items.CHEST);
            this.button("Wr\u00f3\u0107", this.width / 2 - 60, this.height - 26, 120, () -> {
                this.preferences = false;
                this.rebuildWidgets();
            });
        } else {
            this.themedButton("Pytanie r\u0119cznie", left, 108, half, () -> this.app.requestQuestion(this, false, false), WordcraftScreen.Tone.PRIMARY, Items.BOOK);
            this.themedButton("Statystyki i powt\u00f3rki", left + half + 6, 108, area - half - 6, () -> this.minecraft.setScreen((Screen)new StatsScreen(this.app, (Screen)this)), WordcraftScreen.Tone.SECONDARY, Items.EXPERIENCE_BOTTLE);
            this.themedButton("Rozpocznij pr\u00f3bny egzamin", left, 132, area, () -> this.minecraft.setScreen((Screen)new ExamIntroScreen(this.app, (Screen)this)), WordcraftScreen.Tone.AMBER, Items.GOLD_INGOT);
            this.automaticButton = this.themedButton(this.automaticLabel(), left, 167, half, this.app::prepareAutomatic, WordcraftScreen.Tone.QUIET);
            this.themedButton("Ustawienia nauki", left + half + 6, 167, area - half - 6, () -> {
                this.preferences = true;
                this.rebuildWidgets();
            }, WordcraftScreen.Tone.QUIET, Items.REDSTONE_TORCH);
            this.themedButton("Gotowe", this.width / 2 - 60, this.height - 26, 120, this::onClose, WordcraftScreen.Tone.PRIMARY);
        }
        this.addBulkControls();
        this.tick();
    }

    private void addBulkControls() {
        int area = Math.min(520, this.width - 24);
        int left = (this.width - area) / 2;
        int half = (area - 6) / 2;
        this.bulkButton = this.themedButton("Materiały (eksperymentalne)", left, 191, half,
                () -> this.minecraft.setScreen(new BulkDownloadScreen(this.app, this)), WordcraftScreen.Tone.AMBER);
        this.themedButton("Folder materiałów", left + half + 6, 191, area - half - 6,
                () -> BulkMediaFeature.openFolder(this.app, this), WordcraftScreen.Tone.QUIET, Items.CHEST);
    }

    private String automaticLabel() {
        return this.app.importing() ? "Przygotowywanie bazy\u2026" : (this.app.source().automatic() && !this.app.usable().isEmpty() ? "Sprawd\u017a baz\u0119 automatyczn\u0105" : "Przygotuj baz\u0119 automatycznie");
    }

    public void tick() {
        if (this.automaticButton != null) {
            this.automaticButton.active = !this.app.importing() && this.app.ready() && !BulkMediaFeature.manager(this.app).snapshot().state().busy();
            this.automaticButton.setMessage((Component)Component.literal((String)this.automaticLabel()));
        }
        boolean busy = BulkMediaFeature.manager(this.app).snapshot().state().busy();
        if (this.manualImport != null) this.manualImport.active = !busy && !this.app.importing();
        if (this.bulkButton != null) {
            pl.wordcraft.core.BulkDownloader.Snapshot state = BulkMediaFeature.displaySnapshot(this.app);
            this.bulkButton.setMessage(Component.literal(busy ? "Pobieranie " + state.ready() + "/" + state.total() : "Materiały (eksperymentalne)"));
        }
    }

    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.backdrop(g, this.preferences ? "Dopasuj nauk\u0119 do swojego rytmu" : "Kategoria B \u00b7 oficjalne pytania \u00b7 Twoje tempo");
        int area = Math.min(520, this.width - 24);
        int left = (this.width - area) / 2;
        if (this.preferences) {
            this.card(g, left, 43, area, 69);
            this.section(g, "NAUKA W GRZE", left + 7, 48, area - 14);
            this.card(g, left, 116, area, 67);
            this.section(g, "BAZA PYTA\u0143", left + 7, 120, area - 14);
        } else {
            this.card(g, left, 45, area, 40);
            this.itemIcon(g, this.app.usable().isEmpty() ? Items.COMPASS : Items.EMERALD, left + 9, 56);
            String state = this.app.importing() ? "Przygotowywanie bazy" : (this.app.usable().isEmpty() ? "Baza wymaga przygotowania" : "Baza gotowa \u00b7 " + this.app.usable().size() + " pyta\u0144");
            g.drawString(this.font, state, left + 33, 53, this.app.usable().isEmpty() ? -800132 : -9117498, false);
            g.drawString(this.font, this.font.plainSubstrByWidth(this.app.status(), area - 44), left + 33, 68, -5653041, false);
            this.section(g, "WYBIERZ, CO CHCESZ \u0106WICZY\u0106", left, 95, area);
        }
        super.render(g, mx, my, delta);
    }
}
