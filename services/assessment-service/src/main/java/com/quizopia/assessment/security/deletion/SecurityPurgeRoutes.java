package com.quizopia.assessment.security.deletion;

import com.quizopia.assessment.configuration.AssessmentDeletionProperties;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Exact private purge operations for the Quiz Service coordinator only; a transport prefix
 * grants no capability. Scope model shared with Proctoring in FB-015 PR C: revocation fencing
 * uses quiz.deletion.revoke and hard purging uses quiz.deletion.purge, each bound to the
 * exact quiz-service SERVICE identity.
 */
public final class SecurityPurgeRoutes {
    public static final String BATCH_PATH = "/internal/security/quiz-purgings/batch";
    public static final String SCOPE = "quiz.deletion.purge";
    private static final String STATUS =
            "/internal/security/quiz-purgings/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/status";
    private static final String DEPENDENTS =
            "/internal/security/quiz-revocations/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/dependents";

    private SecurityPurgeRoutes() {}

    public static boolean matches(String method, String path) {
        return "POST".equals(method) && BATCH_PATH.equals(path)
                || "GET".equals(method) && (path.matches(STATUS) || path.matches(DEPENDENTS));
    }

    public static boolean permitted(Authentication auth, AssessmentDeletionProperties properties) {
        if (!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated()) return false;
        var jwt = token.getToken();
        var now = Instant.now();
        return "RS256".equals(jwt.getHeaders().get("alg"))
                && "SERVICE".equals(jwt.getClaims().get("principal_type"))
                && properties.quizServiceClientId() != null
                && properties.quizServiceClientId().equals(jwt.getSubject())
                && jwt.getIssuedAt() != null
                && !jwt.getIssuedAt().isAfter(now)
                && jwt.getExpiresAt() != null
                && jwt.getExpiresAt().isAfter(now)
                && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("TOKEN_SERVICE"))
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + SCOPE));
    }
}
