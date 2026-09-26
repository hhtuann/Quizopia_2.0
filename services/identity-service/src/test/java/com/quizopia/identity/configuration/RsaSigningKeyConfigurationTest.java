package com.quizopia.identity.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

class RsaSigningKeyConfigurationTest {
    private static final String KID = "wave-1a-test-key";
    private static final String PRIVATE_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_END = "-----END PRIVATE KEY-----";
    private static final String PUBLIC_BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String PUBLIC_END = "-----END PUBLIC KEY-----";

    @TempDir(cleanup = CleanupMode.NEVER)
    Path temporaryDirectory;

    private KeyPair keyPair;
    private Path privateKeyPath;
    private Path publicKeyPath;
    private RsaSigningKeyLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = generateRsaKeyPair();
        privateKeyPath = temporaryDirectory.resolve("signing-key-private.pem");
        publicKeyPath = temporaryDirectory.resolve("signing-key-public.pem");
        Files.writeString(
                privateKeyPath,
                pem(PRIVATE_BEGIN, PRIVATE_END, keyPair.getPrivate().getEncoded()));
        Files.writeString(
                publicKeyPath, pem(PUBLIC_BEGIN, PUBLIC_END, keyPair.getPublic().getEncoded()));
        loader = new RsaSigningKeyLoader();
    }

    @AfterEach
    void cleanUpTemporaryKeyMaterial() throws Exception {
        try (Stream<Path> paths = Files.walk(temporaryDirectory)) {
            paths.sorted((first, second) -> second.compareTo(first)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException("Test key material could not be removed: " + path, exception);
                }
            });
        }
    }

    @Test
    void loadsEphemeralRsaPairAndPreservesConfiguredKid() {
        RSAKey signingJwk = loader.load(properties());

        assertEquals(KID, signingJwk.getKeyID());
        assertEquals(JWSAlgorithm.RS256, signingJwk.getAlgorithm());
        assertTrue(signingJwk.isPrivate());
        assertNotNull(signingJwk.getPrivateExponent());
    }

    @Test
    void signsWithRs256AndVerifiesOnlyWithCorrespondingPublicKey() throws Exception {
        RsaSigningKeyConfiguration configuration = new RsaSigningKeyConfiguration();
        RSAKey signingJwk = configuration.identitySigningJwk(loader, properties());
        JwtEncoder encoder = configuration.identityJwtEncoder(signingJwk);

        String token = encoder.encode(JwtEncoderParameters.from(
                        JwsHeader.with(SignatureAlgorithm.RS256).keyId(KID).build(),
                        JwtClaimsSet.builder()
                                .subject("test-subject")
                                .claim("test", true)
                                .build()))
                .getTokenValue();
        SignedJWT signedJwt = SignedJWT.parse(token);
        JWSVerifier correspondingVerifier =
                new RSASSAVerifier((java.security.interfaces.RSAPublicKey) keyPair.getPublic());

        KeyPair unrelatedKeyPair = generateRsaKeyPair();
        JWSVerifier unrelatedVerifier =
                new RSASSAVerifier((java.security.interfaces.RSAPublicKey) unrelatedKeyPair.getPublic());

        assertEquals(JWSAlgorithm.RS256, signedJwt.getHeader().getAlgorithm());
        assertEquals(KID, signedJwt.getHeader().getKeyID());
        assertTrue(signedJwt.verify(correspondingVerifier));
        assertFalse(signedJwt.verify(unrelatedVerifier));
    }

    @Test
    void publicJwkSetContainsNoPrivateRsaParameters() {
        RsaSigningKeyConfiguration configuration = new RsaSigningKeyConfiguration();
        RSAKey signingJwk = configuration.identitySigningJwk(loader, properties());
        JWKSet publicJwkSet = configuration.identityPublicJwkSet(signingJwk);
        Map<String, ?> publicJwkJson = publicJwkSet.getKeys().getFirst().toJSONObject();
        RSAKey publicJwk = (RSAKey) publicJwkSet.getKeys().getFirst();

        assertFalse(publicJwk.isPrivate());
        assertEquals(KID, publicJwk.getKeyID());
        assertEquals(JWSAlgorithm.RS256, publicJwk.getAlgorithm());
        assertTrue(publicJwkJson.containsKey("n"));
        assertTrue(publicJwkJson.containsKey("e"));
        assertFalse(publicJwkJson.containsKey("d"));
        assertFalse(publicJwkJson.containsKey("p"));
        assertFalse(publicJwkJson.containsKey("q"));
        assertFalse(publicJwkJson.containsKey("dp"));
        assertFalse(publicJwkJson.containsKey("dq"));
        assertFalse(publicJwkJson.containsKey("qi"));
        assertFalse(publicJwkJson.containsKey("oth"));
    }

    @Test
    void rejectsMismatchedPrivateAndPublicKeys() throws Exception {
        KeyPair otherKeyPair = generateRsaKeyPair();
        Files.writeString(
                publicKeyPath,
                pem(PUBLIC_BEGIN, PUBLIC_END, otherKeyPair.getPublic().getEncoded()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(properties()));

        assertEquals("RSA private and public signing keys do not match", exception.getMessage());
    }

    @Test
    void rejectsMalformedPrivatePem() throws Exception {
        Files.writeString(privateKeyPath, "not-a-private-key", StandardCharsets.US_ASCII);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(properties()));

        assertTrue(exception.getMessage().contains("unsupported or malformed format"));
    }

    @Test
    void rejectsMissingPublicKeyFile() throws Exception {
        Files.delete(publicKeyPath);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(properties()));

        assertTrue(exception.getMessage().contains("public key file does not exist"));
    }

    @Test
    void rejectsRsaKeyBelow2048Bits() throws Exception {
        KeyPair weakKeyPair = generateRsaKeyPair(1024);
        Files.writeString(
                privateKeyPath,
                pem(PRIVATE_BEGIN, PRIVATE_END, weakKeyPair.getPrivate().getEncoded()));
        Files.writeString(
                publicKeyPath,
                pem(PUBLIC_BEGIN, PUBLIC_END, weakKeyPair.getPublic().getEncoded()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(properties()));

        assertEquals("RSA signing key must be at least 2048 bits; actual size: 1024", exception.getMessage());
    }

    @Test
    void rejectsWrongPublicKeyType() throws Exception {
        KeyPair ecKeyPair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        Files.writeString(
                publicKeyPath,
                pem(PUBLIC_BEGIN, PUBLIC_END, ecKeyPair.getPublic().getEncoded()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> loader.load(properties()));

        assertTrue(exception.getMessage().contains("not a valid X.509 SubjectPublicKeyInfo RSA key"));
    }

    private RsaSigningKeyProperties properties() {
        RsaSigningKeyProperties properties = new RsaSigningKeyProperties();
        properties.setKid(KID);
        properties.setPrivateKeyPath(privateKeyPath.toString());
        properties.setPublicKeyPath(publicKeyPath.toString());
        return properties;
    }

    private static KeyPair generateRsaKeyPair() throws Exception {
        return generateRsaKeyPair(2048);
    }

    private static KeyPair generateRsaKeyPair(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    private static String pem(String begin, String end, byte[] encoded) {
        return begin
                + System.lineSeparator()
                + java.util.Base64.getMimeEncoder(64, System.lineSeparator().getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(encoded)
                + System.lineSeparator()
                + end
                + System.lineSeparator();
    }
}
