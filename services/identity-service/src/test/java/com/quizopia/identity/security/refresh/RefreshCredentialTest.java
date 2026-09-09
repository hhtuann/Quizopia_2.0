package com.quizopia.identity.security.refresh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RefreshCredentialTest {
    private final RefreshCredentialGenerator generator = new RefreshCredentialGenerator();
    private final RefreshCredentialHasher hasher = new RefreshCredentialHasher();

    @Test
    void repeatedGenerationProducesDifferentOpaqueCredentials() {
        RawRefreshCredential first = generator.generate();
        RawRefreshCredential second = generator.generate();

        assertNotEquals(first.value(), second.value());
    }

    @Test
    void generatedCredentialHasExpectedEntropyAndUrlSafeRepresentation() {
        RawRefreshCredential credential = generator.generate();

        assertEquals(43, credential.value().length());
        assertEquals(
                RefreshCredentialGenerator.ENTROPY_BYTES, Base64.getUrlDecoder().decode(credential.value()).length);
        assertFalse(credential.value().contains("="));
        assertFalse(credential.value().matches(".*[^A-Za-z0-9_-].*"));
    }

    @Test
    void hashingIsDeterministic() {
        RawRefreshCredential credential = generator.generate();

        assertArrayEquals(hasher.hash(credential), hasher.hash(credential));
        assertEquals(RefreshCredentialHasher.HASH_BYTES, hasher.hash(credential).length);
    }

    @Test
    void distinctCredentialsProduceDistinctHashes() {
        byte[] firstHash = hasher.hash(generator.generate());
        byte[] secondHash = hasher.hash(generator.generate());

        assertFalse(Arrays.equals(firstHash, secondHash));
    }

    @Test
    void hashIsNotRawCredentialBytes() {
        RawRefreshCredential credential = generator.generate();

        assertFalse(Arrays.equals(credential.value().getBytes(StandardCharsets.UTF_8), hasher.hash(credential)));
    }

    @Test
    void wrapperStringRepresentationDoesNotExposeCredential() {
        RawRefreshCredential credential = generator.generate();

        assertFalse(credential.toString().contains(credential.value()));
    }
}
