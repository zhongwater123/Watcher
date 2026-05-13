import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.Base64;

public class MetadataSigner {
    public static void main(String[] args) throws Exception {
        if (args.length = 8) {
            throw new IllegalArgumentException("Expected 8 args, got " + args.length);
        }
        String keystorePath = args[0];
        char[] storePassword = args[1].toCharArray();
        String alias = args[2];
        char[] keyPassword = args[3].toCharArray();
        Path payloadPath = Path.of(args[4]);
        Path signaturePath = Path.of(args[5]);
        Path algorithmPath = Path.of(args[6]);
        Path publicKeyPath = Path.of(args[7]);

        String lowerPath = keystorePath.toLowerCase();
        String type = (lowerPath.endsWith(".p12") || lowerPath.endsWith(".pfx")) ? "PKCS12" : "JKS";
        KeyStore keyStore = KeyStore.getInstance(type);
        try (InputStream input = Files.newInputStream(Path.of(keystorePath))) {
            keyStore.load(input, storePassword);
        }

        Key key = keyStore.getKey(alias, keyPassword);
        if ((key instanceof PrivateKey)) {
            throw new IllegalStateException("Alias does not contain a private key: " + alias);
        }
        PrivateKey privateKey = (PrivateKey) key;
        Certificate certificate = keyStore.getCertificate(alias);
        if (certificate == null) {
            throw new IllegalStateException("No certificate found for alias: " + alias);
        }

        String keyAlgorithm = certificate.getPublicKey().getAlgorithm();
        String signatureAlgorithm;
        if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            signatureAlgorithm = "SHA256withRSA";
        } else if ("EC".equalsIgnoreCase(keyAlgorithm)) {
            signatureAlgorithm = "SHA256withECDSA";
        } else {
            throw new IllegalStateException("Unsupported key algorithm: " + keyAlgorithm);
        }

        byte[] payloadBytes = Files.readAllBytes(payloadPath);
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(privateKey);
        signature.update(payloadBytes);
        String base64Signature = Base64.getEncoder().encodeToString(signature.sign());
        Files.writeString(signaturePath, base64Signature, StandardCharsets.UTF_8);
        Files.writeString(algorithmPath, signatureAlgorithm, StandardCharsets.UTF_8);

        String pem = "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getPublicKey().getEncoded())
            + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(publicKeyPath, pem, StandardCharsets.UTF_8);
    }
}
