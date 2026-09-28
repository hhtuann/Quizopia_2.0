package com.quizopia.identity.api.auth;

import com.quizopia.identity.application.login.AuthenticatedBrowserSession;
import com.quizopia.identity.application.refresh.RefreshedBrowserSession;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.http.ResponseCookie;

public final class RefreshCookieFactory {
    public static final String COOKIE_NAME = "quizopia_refresh";
    public static final String COOKIE_PATH = "/api/auth";
    public static final String SAME_SITE = "Lax";

    public enum SecurityMode {
        LOCAL_DEVELOPMENT(false),
        PRODUCTION(true);

        private final boolean secure;

        SecurityMode(boolean secure) {
            this.secure = secure;
        }
    }

    private final Clock clock;
    private final SecurityMode securityMode;

    public RefreshCookieFactory(Clock clock, SecurityMode securityMode) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.securityMode = Objects.requireNonNull(securityMode, "securityMode");
    }

    public ResponseCookie create(AuthenticatedBrowserSession session) {
        Objects.requireNonNull(session, "session");
        return create(session.refreshCredential(), session.familyExpiresAt());
    }

    public ResponseCookie create(RefreshedBrowserSession session) {
        Objects.requireNonNull(session, "session");
        return base(session.refreshCredential().value())
                .maxAge(Duration.ofSeconds(session.cookieMaxAgeSeconds()))
                .build();
    }

    private ResponseCookie create(RawRefreshCredential credential, Instant familyExpiresAt) {
        Instant responseTime = clock.instant();
        long remainingSeconds = Duration.between(responseTime, familyExpiresAt).getSeconds();
        if (remainingSeconds <= 0) {
            throw new IllegalStateException("Refresh family has no remaining lifetime");
        }
        return base(credential.value())
                .maxAge(Duration.ofSeconds(remainingSeconds))
                .build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(securityMode.secure)
                .path(COOKIE_PATH)
                .sameSite(SAME_SITE);
    }
}
