package com.quizopia.assessment.security.realtime;

import com.quizopia.assessment.configuration.RealtimeAuthorityProperties;
import java.time.Instant;
import java.util.Collection;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Exact private operation; a transport prefix grants no capability. */
public final class RealtimeAuthorityRoutes {
    public static final String PATH = "/internal/realtime/subscription-authorizations";
    public static final String SCOPE = "assessment.realtime.subscription-authorize";
    public static final String AUDIENCE = "quizopia-assessment-realtime-authority";

    private RealtimeAuthorityRoutes() {}

    public static boolean matches(String method, String path) {
        return "POST".equals(method) && PATH.equals(path);
    }

    public static boolean permitted(Authentication auth, RealtimeAuthorityProperties properties) {
        if (!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated()) return false;
        var jwt = token.getToken();
        var now = Instant.now();
        Object audience = jwt.getClaims().get("aud");
        boolean audienceGranted = audience instanceof Collection<?> values
                && values.size() == 1
                && AUDIENCE.equals(values.iterator().next());
        return "RS256".equals(jwt.getHeaders().get("alg"))
                && "SERVICE".equals(jwt.getClaims().get("principal_type"))
                && properties.gatewayClientId() != null
                && properties.gatewayClientId().equals(jwt.getSubject())
                && audienceGranted
                && jwt.getIssuedAt() != null
                && !jwt.getIssuedAt().isAfter(now)
                && jwt.getExpiresAt() != null
                && jwt.getExpiresAt().isAfter(now)
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TOKEN_SERVICE"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + SCOPE));
    }
}
