package com.quizopia.identity.application.logout;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Clock;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class CurrentSessionLogoutService {
    private final CurrentSessionLogoutTransaction transaction;
    private final Clock clock;

    public CurrentSessionLogoutService(
            CurrentSessionLogoutTransaction transaction, @Qualifier("identityClock") Clock clock) {
        this.transaction = transaction;
        this.clock = clock;
    }

    public void logout(RawRefreshCredential credential) {
        transaction.revokePresentedFamily(Objects.requireNonNull(credential, "credential"), clock.instant());
    }
}
