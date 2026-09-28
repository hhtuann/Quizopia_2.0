package com.quizopia.identity.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.quizopia.identity.application.login.AuthenticatedBrowserSession;
import com.quizopia.identity.application.refresh.RefreshedBrowserSession;
import com.quizopia.identity.security.refresh.RawRefreshCredential;
import com.quizopia.identity.security.token.IssuedUserAccessToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class RefreshCookieFactoryTest {
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private static final Instant FAMILY_EXPIRES_AT =
            NOW.plus(Duration.ofDays(7)).minusSeconds(37);

    @Test
    void localCookieUsesActualRemainingFamilyLifetimeAndFixedSecurityAttributes() {
        ResponseCookie cookie =
                factory(RefreshCookieFactory.SecurityMode.LOCAL_DEVELOPMENT).create(session());

        assertEquals(RefreshCookieFactory.COOKIE_NAME, cookie.getName());
        assertEquals("opaque-refresh-value", cookie.getValue());
        assertTrue(cookie.isHttpOnly());
        assertFalse(cookie.isSecure());
        assertEquals(RefreshCookieFactory.COOKIE_PATH, cookie.getPath());
        assertEquals(RefreshCookieFactory.SAME_SITE, cookie.getSameSite());
        assertNull(cookie.getDomain());
        assertEquals(Duration.between(NOW, FAMILY_EXPIRES_AT), cookie.getMaxAge());
    }

    @Test
    void productionCookieIsAlwaysSecure() {
        ResponseCookie cookie =
                factory(RefreshCookieFactory.SecurityMode.PRODUCTION).create(session());

        assertTrue(cookie.isSecure());
        assertTrue(cookie.isHttpOnly());
        assertEquals("Lax", cookie.getSameSite());
    }

    @Test
    void refreshedCookieUsesPrevalidatedTransactionSnapshotLifetimeWithoutReadingClock() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        var session = new RefreshedBrowserSession(
                new IssuedUserAccessToken("access-token", NOW, NOW.plusSeconds(300)),
                RawRefreshCredential.from("rotated-refresh-value"),
                NOW.plusMillis(1001),
                1);

        ResponseCookie cookie =
                new RefreshCookieFactory(clock, RefreshCookieFactory.SecurityMode.LOCAL_DEVELOPMENT).create(session);

        assertEquals(Duration.ofSeconds(1), cookie.getMaxAge());
        org.mockito.Mockito.verifyNoInteractions(clock);
    }

    @Test
    void clearingContractReusesNamePathDomainAndSecurity() {
        ResponseCookie cookie =
                factory(RefreshCookieFactory.SecurityMode.PRODUCTION).clear();

        assertEquals(RefreshCookieFactory.COOKIE_NAME, cookie.getName());
        assertEquals(RefreshCookieFactory.COOKIE_PATH, cookie.getPath());
        assertNull(cookie.getDomain());
        assertTrue(cookie.isHttpOnly());
        assertTrue(cookie.isSecure());
        assertEquals(Duration.ZERO, cookie.getMaxAge());
    }

    private static RefreshCookieFactory factory(RefreshCookieFactory.SecurityMode mode) {
        return new RefreshCookieFactory(Clock.fixed(NOW, ZoneOffset.UTC), mode);
    }

    private static AuthenticatedBrowserSession session() {
        return new AuthenticatedBrowserSession(
                new IssuedUserAccessToken("access-token", NOW, NOW.plusSeconds(300)),
                RawRefreshCredential.from("opaque-refresh-value"),
                FAMILY_EXPIRES_AT);
    }
}
