package com.quizopia.identity.configuration;

import com.quizopia.identity.api.auth.AuthController;
import com.quizopia.identity.api.auth.IdentityAccessDeniedHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class TrustedCookieAuthOriginFilter extends OncePerRequestFilter {
    private static final List<PathPatternRequestMatcher> PROTECTED_REQUESTS = List.of(
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.REFRESH_PATH),
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, AuthController.LOGOUT_PATH));

    private final TrustedBrowserOriginProperties origins;
    private final IdentityAccessDeniedHandler accessDeniedHandler;

    public TrustedCookieAuthOriginFilter(
            TrustedBrowserOriginProperties origins, IdentityAccessDeniedHandler accessDeniedHandler) {
        this.origins = origins;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PROTECTED_REQUESTS.stream().noneMatch(matcher -> matcher.matches(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<String> suppliedOrigins = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (suppliedOrigins.size() != 1 || !origins.trusts(suppliedOrigins.getFirst())) {
            accessDeniedHandler.handle(request, response, new AccessDeniedException("Trusted Origin is required"));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
