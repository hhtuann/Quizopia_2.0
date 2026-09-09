package com.quizopia.identity.configuration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class RsaSigningKeyLoader {
    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END = "-----END PUBLIC KEY-----";
    private static final byte[] KEY_PAIR_CHECK_MESSAGE =
            "quizopia-rsa-signing-key-pair-check".getBytes(StandardCharsets.US_ASCII);

    public RSAKey load(RsaSigningKeyProperties properties) {
        if (properties == null) {
            throw new IllegalStateException("RSA signing key configuration is required");
        }

        String kid = requiredValue(properties.getKid(), "quizopia.identity.security.signing-key.kid");
        Path privateKeyPath =
                requiredPath(properties.getPrivateKeyPath(), "quizopia.identity.security.signing-key.private-key-path");
        Path publicKeyPath =
                requiredPath(properties.getPublicKeyPath(), "quizopia.identity.security.signing-key.public-key-path");

        RSAPrivateKey privateKey = parsePrivateKey(privateKeyPath);
        RSAPublicKey publicKey = parsePublicKey(publicKeyPath);
        validateKeySize(publicKey);
        validateKeyPair(privateKey, publicKey);

        return new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(kid)
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
    }

    private static RSAPrivateKey parsePrivateKey(Path path) {
        byte[] encoded = readPem(path, PRIVATE_BEGIN, PRIVATE_END, "RSA private");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | ClassCastException exception) {
            throw new IllegalStateException("RSA private key PEM is not a valid PKCS#8 RSA key: " + path, exception);
        }
    }

    private static RSAPublicKey parsePublicKey(Path path) {
        byte[] encoded = readPem(path, PUBLIC_BEGIN, PUBLIC_END, "RSA public");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException | ClassCastException exception) {
            throw new IllegalStateException(
                    "RSA public key PEM is not a valid X.509 SubjectPublicKeyInfo RSA key: " + path, exception);
        }
    }

    private static byte[] readPem(Path path, String beginMarker, String endMarker, String keyDescription) {
        String pem;
        try {
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException(
                        keyDescription + " key file does not exist or is not a regular file: " + path);
            }
            pem = Files.readString(path, StandardCharsets.US_ASCII).trim();
        } catch (IOException exception) {
            throw new IllegalStateException(keyDescription + " key file could not be read: " + path, exception);
        }

        if (!pem.startsWith(beginMarker) || !pem.endsWith(endMarker)) {
            throw new IllegalStateException(
                    keyDescription + " key PEM has an unsupported or malformed format: " + path);
        }

        String base64 = pem.substring(beginMarker.length(), pem.length() - endMarker.length())
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(keyDescription + " key PEM contains invalid Base64: " + path, exception);
        }
    }

    private static String requiredValue(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required RSA signing key property is missing: " + propertyName);
        }
        return value.trim();
    }

    private static Path requiredPath(String value, String propertyName) {
        String pathValue = requiredValue(value, propertyName);
        try {
            return Path.of(pathValue);
        } catch (InvalidPathException exception) {
            throw new IllegalStateException("RSA signing key property is not a valid path: " + propertyName, exception);
        }
    }

    private static void validateKeySize(RSAPublicKey publicKey) {
        int keySize = publicKey.getModulus().bitLength();
        if (keySize < 2048) {
            throw new IllegalStateException("RSA signing key must be at least 2048 bits; actual size: " + keySize);
        }
    }

    private static void validateKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(KEY_PAIR_CHECK_MESSAGE);
            byte[] signatureBytes = signature.sign();

            signature.initVerify(publicKey);
            signature.update(KEY_PAIR_CHECK_MESSAGE);
            if (!signature.verify(signatureBytes)) {
                throw new IllegalStateException("RSA private and public signing keys do not match");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("RSA private and public signing keys could not be validated", exception);
        }
    }
}
