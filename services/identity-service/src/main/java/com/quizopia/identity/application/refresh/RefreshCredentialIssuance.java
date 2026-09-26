package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.util.Objects;
import java.util.UUID;

public final class RefreshCredentialIssuance {
    private final UUID familyId;
    private final UUID tokenId;
    private final RawRefreshCredential credential;

    public RefreshCredentialIssuance(UUID familyId, UUID tokenId, RawRefreshCredential credential) {
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenId = Objects.requireNonNull(tokenId, "tokenId");
        this.credential = Objects.requireNonNull(credential, "credential");
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

    @Override
    public String toString() {
        return "RefreshCredentialIssuance{familyId=" + familyId + ", tokenId=" + tokenId + ", credentialPresent=true}";
    }
}
