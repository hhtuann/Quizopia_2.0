package com.quizopia.proctoring.security.admission;

import com.quizopia.proctoring.configuration.MonitoringProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public final class MonitoringAdmissionFilter extends OncePerRequestFilter {
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties bounds;
    private final MonitoringProperties monitoring;
    private final com.quizopia.proctoring.media.MediaProperties media;

    public MonitoringAdmissionFilter(
            IdentityAdmissionClient admission, AdmissionProperties bounds, MonitoringProperties monitoring) {
        this(admission, bounds, monitoring, new com.quizopia.proctoring.media.MediaProperties());
    }

    public MonitoringAdmissionFilter(
            IdentityAdmissionClient admission,
            AdmissionProperties bounds,
            MonitoringProperties monitoring,
            com.quizopia.proctoring.media.MediaProperties media) {
        this.media = media;
        this.admission = admission;
        this.bounds = bounds;
        this.monitoring = monitoring;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String role = ProctoringRoutes.role(request.getMethod(), request.getRequestURI());
        if (role == null) {
            chain.doFilter(request, response);
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        try {
            if (!monitoring.isEnabled()
                    || MediaRoutes.matches(request.getMethod(), request.getRequestURI()) && !media.isEnabled()) {
                error(response, 503, "AUTHORITY_UNAVAILABLE");
                return;
            }
            String path = request.getRequestURI();
            if (!path.equals(request.getServletPath())
                    || path.contains("%")
                    || path.contains(";")
                    || path.contains("//")) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.size() != 1
                    || !headers.getFirst().matches("(?i)Bearer [A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw AdmissionFailure.invalidUser();
            for (String name : Collections.list(request.getHeaderNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.startsWith("x-quizopia-")
                        || lower.startsWith("x-authenticated-")
                        || Set.of("x-user-id", "x-user-roles", "x-service-identity")
                                .contains(lower)) {
                    error(response, 400, "INVALID_REQUEST");
                    return;
                }
            }
            if (request.getHeader("Content-Encoding") != null) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            boolean timeline = request.getMethod().equals("GET") && path.endsWith("/events");
            if (!timeline && request.getQueryString() != null) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            if (timeline
                    && request.getParameterMap().entrySet().stream()
                            .anyMatch(
                                    e -> !Set.of("after", "limit").contains(e.getKey()) || e.getValue().length != 1)) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            boolean body = request.getMethod().equals("POST")
                    && !path.endsWith("/reconcile")
                    && !MediaRoutes.matches(request.getMethod(), path);
            if (body
                    && (Collections.list(request.getHeaders("Content-Type")).size() != 1
                            || request.getContentType() == null
                            || !request.getContentType()
                                    .matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken user))
                throw AdmissionFailure.invalidUser();
            admission
                    .admit(user.getToken(), role)
                    .block(bounds.getTotalTimeout().plusMillis(100));
            byte[] bytes = request.getInputStream().readNBytes(body ? 16385 : 1);
            if (body && bytes.length > 16384) {
                error(response, 413, "PAYLOAD_TOO_LARGE");
                return;
            }
            if (!body && bytes.length != 0) {
                error(response, 400, "INVALID_REQUEST");
                return;
            }
            chain.doFilter(new BufferedRequest(request, bytes), response);
        } catch (AdmissionFailure f) {
            error(response, f.status(), f.code());
        } catch (RuntimeException unavailable) {
            error(response, 503, "AUTHORITY_UNAVAILABLE");
        }
    }

    private void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] bytes;

        BufferedRequest(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.bytes = bytes;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
