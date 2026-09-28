package com.quizopia.identity.application.login;

import com.quizopia.identity.application.localauthentication.LocalAuthenticationInput;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationService;
import com.quizopia.identity.application.localauthentication.LocalAuthenticationStatus;
import com.quizopia.identity.application.refresh.RefreshCredentialIssuance;
import com.quizopia.identity.application.refresh.RefreshSessionService;
import com.quizopia.identity.security.token.IssuedUserAccessToken;
import com.quizopia.identity.security.token.UserAccessTokenIssuer;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class InitialLoginService {
    private final LocalAuthenticationService authenticationService;
    private final RefreshSessionService refreshSessionService;
    private final ObjectProvider<UserAccessTokenIssuer> accessTokenIssuer;
    private final InitialLoginSessionPolicy sessionPolicy;
    private final Clock clock;

    public InitialLoginService(
            LocalAuthenticationService authenticationService,
            RefreshSessionService refreshSessionService,
            ObjectProvider<UserAccessTokenIssuer> accessTokenIssuer,
            InitialLoginSessionPolicy sessionPolicy,
            @Qualifier("identityClock") Clock clock) {
        this.authenticationService = authenticationService;
        this.refreshSessionService = refreshSessionService;
        this.accessTokenIssuer = accessTokenIssuer;
        this.sessionPolicy = sessionPolicy;
        this.clock = clock;
    }

    @Transactional
    public InitialLoginResult login(LocalAuthenticationInput input) {
        Objects.requireNonNull(input, "input");
        var authentication = authenticationService.authenticate(input);
        if (authentication.status() != LocalAuthenticationStatus.AUTHENTICATED) {
            return InitialLoginResult.invalidCredentials();
        }

        UUID userId = authentication.authenticatedUserId().orElseThrow();
        Instant loginTime = clock.instant();
        RefreshCredentialIssuance refresh = refreshSessionService
                .issueInitialIfEligible(userId, loginTime, sessionPolicy.familyExpiresAt(loginTime))
                .orElse(null);
        if (refresh == null) {
            return InitialLoginResult.invalidCredentials();
        }
        UserAccessTokenIssuer issuer = accessTokenIssuer.getIfAvailable();
        if (issuer == null) {
            throw new IllegalStateException("Login token issuance is unavailable");
        }
        IssuedUserAccessToken accessToken = issuer.issue(userId);
        return InitialLoginResult.authenticated(
                new AuthenticatedBrowserSession(accessToken, refresh.credential(), refresh.familyExpiresAt()));
    }
}
