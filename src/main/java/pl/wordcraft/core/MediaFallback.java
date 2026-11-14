package pl.wordcraft.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import pl.wordcraft.data.Question;

/** Bounded recovery from media download failures, scoped to one screen/session. */
public final class MediaFallback {
    private static final int MAX_REMOTE_REPLACEMENTS = 3;
    private static final int MAX_FAILURES = 8;
    private final Set<String> failedIds = new HashSet<>();
    private final Set<String> failedMedia = new HashSet<>();
    private int failures;
    private int remoteReplacements;

    public Optional<Question> choose(List<Question> bank, Question failed,
            List<Question> occupied, boolean keepType, boolean keepPoints,
            Function<Question, Path> localPath, RandomGenerator random) {
        Objects.requireNonNull(failed);
        failedIds.add(failed.id());
        if (!failed.media().isBlank()) failedMedia.add(mediaKey(failed));
        if (++failures > MAX_FAILURES) return Optional.empty();
        Set<String> reserved = new HashSet<>();
        for (Question question : occupied) reserved.add(question.id());
        List<Question> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Question candidate : bank) {
            if (!seen.add(candidate.id()) || failedIds.contains(candidate.id())
                    || reserved.contains(candidate.id())
                    || (!candidate.media().isBlank() && failedMedia.contains(mediaKey(candidate)))
                    || (keepType && candidate.basic() != failed.basic())
                    || (keepPoints && candidate.points() != failed.points())) continue;
            if (remoteReplacements >= MAX_REMOTE_REPLACEMENTS && !isLocal(candidate, localPath)) continue;
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) return Optional.empty();
        Question replacement = candidates.get(random.nextInt(candidates.size()));
        if (!isLocal(replacement, localPath)) remoteReplacements++;
        return Optional.of(replacement);
    }

    public void succeeded() {
        failures = 0;
        remoteReplacements = 0;
    }

    private static boolean isLocal(Question question, Function<Question, Path> localPath) {
        if (question.media().isBlank()) return true;
        Path path = localPath.apply(question);
        return path != null && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static String mediaKey(Question question) {
        return question.media().strip().toLowerCase(Locale.ROOT);
    }
}
