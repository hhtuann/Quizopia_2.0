package com.quizopia.identity.security.emailverification;

import java.util.Objects;

/** Transient six-digit email-verification material with redacted diagnostics. */
public final class RawEmailVerificationOtp {
    private final String value;

    private RawEmailVerificationOtp(String value) {
        this.value = value;
    }

    public static RawEmailVerificationOtp from(String value) {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("Email verification OTP must contain exactly six decimal digits");
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
