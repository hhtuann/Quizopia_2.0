package com.quizopia.identity.security.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.identity.configuration.ConfiguredOutboxPayloadKeyRing;
import com.quizopia.identity.configuration.OutboxPayloadEncryptionProperties;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OutboxPayloadCipherTest {
    private static final String OTP = "012345";
    private static final String KEY_ONE = Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    private static final String KEY_TWO = Base64.getEncoder()
            .encodeToString("abcdef0123456789abcdef0123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    private static final UUID JOB_ID = UUID.fromString("173b2907-3337-4a65-a980-e380768ece9f");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-23T10:10:00.123456Z");

    @Test
    void aes256GcmRoundTripUsesNinetySixBitNonceAndSixDigitPlaintext() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));
        OutboxPayloadBinding binding = binding();

        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding);

        assertEquals(12, encrypted.nonce().length);
        assertEquals(22, encrypted.ciphertext().length);
        assertFalse(Arrays.equals(OTP.getBytes(java.nio.charset.StandardCharsets.US_ASCII), encrypted.ciphertext()));
        assertEquals(OTP, cipher.decrypt(encrypted, binding).value());
    }

    @Test
    void canonicalVersionOnePayloadMatchesCompatibilityVector() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));

        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());

        assertEquals("77NmmQaYUUUUkQDRcPqztn5YK2xShQ==", Base64.getEncoder().encodeToString(encrypted.ciphertext()));
    }

    @Test
    void eachEncryptionUsesTheNextFreshNonce() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1), nonce(2));

        EncryptedOutboxPayload first = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        EncryptedOutboxPayload second = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());

        assertNotEquals(
                Base64.getEncoder().encodeToString(first.nonce()),
                Base64.getEncoder().encodeToString(second.nonce()));
    }

    @Test
    void secureProductionNonceGeneratorReturnsFreshNinetySixBitValues() {
        SecureOutboxNonceGenerator generator = new SecureOutboxNonceGenerator();

        byte[] first = generator.generate();
        byte[] second = generator.generate();

        assertEquals(12, first.length);
        assertEquals(12, second.length);
        assertFalse(Arrays.equals(first, second));
    }

    @Test
    void nonceGenerationFailureIsSanitized() {
        OutboxPayloadKeyRing keyRing = new ConfiguredOutboxPayloadKeyRing(properties("v1", Map.of("v1", KEY_ONE)));
        AesGcmOutboxPayloadCipher cipher = new AesGcmOutboxPayloadCipher(keyRing, () -> {
            throw new IllegalStateException("sensitive nonce failure " + OTP + " " + KEY_ONE);
        });

        OutboxPayloadEncryptionException exception = assertThrows(
                OutboxPayloadEncryptionException.class,
                () -> cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding()));

        assertFalse(exception.toString().contains(OTP));
        assertFalse(exception.toString().contains(KEY_ONE));
    }

    @Test
    void bindingRejectsExpiryThatCannotRoundTripThroughPostgresql() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OutboxPayloadBinding(
                        JOB_ID, "learner@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT.plusNanos(1), 1));
    }

    @Test
    void configurationRequiresActiveVersionAndExactlyThirtyTwoDecodedBytes() {
        assertThrows(IllegalStateException.class, () -> new ConfiguredOutboxPayloadKeyRing(properties(null, Map.of())));
        assertThrows(
                IllegalStateException.class,
                () -> new ConfiguredOutboxPayloadKeyRing(properties("missing", Map.of("v1", KEY_ONE))));
        assertThrows(
                IllegalStateException.class,
                () -> new ConfiguredOutboxPayloadKeyRing(properties("v1", Map.of("v1", "not-base64"))));
        assertThrows(
                IllegalStateException.class,
                () -> new ConfiguredOutboxPayloadKeyRing(
                        properties("v1", Map.of("v1", Base64.getEncoder().encodeToString(new byte[31])))));
    }

    @Test
    void activeVersionChangesNewEncryptionWhileHistoricalKeysRemainUsable() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("v1", KEY_ONE);
        keys.put("v2", KEY_TWO);
        AesGcmOutboxPayloadCipher firstCipher = cipher("v1", keys, nonce(1));
        AesGcmOutboxPayloadCipher secondCipher = cipher("v2", keys, nonce(2));

        EncryptedOutboxPayload first = firstCipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        EncryptedOutboxPayload second = secondCipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());

        assertEquals("v1", first.keyVersion());
        assertEquals("v2", second.keyVersion());
        assertEquals(OTP, secondCipher.decrypt(first, binding()).value());
        assertEquals(OTP, secondCipher.decrypt(second, binding()).value());
    }

    @ParameterizedTest
    @MethodSource("tamperedBindings")
    void everyBoundAadDimensionRejectsTampering(OutboxPayloadBinding tamperedBinding) {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));
        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());

        OutboxPayloadDecryptionException exception =
                assertThrows(OutboxPayloadDecryptionException.class, () -> cipher.decrypt(encrypted, tamperedBinding));

        assertEquals(OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED, exception.category());
    }

    @Test
    void keyVersionIsAuthenticatedAdditionalData() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE, "v2", KEY_ONE), nonce(1));
        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        EncryptedOutboxPayload tampered = new EncryptedOutboxPayload(
                encrypted.ciphertext(), encrypted.nonce(), "v2", encrypted.payloadFormatVersion());

        OutboxPayloadDecryptionException exception =
                assertThrows(OutboxPayloadDecryptionException.class, () -> cipher.decrypt(tampered, binding()));

        assertEquals(OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED, exception.category());
    }

    @Test
    void ciphertextTamperingFailsAuthentication() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));
        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        byte[] changedCiphertext = encrypted.ciphertext();
        changedCiphertext[0] ^= 1;
        EncryptedOutboxPayload tampered = new EncryptedOutboxPayload(
                changedCiphertext, encrypted.nonce(), encrypted.keyVersion(), encrypted.payloadFormatVersion());

        OutboxPayloadDecryptionException exception =
                assertThrows(OutboxPayloadDecryptionException.class, () -> cipher.decrypt(tampered, binding()));

        assertEquals(OutboxPayloadDecryptionException.Category.AUTHENTICATION_FAILED, exception.category());
    }

    @Test
    void missingHistoricalKeyHasDistinctSafeCategory() {
        AesGcmOutboxPayloadCipher encryptingCipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));
        EncryptedOutboxPayload encrypted = encryptingCipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        AesGcmOutboxPayloadCipher decryptingCipher = cipher("v2", Map.of("v2", KEY_TWO), nonce(2));

        OutboxPayloadDecryptionException exception = assertThrows(
                OutboxPayloadDecryptionException.class, () -> decryptingCipher.decrypt(encrypted, binding()));

        assertEquals(OutboxPayloadDecryptionException.Category.MISSING_KEY, exception.category());
    }

    @Test
    void diagnosticsDoNotExposeSensitiveMaterial() {
        AesGcmOutboxPayloadCipher cipher = cipher("v1", Map.of("v1", KEY_ONE), nonce(1));
        EncryptedOutboxPayload encrypted = cipher.encrypt(RawEmailVerificationOtp.from(OTP), binding());
        String ciphertext = Base64.getEncoder().encodeToString(encrypted.ciphertext());
        String nonce = Base64.getEncoder().encodeToString(encrypted.nonce());
        byte[] changedCiphertext = encrypted.ciphertext();
        changedCiphertext[0] ^= 1;
        OutboxPayloadDecryptionException exception = assertThrows(
                OutboxPayloadDecryptionException.class,
                () -> cipher.decrypt(
                        new EncryptedOutboxPayload(
                                changedCiphertext,
                                encrypted.nonce(),
                                encrypted.keyVersion(),
                                encrypted.payloadFormatVersion()),
                        binding()));

        for (String diagnostic : new String[] {encrypted.toString(), binding().toString(), exception.toString()}) {
            assertFalse(diagnostic.contains(OTP));
            assertFalse(diagnostic.contains(KEY_ONE));
            assertFalse(diagnostic.contains(ciphertext));
            assertFalse(diagnostic.contains(nonce));
        }
    }

    static Stream<Arguments> tamperedBindings() {
        return Stream.of(
                Arguments.of(new OutboxPayloadBinding(
                        UUID.randomUUID(), "learner@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT, 1)),
                Arguments.of(
                        new OutboxPayloadBinding(JOB_ID, "other@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT, 1)),
                Arguments.of(new OutboxPayloadBinding(JOB_ID, "learner@gmail.com", "OTHER_TEMPLATE", EXPIRES_AT, 1)),
                Arguments.of(new OutboxPayloadBinding(
                        JOB_ID, "learner@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT.plusSeconds(1), 1)),
                Arguments.of(new OutboxPayloadBinding(
                        JOB_ID, "learner@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT, 2)));
    }

    private static OutboxPayloadBinding binding() {
        return new OutboxPayloadBinding(JOB_ID, "learner@gmail.com", "EMAIL_VERIFICATION_OTP", EXPIRES_AT, 1);
    }

    private static AesGcmOutboxPayloadCipher cipher(String activeVersion, Map<String, String> keys, byte[]... nonces) {
        return new AesGcmOutboxPayloadCipher(
                new ConfiguredOutboxPayloadKeyRing(properties(activeVersion, keys)),
                new ControlledNonceGenerator(nonces));
    }

    private static OutboxPayloadEncryptionProperties properties(String activeVersion, Map<String, String> keys) {
        OutboxPayloadEncryptionProperties properties = new OutboxPayloadEncryptionProperties();
        properties.setActiveKeyVersion(activeVersion);
        properties.setKeys(keys);
        return properties;
    }

    private static byte[] nonce(int lastByte) {
        byte[] nonce = new byte[12];
        nonce[nonce.length - 1] = (byte) lastByte;
        return nonce;
    }

    private static final class ControlledNonceGenerator implements OutboxNonceGenerator {
        private final ArrayDeque<byte[]> nonces;

        private ControlledNonceGenerator(byte[][] nonces) {
            this.nonces = new ArrayDeque<>();
            for (byte[] nonce : nonces) {
                this.nonces.add(Arrays.copyOf(nonce, nonce.length));
            }
        }

        @Override
        public byte[] generate() {
            return Arrays.copyOf(nonces.remove(), 12);
        }
    }
}
