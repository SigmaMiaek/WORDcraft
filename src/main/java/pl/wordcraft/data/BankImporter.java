package pl.wordcraft.data;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

public final class BankImporter {
    private static final long MAX_XML_BYTES = 83886080L;
    private static final int MAX_ROWS = 100000;
    private static final int MAX_STRINGS = 200000;
    private static final int MAX_TEXT = 500000;
    private static final List<String> REQUIRED = List.of(
        "numer pytania", "pytanie", "odpowiedź a", "odpowiedź b", "odpowiedź c", "poprawna odp", "media", "zakres struktury", "liczba punktów", "kategorie"
    );

    public BankImporter.ImportResult readCatalog(Path excel) throws IOException {
        if (!Files.isRegularFile(excel)) {
            throw new IOException("Nie znaleziono oficjalnego pliku XLSX: " + excel);
        }

        List<Question> questions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        int[] sourceRows = new int[]{0};
        Map<String, Integer> headers = new HashMap<>();

        try (ZipFile zip = new ZipFile(excel.toFile())) {
            XMLInputFactory xml = secureFactory();
            List<String> strings = sharedStrings(zip, xml);
            String sheet = cataloguePath(zip, xml, warnings);
            readRows(
                zip,
                xml,
                sheet,
                strings,
                row -> {
                    if (!row.values().stream().allMatch(String::isBlank)) {
                        if (headers.isEmpty()) {
                            for (Entry<Integer, String> cell : row.entrySet()) {
                                String header = normalize(cell.getValue());
                                if (!header.isEmpty() && headers.put(header, cell.getKey()) != null) {
                                    throw new BankImporter.ImportFailure("Powtórzony nagłówek arkusza katalog: " + cell.getValue());
                                }
                            }

                            if (!headers.keySet().containsAll(REQUIRED)) {
                                throw new BankImporter.ImportFailure("Nieobsługiwane nagłówki arkusza katalog. Wymagane: " + REQUIRED);
                            }
                        } else {
                            sourceRows[0]++;
                            if (sourceRows[0] > 100000) {
                                throw new BankImporter.ImportFailure("Arkusz przekracza limit liczby pytań.");
                            }

                            String categories = cell(row, headers, "kategorie");
                            boolean categoryB = false;

                            for (String category : categories.split("[,;\\s]+")) {
                                if (category.equals("B")) {
                                    categoryB = true;
                                }
                            }

                            if (categoryB) {
                                String id = cell(row, headers, "numer pytania").strip();
                                if (!id.matches("[1-9][0-9]*")) {
                                    throw new BankImporter.ImportFailure("Nieprawidłowy numer pytania: " + id);
                                }

                                if (!ids.add(id)) {
                                    throw new BankImporter.ImportFailure("Powtórzony numer pytania kategorii B: " + id);
                                }

                                String text = cell(row, headers, "pytanie");
                                if (text.isBlank()) {
                                    throw new BankImporter.ImportFailure("Brak treści pytania " + id);
                                }

                                String pointText = cell(row, headers, "liczba punktów").strip();
                                if (!pointText.matches("[123](?:\\.0+)?")) {
                                    throw new BankImporter.ImportFailure("Nieprawidłowa punktacja pytania " + id + ": " + pointText);
                                }

                                int points = Integer.parseInt(pointText.substring(0, 1));
                                String scope = normalize(cell(row, headers, "zakres struktury"));
                                if (scope.equals("specajlistyczny")) {
                                    warnings.add("Pytanie " + id + ": źródło zawiera literówkę „Specajlistyczny”; rozpoznano zakres specjalistyczny.");
                                    scope = "specjalistyczny";
                                }

                                if (!scope.equals("podstawowy") && !scope.equals("specjalistyczny")) {
                                    throw new BankImporter.ImportFailure("Nieznany zakres pytania " + id + ": " + scope);
                                }

                                boolean basic = scope.equals("podstawowy");
                                String correct = cell(row, headers, "poprawna odp").strip().toUpperCase(Locale.ROOT);
                                List<String> abc = List.of(
                                    cell(row, headers, "odpowiedź a"), cell(row, headers, "odpowiedź b"), cell(row, headers, "odpowiedź c")
                                );
                                boolean validBinary = (correct.equals("T") || correct.equals("N")) && abc.stream().allMatch(String::isBlank);
                                boolean validAbc = correct.matches("[ABC]") && abc.stream().noneMatch(String::isBlank);
                                if ((!basic || !validAbc) && (basic || !validBinary)) {
                                    if ((!basic || validBinary) && (basic || validAbc)) {
                                        String media = cell(row, headers, "media");
                                        if (!media.isEmpty()) {
                                            validateMediaName(media, id);
                                        }

                                        int correctIndex = basic ? (correct.equals("T") ? 0 : 1) : correct.charAt(0) - 'A';
                                        questions.add(new Question(id, text, basic ? List.of("TAK", "NIE") : abc, correctIndex, basic, points, media, ""));
                                    } else {
                                        throw new BankImporter.ImportFailure("Nieprawidłowy lub niepełny klucz odpowiedzi pytania " + id);
                                    }
                                } else {
                                    warnings.add(
                                        "KWARANTANNA: pytanie "
                                            + id
                                            + " pominięto: oficjalny zakres „"
                                            + cell(row, headers, "zakres struktury")
                                            + "” jest sprzeczny z odpowiedziami "
                                            + (validAbc ? "A/B/C" : "TAK/NIE")
                                            + ". Nie zmieniono klucza ani zakresu."
                                    );
                                }
                            }
                        }
                    }
                }
            );
        } catch (BankImporter.ImportFailure ex) {
            throw new IOException(ex.getMessage(), ex);
        } catch (XMLStreamException ex) {
            throw new IOException("Uszkodzony lub niedozwolony XML w XLSX: " + ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Nieprawidłowy oficjalny arkusz: " + ex.getMessage(), ex);
        }

        if (questions.isEmpty()) {
            throw new IOException("Arkusz katalog nie zawiera poprawnych pytań kategorii B.");
        } else {
            return new BankImporter.ImportResult(questions, Map.of(), warnings, sourceRows[0]);
        }
    }

    public BankImporter.ImportResult importFile(Path excel, Path mediaDirectory) throws IOException {
        if (!Files.isRegularFile(excel)) {
            throw new IOException("Nie znaleziono oficjalnego pliku XLSX: " + excel);
        }

        if (!Files.isDirectory(mediaDirectory)) {
            throw new IOException("Nie znaleziono katalogu multimediów: " + mediaDirectory);
        }

        BankImporter.ImportResult catalog = this.readCatalog(excel);
        List<Question> questions = catalog.questions();
        List<String> warnings = new ArrayList<>(catalog.warnings());
        Map<String, List<Path>> index = mediaIndex(mediaDirectory);
        Map<String, Path> media = new LinkedHashMap<>();
        Set<String> missingNames = new HashSet<>();

        for (Question q : questions) {
            if (!q.media().isEmpty()) {
                List<Path> matches = index.getOrDefault(normalize(q.media()), List.of());
                if (matches.size() > 1) {
                    throw new IOException("Niejednoznaczne multimedia dla pytania " + q.id() + ": " + q.media() + " — " + matches);
                }

                if (matches.isEmpty()) {
                    warnings.add(
                        "BRAK MULTIMEDIUM: pytanie "
                            + q.id()
                            + " wymaga pliku „"
                            + q.media()
                            + "”. Pytanie zachowano w audycie; nie wolno wyświetlać go bez materiału."
                    );
                    missingNames.add(q.media());
                } else {
                    media.put(q.media(), matches.getFirst());
                }
            }
        }

        warnings.add(
            "Audyt: "
                + catalog.sourceRows()
                + " wierszy katalogu, "
                + questions.size()
                + " poprawnych pytań B, "
                + media.size()
                + " powiązanych plików, "
                + missingNames.size()
                + " brakujących różnych plików. Braki i kwarantanna nie są pytaniami bez multimediów."
        );
        return new BankImporter.ImportResult(questions, media, warnings, catalog.sourceRows());
    }

    private static void validateMediaName(String name, String id) {
        if (!name.contains("/")
            && !name.contains("\\")
            && !name.contains(":")
            && name.indexOf(0) < 0
            && !name.equals(".")
            && !name.equals("..")
            && name.equals(name.strip())) {
            String normalized = normalize(name);
            if (!normalized.matches(".+\\.(jpg|jpeg|png|bmp|gif|wmv|mp4|m4v|avi|mov|mkv|webm)")) {
                throw new BankImporter.ImportFailure("Nieobsługiwany format medium pytania " + id + ": " + name);
            }
        } else {
            throw new BankImporter.ImportFailure("Niedozwolona nazwa medium pytania " + id + ": " + name);
        }
    }

    private static Map<String, List<Path>> mediaIndex(Path directory) throws IOException {
        Map<String, List<Path>> index = new HashMap<>();
        int count = 0;

        try (Stream<Path> files = Files.walk(directory, 20)) {
            Iterator<Path> iterator = files.iterator();

            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    if (++count > 100000) {
                        throw new IOException("Katalog multimediów przekracza limit 100 000 plików.");
                    }

                    index.computeIfAbsent(normalize(file.getFileName().toString()), ignored -> new ArrayList<>()).add(file.toAbsolutePath().normalize());
                }
            }
        }

