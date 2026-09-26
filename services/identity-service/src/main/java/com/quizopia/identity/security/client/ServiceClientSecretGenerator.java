package com.quizopia.identity.security.client;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

@Component
public final class ServiceClientSecretGenerator {
    public static final int ENTROPY_BYTES = 32;

    private final SecureRandom secureRandom;

    public ServiceClientSecretGenerator() {
        this(new SecureRandom());
    }

    ServiceClientSecretGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public RawServiceClientSecret generate() {
        byte[] entropy = new byte[ENTROPY_BYTES];
        secureRandom.nextBytes(entropy);
        return new RawServiceClientSecret(
                Base64.getUrlEncoder().withoutPadding().encodeToString(entropy));
    }
}
