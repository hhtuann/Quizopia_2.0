package com.quizopia.assessment.api;

import com.quizopia.assessment.application.ProctoringAuthorityService;
import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.configuration.ProctoringAuthorityProperties;
import com.quizopia.assessment.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.assessment.security.admission.AdmissionFailure;
import com.quizopia.assessment.security.admission.AdmissionProperties;
import com.quizopia.assessment.security.admission.IdentityAdmissionClient;
import com.quizopia.assessment.security.proctoring.ProctoringAuthorityRoutes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class ProctoringAuthorityController {
    private final ProctoringAuthorityProperties properties;
    private final IdentityAdmissionClient admission;
    private final AdmissionProperties bounds;
    private final JwtDecoder decoder;
    private final QuizopiaJwtAuthenticationConverter converter;
    private final ProctoringAuthorityService service;
    private final Semaphore capacity = new Semaphore(8);
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ProctoringAuthorityController(
            ProctoringAuthorityProperties properties,
            IdentityAdmissionClient admission,
            AdmissionProperties bounds,
            JwtDecoder decoder,
            QuizopiaJwtAuthenticationConverter converter,
            ProctoringAuthorityService service,
            Environment environment) {
        if (environment.matchesProfiles("prod") && properties.allowInsecureLoopback())
            throw new IllegalStateException("Private Proctoring authority requires HTTPS in production");
        this.properties = properties;
        this.admission = admission;
        this.bounds = bounds;
        this.decoder = decoder;
        this.converter = converter;
        this.service = service;
    }

    @PostMapping("/internal/proctoring/attempts/{attemptId}/authority")
    public ProctoringAuthorityService.View authority(
            @PathVariable("attemptId") String attemptId,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!ProctoringAuthorityRoutes.permitted(authentication, properties)) throw failure(403, "ACCESS_DENIED");
        if (!properties.enabled()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        if (!request.isSecure()
                && !(properties.allowInsecureLoopback()
                        && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())))
            throw failure(403, "ACCESS_DENIED");
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            var input = input(request);
            String role = input.role();
            String evidence = oneHeader(request, IdentityAdmissionClient.USER_HEADER);
            if (evidence.length() > 16384 || !evidence.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
                throw failure(401, "USER_EVIDENCE_INVALID");
            Jwt user;
            try {
                user = decoder.decode(evidence);
            } catch (BadJwtException invalid) {
                throw failure(401, "USER_EVIDENCE_INVALID");
            } catch (JwtException unavailable) {
                throw failure(503, "AUTHORITY_UNAVAILABLE");
            }
            try {
                var converted = converter.convert(user);
                if (user.getIssuedAt() == null
                        || user.getIssuedAt().isAfter(Instant.now())
                        || user.getExpiresAt() == null
                        || !user.getExpiresAt().isAfter(Instant.now())
                        || !user.getExpiresAt().isAfter(user.getIssuedAt())
                        || !"RS256".equals(user.getHeaders().get("alg"))
                        || !"USER".equals(user.getClaims().get("principal_type")))
                    throw failure(401, "USER_EVIDENCE_INVALID");
                if (converted.getAuthorities().stream()
                        .noneMatch(a -> a.getAuthority().equals("ROLE_" + role))) throw failure(403, "ACCESS_DENIED");
                admission.admit(user, role).block(bounds.getTotalTimeout().plusMillis(100));
            } catch (AdmissionFailure failure) {
                throw failure(
                        failure.status(),
                        failure.status() == 503
                                ? "AUTHORITY_UNAVAILABLE"
                                : failure.status() == 401 ? "USER_EVIDENCE_INVALID" : failure.code());
            } catch (org.springframework.security.core.AuthenticationException invalid) {
                throw failure(401, "USER_EVIDENCE_INVALID");
            }
            var view = service.authority(
                    UUID.fromString(user.getSubject()), role, UUID.fromString(attemptId), evidence, input.purpose());
            if (user.getExpiresAt() == null || !user.getExpiresAt().isAfter(Instant.now()))
                throw failure(401, "USER_EVIDENCE_INVALID");
            if (!ProctoringAuthorityRoutes.permitted(authentication, properties)) throw failure(401, "UNAUTHENTICATED");
            return view;
        } catch (PublicationFailure known) {
            throw known;
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    private String oneHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw failure(400, "INVALID_REQUEST");
        return values.getFirst();
    }

    private record Input(String role, ProctoringAuthorityService.Purpose purpose) {}

    private Input input(HttpServletRequest request) {
        if (request.getQueryString() != null
                || request.getHeader("Content-Encoding") != null
                || !oneHeader(request, "Content-Type")
                        .matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
            throw failure(400, "INVALID_REQUEST");
        oneHeader(request, "Authorization");
        for (String name : Collections.list(request.getHeaderNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if ((lower.startsWith("x-quizopia-") && !lower.equals("x-quizopia-user-evidence"))
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower))
                throw failure(400, "INVALID_REQUEST");
        }
        try {
            byte[] bytes = request.getInputStream().readNBytes(257);
            if (bytes.length == 0 || bytes.length > 256) throw failure(400, "INVALID_REQUEST");
            var value = json.readTree(bytes);
            if (value == null
                    || !value.isObject()
                    || value.size() != 2
                    || !value.path("viewerRole").isString()
                    || !Set.of("STUDENT", "TEACHER")
                            .contains(value.path("viewerRole").asString())
                    || !value.path("purpose").isString()
                    || !Set.of("HISTORY", "LIVE_MEDIA")
                            .contains(value.path("purpose").asString())) throw failure(400, "INVALID_REQUEST");
            return new Input(
                    value.path("viewerRole").asString(),
                    ProctoringAuthorityService.Purpose.valueOf(
                            value.path("purpose").asString()));
        } catch (IOException | JacksonException invalid) {
            throw failure(400, "INVALID_REQUEST");
        }
    }

    private PublicationFailure failure(int status, String code) {
        return new PublicationFailure(status, code);
    }
}
