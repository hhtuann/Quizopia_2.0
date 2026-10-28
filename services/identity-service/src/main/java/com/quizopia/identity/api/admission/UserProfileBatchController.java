package com.quizopia.identity.api.admission;

import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.application.admission.UserProfileBatchService;
import com.quizopia.identity.application.admission.UserProfileRequestBudget;
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
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Hidden
@RestController
@Profile("!test")
@ConditionalOnProperty(prefix = "quizopia.identity.user-profiles", name = "enabled", havingValue = "true")
public class UserProfileBatchController {
    static final int MAX_BODY_BYTES = 16384;
    static final int MAX_IDS = 100;

    private final UserProfileBatchService profiles;
    private final UserProfileRequestBudget budget;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public UserProfileBatchController(UserProfileBatchService profiles, UserProfileRequestBudget budget) {
        this.profiles = profiles;
        this.budget = budget;
    }

    @PostMapping("/internal/security/user-profiles/batch")
    public UserProfileBatchService.Response batch(@AuthenticationPrincipal Jwt caller, HttpServletRequest request)
            throws IOException {
        budget.acquire(caller.getSubject());
        if (request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getHeader("Transfer-Encoding") != null
                || request.getHeader("Content-Encoding") != null
                || request.getContentLengthLong() > MAX_BODY_BYTES) throw invalid();
        for (String header : Collections.list(request.getHeaderNames())) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-quizopia-")
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower)) throw invalid();
        }
        List<UUID> studentIds;
        try {
            var contentTypes = Collections.list(request.getHeaders("Content-Type"));
            if (contentTypes.size() != 1
                    || !contentTypes
                            .getFirst()
                            .matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?")) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw invalid();
            JsonNode body = json.readTree(bytes);
            if (!body.isObject() || body.size() != 1 || !body.path("studentIds").isArray()) throw invalid();
            JsonNode ids = body.path("studentIds");
            if (ids.size() < 1 || ids.size() > MAX_IDS) throw invalid();
            studentIds = new ArrayList<>(ids.size());
            Set<UUID> unique = new HashSet<>(ids.size());
            for (JsonNode id : ids) {
                if (!id.isString()) throw invalid();
                String value = id.asString();
                if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !unique.add(UUID.fromString(value))) throw invalid();
                studentIds.add(UUID.fromString(value));
            }
        } catch (RuntimeException failure) {
            throw invalid();
        }
        var result = profiles.resolve(studentIds);
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
