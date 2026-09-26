package com.quizopia.identity.security.emailverification;

import java.util.Objects;

/** Transient caller-supplied OTP material; this type deliberately defines no OTP format. */
public final class RawEmailVerificationOtp {
    private final String value;

    private RawEmailVerificationOtp(String value) {
        this.value = value;
    }

    public static RawEmailVerificationOtp from(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Email verification OTP must not be blank");
        }
        return new RawEmailVerificationOtp(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED_EMAIL_VERIFICATION_OTP]";
    }
}