        return index;
    }

    private static String cell(Map<Integer, String> row, Map<String, Integer> headers, String key) {
        return row.getOrDefault(headers.get(key), "");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }

    private static XMLInputFactory secureFactory() {
        XMLInputFactory xml = XMLInputFactory.newFactory();
        xml.setProperty("javax.xml.stream.supportDTD", false);
        xml.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        xml.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("Zewnętrzne encje są niedozwolone.");
        });
        return xml;
    }

    private static String cataloguePath(ZipFile zip, XMLInputFactory xml, List<String> warnings) throws IOException, XMLStreamException {
        String catalogueId = null;

        try (InputStream in = entry(zip, "xl/workbook.xml")) {
            XMLStreamReader reader = xml.createXMLStreamReader(in);

            try {
                while (reader.hasNext()) {
                    if (reader.next() == 1 && reader.getLocalName().equals("sheet")) {
                        String name = reader.getAttributeValue(null, "name");
                        String id = reader.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id");
                        if (normalize(name).equals("katalog")) {
                            if (catalogueId != null) {
                                throw new IOException("Znaleziono więcej niż jeden arkusz katalog.");
                            }

                            catalogueId = id;
                        } else {
                            warnings.add("Pominięto arkusz „" + name + "”; import dotyczy wyłącznie zatwierdzonego arkusza katalog.");
                        }
                    }
                }
            } finally {
                reader.close();
            }
        }

        if (catalogueId == null) {
            throw new IOException("Brak zatwierdzonego arkusza „katalog” w oficjalnym XLSX.");
        }

        try (InputStream in = entry(zip, "xl/_rels/workbook.xml.rels")) {
            XMLStreamReader reader = xml.createXMLStreamReader(in);

            try {
                while (reader.hasNext()) {
                    if (reader.next() == 1 && reader.getLocalName().equals("Relationship") && catalogueId.equals(reader.getAttributeValue(null, "Id"))) {
                        String target = reader.getAttributeValue(null, "Target");
                        if ("External".equalsIgnoreCase(reader.getAttributeValue(null, "TargetMode"))
                            || target == null
                            || target.contains("\\")
                            || target.contains(":")
                            || target.contains("..")) {
                            throw new IOException("Niedozwolone odwołanie do arkusza XLSX.");
                        }

                        String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
                        if (!path.startsWith("xl/worksheets/") || !path.endsWith(".xml")) {
                            throw new IOException("Niedozwolona lokalizacja arkusza: " + path);
                        }

                        return path;
                    }
                }
            } finally {
                reader.close();
            }
        }

        throw new IOException("Nie odnaleziono danych arkusza katalog.");
    }

    private static List<String> sharedStrings(ZipFile zip, XMLInputFactory xml) throws IOException, XMLStreamException {
        List<String> result = new ArrayList<>();
        if (zip.getEntry("xl/sharedStrings.xml") == null) {
            return result;
        }

        try (InputStream in = entry(zip, "xl/sharedStrings.xml")) {
            XMLStreamReader reader = xml.createXMLStreamReader(in);
            StringBuilder text = null;
            int phoneticDepth = 0;

            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    String name = event != 1 && event != 2 ? "" : reader.getLocalName();
                    if (event == 1) {
                        if (name.equals("si")) {
                            text = new StringBuilder();
                        }

                        if (name.equals("rPh")) {
                            phoneticDepth++;
                        }

                        if (name.equals("t") && text != null && phoneticDepth == 0) {
                            append(text, reader.getElementText());
                        }
                    } else if (event == 2) {
                        if (name.equals("rPh")) {
                            phoneticDepth--;
                        }

                        if (name.equals("si") && text != null) {
                            if (result.size() >= 200000) {
                                throw new IOException("Za dużo tekstów w XLSX.");
                            }

                            result.add(text.toString());
                            text = null;
                        }
                    } else if (event == 11) {
                        throw new IOException("DTD w XLSX jest niedozwolone.");
                    }
                }
            } finally {
                reader.close();
            }
        }

        return result;
    }

    private static void readRows(ZipFile zip, XMLInputFactory xml, String sheet, List<String> strings, Consumer<Map<Integer, String>> consumer) throws IOException, XMLStreamException {
        try (InputStream in = entry(zip, sheet)) {
            XMLStreamReader reader = xml.createXMLStreamReader(in);
            Map<Integer, String> row = null;
            String type = null;
            int col = -1;
            StringBuilder value = new StringBuilder();
            boolean formula = false;
            int rows = 0;

            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    String name = event != 1 && event != 2 ? "" : reader.getLocalName();
                    if (event == 1) {
                        switch (name) {
                            case "row":
                                if (++rows > 100010) {
                                    throw new IOException("Za dużo wierszy w XLSX.");
                                }

                                row = new LinkedHashMap<>();
                                break;
                            case "c":
                                String reference = reader.getAttributeValue(null, "r");
                                col = column(reference);
                                type = reader.getAttributeValue(null, "t");
                                value.setLength(0);
                                formula = false;
                                break;
                            case "f":
                                formula = true;
                                break;
                            case "v":
                            case "t":
                                append(value, reader.getElementText());
                        }
                    } else if (event == 2) {
                        if (name.equals("c") && row != null) {
                            if (formula) {
                                throw new IOException("Arkusz katalog zawiera formułę; wymagane oryginalne wartości MI.");
                            }

                            String text = value.toString();
                            if ("s".equals(type)) {
                                try {
                                    int index = Integer.parseInt(text);
                                    if (index < 0 || index >= strings.size()) {
                                        throw new NumberFormatException();
                                    }

                                    text = strings.get(index);
                                } catch (NumberFormatException ex) {
                                    throw new IOException("Uszkodzony indeks tekstu XLSX.", ex);
                                }
                            } else if ("e".equals(type)) {
                                throw new IOException("Błąd komórki w oficjalnym XLSX: " + text);
                            }

                            if (row.put(col, text) != null) {
                                throw new IOException("Powtórzona kolumna komórki w XLSX.");
                            }
                        } else if (name.equals("row") && row != null) {
                            consumer.accept(row);
                            row = null;
                        }
                    } else if (event == 11) {
                        throw new IOException("DTD w XLSX jest niedozwolone.");
                    }
                }
            } finally {
                reader.close();
            }
        }
    }

    private static void append(StringBuilder target, String text) throws IOException {
        if (target.length() + text.length() > 500000) {
            throw new IOException("Tekst komórki XLSX jest zbyt długi.");
        }

        target.append(text);
    }

    private static int column(String reference) throws IOException {
        if (reference != null && reference.matches("[A-Z]{1,3}[1-9][0-9]{0,6}")) {
            int result = 0;

            for (int i = 0; i < reference.length() && Character.isLetter(reference.charAt(i)); i++) {
                result = result * 26 + reference.charAt(i) - 65 + 1;
            }

            if (result > 16384) {
                throw new IOException("Kolumna poza zakresem XLSX.");
            } else {
                return result - 1;
            }
        } else {
            throw new IOException("Nieprawidłowe odwołanie komórki XLSX: " + reference);
        }
    }

    private static InputStream entry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException("Brak elementu XLSX: " + name);
        } else if (entry.getSize() > 83886080L) {
            throw new IOException("Element XLSX jest zbyt duży: " + name);
        } else {
            return new FilterInputStream(zip.getInputStream(entry)) {
                private long read;

                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0 && ++this.read > 83886080L) {
                        throw new IOException("Przekroczono limit XML w XLSX.");
                    } else {
                        return value;
                    }
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    int count = this.in.read(bytes, offset, length);
                    if (count > 0 && (this.read += count) > 83886080L) {
                        throw new IOException("Przekroczono limit XML w XLSX.");
                    } else {
                        return count;
                    }
                }
            };
        }
    }

    private static final class ImportFailure extends RuntimeException {
        private ImportFailure(String message) {
            super(message);
        }
    }

    public record ImportResult(List<Question> questions, Map<String, Path> media, List<String> warnings, int sourceRows) {
        public ImportResult {
            questions = List.copyOf(questions);
            media = Map.copyOf(media);
            warnings = List.copyOf(warnings);
        }
    }
}
