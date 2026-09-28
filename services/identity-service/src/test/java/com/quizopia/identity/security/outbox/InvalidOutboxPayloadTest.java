package com.quizopia.identity.security.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.quizopia.identity.configuration.ConfiguredOutboxPayloadKeyRing;
import com.quizopia.identity.configuration.OutboxPayloadEncryptionProperties;
import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvalidOutboxPayloadTest {
    @Test
    void authenticatedNonSixDigitPlaintextFailsClosed() {
        var properties = new OutboxPayloadEncryptionProperties();
        properties.setActiveKeyVersion("test-v1");
        properties.setKeys(Map.of(
                "test-v1",
                Base64.getEncoder()
                        .encodeToString("0123456789abcdef0123456789abcdef"
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
        var cipher = new AesGcmOutboxPayloadCipher(new ConfiguredOutboxPayloadKeyRing(properties), () -> new byte[12]);
        var binding = new OutboxPayloadBinding(
                UUID.randomUUID(),
                "person@gmail.com",
                "EMAIL_VERIFICATION_OTP",
                Instant.parse("2026-09-23T04:10:00.123456Z"),
                1);
        RawEmailVerificationOtp invalidTestMaterial = mock(RawEmailVerificationOtp.class);
        when(invalidTestMaterial.value()).thenReturn("not-six-digits");
        EncryptedOutboxPayload encrypted = cipher.encrypt(invalidTestMaterial, binding);

        OutboxPayloadDecryptionException failure =
                assertThrows(OutboxPayloadDecryptionException.class, () -> cipher.decrypt(encrypted, binding));

        assertEquals(OutboxPayloadDecryptionException.Category.DECRYPTION_FAILED, failure.category());
    }
}
