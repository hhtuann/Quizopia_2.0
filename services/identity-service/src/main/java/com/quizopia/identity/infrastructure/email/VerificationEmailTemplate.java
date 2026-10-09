package com.quizopia.identity.infrastructure.email;

import com.quizopia.identity.application.emailverification.delivery.VerificationEmailMessage;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class VerificationEmailTemplate {
    static final String SUBJECT = "Quizopia email verification code";

    RenderedVerificationEmail render(VerificationEmailMessage message) {
        Objects.requireNonNull(message, "message");
        String body =
                """
                Verify your Quizopia email address with this six-digit code:

                %s

                Quizopia username: %s

                This code expires at %s. It is valid for no more than 60 seconds.
                If you did not request this code, you can ignore this message.
                """
                        .formatted(
                                message.otp().value(),
                                message.exactUsername(),
                                DateTimeFormatter.ISO_INSTANT.format(message.otpExpiresAt()));
        return new RenderedVerificationEmail(SUBJECT, body);
    }

    record RenderedVerificationEmail(String subject, String body) {
        RenderedVerificationEmail {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(body, "body");
        }

        @Override
        public String toString() {
            return "RenderedVerificationEmail{subjectPresent=true, body=[REDACTED]}";
        }
    }
}
