package com.quizopia.identity.application.login;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.token.IssuedUserAccessToken;
import java.time.Instant;
import java.util.Objects;

public record AuthenticatedBrowserSession(
        IssuedUserAccessToken accessToken, RawRefreshCredential refreshCredential, Instant familyExpiresAt) {
    public AuthenticatedBrowserSession {
        Objects.requireNonNull(accessToken, "accessToken");
        Objects.requireNonNull(refreshCredential, "refreshCredential");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
    }

    @Override
    public String toString() {
        return "AuthenticatedBrowserSession{accessTokenPresent=true, refreshCredentialPresent=true, familyExpiresAt="
                + familyExpiresAt
                + "}";
    }
}
