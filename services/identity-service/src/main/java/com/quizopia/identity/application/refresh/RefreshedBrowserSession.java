package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.token.IssuedUserAccessToken;
import java.time.Instant;
import java.util.Objects;

public record RefreshedBrowserSession(
        IssuedUserAccessToken accessToken,
        RawRefreshCredential refreshCredential,
        Instant familyExpiresAt,
        long cookieMaxAgeSeconds) {
    public RefreshedBrowserSession {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshCredential, "refreshCredential");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
        if (cookieMaxAgeSeconds <= 0) {
            throw new IllegalArgumentException("cookieMaxAgeSeconds must be positive");
        }
    }

    @Override
    public String toString() {
        return "RefreshedBrowserSession{accessTokenPresent=true, refreshCredentialPresent=true, familyExpiresAt="
                + familyExpiresAt + '}';
    }
}
