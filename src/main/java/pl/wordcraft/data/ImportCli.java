package pl.wordcraft.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ImportCli {
    private ImportCli() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            throw new IllegalArgumentException("Użycie: ImportCli <oficjalny.xlsx> <katalog-multimediów> <katalog-wyników>");
        }

        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path mediaDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        BankImporter.ImportResult result = new BankImporter().importFile(source, mediaDirectory);
        Files.createDirectories(output);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        Map<String, String> media = new LinkedHashMap<>();
        result.media().forEach((key, path) -> media.put(key, path.toString()));
        write(output.resolve("bank.json"), gson.toJson(result.questions()));
        write(output.resolve("media-index.json"), gson.toJson(media));
        write(output.resolve("import-source.json"), gson.toJson(Map.of("excel", source.toString(), "mediaDirectory", mediaDirectory.toString())));
        String summary = "WORDcraft — lokalny audyt importu\nŹródło: "
            + source
            + "\nMultimedia: "
            + mediaDirectory
            + "\nWiersze katalogu: "
            + result.sourceRows()
            + "\nPoprawne pytania B: "
            + result.questions().size()
            + "\nPowiązane różne pliki: "
            + result.media().size()
            + "\n\n"
            + String.join("\n", result.warnings())
            + "\n";
        write(output.resolve("import-report.txt"), summary);
        System.out.println(summary);
    }

    private static void write(Path path, String text) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");

        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);

            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
