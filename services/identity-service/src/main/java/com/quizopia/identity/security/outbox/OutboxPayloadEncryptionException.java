package com.quizopia.identity.security.outbox;

public final class OutboxPayloadEncryptionException extends RuntimeException {
    public OutboxPayloadEncryptionException() {
        super("Outbox payload encryption failed");
    }
}
