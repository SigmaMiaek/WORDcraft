package pl.wordcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.platform.InputConstants.Type;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.wordcraft.core.ActivePlayScheduler;
import pl.wordcraft.core.BulkTransfers;
import pl.wordcraft.core.ExamSession;
import pl.wordcraft.core.LearningStats;
import pl.wordcraft.core.MediaAsset;
import pl.wordcraft.core.PracticeSession;
import pl.wordcraft.core.ProgressStore;
import pl.wordcraft.core.WeightedQuestionPicker;
import pl.wordcraft.core.WordcraftConfig;
import pl.wordcraft.data.BankImporter;
import pl.wordcraft.data.Question;
import pl.wordcraft.download.OfficialRepository;

public final class WordcraftClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("WORDcraft");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static WordcraftClient INSTANCE;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WORDcraft-local-io");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService downloads = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "WORDcraft-official-downloads");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<String, CompletableFuture<Path>> mediaRequests = new ConcurrentHashMap<>();
    private final ActivePlayScheduler scheduler = new ActivePlayScheduler();
    private final WeightedQuestionPicker picker = new WeightedQuestionPicker();
    private final Random random = new Random();
    private Path directory;
    private ProgressStore store;
    private boolean initialized;
    private boolean importing;
    private boolean shuttingDown;
    private String status = "Wczytywanie lokalnych ustawień…";
    private volatile String downloadStatus = "";
    private OfficialRepository repository;
    private List<Question> questions = List.of();
    private List<Question> usable = List.of();
    private Map<String, Path> media = Map.of();
    private WordcraftClient.Source source = new WordcraftClient.Source("", "");
    private List<String> audit = List.of();
    private long unsafeUntil;
    private long lastThreatCheck;
    private boolean nearbyThreat;
    private PracticeSession delayedPractice;
    private KeyMapping settingsKey;
    private KeyMapping questionKey;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;
        this.directory = FabricLoader.getInstance().getConfigDir().resolve("wordcraft");
        if (System.getProperty("org.bytedeco.javacpp.cachedir") == null) {
            System.setProperty("org.bytedeco.javacpp.cachedir", this.directory.resolve("native-cache").toAbsolutePath().toString());
        }

        this.store = new ProgressStore(this.directory);
        this.repository = new OfficialRepository(this.directory.resolve("official"), (bytes, total, text) -> {
            String percent = total > 0L ? " " + Math.min(100L, bytes * 100L / total) + "%" : "";
            this.downloadStatus = text + percent;
        });
        String category = "key.category.wordcraft.learning";
        this.settingsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.wordcraft.settings", Type.KEYSYM, 75, category));
        this.questionKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.wordcraft.question", Type.KEYSYM, 74, category));
        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (screen instanceof TitleScreen || screen instanceof PauseScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("WORDcraft"), b -> this.openSettings(screen)).bounds(8, 8, 94, 20).build());
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            this.shuttingDown = true;
            this.downloads.shutdownNow();
            if (mc.screen instanceof QuestionScreen q) {
                q.recordAbortedOnExit();
                q.stopMedia();
            }

            this.io.execute(() -> {
                try {
                    this.store.save();
                } catch (IOException ex) {
                    LOG.error("Saving local progress", ex);
                }
            });
            this.io.shutdown();

            try {
                if (!this.io.awaitTermination(5L, TimeUnit.SECONDS)) {
                    LOG.warn("Local save is still finishing");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        this.io.execute(() -> {
            String warning = "";

            try {
                this.store.load();
                Path saved = this.directory.resolve("bank-source.json");
                if (Files.exists(saved)) {
                    try (BufferedReader reader = Files.newBufferedReader(saved, StandardCharsets.UTF_8)) {
                        WordcraftClient.Source parsed = JSON.fromJson(reader, WordcraftClient.Source.class);
                        if (parsed != null && parsed.excel() != null && parsed.mediaDirectory() != null) {
                            this.source = parsed;
                        }
                    }
                }

                warning = String.join("; ", this.store.warnings());
            } catch (Exception ex) {
                LOG.error("Reading local configuration", ex);
                warning = Objects.toString(ex.getMessage(), "Nie udało się odczytać ustawień.");
            }

            String finalWarning = warning;
            Minecraft.getInstance().execute(() -> {
                this.initialized = true;
                this.status = finalWarning.isBlank() ? "Przygotowuję oficjalną bazę pytań…" : finalWarning;
                if (this.source.setupVersion() > 0 && !this.source.automatic() && !this.source.excel().isBlank()) {
                    this.importBank(this.source.excel(), this.source.mediaDirectory());
                } else {
                    this.prepareAutomatic(false);
                }
            });
        });
        BulkMediaFeature.initialize(this);
    }

    private void tick(Minecraft mc) {
        long now = System.nanoTime();

        while (this.settingsKey.consumeClick()) {
            if (this.initialized && !(mc.screen instanceof QuestionScreen) && !(mc.screen instanceof ExamPreparationScreen)) {
                this.openSettings(mc.screen);
            }
        }

        while (this.questionKey.consumeClick()) {
            if (this.initialized && mc.screen == null) {
                this.requestQuestion(null, false, false);
            }
        }

        if (this.initialized) {
            boolean world = mc.level != null
                && mc.player != null
                && mc.player.isAlive()
                && mc.screen == null
                && mc.getOverlay() == null
                && !mc.isPaused()
                && mc.isWindowActive();
            this.updateThreats(mc, now);
            if (mc.level != null && mc.screen instanceof QuestionScreen q && q.isAutomaticPractice() && this.unsafeEnvironment(mc, now)) {
                PracticeSession session = q.practiceSession();
                this.delayedPractice = session == null || !q.unanswered() && !session.advance() ? null : session;
                q.deferForSafety();
            }

            if (this.store.config().mode != WordcraftConfig.Mode.FREE) {
                this.delayedPractice = null;
            }

            boolean safe = world && !this.unsafeEnvironment(mc, now);
            if (safe && this.delayedPractice != null && this.store.config().enabled && this.store.config().mode == WordcraftConfig.Mode.FREE) {
                PracticeSession session = this.delayedPractice;
                this.delayedPractice = null;
                mc.setScreen(QuestionScreen.automatic(this, null, session));
                this.scheduler.reset(now);
            } else if (this.scheduler
                .tick(now, world, safe && !this.usable.isEmpty() && this.store.config().mode == WordcraftConfig.Mode.FREE, this.store.config())) {
                this.requestQuestion(null, true, false);
            }
        }
    }

    private void updateThreats(Minecraft mc, long now) {
        if (mc.player != null && mc.level != null) {
            if (mc.player.hurtTime > 0 || mc.player.swinging || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) {
                this.unsafeUntil = now + TimeUnit.SECONDS.toNanos(10L);
            }

            if (now - this.lastThreatCheck > TimeUnit.SECONDS.toNanos(1L)) {
                this.lastThreatCheck = now;
                this.nearbyThreat = !mc.level.getEntitiesOfClass(Monster.class, mc.player.getBoundingBox().inflate(14.0), LivingEntity::isAlive).isEmpty();
            }
        } else {
            this.nearbyThreat = false;
        }
    }

    private boolean unsafeEnvironment(Minecraft mc, long now) {
        if (mc.player == null || !mc.player.isAlive()) {
            return true;
        } else {
            return !mc.player.isUsingItem()
                    && mc.player.onGround()
                    && !mc.player.isInWater()
                    && !mc.player.isInLava()
                    && !mc.player.isOnFire()
                    && !mc.player.isFallFlying()
                    && !mc.player.isPassenger()
                    && !mc.player.isSleeping()
                ? this.store.config().suppressCombat && (now < this.unsafeUntil || this.nearbyThreat)
                : true;
        }
    }

    public void openSettings(Screen parent) {
        Minecraft.getInstance().setScreen(new SettingsScreen(this, parent));
    }

    public void requestQuestion(Screen parent, boolean automatic, boolean reviewOnly) {
        List<Question> pool = reviewOnly ? this.usable.stream().filter(q -> this.stats().reviewIds().contains(q.id())).toList() : this.usable;
        if (automatic && !pool.isEmpty()) {
            PracticeSession session = PracticeSession.create(pool, this.picker, this.stats(), this.random);
            this.scheduler.reset(System.nanoTime());
            Minecraft.getInstance().setScreen(QuestionScreen.automatic(this, parent, session));
        } else {
            Optional<Question> chosen = this.picker.pick(pool, this.stats(), this.random);
            if (chosen.isEmpty()) {
                this.message(
                    parent,
                    reviewOnly
                        ? "Brak dostępnych pytań na liście powtórek."
                        : (
                            this.importing
                                ? "Przygotowuję bazę. Poczekaj chwilę — postęp zobaczysz w ustawieniach WORDcraft."
                                : "Baza nie jest jeszcze gotowa. W ustawieniach WORDcraft wybierz przygotowanie automatyczne."
                        )
                );
            } else {
                this.scheduler.reset(System.nanoTime());
                Minecraft.getInstance().setScreen(QuestionScreen.practice(this, parent, chosen.get(), automatic));
            }
        }
    }

    public void startExam(Screen parent) {
        try {
            ExamSession session = ExamSession.create(this.usable, this.random, System.nanoTime());
            Minecraft.getInstance().setScreen(new ExamPreparationScreen(this, parent, session));
        } catch (IllegalArgumentException ex) {
            this.message(parent, ex.getMessage());
        }
    }

    public void message(Screen parent, String text) {
        Minecraft.getInstance().setScreen(new TextScreen("WORDcraft", List.of(text), parent));
    }

    public void record(Question question, int selected) {
        this.stats().record(question, selected);
        this.save();
    }

    public void recordExam(ExamSession.Result result) {
        this.stats().addExamResult(result);
        this.save();
    }

    public void save() {
        if (!this.shuttingDown) {
            this.io.execute(() -> {
                try {
                    this.store.save();
                } catch (IOException ex) {
                    LOG.error("Saving local progress", ex);
                    Minecraft.getInstance().execute(() -> this.status = "Błąd zapisu postępów: " + ex.getMessage());
                }
            });
        }
    }

    public void importBank(String excel, String mediaDirectory) {
        BulkMediaFeature.pauseForImport(this);
        if (!this.importing && this.initialized) {
            this.downloadStatus = "";
            this.importing = true;
            this.status = "Sprawdzanie pliku Excel i powiązań multimediów…";
            WordcraftClient.Source requested = new WordcraftClient.Source(excel.strip(), mediaDirectory.strip());
            this.io.execute(() -> {
                try {
                    if (requested.excel().isBlank() || requested.mediaDirectory().isBlank()) {
                        throw new IOException("Podaj obie ścieżki.");
                    }

                    BankImporter.ImportResult result = new BankImporter().importFile(Path.of(requested.excel()), Path.of(requested.mediaDirectory()));
                    if (result.questions().isEmpty()) {
                        throw new IOException("Nie znaleziono poprawnych pytań kategorii B.");
                    }

                    List<Question> available = result.questions().stream().filter(q -> q.media().isBlank() || result.media().containsKey(q.media())).toList();
                    List<String> report = new ArrayList<>();
                    report.add("WORDcraft · lokalny audyt importu · " + Instant.now());
                    report.add("Źródło: " + requested.excel());
                    report.add("Katalog multimediów: " + requested.mediaDirectory());
                    report.add("Wiersze źródła: " + result.sourceRows());
                    report.add("Poprawne pytania B: " + result.questions().size());
                    report.add("Gotowe do prezentacji: " + available.size());
                    report.addAll(result.warnings());
                    Files.createDirectories(this.directory);
                    Path temporary = this.directory.resolve("bank-source.json.tmp");
                    Files.writeString(temporary, JSON.toJson(requested), StandardCharsets.UTF_8);

                    try {
                        Files.move(temporary, this.directory.resolve("bank-source.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(temporary, this.directory.resolve("bank-source.json"), StandardCopyOption.REPLACE_EXISTING);
                    }

                    Files.write(this.directory.resolve("import-report.txt"), report, StandardCharsets.UTF_8);
                    Minecraft.getInstance().execute(() -> {
                        this.delayedPractice = null;
                        this.source = requested;
                        this.questions = result.questions();
                        this.usable = available;
                        this.media = result.media();
                        this.audit = List.copyOf(report);
                        this.status = "Baza B: " + this.questions.size() + " · gotowe: " + this.usable.size() + " · ostrzeżenia: " + result.warnings().size();
                        this.importing = false;
                        LOG.info("{}", this.status);
                    });
                } catch (Exception ex) {
                    LOG.error("Import failed", ex);
                    Minecraft.getInstance().execute(() -> {
                        this.status = "Import nie powiódł się: " + ex.getMessage();
                        this.importing = false;
                    });
                }
            });
        }
    }

    public void prepareAutomatic() {
        this.prepareAutomatic(true);
    }

    private void prepareAutomatic(boolean refreshIndexes) {
        BulkMediaFeature.pauseForImport(this);
        if (!this.importing && this.initialized && !this.shuttingDown) {
            this.importing = true;
            this.downloadStatus = "";
            this.status = "Przygotowuję oficjalną bazę automatycznie…";
            this.downloads.execute(() -> {
                try {
                    OfficialRepository.PreparedBank bank = this.repository.prepare(refreshIndexes);
                    WordcraftClient.Source automaticSource = new WordcraftClient.Source(bank.excel().toString(), bank.mediaDirectory().toString(), true);
                    List<Question> available = bank.questions().stream().filter(q -> q.media().isBlank() || bank.media().containsKey(q.media())).toList();
                    List<String> report = new ArrayList<>();
                    report.add("WORDcraft · automatyczna baza Ministerstwa Infrastruktury");
                    report.add("Poprawne pytania kategorii B: " + bank.questions().size());
                    report.add("Dostępne do nauki: " + available.size());
                    report.add("Zdjęcia i filmy pobierają się automatycznie, gdy są potrzebne. Pobrane pliki pozostają lokalnie.");
                    report.addAll(bank.warnings());
                    this.io.execute(() -> {
                        try {
                            this.persistSource(automaticSource, report);
                        } catch (IOException ex) {
                            LOG.error("Saving automatic source", ex);
                        }
                    });
                    Minecraft.getInstance().execute(() -> {
                        if (!this.shuttingDown) {
                            this.source = automaticSource;
                            this.questions = bank.questions();
                            this.usable = available;
                            this.media = bank.media();
                            this.audit = List.copyOf(report);
                            this.delayedPractice = null;
                            this.importing = false;
                            this.downloadStatus = "";
                            this.status = "Baza gotowa: " + this.usable.size() + " pytań · materiały pobierają się same";
                            LOG.info("Automatic official bank ready: {} questions", this.usable.size());
                        }
                    });
                } catch (Exception ex) {
                    LOG.error("Automatic preparation failed", ex);
                    List<String> failureReport = new ArrayList<>(pl.wordcraft.core.PreparationFailure.report(ex, this.downloadStatus));
                    failureReport.addAll(Math.min(2, failureReport.size()), this.repository.httpsDiagnostics());
                    Minecraft.getInstance().execute(() -> {
                        this.importing = false;
                        this.downloadStatus = "";
                        this.status = failureReport.getFirst();
                        this.audit = List.copyOf(failureReport);
                    });
                }
            });
        }
    }

    private void persistSource(WordcraftClient.Source selected, List<String> report) throws IOException {
        Files.createDirectories(this.directory);
        Path temporary = this.directory.resolve("bank-source.json.tmp");
        Files.writeString(temporary, JSON.toJson(selected), StandardCharsets.UTF_8);

        try {
            Files.move(temporary, this.directory.resolve("bank-source.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporary, this.directory.resolve("bank-source.json"), StandardCopyOption.REPLACE_EXISTING);
        }

        Files.write(this.directory.resolve("import-report.txt"), report, StandardCharsets.UTF_8);
    }

    public WordcraftConfig config() {
        return this.store.config();
    }

    public LearningStats stats() {
        return this.store.stats();
    }

    public Path directory() {
        return this.directory;
    }

    public WordcraftClient.Source source() {
        return this.source;
    }

    public boolean importing() {
        return this.importing;
    }

    public boolean ready() {
        return this.initialized;
    }

    public String status() {
        return this.importing && !this.downloadStatus.isBlank() ? this.downloadStatus : this.status;
    }

    public String mediaStatus() {
        return this.downloadStatus.isBlank() ? "Pobieranie oryginalnego materiału…" : this.downloadStatus;
    }

    public List<String> audit() {
        return this.audit;
    }

    public Path mediaPath(Question q) {
        return this.media.get(q.media());
    }

    public Optional<Question> questionById(String id) {
        return this.questions.stream().filter(q -> q.id().equals(id)).findFirst();
    }

    public List<Question> usable() {
        return this.usable;
    }

    public CompletableFuture<Path> prepareMedia(Question var1) {
        return this.prepareMediaName(var1.media());
    }

    public CompletableFuture<Path> prepareBulkMedia(MediaAsset var1) {
        return this.source.automatic() && var1.target().equals(this.media.get(var1.name()))
            ? this.prepareMediaName(var1.name())
            : CompletableFuture.failedFuture(new IOException("Baza materiałów uległa zmianie. Otwórz ekran pobierania ponownie."));
    }

    private CompletableFuture<Path> prepareMediaName(String name) {
        Path local = media.get(name);
        if (local == null) {
            return CompletableFuture.failedFuture(new IOException("Nie ma oryginalnego materiału do tego pytania."));
        }
        if (!source.automatic()) return CompletableFuture.completedFuture(local);
        return mediaRequests.computeIfAbsent(name, key -> {
            CompletableFuture<Path> request = CompletableFuture.supplyAsync(() -> {
                try (BulkTransfers.Scope scope = BulkTransfers.scope(key)) {
                    return repository.ensureMedia(key);
                } catch (IOException error) {
                    throw new CompletionException(error);
                }
            }, downloads);
            request.whenComplete((result, failure) -> Minecraft.getInstance().execute(() -> {
                mediaRequests.remove(key, request);
                if (mediaRequests.isEmpty() && !importing) downloadStatus = "";
            }));
            return request;
        });
    }

    public List<MediaAsset> bulkAssets() {
        return this.source.automatic() && this.repository != null ? this.repository.mediaInventory() : List.of();
    }

    public record Source(String excel, String mediaDirectory, boolean automatic, int setupVersion) {
        public Source(String excel, String mediaDirectory) {
            this(excel, mediaDirectory, false, 1);
        }

        public Source(String excel, String mediaDirectory, boolean automatic) {
            this(excel, mediaDirectory, automatic, 1);
        }
    }
}
