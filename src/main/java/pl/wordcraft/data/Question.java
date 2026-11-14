package pl.wordcraft.data;

import java.util.List;
import java.util.Objects;

public record Question(String id, String text, List<String> answers, int correctIndex, boolean basic, int points, String media, String legalBasis) {
    public Question(String id, String text, List<String> answers, int correctIndex, boolean basic, int points, String media, String legalBasis) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        answers = List.copyOf(answers);
        media = Objects.requireNonNullElse(media, "");
        legalBasis = Objects.requireNonNullElse(legalBasis, "");
        if (id.matches("[1-9][0-9]*") && !text.isBlank()) {
            if (answers.size() == (basic ? 2 : 3)
                && !answers.stream().anyMatch(String::isBlank)
                && correctIndex >= 0
                && correctIndex < answers.size()
                && points >= 1
                && points <= 3) {
                this.id = id;
                this.text = text;
                this.answers = answers;
                this.correctIndex = correctIndex;
                this.basic = basic;
                this.points = points;
                this.media = media;
                this.legalBasis = legalBasis;
            } else {
                throw new IllegalArgumentException("Pytanie " + id + " ma nieprawidłowe odpowiedzi lub punktację.");
            }
        } else {
            throw new IllegalArgumentException("Pytanie ma nieprawidłowy numer lub pustą treść.");
        }
    }
}
