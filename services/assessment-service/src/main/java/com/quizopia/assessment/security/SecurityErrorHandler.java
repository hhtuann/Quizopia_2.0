package com.quizopia.assessment.security;

import com.quizopia.assessment.security.realtime.RealtimeAuthorityRoutes;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public final class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException error)
            throws IOException {
        if (internalServiceRoute(request)) write(response, 401, "SERVICE_AUTHENTICATION_INVALID");
        else write(response, 401, "UNAUTHENTICATED");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException error)
            throws IOException {
        if (internalServiceRoute(request)) write(response, 403, "SERVICE_CALLER_FORBIDDEN");
        else write(response, 403, "ACCESS_DENIED");
    }

    private static boolean internalServiceRoute(HttpServletRequest request) {
        return com.quizopia.assessment.security.deletion.SecurityRevocationRoutes.matches(
                        request.getMethod(), request.getRequestURI())
                || com.quizopia.assessment.security.deletion.SecurityPurgeRoutes.matches(
                        request.getMethod(), request.getRequestURI())
                || RealtimeAuthorityRoutes.matches(request.getMethod(), request.getRequestURI());
    }

    private void write(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
