package com.quizopia.identity.api.admission;

import com.quizopia.identity.application.admission.*;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Hidden
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "quizopia.identity.notice-recipients", name = "enabled", havingValue = "true")
public class NoticeRecipientController {
    private final NoticeRecipientEligibility eligibility;

    public NoticeRecipientController(NoticeRecipientEligibility eligibility) {
        this.eligibility = eligibility;
    }

    @PostMapping("/internal/security/notice-recipients/{studentId}/eligibility")
    public NoticeRecipientEligibility.Response check(
            @PathVariable("studentId") String student, @AuthenticationPrincipal Jwt caller, HttpServletRequest request)
            throws IOException {
        if (!student.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getHeader("Transfer-Encoding") != null
                || request.getContentLengthLong() > 0
                || request.getInputStream().read() != -1) throw new AdmissionFailure(400, "INVALID_INTERNAL_REQUEST");
        for (String header : Collections.list(request.getHeaderNames())) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-quizopia-")
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower))
                throw new AdmissionFailure(400, "INVALID_INTERNAL_REQUEST");
        }
        var result = eligibility.check(UUID.fromString(student));
        if (!Instant.now().isBefore(caller.getExpiresAt()))
            throw new org.springframework.security.oauth2.server.resource.InvalidBearerTokenException(
                    "Expired service credential");
        return result;
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
