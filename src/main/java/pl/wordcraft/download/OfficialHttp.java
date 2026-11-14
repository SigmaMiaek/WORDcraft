package pl.wordcraft.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Uses the operating system's maintained CA store without changing JVM defaults. */
final class OfficialHttp {
    private OfficialHttp() {}

    static HttpClient client(Duration connectTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
        if (!explicitTrustStore()) {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            SSLContext context = os.startsWith("windows") ? WindowsContext.VALUE
                    : os.startsWith("linux") ? LinuxContext.VALUE : null;
            if (context != null) {
                builder.sslContext(context);
            }
        }
        return builder.build();
    }

    private static boolean explicitTrustStore() {
        return System.getProperty("javax.net.ssl.trustStore") != null
                || System.getProperty("javax.net.ssl.trustStoreType") != null
                || System.getProperty("javax.net.ssl.trustStoreProvider") != null
                || System.getProperty("javax.net.ssl.trustStorePassword") != null;
    }

    private static X509TrustManager trustManager(KeyStore roots)
            throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
        factory.init(roots);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                return x509;
            }
        }
        throw new GeneralSecurityException("System root store has no PKIX X509 trust manager");
    }

    static X509TrustManager windowsTrustManager()
            throws GeneralSecurityException, IOException {
        KeyStore roots = KeyStore.getInstance("Windows-ROOT");
        roots.load(null, null);
        return trustManager(roots);
    }

    private static SSLContext context(X509TrustManager manager)
            throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{manager}, null);
        return context;
    }

    private static final class LinuxContext {
        private static final SSLContext VALUE = load();

        private static SSLContext load() {
            // Fedora (including the Modrinth Flatpak) and Debian/Ubuntu Java stores.
            for (String name : new String[]{
                    "/etc/pki/java/cacerts",
                    "/etc/ssl/certs/java/cacerts",
                    "/etc/pki/ca-trust/extracted/java/cacerts"}) {
                try {
                    Path path = Path.of(name);
                    if (!Files.isRegularFile(path) || !Files.isReadable(path)) continue;
                    KeyStore roots = KeyStore.getInstance(path.toFile(), (char[]) null);
                    if (roots.size() > 0) return context(trustManager(roots));
                } catch (IOException | SecurityException | GeneralSecurityException unavailable) {
                    // Try the next system-managed store; never trust a peer certificate directly.
                }
            }
            // Distributions that provide only a system PEM bundle.
            for (String name : new String[]{
                    "/etc/ssl/certs/ca-certificates.crt",
                    "/etc/pki/tls/certs/ca-bundle.crt",
                    "/etc/ssl/cert.pem"}) {
                try {
                    Path path = Path.of(name);
                    if (!Files.isRegularFile(path) || !Files.isReadable(path)) continue;
                    KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
                    roots.load(null, null);
                    try (InputStream input = Files.newInputStream(path)) {
                        for (Certificate certificate : CertificateFactory.getInstance("X.509")
                                .generateCertificates(input)) {
                            roots.setCertificateEntry("system-" + roots.size(), certificate);
                        }
                    }
                    if (roots.size() > 0) return context(trustManager(roots));
                } catch (IOException | SecurityException | GeneralSecurityException unavailable) {
                    // Keep the normal JRE trust store if no usable system store exists.
                }
            }
            return null;
        }
    }

    private static final class WindowsContext {
        private static final SSLContext VALUE = load();

        private static SSLContext load() {
            try {
                return context(windowsTrustManager());
            } catch (IOException | SecurityException | GeneralSecurityException unavailable) {
                return null;
            }
        }
    }
}
