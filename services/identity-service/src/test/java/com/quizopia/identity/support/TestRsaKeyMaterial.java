package com.quizopia.identity.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Comparator;
import java.util.stream.Stream;

public record TestRsaKeyMaterial(Path directory, Path privateKeyPath, Path publicKeyPath) implements AutoCloseable {
    public static TestRsaKeyMaterial create(String prefix) {
        try {
            Path directory = Files.createTempDirectory(prefix);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keyPair = generator.generateKeyPair();
            Path privateKeyPath = directory.resolve("private-key.pem");
            Path publicKeyPath = directory.resolve("public-key.pem");
            Files.writeString(
                    privateKeyPath, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
            Files.writeString(
                    publicKeyPath, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
            return new TestRsaKeyMaterial(directory, privateKeyPath, publicKeyPath);
        } catch (Exception exception) {
            throw new IllegalStateException("Test key material could not be created", exception);
        }
    }

    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String pem(String type, byte[] encoded) {
        return "-----BEGIN "
                + type
                + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(encoded)
                + "\n-----END "
                + type
                + "-----\n";
    }
}
