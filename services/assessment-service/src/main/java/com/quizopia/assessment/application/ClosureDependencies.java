package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;

/** Remote authority/readiness checks must run outside local transactions. */
public interface ClosureDependencies {
    record Eligibility(UUID studentId, boolean eligible, Instant checkedAt, long validUntilNanos) {
        public Eligibility(UUID studentId, boolean eligible, Instant checkedAt) {
            this(studentId, eligible, checkedAt, System.nanoTime() + 5_000_000_000L);
        }

        public String toString() {
            return "Eligibility[redacted]";
        }
    }

    void requireInbox();

    Eligibility eligible(UUID student);

    void teacher(ClosureStore.Teacher actor);
}
