package com.quizopia.identity.application.emailverification.delivery;

import com.quizopia.identity.security.emailverification.RawEmailVerificationOtp;
import java.time.Instant;
import java.util.Objects;

public record VerificationEmailMessage(
        String exactRecipientEmail, String exactUsername, RawEmailVerificationOtp otp, Instant otpExpiresAt) {
    public VerificationEmailMessage {
        Objects.requireNonNull(exactRecipientEmail, "exactRecipientEmail");
        Objects.requireNonNull(exactUsername, "exactUsername");
        Objects.requireNonNull(otp, "otp");
        Objects.requireNonNull(otpExpiresAt, "otpExpiresAt");
        if (exactRecipientEmail.isBlank() || exactUsername.isBlank()) {
            throw new IllegalArgumentException("Verification email identity must not be blank");
        }
    }

    @Override
    public String toString() {
        return "VerificationEmailMessage{recipientPresent=true, usernamePresent=true, otp=[REDACTED], otpExpiresAt="
                + otpExpiresAt + '}';
    }
}
