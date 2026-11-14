package pl.wordcraft.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import pl.wordcraft.core.*;

public final class BulkMediaFeature {
    private static WordcraftClient owner;
    private static BulkDownloader downloader;
    private static DownloadDisplay display;
    private static boolean registered;
    private BulkMediaFeature() {}

    public static synchronized BulkDownloader manager(WordcraftClient app) {
        if (owner != app || downloader == null) {
            if (downloader != null) downloader.close();
            owner = app;
            downloader = new BulkDownloader(app::prepareBulkMedia);
            display = new DownloadDisplay();
            BulkTransfers.attach(downloader);
        }
        return downloader;
    }
    public static synchronized BulkDownloader.Snapshot displaySnapshot(WordcraftClient app) {
        BulkDownloader live = manager(app);
        return display.sample(live.snapshot(), System.nanoTime());
    }
    public static synchronized void initialize(WordcraftClient app) {
        manager(app);
        if (registered) return;
        registered = true;
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("wordcraft", "bulk_download"), (graphics, delta) -> render(graphics));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (downloader != null) downloader.close();
            BulkTransfers.attach(null);
        });
    }
    public static synchronized void pauseForImport(WordcraftClient app) {
        if (owner == app && downloader != null) downloader.pause();
    }
    public static Path folder(WordcraftClient app) {
        String configured = app.source().mediaDirectory();
        return (configured == null || configured.isBlank() ? app.directory().resolve("official/media") : Path.of(configured)).toAbsolutePath().normalize();
    }
    public static void openFolder(WordcraftClient app, net.minecraft.client.gui.screens.Screen parent) {
        CompletableFuture.runAsync(() -> {
            try {
                Path path = folder(app);
                Files.createDirectories(path);
                Util.getPlatform().openPath(path);
            } catch (IOException | RuntimeException error) {
                Minecraft.getInstance().execute(() -> app.message(parent, "Nie można otworzyć folderu materiałów: " + folder(app)));
            }
        });
    }
    public static String title(BulkDownloader.Snapshot state) {
        return switch (state.state()) {
            case SCANNING -> "Sprawdzanie materiałów";
            case PAUSING -> "Wstrzymywanie pobierania";
            case PAUSED -> "Pobieranie wstrzymane";
            case COMPLETE -> "Materiały gotowe";
            case PARTIAL -> "Materiały: część niedostępna";
            default -> "Pobieranie materiałów";
        };
    }
    private static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (downloader == null || mc.level == null || mc.player == null || mc.gui.screen() != null || mc.gui.hud.isHidden()) return;
        BulkDownloader.Snapshot state = displaySnapshot(owner);
        if (state.state() == BulkDownloader.State.IDLE || state.state() == BulkDownloader.State.CLOSED) return;
        if (!state.state().busy() && System.nanoTime() - state.changedAtNanos() > 8_000_000_000L) return;
        int x = 6, y = Math.max(6, g.guiHeight() - 104), width = Math.min(280, g.guiWidth() - 12);
        if (width < 80) return;
        int accent = state.state() == BulkDownloader.State.COMPLETE ? 0xFF74D9AA : 0xFF80B7F1;
        g.fill(x, y, x + width, y + 50, 0xBF101725);
        g.text(mc.font, mc.font.plainSubstrByWidth(title(state) + " " + state.ready() + "/" + state.total(), width - 12), x + 6, y + 5, 0xFFFFFFFF, false);
        g.text(mc.font, mc.font.plainSubstrByWidth("ETA: " + DownloadFormat.eta(state.etaSeconds()) + " · " + DownloadFormat.speed(state.bytesPerSecond()), width - 12), x + 6, y + 16, 0xFFD3DCE8, false);
        String size = "Gotowe: " + DownloadFormat.bytes(state.readyDiskBytes()) + " / " + DownloadFormat.bytes(state.totalDiskBytes());
        if (state.failures() > 0) size += " · pominięte: " + state.failures();
        g.text(mc.font, mc.font.plainSubstrByWidth(size, width - 12), x + 6, y + 27, 0xFFADBACC, false);
        g.fill(x + 6, y + 40, x + width - 6, y + 44, 0xFF303D50);
        g.fill(x + 6, y + 40, x + 6 + (int)Math.round((width - 12) * state.fraction()), y + 44, accent);
    }
}
