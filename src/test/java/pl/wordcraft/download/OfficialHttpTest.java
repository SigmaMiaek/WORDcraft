package pl.wordcraft.download;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;

class OfficialHttpTest {
    private static final char[] PASSWORD = "synthetic-test-store".toCharArray();
    @TempDir static Path directory;
    private static KeyStore javaRoots, userRoots, machineRoots, unknownRoots;
    private static KeyStore javaServer, machineServer, wrongHostServer, expiredServer;

    @BeforeAll static void certificates() throws Exception {
        javaRoots = root("java");
        userRoots = root("user");
        machineRoots = root("machine");
        unknownRoots = root("unknown");
        javaServer = serverCertificate("java", "java-server", "ip:127.0.0.1", false);
        machineServer = serverCertificate("machine", "machine-server", "ip:127.0.0.1", false);
        wrongHostServer = serverCertificate("machine", "wrong-host", "dns:wrong.invalid", false);
        expiredServer = serverCertificate("machine", "expired", "ip:127.0.0.1", true);
    }

    @Test void mergesJavaCurrentUserAndMachineRootsWithoutDuplicates() throws Exception {
        Set<String> requested = new HashSet<>();
        var manager = OfficialHttp.windowsTrustManager(manager(javaRoots), type -> {
            requested.add(type);
            return type.equals("Windows-ROOT-LOCALMACHINE") ? machineRoots : userRoots;
        });
        assertEquals(Set.of("Windows-ROOT-CURRENTUSER", "Windows-ROOT-LOCALMACHINE", "Windows-ROOT"), requested);
        assertEquals(3, manager.getAcceptedIssuers().length);
        assertTrue(manager instanceof X509ExtendedTrustManager);
        assertEquals("ok", request(javaServer, manager));
        assertEquals("ok", request(machineServer, manager));
    }

    @Test void unavailableWindowsStoresKeepJavaTrust() throws Exception {
        var manager = OfficialHttp.windowsTrustManager(manager(javaRoots), type -> {
            throw new KeyStoreException("Provider unavailable in this test");
        });
        assertEquals(1, manager.getAcceptedIssuers().length);
        assertEquals("ok", request(javaServer, manager));
        assertThrows(SSLHandshakeException.class, () -> request(machineServer, manager));
    }

    @Test void emptyUserStoreStillUsesMachineRoots() throws Exception {
        KeyStore empty = KeyStore.getInstance("PKCS12"); empty.load(null, null);
        var manager = OfficialHttp.windowsTrustManager(null,
                type -> type.equals("Windows-ROOT-LOCALMACHINE") ? machineRoots : empty);
        assertEquals("ok", request(machineServer, manager));
        assertThrows(GeneralSecurityException.class,
                () -> OfficialHttp.windowsTrustManager(null, type -> empty));
    }

    @Test void mergedRootsStillRejectUnknownIssuersWrongHostAndExpiredCertificates() throws Exception {
        var manager = OfficialHttp.windowsTrustManager(manager(javaRoots), type -> machineRoots);
        assertThrows(SSLHandshakeException.class, () -> request(unknownRoots, manager));
        assertThrows(SSLHandshakeException.class, () -> request(wrongHostServer, manager));
        assertThrows(SSLHandshakeException.class, () -> request(expiredServer, manager));
    }

    @Test void explicitJvmTrustStoreSettingsRetainPriority() throws Exception {
        SSLContext standard = SSLContext.getDefault();
        for (String name : List.of("javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType",
                "javax.net.ssl.trustStoreProvider", "javax.net.ssl.trustStorePassword")) {
            String old = System.getProperty(name);
            try {
                System.setProperty(name, "external-test-setting");
                try (HttpClient client = OfficialHttp.client(Duration.ofSeconds(2))) {
                    assertSame(standard, client.sslContext(), name);
                }
                String diagnostic = String.join("\n", OfficialHttp.diagnostics());
                assertTrue(diagnostic.contains(name.substring("javax.net.ssl.".length())));
                assertFalse(diagnostic.contains("external-test-setting"));
            } finally {
                if (old == null) System.clearProperty(name); else System.setProperty(name, old);
            }
        }
    }

