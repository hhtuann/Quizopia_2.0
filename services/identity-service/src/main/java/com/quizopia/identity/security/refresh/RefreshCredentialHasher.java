package com.quizopia.identity.security.refresh;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Component;

@Component
public final class RefreshCredentialHasher {
    public static final int HASH_BYTES = 32;

    public byte[] hash(RawRefreshCredential credential) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(credential.value().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
