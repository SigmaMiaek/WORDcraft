package pl.wordcraft.download;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/** Published public CA for the current gov.pl chain, scoped to this downloader. */
final class OfficialCa {
    static final String SHA256 = "fe7696573855773e37a95e7ad4d9cc96c30157c15d31765ba9b15704e1ae78fd";
    private OfficialCa() {}

    static X509Certificate certificate() throws GeneralSecurityException, IOException {
        try (var input = OfficialCa.class.getResourceAsStream(
                "/META-INF/wordcraft-certificates/certum-trusted-root-ca.pem")) {
            if (input == null) throw new IOException("Missing official CA compatibility certificate");
            var certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
            if (!SHA256.equals(digest)) throw new GeneralSecurityException("Official CA certificate fingerprint mismatch");
            certificate.checkValidity();
            if (certificate.getBasicConstraints() < 0
                    || !certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal())) {
                throw new GeneralSecurityException("Official CA certificate is not a self-signed root CA");
            }
            certificate.verify(certificate.getPublicKey());
            return certificate;
        }
    }
}
