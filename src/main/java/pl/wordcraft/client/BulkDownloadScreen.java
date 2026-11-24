package pl.wordcraft.client;

import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pl.wordcraft.core.*;

final class BulkDownloadScreen extends WordcraftScreen {
    private final WordcraftClient app;
    private final BulkDownloader downloader;
    private final List<MediaAsset> assets;
    private final long diskBytes;
    private final long networkBytes;
    private final long temporaryBytes;
    private Button start;
    BulkDownloadScreen(WordcraftClient app, Screen parent) {
        super("WORDcraft · Materiały", parent);
        this.app = app;
        downloader = BulkMediaFeature.manager(app);
        assets = app.bulkAssets();
        long disk = 0, network = 0, temporary = 0;
        for (MediaAsset asset : assets) {
            disk += asset.diskBytes();
            network += asset.downloadBytes();
            temporary = Math.max(temporary, asset.diskBytes() + asset.downloadBytes());
        }
        diskBytes = disk;
        networkBytes = network;
        temporaryBytes = temporary;
    }
    @Override protected void init() {
        int area = Math.min(520, width - 24), left = (width - area) / 2, half = (area - 6) / 2;
        start = themedButton("Rozpocznij pobieranie", left, height - 52, half, () -> {
            if (downloader.snapshot().state().busy()) downloader.pause();
            else downloader.start(assets);
            tick();
        }, Tone.PRIMARY);
        themedButton("Otwórz folder materiałów", left + half + 6, height - 52, area - half - 6,
                () -> BulkMediaFeature.openFolder(app, this), Tone.QUIET);
        themedButton("Wróć", width / 2 - 60, height - 26, 120, this::onClose, Tone.QUIET);
        scrollControls(height - 60);
        tick();
    }
    @Override public void tick() {
        if (start == null) return;
        BulkDownloader.Snapshot state = downloader.snapshot();
        String label = state.state().busy() ? state.state() == BulkDownloader.State.PAUSING ? "Wstrzymywanie…" : "Wstrzymaj"
                : state.state() == BulkDownloader.State.IDLE ? "Rozpocznij pobieranie"
                : state.state() == BulkDownloader.State.COMPLETE ? "Sprawdź ponownie" : "Wznów / ponów pobieranie";
        start.setMessage(Component.literal(label));
        start.active = !assets.isEmpty() && !app.importing() && state.state() != BulkDownloader.State.PAUSING;
    }
    @Override public void render(GuiGraphics g, int mx, int my, float delta) {
        backdrop(g, "POBIERANIE WSZYSTKICH MATERIAŁÓW · EKSPERYMENTALNE");
        int area = Math.min(520, width - 24), left = (width - area) / 2;
        int bottom = height - 60;
        g.enableScissor(left, 42, left + area, bottom);
        int y = 47 - scroll;
        y = paragraph(g, "Uwaga: wszystkie materiały zajmą około " + DownloadFormat.bytes(diskBytes)
                + " na dysku (" + assets.size() + " plików).", left + 4, y, area - 8, AMBER) + 5;
        y = paragraph(g, "Dane do pobrania: około " + DownloadFormat.bytes(networkBytes)
                + ". Pliki tymczasowe: do " + DownloadFormat.bytes(temporaryBytes) + ".", left + 4, y, area - 8, MUTED) + 5;
        y = paragraph(g, "Opcja eksperymentalna. Pobrane pliki są sprawdzane i używane ponownie. Możesz zamknąć to okno i grać — postęp zobaczysz w lewym dolnym rogu.",
                left + 4, y, area - 8, TEXT) + 6;
        BulkDownloader.Snapshot state = BulkMediaFeature.displaySnapshot(app);
        if (assets.isEmpty()) {
            y = paragraph(g, "Najpierw przygotuj automatyczną bazę pytań.", left + 4, y, area - 8, AMBER);
        } else if (state.state() != BulkDownloader.State.IDLE) {
            y = paragraph(g, BulkMediaFeature.title(state) + " " + state.ready() + "/" + state.total(), left + 4, y, area - 8, MINT) + 3;
            y = paragraph(g, "ETA: " + DownloadFormat.eta(state.etaSeconds()) + " · " + DownloadFormat.speed(state.bytesPerSecond())
                    + " · gotowe " + DownloadFormat.bytes(state.readyDiskBytes()) + " / " + DownloadFormat.bytes(state.totalDiskBytes()), left + 4, y, area - 8, TEXT) + 5;
            g.fill(left + 4, y, left + area - 4, y + 5, BORDER);
            g.fill(left + 4, y, left + 4 + (int)Math.round((area - 8) * state.fraction()), y + 5, MINT);
            y = paragraph(g, state.message(), left + 4, y + 10, area - 8, MUTED);
        }
        contentHeight = y + scroll - 42;
        g.disableScissor();
        super.render(g, mx, my, delta);
    }
}
