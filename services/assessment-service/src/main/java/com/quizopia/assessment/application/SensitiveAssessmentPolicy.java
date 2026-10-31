package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** No deployment flag can approve pending privacy/retention/key-custody policy.
 * Tests supply an isolated synthetic permit; the application always fails closed. */
public interface SensitiveAssessmentPolicy {
    Instant expiresAt(Instant now);

    default boolean disclosureAvailable() {
        return false;
    }

    default void requireActor(UUID actor) {}

    default boolean demo() {
        return false;
    }

    default Instant observationExpiry(Instant received) {
        return expiresAt(received);
    }

    @Component
    @Profile("!academic-demo")
    final class Pending implements SensitiveAssessmentPolicy {
        public Instant expiresAt(Instant now) {
            throw new PublicationFailure(503, "SENSITIVE_POLICY_PENDING");
        }
    }
}
