package pl.wordcraft.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;

final class ManualImportScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private String excelValue;
    private String mediaValue;
    private Button importButton;

    ManualImportScreen(WordcraftClient app, Screen parent) {
        super("WORDcraft · Własne pliki", parent);
        this.app = app;
        this.excelValue = app.source().automatic() ? "" : app.source().excel();
        this.mediaValue = app.source().automatic() ? "" : app.source().mediaDirectory();
    }

    @Override
    protected void init() {
        int area = Math.min(480, this.width - 24);
        int left = (this.width - area) / 2;
        EditBox excel = this.addRenderableWidget(new EditBox(this.font, left + 8, 70, area - 16, 20, Component.literal("Oficjalny plik XLSX")));
        excel.setMaxLength(32767);
        excel.setValue(this.excelValue);
        excel.setHint(Component.literal("Ścieżka do oficjalnego pliku .xlsx"));
        excel.setResponder(v -> this.excelValue = v);
        excel.setTextColor(-1182209);
        EditBox media = this.addRenderableWidget(new EditBox(this.font, left + 8, 113, area - 16, 20, Component.literal("Katalog multimediów")));
        media.setMaxLength(32767);
        media.setValue(this.mediaValue);
        media.setHint(Component.literal("Katalog rozpakowanych zdjęć i filmów"));
        media.setResponder(v -> this.mediaValue = v);
        media.setTextColor(-1182209);
        this.importButton = this.themedButton(
            "Importuj / sprawdź",
            left + 8,
            145,
            area - 16,
            () -> this.app.importBank(this.excelValue, this.mediaValue),
            WordcraftScreen.Tone.PRIMARY,
            Items.CHEST
        );
        this.button("Wróć", this.width / 2 - 60, this.height - 25, 120, this::onClose);
    }

    @Override
    public void tick() {
        if (this.importButton != null) {
            this.importButton.active = this.app.ready() && !this.app.importing();
        }
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        for (Path path : files) {
            if (Files.isDirectory(path)) {
                this.mediaValue = path.toAbsolutePath().toString();
            } else if (path.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
                this.excelValue = path.toAbsolutePath().toString();
            }
        }

        this.rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        this.backdrop(g, "Opcjonalne — tryb automatyczny nie wymaga tych pól");
        int area = Math.min(480, this.width - 24);
        int left = (this.width - area) / 2;
        this.card(g, left, 45, area, 126);
        g.drawString(this.font, "Oficjalny arkusz pytań (.xlsx)", left + 8, 55, -5653041, false);
        g.drawString(this.font, "Folder oryginalnych zdjęć i filmów", left + 8, 98, -5653041, false);
        g.enableScissor(left, 179, left + area, this.height - 35);
        this.paragraph(g, this.app.status(), left, 179, area, -9117498);
        g.disableScissor();
        super.render(g, mx, my, delta);
    }
}
