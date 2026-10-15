package com.quizopia.identity.api.admission;

import com.quizopia.identity.application.admission.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Hidden
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "quizopia.identity.proctoring-students", name = "enabled", havingValue = "true")
public class ProctoringStudentController {
    private final ProctoringStudentEligibility eligibility;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ProctoringStudentController(ProctoringStudentEligibility eligibility) {
        this.eligibility = eligibility;
    }

    @PostMapping("/internal/security/proctoring-students/{studentId}/eligibility")
    public ProctoringStudentEligibility.Response check(
            @PathVariable("studentId") String student, @AuthenticationPrincipal Jwt caller, HttpServletRequest request)
            throws IOException {
        if (!student.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getHeader("Transfer-Encoding") != null
                || request.getHeader("Content-Encoding") != null
                || request.getContentLengthLong() > 256) throw invalid();
        for (String header : Collections.list(request.getHeaderNames())) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-quizopia-")
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower)) throw invalid();
        }
        Instant started;
        try {
            var contentTypes = Collections.list(request.getHeaders("Content-Type"));
            if (contentTypes.size() != 1
                    || !contentTypes
                            .getFirst()
                            .matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?")) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(257);
            if (bytes.length == 0 || bytes.length > 256) throw invalid();
            var body = json.readTree(bytes);
            if (!body.isObject()
                    || body.size() != 1
                    || !body.path("attemptStartedAt").isString()) throw invalid();
            String wire = body.path("attemptStartedAt").asString();
            started = Instant.parse(wire);
            int year = started.atOffset(ZoneOffset.UTC).getYear();
            if (!wire.equals(started.toString())
                    || started.getNano() % 1000 != 0
                    || year < 1
                    || year > 9999
                    || started.isAfter(Instant.now())) throw invalid();
        } catch (RuntimeException failure) {
            throw invalid();
        }
        var result = eligibility.check(UUID.fromString(student), started);
        if (!Instant.now().isBefore(caller.getExpiresAt()))
            throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(
                    "Expired service credential");
        return result;
    }

    private static AdmissionFailure invalid() {
        return new AdmissionFailure(400, "INVALID_INTERNAL_REQUEST");
    }

    @ExceptionHandler(IOException.class)
    void malformedTransport(HttpServletResponse response) throws IOException {
        AdmissionErrors.write(response, 400, "INVALID_INTERNAL_REQUEST");
    }

    @ExceptionHandler(org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class)
    void expired(HttpServletResponse response) throws IOException {
        AdmissionErrors.write(response, 401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @ExceptionHandler(AdmissionFailure.class)
    void failure(AdmissionFailure failure, HttpServletResponse response) throws IOException {
        AdmissionErrors.write(response, failure.status(), failure.code());
    }
}
