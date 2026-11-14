package pl.wordcraft.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import pl.wordcraft.core.MediaAsset;
import pl.wordcraft.data.BankImporter;
import pl.wordcraft.data.Question;

public final class OfficialRepository {
    public static final URI CATALOG_URI = URI.create("https://www.gov.pl/attachment/a5c6c329-28a5-4274-a1a8-e2813f0a51bd");
    public static final String CATALOG_SHA256 = "1d569f59c48c1fb76afd95470b91062d7167305caef4f992a0ef9233dec23300";
    public static final List<URI> MEDIA_URIS = List.of(
        URI.create("https://www.gov.pl/pliki/mi/multimedia_do_pytan.zip"), URI.create("https://www.gov.pl/attachment/10d143bf-9e93-4d82-935d-48c89353d3ce")
    );
    private static final long MAX_CATALOG_BYTES = 16777216L;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("wordcraft-catalog-timeout").factory()
    );
    private final Path directory;
    private final Path catalogue;
    private final Path mediaDirectory;
    private final URI catalogueUri;
    private final String catalogueHash;
    private final List<URI> archiveUris;
    private final boolean testing;
    private final OfficialRepository.Progress progress;
    private final HttpClient http = OfficialHttp.client(Duration.ofSeconds(20L));
    private volatile Map<String, OfficialRepository.MediaSource> mediaSources = Map.of();
    private volatile String operation = "Przygotowywanie oficjalnej bazy pytań";

    public OfficialRepository(Path root, OfficialRepository.Progress listener) {
        this(root, listener, CATALOG_URI, "1d569f59c48c1fb76afd95470b91062d7167305caef4f992a0ef9233dec23300", MEDIA_URIS, false);
    }

    OfficialRepository(Path root, OfficialRepository.Progress listener, URI catalogUri, String sha256, List<URI> sources) {
        this(root, listener, catalogUri, sha256, sources, true);
    }

    private OfficialRepository(Path root, OfficialRepository.Progress listener, URI catalogUri, String sha256, List<URI> sources, boolean testing) {
        this.directory = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.catalogue = this.directory.resolve("KATALOG_072026.xlsx");
        this.mediaDirectory = this.directory.resolve("media");
        this.catalogueUri = Objects.requireNonNull(catalogUri, "catalogue URI");
        this.catalogueHash = Objects.requireNonNull(sha256, "catalogue hash").toLowerCase(Locale.ROOT);
        if (!this.catalogueHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid catalogue SHA-256");
        }

        this.archiveUris = List.copyOf(sources);
        if (!this.archiveUris.isEmpty() && this.archiveUris.size() <= 4) {
            this.testing = testing;
            this.validateSource(this.catalogueUri);
            this.archiveUris.forEach(this::validateSource);
            this.progress = listener == null ? (bytes, total, text) -> {} : listener;
        } else {
            throw new IllegalArgumentException("Invalid source count");
        }
    }

    public synchronized OfficialRepository.PreparedBank prepare() throws IOException {
        return this.prepare(false);
    }

    public synchronized OfficialRepository.PreparedBank prepare(boolean refreshIndexes) throws IOException {
        Files.createDirectories(this.directory);
        Files.createDirectories(this.mediaDirectory);
        BankImporter.ImportResult catalog = this.prepareCatalogue();
        Map<String, List<OfficialRepository.MediaSource>> available = new HashMap<>();

        for (int i = 0; i < this.archiveUris.size(); i++) {
            int part = i + 1;
            this.operation = "Przygotowywanie zdjęć i filmów (" + part + "/" + this.archiveUris.size() + ")";
            this.progress.update(0L, -1L, this.operation);
            RemoteZip archive = new RemoteZip(
                this.archiveUris.get(i), this.directory.resolve("indexes"), (bytes, total, text) -> this.progress.update(bytes, total, this.operation)
            );
            RemoteZip.Index index = refreshIndexes ? null : archive.cachedIndex().orElse(null);
            if (index == null) {
                index = archive.index();
            }

            for (RemoteZip.Entry entry : index.entries()) {
                String name = basename(entry.name());
                if (!name.isEmpty() && supportedMedia(name)) {
                    String normalized = normalize(name);
                    Path target = this.mediaPath(normalized);
                    available.computeIfAbsent(normalized, ignored -> new ArrayList<>()).add(new OfficialRepository.MediaSource(archive, entry, target));
                }
            }
        }

        Map<String, OfficialRepository.MediaSource> resolved = new LinkedHashMap<>();
        Map<String, Path> media = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(catalog.warnings());
        Set<String> missingNames = new HashSet<>();

        for (Question question : catalog.questions()) {
            if (!question.media().isEmpty()) {
                List<OfficialRepository.MediaSource> matches = available.getOrDefault(normalize(question.media()), List.of());
                if (matches.size() > 1) {
                    throw new IOException("Niejednoznaczne oficjalne multimedia dla pytania " + question.id() + ": " + question.media());
                }

                if (matches.isEmpty()) {
                    warnings.add(
                        "BRAK MULTIMEDIUM: pytanie "
                            + question.id()
                            + " wymaga pliku „"
                            + question.media()
                            + "”, którego nie ma w oficjalnych materiałach Ministerstwa. Pytanie jest wyłączone z prezentacji."
                    );
                    missingNames.add(question.media());
                } else {
                    OfficialRepository.MediaSource source = matches.getFirst();
                    resolved.put(question.media(), source);
                    media.put(question.media(), source.target());
                }
            }
        }

        warnings.add(
            "Audyt: "
                + catalog.sourceRows()
                + " wierszy katalogu, "
                + catalog.questions().size()
                + " poprawnych pytań B, "
                + media.size()
                + " dostępnych plików multimediów, "
                + missingNames.size()
                + " brakujących różnych plików. Multimedia pobierają się automatycznie przed pytaniem."
        );
        this.mediaSources = Map.copyOf(resolved);
        this.progress.update(1L, 1L, "Oficjalna baza pytań jest gotowa");
        return new OfficialRepository.PreparedBank(catalog.questions(), media, warnings, catalog.sourceRows(), this.catalogue, this.mediaDirectory);
    }

    public boolean canResolve(String mediaName) {
        return mediaName != null && this.mediaSources.containsKey(mediaName);
    }

    public synchronized Path ensureMedia(String mediaName) throws IOException {
        OfficialRepository.MediaSource source = this.mediaSources.get(mediaName);
        if (source == null) {
            throw new IOException("Brak oficjalnego pliku multimedialnego: " + mediaName);
        }

        this.operation = "Pobieranie materiału do pytania: " + mediaName;
        this.progress.update(0L, source.entry().compressedSize(), this.operation);

        try {
            return source.archive().extract(source.entry(), source.target());
        } catch (IOException ex) {
            if (Objects.toString(ex.getMessage(), "").contains("Remote archive changed")) {
                throw new IOException("Ministerstwo zaktualizowało materiały. Przygotuj bazę ponownie w ustawieniach WORDcraft.", ex);
            } else {
                throw ex;
            }
        }
    }

    private BankImporter.ImportResult prepareCatalogue() throws IOException {
        BankImporter importer = new BankImporter();
        if (Files.isRegularFile(this.catalogue, LinkOption.NOFOLLOW_LINKS)
            && Files.size(this.catalogue) <= 16777216L
            && sha256(this.catalogue).equals(this.catalogueHash)) {
            this.progress.update(1L, 1L, "Wczytywanie zapisanej oficjalnej bazy pytań");
            return importer.readCatalog(this.catalogue);
        }

        this.progress.update(0L, -1L, "Pobieranie oficjalnej bazy pytań Ministerstwa Infrastruktury");
        Path temporary = Files.createTempFile(this.directory, "catalogue-", ".part");

        try {
            this.downloadCatalogue(temporary);
            if (!sha256(temporary).equals(this.catalogueHash)) {
                throw new IOException("Pobrana baza pytań ma nieprawidłową sumę kontrolną. Spróbuj ponownie; oryginalna baza nie została zmieniona.");
            }

            BankImporter.ImportResult parsed = importer.readCatalog(temporary);
            atomicMove(temporary, this.catalogue);
            return parsed;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void downloadCatalogue(Path target) throws IOException {
        URI uri = this.catalogueUri;

        for (int redirect = 0; redirect <= 3; redirect++) {
            try {
                this.validateSource(uri);
            } catch (IllegalArgumentException ex) {
                throw new IOException("Serwer przekierował pobieranie poza oficjalne źródło Ministerstwa.", ex);
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(90L))
                .header("User-Agent", "WORDcraft/1.0 (official learning materials)")
                .header("Accept-Encoding", "identity")
                .GET()
                .build();

            HttpResponse<InputStream> response;
            try {
                response = this.http.send(request, BodyHandlers.ofInputStream());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Pobieranie bazy pytań zostało przerwane.", ex);
            }

            try (InputStream input = response.body()) {
                ScheduledFuture<?> timeout = TIMEOUTS.schedule(() -> {
                    try {
                        input.close();
                    } catch (IOException var2x) {
                    }
                }, 90L, TimeUnit.SECONDS);

                try {
                    if (!Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                        if (response.statusCode() != 200) {
                            throw new IOException("Serwer Ministerstwa zwrócił błąd " + response.statusCode() + ". Spróbuj ponownie później.");
                        }

                        long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
                        if (total > 16777216L) {
                            throw new IOException("Oficjalny arkusz przekracza dozwolony rozmiar.");
                        }

                        long count = 0L;

                        try (OutputStream output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[65536];

                            int n;
                            while ((n = input.read(buffer)) != -1) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new IOException("Pobieranie przerwane.");
                                }

                                count += n;
                                if (count > 16777216L) {
                                    throw new IOException("Oficjalny arkusz przekracza dozwolony rozmiar.");
                                }

                                output.write(buffer, 0, n);
                                this.progress.update(count, total, "Pobieranie oficjalnej bazy pytań Ministerstwa Infrastruktury");
                            }
                        }

                        if (total >= 0L && count != total) {
                            throw new IOException("Pobrano niepełny arkusz pytań. Spróbuj ponownie.");
                        }

                        return;
                    }

                    String location = response.headers()
                        .firstValue("location")
                        .orElseThrow(() -> new IOException("Brak adresu przekierowania oficjalnej bazy."));
                    uri = uri.resolve(location);
                } finally {
                    timeout.cancel(false);
                }
            }
        }

        throw new IOException("Zbyt wiele przekierowań serwera oficjalnej bazy pytań.");
    }

    private void validateSource(URI uri) {
        boolean official = "https".equalsIgnoreCase(uri.getScheme()) && "www.gov.pl".equalsIgnoreCase(uri.getHost()) && uri.getPort() == -1;
        boolean loopback = this.testing && "http".equalsIgnoreCase(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        if (!official && !loopback || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Niedozwolone źródło oficjalnych materiałów.");
        }
    }

    private Path mediaPath(String normalized) {
        String extension = normalized.substring(normalized.lastIndexOf(46));
        String hash = HexFormat.of().formatHex(digest().digest(normalized.getBytes(StandardCharsets.UTF_8)));
        return this.mediaDirectory.resolve(hash + extension);
    }

    private static String basename(String name) {
        return name.substring(Math.max(name.lastIndexOf(47), name.lastIndexOf(92)) + 1);
    }

    private static boolean supportedMedia(String name) {
        return normalize(name).matches(".+\\.(jpg|jpeg|png|bmp|gif|wmv|mp4|m4v|avi|mov|mkv|webm)");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest hash = digest();

        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];

            int n;
            while ((n = input.read(buffer)) != -1) {
                hash.update(buffer, 0, n);
            }
        }

        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<MediaAsset> mediaInventory() {
        LinkedHashMap<Path, MediaAsset> var1 = new LinkedHashMap<>();

        for (Entry<String, OfficialRepository.MediaSource> var2 : this.mediaSources.entrySet()) {
            OfficialRepository.MediaSource var4 = var2.getValue();
            Path var5 = var4.target().toAbsolutePath().normalize();
            var1.putIfAbsent(var5, new MediaAsset(var2.getKey(), var5, var4.entry().compressedSize(), var4.entry().size()));
        }

        ArrayList<MediaAsset> var6 = new ArrayList<>(var1.values());
        var6.sort(Comparator.comparing(MediaAsset::name));
        return List.copyOf(var6);
    }

    private record MediaSource(RemoteZip archive, RemoteZip.Entry entry, Path target) {
    }

    public record PreparedBank(List<Question> questions, Map<String, Path> media, List<String> warnings, int sourceRows, Path excel, Path mediaDirectory) {
        public PreparedBank {
            questions = List.copyOf(questions);
            media = Map.copyOf(media);
            warnings = List.copyOf(warnings);
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(long var1, long var3, String var5);
    }
}
