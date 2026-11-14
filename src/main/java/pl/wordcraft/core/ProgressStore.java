package pl.wordcraft.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class ProgressStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path directory;
    private WordcraftConfig config = new WordcraftConfig();
    private LearningStats stats = new LearningStats();
    private final List<String> warnings = new ArrayList<>();

    public ProgressStore(Path directory) {
        this.directory = Objects.requireNonNull(directory);
    }

    public WordcraftConfig config() {
        return this.config;
    }

    public LearningStats stats() {
        return this.stats;
    }

    public List<String> warnings() {
        return List.copyOf(this.warnings);
    }

    public synchronized void load() throws IOException {
        Files.createDirectories(this.directory);
        this.warnings.clear();
        this.config = this.readRecovering("config.json", WordcraftConfig.class, WordcraftConfig::new);
        this.stats = this.readRecovering("progress.json", LearningStats.class, LearningStats::new);
        this.config.normalize();
        this.stats.normalize();
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(this.directory);
        this.config.normalize();
        String progressJson;
        synchronized (this.stats) {
            this.stats.normalize();
            progressJson = GSON.toJson(this.stats);
        }

        writeAtomic(this.directory.resolve("config.json"), GSON.toJson(this.config), WordcraftConfig.class, true);
        writeAtomic(this.directory.resolve("progress.json"), progressJson, LearningStats.class, true);
    }

    private <T> T readRecovering(String name, Class<T> type, Supplier<T> defaults) throws IOException {
        Path file = this.directory.resolve(name);
        Path backup = file.resolveSibling(name + ".bak");
        if (Files.exists(file)) {
            try {
                return parse(file, type);
            } catch (IOException | RuntimeException ex) {
                this.warnings.add(name + ": uszkodzony zapis; zachowano kopię do odzyskania.");
                preserveCorrupt(file);
            }
        }

        if (Files.exists(backup)) {
            try {
                T value = parse(backup, type);
                writeAtomic(file, GSON.toJson(value), type, false);
                this.warnings.add(name + ": odtworzono poprzedni poprawny zapis (.bak).");
                return value;
            } catch (IOException | RuntimeException ex) {
                this.warnings.add(name + ": kopia .bak również uszkodzona; użyto wartości domyślnych.");
            }
        }

        return defaults.get();
    }

    private static <T> T parse(Path file, Class<T> type) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement tree = JsonParser.parseReader(reader);
            if (!tree.isJsonObject()) {
                throw new JsonParseException("Expected JSON object");
            } else {
                T result = GSON.fromJson(tree, type);
                if (result == null) {
                    throw new JsonParseException("Missing object");
                } else {
                    return result;
                }
            }
        }
    }

    private static void preserveCorrupt(Path file) throws IOException {
        Path preserved = file.resolveSibling(file.getFileName() + ".corrupt-" + UUID.randomUUID());
        Files.move(file, preserved);
    }

    private static void writeAtomic(Path file, String json, Class<?> type, boolean backup) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(true);
        }

        if (backup && Files.exists(file)) {
            try {
                parse(file, type);
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            } catch (RuntimeException ex) {
                preserveCorrupt(file);
            }
        }

        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
