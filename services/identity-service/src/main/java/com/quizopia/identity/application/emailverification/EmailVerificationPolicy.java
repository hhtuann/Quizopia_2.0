package com.quizopia.identity.application.emailverification;

import java.time.Duration;
import java.util.Objects;

/** Email-verification challenge policy. */
public record EmailVerificationPolicy(Duration expiry, int maxAttempts, Duration resendCooldown) {
    private static final EmailVerificationPolicy PRODUCTION =
            new EmailVerificationPolicy(Duration.ofMinutes(10), 5, Duration.ofSeconds(60));

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

    public static EmailVerificationPolicy production() {
        return PRODUCTION;
    }
}
