package pl.wordcraft.core;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.security.cert.CertPathBuilderException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;

class PreparationFailureTest {
    @Test void certificateFailureShowsTlsAndRetainsItsRootCauseAndOperation() {
        SSLHandshakeException tls = new SSLHandshakeException("PKIX path building failed");
        tls.initCause(new CertPathBuilderException("unable to find valid certification path to requested target"));
        var report = PreparationFailure.report(new IOException("Download failed", tls), "Pobieranie katalogu");
        assertTrue(report.getFirst().contains("HTTPS"));
        assertTrue(report.contains("Etap: Pobieranie katalogu"));
        assertTrue(report.stream().anyMatch(line -> line.contains("CertPathBuilderException") && line.contains("valid certification path")));
        assertThrows(UnsupportedOperationException.class, () -> report.add("changed"));
    }

    @Test void distinguishesDnsTimeoutAndDiskPermissionFailures() {
        assertTrue(PreparationFailure.summary(new UnknownHostException("www.gov.pl")).contains("DNS"));
        assertTrue(PreparationFailure.summary(new HttpTimeoutException("timeout")).contains("na czas"));
        assertTrue(PreparationFailure.summary(new AccessDeniedException("catalogue.xlsx")).contains("uprawnień"));
        assertFalse(PreparationFailure.summary(new IOException("HTTP 403")).contains("Sprawdź internet"));
    }

    @Test void unexpectedCyclicCausesCannotHangTheFailureReport() {
        var cyclic = new RuntimeException("cyclic") { @Override public Throwable getCause() { return this; } };
        assertEquals(2, PreparationFailure.report(cyclic, "").size());
    }
}
