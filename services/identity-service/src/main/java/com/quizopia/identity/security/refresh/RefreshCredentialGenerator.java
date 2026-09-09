package com.quizopia.identity.security.refresh;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class RefreshCredentialGenerator {
    public static final int ENTROPY_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public RawRefreshCredential generate() {
        byte[] randomBytes = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(randomBytes);
        return RawRefreshCredential.from(Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes));
    }
}
