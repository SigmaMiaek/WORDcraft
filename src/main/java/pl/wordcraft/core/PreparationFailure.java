package pl.wordcraft.core;

import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import javax.net.ssl.SSLException;

/** User-facing failure details; the complete exception is still kept in the log. */
public final class PreparationFailure {
    private PreparationFailure() {}

    public static String summary(Throwable error) {
        var causes = causes(error);
        if (causes.stream().anyMatch(SSLException.class::isInstance)) {
            return "Nie udało się zweryfikować połączenia HTTPS. Szczegóły: Ustawienia nauki → Raport bazy.";
        }
        if (causes.stream().anyMatch(AccessDeniedException.class::isInstance)) {
            return "Brak uprawnień do zapisu bazy. Szczegóły: Ustawienia nauki → Raport bazy.";
        }
        if (causes.stream().anyMatch(FileSystemException.class::isInstance)) {
            return "Nie można zapisać bazy na dysku. Szczegóły: Ustawienia nauki → Raport bazy.";
        }
        if (causes.stream().anyMatch(UnknownHostException.class::isInstance)) {
            return "Nie można odnaleźć serwera Ministerstwa (DNS). Szczegóły: Raport bazy.";
        }
        if (causes.stream().anyMatch(cause -> cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException)) {
            return "Serwer Ministerstwa nie odpowiedział na czas. Spróbuj ponownie; szczegóły: Raport bazy.";
        }
        return "Nie udało się przygotować bazy. Szczegóły: Ustawienia nauki → Raport bazy.";
    }

    public static List<String> report(Throwable error, String operation) {
        List<String> lines = new ArrayList<>();
        lines.add(summary(error));
        if (operation != null && !operation.isBlank()) lines.add("Etap: " + operation);
        for (Throwable cause : causes(error)) {
            String message = cause.getMessage();
            lines.add(cause.getClass().getName() + (message == null || message.isBlank() ? "" : ": " + message));
        }
        return List.copyOf(lines);
    }

    private static List<Throwable> causes(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && chain.size() < 12 && seen.add(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }
}
