package com.quizopia.identity.infrastructure.revocation;

import java.util.Objects;
import java.util.UUID;

public final class RedisRevocationKey {
    private static final String PREFIX = "quizopia:identity:revocation:user:";

    private RedisRevocationKey() {}

    public static String forUser(UUID userId) {
        return PREFIX + Objects.requireNonNull(userId, "userId must not be null");
    }
}
