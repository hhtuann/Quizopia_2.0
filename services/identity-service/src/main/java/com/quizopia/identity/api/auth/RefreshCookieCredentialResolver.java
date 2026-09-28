package com.quizopia.identity.api.auth;

import com.quizopia.identity.security.refresh.RawRefreshCredential;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public final class RefreshCookieCredentialResolver {
    private static final Pattern CREDENTIAL_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    public Optional<RawRefreshCredential> resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        String value = null;
        for (Cookie cookie : cookies) {
            if (!RefreshCookieFactory.COOKIE_NAME.equals(cookie.getName())) {
                continue;
            }
            if (value != null) {
                return Optional.empty();
            }
            value = cookie.getValue();
        }
        if (value == null || !CREDENTIAL_FORMAT.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(RawRefreshCredential.from(value));
    }
}
