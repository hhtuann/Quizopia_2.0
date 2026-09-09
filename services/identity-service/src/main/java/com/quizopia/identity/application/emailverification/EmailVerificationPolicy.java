package com.quizopia.identity.application.emailverification;

import java.time.Duration;
import java.util.Objects;

/** Explicit per-issuance values. Production policy remains unresolved under ID-02. */
public record EmailVerificationPolicy(Duration expiry, int maxAttempts, Duration resendCooldown) {
    public EmailVerificationPolicy {
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(resendCooldown, "resendCooldown");
        if (expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("expiry must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        // Zero permits immediate replacement; requiring a positive delay would select policy.
        if (resendCooldown.isNegative()) {
            throw new IllegalArgumentException("resendCooldown must not be negative");
        }
    }
}
