package pl.wordcraft.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.random.RandomGenerator;
import pl.wordcraft.data.Question;

public final class PracticeSession {
    private List<Question> questions;
    private int questionIndex;

    private PracticeSession(List<Question> questions) {
        this.questions = List.copyOf(questions);
    }

    public static PracticeSession create(List<Question> bank, WeightedQuestionPicker picker, LearningStats stats, RandomGenerator random) {
        Objects.requireNonNull(picker);
        Objects.requireNonNull(stats);
        Objects.requireNonNull(random);
        LinkedHashMap<String, Question> unique = new LinkedHashMap<>();

        for (Question question : bank) {
            unique.putIfAbsent(question.id(), question);
        }

        List<Question> remaining = new ArrayList<>(unique.values());
        List<Question> selected = new ArrayList<>(3);

        for (boolean basic : List.of(true, true, false)) {
            List<Question> pool = remaining.stream().filter(q -> q.basic() == basic).toList();
            picker.pick(pool, stats, random).ifPresent(question -> {
                selected.add(question);
                remaining.removeIf(q -> q.id().equals(question.id()));
            });
        }

        while (selected.size() < 3 && !remaining.isEmpty()) {
            Question question = picker.pick(remaining, stats, random).orElseThrow();
            selected.add(question);
            remaining.removeIf(q -> q.id().equals(question.id()));
        }

        selected.sort(Comparator.comparing(Question::basic).reversed());
        return new PracticeSession(selected);
    }

    public List<Question> questions() {
        return this.questions;
    }

    public int questionIndex() {
        return this.questionIndex;
    }

    public int size() {
        return this.questions.size();
    }

    public Question currentQuestion() {
        if (this.questions.isEmpty()) {
            throw new NoSuchElementException("Brak pytań w serii.");
        } else {
            return this.questions.get(this.questionIndex);
        }
    }

    public boolean hasNext() {
        return this.questionIndex + 1 < this.questions.size();
    }

    public boolean advance() {
        if (!this.hasNext()) {
            return false;
        }

        this.questionIndex++;
        return true;
    }

    public boolean replaceCurrentQuestion(Question var1) {
        if (var1 != null && !this.questions.isEmpty() && var1.basic() == this.questions.get(this.questionIndex).basic()) {
            for (Question var2 : this.questions) {
                if (var2.id().equals(var1.id())) {
                    return false;
                }
            }

            ArrayList<Question> var4 = new ArrayList<>(this.questions);
            var4.set(this.questionIndex, var1);
            this.questions = List.copyOf(var4);
            return true;
        } else {
            return false;
        }
    }
}
