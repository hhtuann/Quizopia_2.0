package com.quizopia.identity.security.outbox;

public final class OutboxPayloadDecryptionException extends RuntimeException {
    public enum Category {
        MISSING_KEY,
        AUTHENTICATION_FAILED,
        DECRYPTION_FAILED
    }

    private final Category category;

    public OutboxPayloadDecryptionException(Category category) {
        super("Outbox payload decryption failed: " + category);
        this.category = category;
    }

    public Category category() {
        return category;
    }
}
