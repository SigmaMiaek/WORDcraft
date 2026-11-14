package pl.wordcraft.data;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class QuestionExplanations {
    private static final String RESOURCE = "/assets/wordcraft/explanations/pl_pl.json";
    private static final Map<String, QuestionExplanations.Entry> VERIFIED = load();

    private QuestionExplanations() {
    }

    public static QuestionExplanations.Explanation forQuestion(Question question, int selectedIndex) {
        Objects.requireNonNull(question, "question");
        QuestionExplanations.Entry entry = VERIFIED.get(question.id());
        if (entry != null && entry.fingerprint().equals(fingerprint(question))) {
            String why = selectedIndex < 0 || selectedIndex >= question.answers().size()
                ? "Nie zaznaczono odpowiedzi."
                : (selectedIndex == question.correctIndex() ? "" : entry.wrongAnswers().get(selectedIndex));
            return new QuestionExplanations.Explanation(entry.reason(), why, entry.sources(), true);
        }

        String reason = "Oficjalny katalog nie zawiera uzasadnień. Dla tego pytania nie ma jeszcze sprawdzonego wyjaśnienia.";
        if (!question.legalBasis().isBlank()) {
            reason = reason + " Podstawa podana w importowanych danych (niezweryfikowana): " + question.legalBasis();
        }

        String why = selectedIndex >= 0 && selectedIndex < question.answers().size()
            ? (
                selectedIndex == question.correctIndex()
                    ? ""
                    : "Przyczyna błędu nie została jeszcze zweryfikowana. Porównaj odpowiedzi i ponownie obejrzyj materiał, jeśli jest dostępny."
            )
            : "Nie zaznaczono odpowiedzi.";
        return new QuestionExplanations.Explanation(reason, why, List.of(), false);
    }

    public static int verifiedCount() {
        return VERIFIED.size();
    }

    static String fingerprint(Question q) {
        List<String> values = new ArrayList<>(List.of(q.text()));
        values.addAll(q.answers());
        values.add(Integer.toString(q.correctIndex()));
        values.add(Boolean.toString(q.basic()));
        values.add(Integer.toString(q.points()));
        values.add(q.media());

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\u001f", values).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Map<String, QuestionExplanations.Entry> load() {
        try (InputStream stream = QuestionExplanations.class.getResourceAsStream("/assets/wordcraft/explanations/pl_pl.json")) {
            if (stream == null) {
                return Map.of();
            }

            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) {
                return Map.of();
            }

            LocalDate checked = LocalDate.parse(root.get("verifiedOn").getAsString());
            Map<String, QuestionExplanations.Entry> entries = new HashMap<>();

            for (JsonElement element : root.getAsJsonArray("entries")) {
                JsonObject object = element.getAsJsonObject();
                String id = object.get("id").getAsString();
                String digest = object.get("fingerprint").getAsString();
                String reason = object.get("reason").getAsString();
                List<String> wrong = new ArrayList<>();

                for (JsonElement answer : object.getAsJsonArray("wrongAnswers")) {
                    wrong.add(answer.getAsString());
                }

                List<QuestionExplanations.Source> sources = new ArrayList<>();

                for (JsonElement source : object.getAsJsonArray("sources")) {
                    JsonObject value = source.getAsJsonObject();
                    String url = value.get("url").getAsString();
                    if (!url.startsWith("https://")) {
                        throw new IllegalArgumentException("Non-HTTPS source");
                    }

                    sources.add(new QuestionExplanations.Source(value.get("label").getAsString() + " · sprawdzono " + checked, url));
                }

                if (!id.matches("[1-9][0-9]*")
                    || !digest.matches("[0-9a-f]{64}")
                    || reason.isBlank()
                    || wrong.size() < 2
                    || wrong.size() > 3
                    || sources.isEmpty()
                    || entries.put(id, new QuestionExplanations.Entry(digest, reason, List.copyOf(wrong), List.copyOf(sources))) != null) {
                    throw new IllegalArgumentException("Invalid explanation entry");
                }
            }

            return Map.copyOf(entries);
        } catch (IOException | RuntimeException invalidOptionalResource) {
            return Map.of();
        }
    }

    private record Entry(String fingerprint, String reason, List<String> wrongAnswers, List<QuestionExplanations.Source> sources) {
    }

    public record Explanation(String reason, String whySelectedIsWrong, List<QuestionExplanations.Source> sources, boolean verified) {
        public Explanation {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(whySelectedIsWrong);
            sources = List.copyOf(sources);
        }
    }

    public record Source(String label, String url) {
        public Source {
            Objects.requireNonNull(label);
            Objects.requireNonNull(url);
        }
    }
}
