package com.quizopia.identity.security.outbox;

import java.util.Arrays;
import java.util.Objects;

public final class EncryptedOutboxPayload {
    private final byte[] ciphertext;
    private final byte[] nonce;
    private final String keyVersion;
    private final int payloadFormatVersion;

    public EncryptedOutboxPayload(byte[] ciphertext, byte[] nonce, String keyVersion, int payloadFormatVersion) {
        Objects.requireNonNull(ciphertext, "ciphertext");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(keyVersion, "keyVersion");
        if (ciphertext.length == 0 || nonce.length == 0 || keyVersion.isBlank() || payloadFormatVersion <= 0) {
            throw new IllegalArgumentException("Encrypted outbox payload metadata is invalid");
        }
        this.ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
        this.nonce = Arrays.copyOf(nonce, nonce.length);
        this.keyVersion = keyVersion;
        this.payloadFormatVersion = payloadFormatVersion;
    }

    public byte[] ciphertext() {
        return Arrays.copyOf(ciphertext, ciphertext.length);
    }

    public byte[] nonce() {
        return Arrays.copyOf(nonce, nonce.length);
    }

    public String keyVersion() {
        return keyVersion;
    }

    public int payloadFormatVersion() {
        return payloadFormatVersion;
    }

    @Override
    public String toString() {
        return "EncryptedOutboxPayload{ciphertextPresent=true, noncePresent=true, keyVersion=" + keyVersion
                + ", payloadFormatVersion=" + payloadFormatVersion + '}';
    }
}
