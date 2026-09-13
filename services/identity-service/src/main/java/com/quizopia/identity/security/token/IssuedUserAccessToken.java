package com.quizopia.identity.security.token;

import java.time.Instant;
import java.util.Objects;

public record IssuedUserAccessToken(String value, Instant issuedAt, Instant expiresAt) {
    public IssuedUserAccessToken {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "IssuedUserAccessToken[value=REDACTED, issuedAt=" + issuedAt + ", expiresAt=" + expiresAt + "]";
    }
}
