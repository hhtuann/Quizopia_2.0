package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RefreshCredentialIssuance {
    private final UUID familyId;
    private final UUID tokenId;
    private final RawRefreshCredential credential;
    private final Instant familyExpiresAt;

    public RefreshCredentialIssuance(
            UUID familyId, UUID tokenId, RawRefreshCredential credential, Instant familyExpiresAt) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.credential = Objects.requireNonNull(credential, "credential");
        this.familyExpiresAt = Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
    }

    public UUID familyId() {
        return familyId;
    }

    public UUID tokenId() {
        return tokenId;
    }

    public RawRefreshCredential credential() {
        return credential;
    }

    public Instant familyExpiresAt() {
        return familyExpiresAt;
    }

    @Override
    public String toString() {
        return "RefreshCredentialIssuance{familyId="
                + familyId
                + ", tokenId="
                + tokenId
                + ", credentialPresent=true, familyExpiresAt="
                + familyExpiresAt
                + "}";
    }
}
