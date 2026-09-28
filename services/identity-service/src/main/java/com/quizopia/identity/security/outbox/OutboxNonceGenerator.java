package com.quizopia.identity.security.outbox;

public interface OutboxNonceGenerator {
    byte[] generate();
}
