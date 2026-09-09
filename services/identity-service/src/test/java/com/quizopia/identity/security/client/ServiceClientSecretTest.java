package com.quizopia.identity.security.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.serviceclient.ServiceClientRegistration;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class ServiceClientSecretTest {
    private final ServiceClientSecretGenerator generator = new ServiceClientSecretGenerator();
    private final ServiceClientSecretHasher hasher =
            new ServiceClientSecretHasher(PasswordEncoderFactories.createDelegatingPasswordEncoder());

    @Test
    void repeatedGenerationProducesDifferentSecrets() {
        RawServiceClientSecret first = generator.generate();
        RawServiceClientSecret second = generator.generate();

        assertNotEquals(first.value(), second.value());
    }

    @Test
    void generatedSecretHasExpectedEntropyAndUrlSafeRepresentation() {
        RawServiceClientSecret secret = generator.generate();

        assertEquals(43, secret.value().length());
        assertEquals(
                ServiceClientSecretGenerator.ENTROPY_BYTES,
                Base64.getUrlDecoder().decode(secret.value()).length);
        assertFalse(secret.value().contains("="));
        assertTrue(secret.value().matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void encodedSecretMatchesOnlyTheOriginalSecret() {
        RawServiceClientSecret secret = generator.generate();
        String encoded = hasher.encode(secret);

        assertNotEquals(secret.value(), encoded);
        assertTrue(encoded.startsWith("{bcrypt}"));
        assertTrue(hasher.matches(secret, encoded));
        assertFalse(hasher.matches(new RawServiceClientSecret("unrelated-secret"), encoded));
    }

    @Test
    void rawSecretStringRepresentationDoesNotExposeSecret() {
        RawServiceClientSecret secret = generator.generate();

        assertFalse(secret.toString().contains(secret.value()));
    }

    @Test
    void registrationStringRepresentationDoesNotExposeSecret() {
        RawServiceClientSecret secret = generator.generate();
        ServiceClientRegistration registration =
                new ServiceClientRegistration(UUID.randomUUID(), "test-service-client", secret);

        assertFalse(registration.toString().contains(secret.value()));
    }
}
