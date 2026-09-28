package com.quizopia.identity.application.login;

import java.util.Objects;
import java.util.Optional;

public record InitialLoginResult(InitialLoginStatus status, Optional<AuthenticatedBrowserSession> session) {
    public InitialLoginResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(session, "session");
        boolean authenticated = status == InitialLoginStatus.AUTHENTICATED;
        if (authenticated != session.isPresent()) {
            throw new IllegalArgumentException("session presence does not match login status");
        }
    }

    public static InitialLoginResult authenticated(AuthenticatedBrowserSession session) {
        return new InitialLoginResult(InitialLoginStatus.AUTHENTICATED, Optional.of(Objects.requireNonNull(session)));
    }

    public static InitialLoginResult invalidCredentials() {
        return new InitialLoginResult(InitialLoginStatus.INVALID_CREDENTIALS, Optional.empty());
    }
}
