package com.quizopia.assessment.security.admission;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class UserAdmissionFilter extends OncePerRequestFilter {
    private final boolean inboxEnabled;
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties properties;
    private final PublicationProperties publication;
    private final com.quizopia.assessment.configuration.AssessmentFeatureProperties features;

    public UserAdmissionFilter(
            IdentityAdmissionClient admission, AdmissionProperties properties, PublicationProperties publication) {
        this(
                admission,
                properties,
                publication,
                new com.quizopia.assessment.configuration.AssessmentFeatureProperties(false, false, false));
    }

    public UserAdmissionFilter(
            IdentityAdmissionClient admission,
            AdmissionProperties properties,
            PublicationProperties publication,
            com.quizopia.assessment.configuration.AssessmentFeatureProperties features) {
        this(admission, properties, publication, features, false);
    }

    public UserAdmissionFilter(
            IdentityAdmissionClient admission,
            AdmissionProperties properties,
            PublicationProperties publication,
            com.quizopia.assessment.configuration.AssessmentFeatureProperties features,
            boolean inboxEnabled) {
        this.inboxEnabled = inboxEnabled;
        this.features = features;
        this.admission = admission;
        this.properties = properties;
        this.publication = publication;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String role = requiredRole(request.getMethod(), request.getRequestURI());
        if (role == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        if (ClosureRoutes.role(request.getMethod(), request.getRequestURI()) != null
                ? !ClosureRoutes.enabled(
                        request.getMethod(),
                        request.getRequestURI(),
                        features.resultsEnabled(),
                        features.closureEnabled(),
                        inboxEnabled)
                : StudentAttemptRoutes.matches(request.getMethod(), request.getRequestURI())
                        ? !StudentAttemptRoutes.enabled(
                                request.getMethod(),
                                request.getRequestURI(),
                                features.attemptsEnabled(),
                                features.resultsEnabled())
                        : !publication.isEnabled()) {
            write(response, AdmissionFailure.unavailable());
            return;
        }
        var token = SecurityContextHolder.getContext().getAuthentication();
        try {
            if (!(token instanceof JwtAuthenticationToken user)) throw AdmissionFailure.invalidUser();
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1
                    || !headers.getFirst().matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw AdmissionFailure.invalidUser();
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-quizopia-")
                        || lower.startsWith("x-authenticated-")
                        || java.util.Set.of("x-user-id", "x-user-roles", "x-service-identity")
                                .contains(lower)) {
                    response.setStatus(400);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"INVALID_REQUEST\"}");
                    return;
                }
            }
            // Servlet request thread only. Network I/O and OAuth acquisition remain reactive and bounded.
            admission
                    .admit(user.getToken(), role)
                    .block(properties.getTotalTimeout().plusMillis(100));
        } catch (AdmissionFailure failure) {
            write(response, failure);
            return;
        } catch (RuntimeException failure) {
            write(response, AdmissionFailure.unavailable());
            return;
        }
        chain.doFilter(request, response);
    }

    public static String requiredRole(String method, String path) {
        return PublicationRoutes.requiredRole(method, path);
    }

    private void write(HttpServletResponse response, AdmissionFailure failure) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + failure.code() + "\"}");
    }
}
