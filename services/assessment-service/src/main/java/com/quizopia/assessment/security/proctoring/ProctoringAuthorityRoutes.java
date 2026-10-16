package com.quizopia.assessment.security.proctoring;

import com.quizopia.assessment.configuration.ProctoringAuthorityProperties;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Exact private operation; a transport prefix grants no capability. */
public final class ProctoringAuthorityRoutes {
    public static final String SCOPE = "assessment.proctoring-authority.read";

    private ProctoringAuthorityRoutes() {}

    public static boolean matches(String method, String path) {
        return "POST".equals(method)
                && path.matches(
                        "/internal/proctoring/attempts/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/authority");
    }

    public static boolean permitted(Authentication auth, ProctoringAuthorityProperties properties) {
        if (!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated()) return false;
        var jwt = token.getToken();
        var now = Instant.now();
        return "RS256".equals(jwt.getHeaders().get("alg"))
                && "SERVICE".equals(jwt.getClaims().get("principal_type"))
                && properties.clientId() != null
                && properties.clientId().equals(jwt.getSubject())
                && jwt.getIssuedAt() != null
                && !jwt.getIssuedAt().isAfter(now)
                && jwt.getExpiresAt() != null
                && jwt.getExpiresAt().isAfter(now)
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TOKEN_SERVICE"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + SCOPE));
    }
}
