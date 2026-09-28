package com.quizopia.identity.security.outbox;

import java.security.SecureRandom;

public final class SecureOutboxNonceGenerator implements OutboxNonceGenerator {
    public static final int NONCE_LENGTH_BYTES = 12;

    private final SecureRandom secureRandom;

    public SecureOutboxNonceGenerator() {
        this(new SecureRandom());
    }

    SecureOutboxNonceGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public byte[] generate() {
        byte[] nonce = new byte[NONCE_LENGTH_BYTES];
        secureRandom.nextBytes(nonce);
        return nonce;
    }
}
