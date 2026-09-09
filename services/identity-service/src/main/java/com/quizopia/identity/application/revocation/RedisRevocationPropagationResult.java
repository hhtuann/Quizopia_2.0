package com.quizopia.identity.application.revocation;

import java.util.Objects;

public record RedisRevocationPropagationResult(RedisRevocationPropagationStatus status) {
    public RedisRevocationPropagationResult {
        Objects.requireNonNull(status, "status must not be null");
    }

    public static RedisRevocationPropagationResult propagated() {
        return new RedisRevocationPropagationResult(RedisRevocationPropagationStatus.PROPAGATED);
    }

    public static RedisRevocationPropagationResult unavailable() {
        return new RedisRevocationPropagationResult(RedisRevocationPropagationStatus.UNAVAILABLE);
    }

    public static RedisRevocationPropagationResult notApplicable() {
        return new RedisRevocationPropagationResult(RedisRevocationPropagationStatus.NOT_APPLICABLE);
    }
}