    @Test void compatibilityCertificateMatchesThePublishedCertumRoot() throws Exception {
        var certificate = OfficialCa.certificate();
        assertEquals(OfficialCa.SHA256, HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        assertTrue(certificate.getBasicConstraints() >= 0);
        certificate.checkValidity();
        certificate.verify(certificate.getPublicKey());
    }

    @Test void compatibilityRootPreservesPlatformTrustAndAllHttpsChecks() throws Exception {
        var trusted = OfficialHttp.officialTrustManager(manager(machineRoots));
        assertEquals(2, trusted.getAcceptedIssuers().length);
        assertTrue(Arrays.asList(trusted.getAcceptedIssuers()).contains(OfficialCa.certificate()));
        assertEquals("ok", request(machineServer, trusted));
        assertThrows(SSLHandshakeException.class, () -> request(unknownRoots, trusted));
        assertThrows(SSLHandshakeException.class, () -> request(wrongHostServer, trusted));
        assertThrows(SSLHandshakeException.class, () -> request(expiredServer, trusted));
    }

    @Test void compatibilityRootWorksWithMissingPlatformStoreAndDoesNotTrustArbitraryPeers() throws Exception {
        var trusted = OfficialHttp.officialTrustManager(null);
        assertEquals(1, trusted.getAcceptedIssuers().length);
        assertEquals(OfficialCa.certificate(), trusted.getAcceptedIssuers()[0]);
        assertThrows(SSLHandshakeException.class, () -> request(unknownRoots, trusted));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    void nativeWindowsStoresRetainEveryDefaultJavaRoot() throws Exception {
        var combined = Set.of(OfficialHttp.windowsTrustManager().getAcceptedIssuers());
        assertFalse(combined.isEmpty());
        assertTrue(combined.containsAll(Arrays.asList(manager(null).getAcceptedIssuers())));
    }

    @Test @EnabledOnOs(OS.WINDOWS)
    @EnabledIfEnvironmentVariable(named = "WORDCRAFT_ONLINE_TESTS", matches = "true")
    void officialHttpsEndpointsHaveValidCertificatesOnWindows() throws Exception {
        List<URI> sources = new ArrayList<>(OfficialRepository.MEDIA_URIS);
        sources.add(OfficialRepository.CATALOG_URI);
        try (HttpClient client = OfficialHttp.client(Duration.ofSeconds(20))) {
            for (URI source : sources) {
                var request = HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(30))
                        .header("Range", "bytes=0-0").header("Accept-Encoding", "identity").GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body()) {
                    assertEquals(206, response.statusCode(), source.toString());
                    assertNotEquals(-1, input.read());
                }
            }
        }
    }

    private static X509TrustManager manager(KeyStore roots) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX"); factory.init(roots);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst().orElseThrow();
    }

    private static String request(KeyStore identity, X509TrustManager trust) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(identity, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS"); serverContext.init(keys.getKeyManagers(), null, null);
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            try (var output = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, 2); output.write(new byte[]{'o', 'k'});
            }
        });
        server.start();
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
        try (HttpClient client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(3)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        } finally { server.stop(0); }
    }

    private static KeyStore root(String name) throws Exception {
        keytool("-genkeypair", "-alias", "root", "-keystore", store(name), "-storetype", "PKCS12",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Synthetic " + name,
                "-validity", "3650", "-ext", "bc=ca:true", "-ext", "san=ip:127.0.0.1");
        return load(name);
    }

    private static KeyStore serverCertificate(String root, String name, String san, boolean expired) throws Exception {
        keytool("-genkeypair", "-alias", "server", "-keystore", store(name), "-storetype", "PKCS12",
                "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=Synthetic server");
        Path csr = directory.resolve(name + ".csr"), cert = directory.resolve(name + ".crt");
        keytool("-certreq", "-alias", "server", "-keystore", store(name), "-file", csr.toString());
        List<String> sign = new ArrayList<>(List.of("-gencert", "-alias", "root", "-keystore", store(root),
                "-infile", csr.toString(), "-outfile", cert.toString(), "-ext", "san=" + san,
                "-ext", "eku=serverAuth", "-validity", expired ? "1" : "365"));
        if (expired) sign.addAll(List.of("-startdate", "2020/01/01 00:00:00"));
        keytool(sign.toArray(String[]::new));
        Path ca = directory.resolve(name + "-ca.crt");
        keytool("-exportcert", "-alias", "root", "-keystore", store(root), "-file", ca.toString());
        keytool("-importcert", "-noprompt", "-alias", "trusted-root", "-keystore", store(name), "-file", ca.toString());
        keytool("-importcert", "-noprompt", "-alias", "server", "-keystore", store(name), "-file", cert.toString());
        return load(name);
    }

    private static String store(String name) { return directory.resolve(name + ".p12").toString(); }
    private static KeyStore load(String name) throws Exception { return KeyStore.getInstance(Path.of(store(name)).toFile(), PASSWORD); }
    private static void keytool(String... args) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString()));
        command.addAll(List.of(args)); command.addAll(List.of("-storepass", new String(PASSWORD)));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
}
