package com.quizopia.identity.application.emailverification.delivery;

import java.util.Objects;

public final class VerificationEmailDeliveryException extends RuntimeException {
    public enum Category {
        SMTP_TRANSIENT_FAILURE,
        SMTP_PERMANENT_FAILURE
    }

    private final Category category;

    public VerificationEmailDeliveryException(Category category) {
        super("Verification email delivery failed: " + Objects.requireNonNull(category, "category"));
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
