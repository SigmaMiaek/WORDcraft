package pl.wordcraft.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import pl.wordcraft.data.Question;

public final class ExamMediaPlan {
    private volatile List<Question> questions;
    private final Function<Question, CompletableFuture<Path>> loader;
    private final Map<String, CompletableFuture<Path>> requests = new HashMap<>();
    private final Set<String> ready = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private boolean background;
    private boolean stopped;
    private boolean pumping;
    private int focus;

    public ExamMediaPlan(List<Question> questions, Function<Question, CompletableFuture<Path>> loader) {
        this.questions = List.copyOf(questions);
        this.loader = Objects.requireNonNull(loader);
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("Empty exam media plan");
        }
    }

    public CompletableFuture<Path> prepareFirst() {
        return this.prepare(this.questions.getFirst());
    }

    public synchronized CompletableFuture<Path> prepare(Question question) {
        for (int i = 0; i < this.questions.size(); i++) {
            if (this.questions.get(i).id().equals(question.id())) {
                this.focus = i;
                break;
            }
        }

        if (question.media().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        CompletableFuture<Path> existing = this.requests.get(question.media());
        if (existing != null && !existing.isCompletedExceptionally() && !existing.isCancelled()) {
            return existing;
        }

        if (this.stopped) {
            return CompletableFuture.failedFuture(new IllegalStateException("Pobieranie egzaminu zostało zakończone."));
        }

        this.failed.remove(question.media());
        return this.request(question);
    }

    private CompletableFuture<Path> request(Question question) {
        CompletableFuture<Path> retained = new CompletableFuture<>();
        this.requests.put(question.media(), retained);

        CompletableFuture<Path> loading;
        try {
            loading = this.loader.apply(question);
        } catch (Throwable error) {
            loading = CompletableFuture.failedFuture(error);
        }

        loading.whenComplete((path, error) -> {
            synchronized (this) {
                if (error == null) {
                    this.ready.add(question.media());
                    this.failed.remove(question.media());
                } else {
                    this.failed.add(question.media());
                }
            }

            if (error == null) {
                retained.complete(path);
            } else {
                retained.completeExceptionally(error);
            }
        });
        return retained;
    }

    public synchronized void startBackground() {
        this.background = true;
        this.pump();
    }

    private synchronized void pump() {
        if (this.background && !this.stopped && !this.pumping) {
            Question next = null;

            for (int offset = 0; offset < this.questions.size(); offset++) {
                Question candidate = this.questions.get((this.focus + offset) % this.questions.size());
                if (!candidate.media().isBlank() && !this.ready.contains(candidate.media()) && !this.failed.contains(candidate.media())) {
                    next = candidate;
                    break;
                }
            }

            if (next != null) {
                this.pumping = true;
                CompletableFuture<Path> loading = this.requests.get(next.media());
                if (loading == null) {
                    loading = this.request(next);
                }

                loading.whenComplete((path, error) -> {
                    synchronized (this) {
                        this.pumping = false;
                    }

                    this.pump();
                });
            }
        }
    }

    public synchronized void stop() {
        this.stopped = true;
        this.background = false;
    }

    public synchronized int readyQuestions() {
        return (int)this.questions.stream().filter(q -> q.media().isBlank() || this.ready.contains(q.media())).count();
    }

    public int totalQuestions() {
        return this.questions.size();
    }

    public synchronized boolean downloading() {
        return this.background && !this.stopped && this.readyQuestions() < this.questions.size() && this.pumping;
    }

    public synchronized boolean allReady() {
        return this.readyQuestions() == this.questions.size();
    }

    public synchronized boolean replaceQuestion(Question var1, Question var2) {
        int var3 = -1;

        for (int var4 = 0; var4 < this.questions.size(); var4++) {
            if (this.questions.get(var4).id().equals(var1.id())) {
                var3 = var4;
            }
        }

        if (var3 < 0) {
            return false;
        }

        ArrayList<Question> var8 = new ArrayList<>(this.questions);
        var8.set(var3, var2);
        this.questions = List.copyOf(var8);
        boolean var5 = false;

        for (Question var6 : this.questions) {
            if (var6.media().equals(var1.media())) {
                var5 = true;
            }
        }

        if (!var5) {
            this.requests.remove(var1.media());
            this.ready.remove(var1.media());
            this.failed.remove(var1.media());
        }

        return true;
    }

    public synchronized int failures() {
        HashSet<String> var1 = new HashSet<>();

        for (Question var2 : this.questions) {
            if (this.failed.contains(var2.media())) {
                var1.add(var2.media());
            }
        }

        return var1.size();
    }
}
