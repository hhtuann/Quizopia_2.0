package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Instant;
import java.util.Objects;
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

    public RefreshRotationResult rotate(RawRefreshCredential presentedCredential, Instant now) {
        Objects.requireNonNull(presentedCredential, "presentedCredential");
        Objects.requireNonNull(now, "now");
        try {
            return transaction.rotate(presentedCredential, now);
        } catch (RefreshRotationRaceLostException exception) {
            return RefreshRotationResult.rejected(RefreshRotationStatus.REUSE_DETECTED);
        }
    }
}
