package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RefreshSessionService {
    private final RefreshSessionTransaction transaction;

    public RefreshSessionService(RefreshSessionTransaction transaction) {
        this.transaction = transaction;
    }

    public RefreshCredentialIssuance issueInitial(UUID userId, Instant familyExpiresAt) {
        return transaction.issueInitial(userId, familyExpiresAt);
    }

    public Optional<RefreshCredentialIssuance> issueInitialIfEligible(
            UUID userId, Instant authenticatedAt, Instant familyExpiresAt) {
        return transaction.issueInitialIfEligible(userId, authenticatedAt, familyExpiresAt);
    }

    public RefreshRotationResult rotate(RawRefreshCredential presentedCredential, Instant now) {
        Objects.requireNonNull(presentedCredential, "presentedCredential");
        Objects.requireNonNull(now, "now");
        try {
            return rotateOnce(presentedCredential, now);
        } catch (RefreshRotationRaceLostException exception) {
            revokeAfterRace(presentedCredential, now);
            return RefreshRotationResult.rejected(RefreshRotationStatus.REUSE_DETECTED);
        }
    }

    RefreshRotationResult rotateOnce(RawRefreshCredential presentedCredential, Instant now) {
        return transaction.rotate(presentedCredential, now);
    }

    void revokeAfterRace(RawRefreshCredential presentedCredential, Instant now) {
        transaction.revokeAfterRace(presentedCredential, now);
    }
}
