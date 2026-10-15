package com.quizopia.classroom.security.internal;

import com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.classroom.security.admission.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class InternalAuthorityFilter extends OncePerRequestFilter {
    public static final String ACTOR = InternalAuthorityFilter.class.getName() + ".actor";
    public static final String UUID_PATH = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final InternalAuthorityProperties config;
    private final JwtDecoder decoder;
    private final QuizopiaJwtAuthenticationConverter converter;
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties bounds;

    public InternalAuthorityFilter(
            InternalAuthorityProperties config,
            JwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            IdentityAdmissionClient admission,
            AdmissionProperties bounds) {
        this.config = config;
        this.decoder = decoder;
        this.converter = converter;
        this.admission = admission;
        this.bounds = bounds;
        if (config.isEnabled()
                && (config.getAssessmentClientId() == null
                        || !config.getAssessmentClientId().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")))
            throw new IllegalStateException("Invalid internal caller configuration");
    }

    public static String scope(String method, String path) {
        if (!"POST".equals(method)) return null;
        if (path.matches("/internal/classrooms/" + UUID_PATH + "/teacher-authority"))
            return "classroom.teacher-authority.read";
        if (path.matches("/internal/classrooms/" + UUID_PATH + "/student-membership"))
            return "classroom.membership.read";
        if (path.matches("/internal/classrooms/" + UUID_PATH + "/proctoring-students/" + UUID_PATH + "/membership"))
            return "classroom.proctoring-membership.read";
        return null;
    }

    public static java.util.UUID actor(HttpServletRequest request) {
        return java.util.UUID.fromString(((Jwt) request.getAttribute(ACTOR)).getSubject());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getToken().getIssuedAt() == null
                || token.getToken().getExpiresAt() == null
                || !token.getToken().getExpiresAt().isAfter(Instant.now())
                || token.getToken().getIssuedAt().isAfter(Instant.now())
                || !token.getToken().getExpiresAt().isAfter(token.getToken().getIssuedAt())) {
            InternalErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID");
            return;
        }
        boolean proctoring =
                "classroom.proctoring-membership.read".equals(scope(request.getMethod(), request.getRequestURI()));
        if (!config.isEnabled()
                || (proctoring && !config.isProctoringMembershipEnabled())
                || (config.isRequireTls() && !request.isSecure())) {
            InternalErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
            return;
        }
        var evidence = Collections.list(request.getHeaders(IdentityAdmissionClient.USER_HEADER));
        var authorization = Collections.list(request.getHeaders("Authorization"));
        if (authorization.size() != 1
                || !authorization.getFirst().matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                || evidence.size() != 1
                || evidence.getFirst().length() > 8192
                || !evidence.getFirst().matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
                || request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getInputStream().read() != -1) {
            InternalErrors.write(response, 400, "INVALID_INTERNAL_REQUEST");
            return;
        }
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("x-quizopia-") && !name.equalsIgnoreCase(IdentityAdmissionClient.USER_HEADER))
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower)) {
                InternalErrors.write(response, 400, "INVALID_INTERNAL_REQUEST");
                return;
            }
        }
        Jwt user;
        try {
            user = decoder.decode(evidence.getFirst());
            converter.convert(user);
            if (user.getIssuedAt() == null
                    || user.getExpiresAt() == null
                    || !user.getExpiresAt().isAfter(Instant.now())
                    || user.getIssuedAt().isAfter(Instant.now())
                    || !user.getExpiresAt().isAfter(user.getIssuedAt())
                    || !"USER".equals(user.getClaims().get("principal_type")))
                throw new BadJwtException("Invalid evidence");
        } catch (RuntimeException failure) {
            InternalErrors.write(response, 401, "USER_EVIDENCE_INVALID");
            return;
        }
        try {
            String required = request.getRequestURI().endsWith("/student-membership") ? "STUDENT" : "TEACHER";
            if (!user.getClaimAsStringList("roles").contains(required)) throw AdmissionFailure.forbidden();
            admission.admit(user, required).block(bounds.getTotalTimeout().plusMillis(100));
        } catch (AdmissionFailure failure) {
            InternalErrors.write(
                    response,
                    failure.status(),
                    failure.status() == 401
                            ? "USER_EVIDENCE_INVALID"
                            : failure.status() == 403 ? "USER_ROLE_FORBIDDEN" : "AUTHORITY_UNAVAILABLE");
            return;
        } catch (RuntimeException failure) {
            InternalErrors.write(response, 503, "AUTHORITY_UNAVAILABLE");
            return;
        }
        if (proctoring && !user.getExpiresAt().isAfter(Instant.now())) {
            InternalErrors.write(response, 401, "USER_EVIDENCE_INVALID");
            return;
        }
        if (proctoring && !token.getToken().getExpiresAt().isAfter(Instant.now())) {
            InternalErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID");
            return;
        }
        request.setAttribute(ACTOR, user);
        chain.doFilter(request, response);
    }
}
