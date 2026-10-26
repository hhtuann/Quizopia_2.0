package com.quizopia.classroom.security.admission;

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
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties properties;

    public UserAdmissionFilter(IdentityAdmissionClient admission, AdmissionProperties properties) {
        this.admission = admission;
        this.properties = properties;
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
        if ((method.equals("POST") || method.equals("GET")) && path.equals("/classrooms")) return "TEACHER";
        if (method.equals("GET")
                && path.matches("/classrooms/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            return "STUDENT";
        return null;
    }

    private void write(HttpServletResponse response, AdmissionFailure failure) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + failure.code() + "\"}");
    }
}
