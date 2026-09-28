package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class RefreshAccessService {
    private final RefreshAccessTransaction transaction;
    private final RefreshSessionService refreshSessionService;
    private final Clock clock;

    public RefreshAccessService(
            RefreshAccessTransaction transaction,
            RefreshSessionService refreshSessionService,
            @Qualifier("identityClock") Clock clock) {
        this.transaction = transaction;
        this.refreshSessionService = refreshSessionService;
        this.clock = clock;
    }

    public RefreshAccessResult refresh(RawRefreshCredential credential) {
        Objects.requireNonNull(credential, "credential");
        Instant now = clock.instant();
        try {
            return transaction.refresh(credential, now);
        } catch (RefreshRotationRaceLostException exception) {
            refreshSessionService.revokeAfterRace(credential, now);
            return RefreshAccessResult.rejected();
        }
    }
}
