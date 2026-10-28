package com.quizopia.proctoring.security.deletion;

import com.quizopia.proctoring.configuration.ProctoringDeletionProperties;
import java.time.Instant;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Exact private deletion operations for the Quiz Service coordinator only; a transport
 * prefix grants no capability. Both services share the scope model chosen for FB-015 PR C:
 * revocation fencing uses quiz.deletion.revoke and hard purging uses quiz.deletion.purge,
 * each bound to the exact quiz-service SERVICE identity.
 */
public final class SecurityDeletionRoutes {
    public static final String REVOCATION_BATCH_PATH = "/internal/security/quiz-revocations/batch";
    public static final String PURGE_BATCH_PATH = "/internal/security/quiz-purgings/batch";
    public static final String SCOPE_REVOKE = "quiz.deletion.revoke";
    public static final String SCOPE_PURGE = "quiz.deletion.purge";
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String REVOCATION_STATUS = "/internal/security/quiz-revocations/" + UUID + "/status";
    private static final String PURGE_STATUS = "/internal/security/quiz-purgings/" + UUID + "/status";

    private SecurityDeletionRoutes() {}

    public static boolean matches(String method, String path) {
        return "POST".equals(method) && (REVOCATION_BATCH_PATH.equals(path) || PURGE_BATCH_PATH.equals(path))
                || "GET".equals(method) && (path.matches(REVOCATION_STATUS) || path.matches(PURGE_STATUS));
    }

    public static String requiredScope(String method, String path) {
        if ("POST".equals(method) && PURGE_BATCH_PATH.equals(path)) return SCOPE_PURGE;
        if ("GET".equals(method) && path.matches(PURGE_STATUS)) return SCOPE_PURGE;
        if (matches(method, path)) return SCOPE_REVOKE;
        return null;
    }

    public static boolean permitted(Authentication auth, ProctoringDeletionProperties properties, String scope) {
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
                && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("SCOPE_" + scope));
    }
}
