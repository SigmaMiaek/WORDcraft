package pl.wordcraft.download;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.HexFormat;
import java.util.List;
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
            TrustConfiguration configuration = os.startsWith("windows") ? WindowsContext.VALUE
                    : os.startsWith("linux") ? LinuxContext.VALUE : null;
            if (configuration == null) configuration = JavaContext.VALUE;
            if (configuration != null) {
                builder.sslContext(configuration.context());
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
        X509TrustManager javaRoots;
        try {
            javaRoots = trustManager(null);
        } catch (GeneralSecurityException | SecurityException unavailable) {
            javaRoots = null;
        }
        return windowsTrustManager(javaRoots, type -> {
            KeyStore store = KeyStore.getInstance(type);
            store.load(null, null);
            return store;
        });
    }

    @FunctionalInterface
    interface RootStoreLoader {
        KeyStore load(String type) throws GeneralSecurityException, IOException;
    }

    static X509TrustManager windowsTrustManager(X509TrustManager javaRoots, RootStoreLoader loader)
            throws GeneralSecurityException, IOException {
        KeyStore combined = KeyStore.getInstance(KeyStore.getDefaultType());
        combined.load(null, null);
        if (javaRoots != null) {
            for (X509Certificate certificate : javaRoots.getAcceptedIssuers()) {
                addRoot(combined, certificate);
            }
        }
        // Windows-ROOT is the current-user store. Machine-wide roots must also
        // be loaded, without replacing the JRE's public CA set. Older runtimes
        // may support only the Windows-ROOT alias; unavailable stores are optional.
        for (String type : new String[]{"Windows-ROOT-CURRENTUSER", "Windows-ROOT-LOCALMACHINE", "Windows-ROOT"}) {
            try {
                KeyStore store = loader.load(type);
                var aliases = store.aliases();
                while (aliases.hasMoreElements()) {
                    Certificate certificate = store.getCertificate(aliases.nextElement());
                    if (certificate instanceof X509Certificate) addRoot(combined, certificate);
                }
            } catch (GeneralSecurityException | IOException | SecurityException unavailable) {
                // A missing provider, empty user store or inaccessible machine
                // store must not discard the other trusted sources.
            }
        }
        if (combined.size() == 0) {
            throw new GeneralSecurityException("No trusted CA certificates are available in Java or Windows root stores");
        }
        // Keep the standard PKIX extended manager: chain, expiry, algorithm and
        // HTTPS hostname validation remain JSSE's responsibility.
        return trustManager(combined);
    }

    private static void addRoot(KeyStore target, Certificate certificate) throws GeneralSecurityException {
        String alias = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        target.setCertificateEntry(alias, certificate);
    }

    static X509TrustManager officialTrustManager(X509TrustManager platformRoots)
            throws GeneralSecurityException, IOException {
        KeyStore combined = KeyStore.getInstance(KeyStore.getDefaultType());
        combined.load(null, null);
        if (platformRoots != null) {
            for (X509Certificate certificate : platformRoots.getAcceptedIssuers()) addRoot(combined, certificate);
        }
        // This is a published CA anchor, never a peer certificate learned from
        // a failed connection. Leave all certificate and hostname checks to PKIX.
        addRoot(combined, OfficialCa.certificate());
        return trustManager(combined);
    }

    private record TrustConfiguration(SSLContext context, int roots) {}

    private static TrustConfiguration context(X509TrustManager platformRoots)
            throws GeneralSecurityException, IOException {
        X509TrustManager manager = officialTrustManager(platformRoots);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{manager}, null);
        return new TrustConfiguration(context, manager.getAcceptedIssuers().length);
    }

    static List<String> diagnostics() {
        String runtime = "Java: " + System.getProperty("java.version", "?") + " · "
                + System.getProperty("java.vendor", "?") + " · " + System.getProperty("os.name", "?");
        String clock = "Czas systemowy UTC: " + Instant.now();
        if (explicitTrustStore()) {
            StringBuilder options = new StringBuilder();
            for (String name : new String[]{"trustStore", "trustStoreType", "trustStoreProvider", "trustStorePassword"}) {
                if (System.getProperty("javax.net.ssl." + name) != null) {
                    if (!options.isEmpty()) options.append(", ");
                    options.append(name);
                }
            }
            return List.of(runtime, clock, "TLS: własny magazyn JVM/launchera (" + options + "). Automatyczne certyfikaty moda nie nadpisują tej konfiguracji.");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        TrustConfiguration configuration = os.startsWith("windows") ? WindowsContext.VALUE
                : os.startsWith("linux") ? LinuxContext.VALUE : null;
        String source = os.startsWith("windows") ? "Java + Windows" : os.startsWith("linux") ? "system Linux" : "Java";
        if (configuration == null) { configuration = JavaContext.VALUE; source = "Java"; }
        return List.of(runtime, clock, configuration == null ? "TLS: domyślna konfiguracja JVM; brak konfiguracji zgodności Certum."
                : "TLS: " + source + " + Certum Trusted Root CA · zaufane CA: " + configuration.roots());
    }

    private static final class LinuxContext {
        private static final TrustConfiguration VALUE = load();

        private static TrustConfiguration load() {
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
        private static final TrustConfiguration VALUE = load();

        private static TrustConfiguration load() {
            try {
                return context(windowsTrustManager());
            } catch (IOException | SecurityException | GeneralSecurityException unavailable) {
                return null;
            }
        }
    }

    private static final class JavaContext {
        private static final TrustConfiguration VALUE = load();

        private static TrustConfiguration load() {
            try {
                return context(trustManager(null));
            } catch (IOException | SecurityException | GeneralSecurityException unavailable) {
                try { return context(null); }
                catch (IOException | SecurityException | GeneralSecurityException noCompatibilityStore) { return null; }
            }
        }
    }
}
