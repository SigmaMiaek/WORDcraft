import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.wordcraft.core.ActivePlayScheduler;
import pl.wordcraft.core.ProgressStore;
import pl.wordcraft.core.WordcraftConfig;
import pl.wordcraft.data.BankImporter;
import pl.wordcraft.data.Question;

class RecoveredCoreTest {
    @TempDir Path directory;

    @Test void importsCategoryBAndRetainsMissingMediaInAudit() throws Exception {
        Path catalogue = catalogue("photo.jpg");
        var result = new BankImporter().importFile(catalogue, directory);
        assertEquals(2, result.sourceRows());
        assertEquals(1, result.questions().size());
        Question question = result.questions().getFirst();
        assertEquals("1", question.id());
        assertEquals(List.of("TAK", "NIE"), question.answers());
        assertEquals(0, question.correctIndex());
        assertEquals("photo.jpg", question.media());
        assertTrue(result.media().isEmpty());
        Files.write(directory.resolve("photo.jpg"), new byte[] {1, 2, 3});
        var withMedia = new BankImporter().importFile(catalogue, directory);
        assertEquals(directory.resolve("photo.jpg"), withMedia.media().get("photo.jpg"));
    }

    @Test void rejectsMediaPathsOutsideImportFolder() throws Exception {
        Path catalogue = catalogue("../outside.jpg");
        assertThrows(IOException.class, () -> new BankImporter().readCatalog(catalogue));
    }

    @Test void progressRoundTripsAndRecoversBackupAfterCorruption() throws Exception {
        ProgressStore first = new ProgressStore(directory);
        first.load();
        Question question = new Question("1", "Synthetic question?", List.of("Yes", "No"), 0, true, 3, "", "");
        first.config().intervalMinutes = 5;
        first.stats().record(question, 1);
        first.save();
        ProgressStore loaded = new ProgressStore(directory);
        loaded.load();
        assertEquals(5, loaded.config().intervalMinutes);
        assertEquals(1, loaded.stats().solvedCount());
        assertTrue(loaded.stats().needsErrorReview("1"));
        for (int i = 0; i < 3; i++) loaded.stats().record(question, 0);
        assertFalse(loaded.stats().isMarkedForReview("1"));
        loaded.save();
        Files.writeString(directory.resolve("progress.json"), "{broken");
        ProgressStore recovered = new ProgressStore(directory);
        recovered.load();
        assertEquals(1, recovered.stats().solvedCount());
        assertFalse(recovered.warnings().isEmpty());
        try (var files = Files.list(directory)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().startsWith("progress.json.corrupt-")));
        }
    }

    @Test void schedulerCountsActivePlayAndWaitsForSafeMoment() {
        WordcraftConfig config = new WordcraftConfig();
        config.intervalMinutes = 5;
        ActivePlayScheduler scheduler = new ActivePlayScheduler();
        long minute = TimeUnit.MINUTES.toNanos(1);
        assertFalse(scheduler.tick(0, true, true, config));
        assertFalse(scheduler.tick(4 * minute, true, true, config));
        assertFalse(scheduler.tick(5 * minute, true, false, config));
        assertTrue(scheduler.tick(5 * minute, true, true, config));
        assertFalse(scheduler.tick(6 * minute, false, true, config));
        assertFalse(scheduler.tick(60 * minute, false, true, config));
        assertFalse(scheduler.tick(61 * minute, true, true, config));
        assertEquals(0, scheduler.elapsedMillis());
        assertTrue(scheduler.tick(66 * minute, true, true, config));
    }

    // A tiny synthetic XLSX fixture; no official questions or learning media.
    private Path catalogue(String media) throws IOException {
        List<String> headers = List.of("Numer pytania", "Pytanie", "Odpowiedź A", "Odpowiedź B", "Odpowiedź C",
                "Poprawna odp", "Media", "Zakres struktury", "Liczba punktów", "Kategorie");
        String rows = row(headers) + row(List.of("1", "Synthetic question?", "", "", "", "T", media, "Podstawowy", "3", "B"))
                + row(List.of("2", "Other category", "", "", "", "N", "", "Podstawowy", "1", "A"));
        Path file = directory.resolve("synthetic.xlsx");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "xl/workbook.xml", "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"katalog\" r:id=\"rId1\"/></sheets></workbook>");
            entry(zip, "xl/_rels/workbook.xml.rels", "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
            entry(zip, "xl/worksheets/sheet1.xml", "<worksheet><sheetData>" + rows + "</sheetData></worksheet>");
        }
        return file;
    }

    private static String row(List<String> values) {
        StringBuilder xml = new StringBuilder("<row>");
        for (int i = 0; i < values.size(); i++) {
            xml.append("<c r=\"").append((char) ('A' + i)).append("1\" t=\"inlineStr\"><is><t>")
                    .append(values.get(i)).append("</t></is></c>");
        }
        return xml.append("</row>").toString();
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
