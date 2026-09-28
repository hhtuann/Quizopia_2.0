package com.quizopia.identity.application.refresh;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class RefreshAccessTransaction {
    private final RefreshSessionService refreshSessionService;
    private final ObjectProvider<UserAccessTokenIssuer> accessTokenIssuer;

    public RefreshAccessTransaction(
            RefreshSessionService refreshSessionService, ObjectProvider<UserAccessTokenIssuer> accessTokenIssuer) {
        this.refreshSessionService = refreshSessionService;
        this.accessTokenIssuer = accessTokenIssuer;
    }

    @Transactional
    public RefreshAccessResult refresh(RawRefreshCredential credential, Instant now) {
        RefreshRotationResult rotation = refreshSessionService.rotateOnce(credential, now);
        if (rotation.status() != RefreshRotationStatus.SUCCESS) {
            return RefreshAccessResult.rejected();
        }

        UserAccessTokenIssuer issuer = accessTokenIssuer.getIfAvailable();
        if (issuer == null) {
            throw new IllegalStateException("Refresh access-token issuance is unavailable");
        }
        var accessToken = issuer.issue(rotation.authenticatedUserId().orElseThrow());
        return RefreshAccessResult.refreshed(new RefreshedBrowserSession(
                accessToken,
                rotation.replacementCredential().orElseThrow(),
                rotation.familyExpiresAt().orElseThrow(),
                rotation.cookieMaxAgeSeconds().orElseThrow()));
    }
}
