package com.quizopia.assessment.api;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.application.QuizPurgeService;
import com.quizopia.assessment.application.QuizPurgeService.Operation;
import com.quizopia.assessment.configuration.AssessmentDeletionProperties;
import com.quizopia.assessment.security.deletion.QuizRevocationRequestBudget;
import com.quizopia.assessment.security.deletion.SecurityPurgeRoutes;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * FB-015 PR C private purge surface for the Quiz Service deletion coordinator only (ADR-018
 * Phase B steps 6-7). Exact SERVICE caller, dedicated quiz.deletion.purge scope, strict
 * bounded transport, no-store. Denies never disclose quiz content, counts or PII. Disabled
 * unless quizopia.deletion.enabled.
 */
@Hidden
@RestController
public class QuizPurgeController {
    static final int MAX_BODY_BYTES = 16384;
    static final int MAX_OPERATIONS = 50;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final AssessmentDeletionProperties properties;
    private final QuizPurgeService service;
    private final QuizRevocationRequestBudget budget;
    private final Semaphore capacity;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public QuizPurgeController(
            AssessmentDeletionProperties properties,
            QuizPurgeService service,
            QuizRevocationRequestBudget budget,
            Environment environment) {
        if (environment.matchesProfiles("prod") && properties.allowInsecureLoopback())
            throw new IllegalStateException("Private quiz purge requires HTTPS in production");
        this.properties = properties;
        this.service = service;
        this.budget = budget;
        this.capacity = new Semaphore(properties.maxConcurrent());
    }

    private static PublicationFailure failure(int status, String code) {
        return new PublicationFailure(status, code);
    }

    private static PublicationFailure invalid() {
        return failure(400, "INVALID_INTERNAL_REQUEST");
    }

    private void admit(Authentication authentication, Jwt caller, HttpServletRequest request) {
        if (!SecurityPurgeRoutes.permitted(authentication, properties)) throw failure(403, "SERVICE_CALLER_FORBIDDEN");
        if (!properties.enabled()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        if (!request.isSecure()
                && !(properties.allowInsecureLoopback()
                        && Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr())))
            throw failure(403, "SERVICE_CALLER_FORBIDDEN");
        budget.acquire(caller.getSubject());
    }

    private void transport(HttpServletRequest request) {
        if (request.getQueryString() != null
                || request.getHeader("Cookie") != null
                || request.getHeader("Transfer-Encoding") != null
                || request.getHeader("Content-Encoding") != null
                || request.getContentLengthLong() > MAX_BODY_BYTES) throw invalid();
        oneHeader(request, "Authorization");
        for (String header : Collections.list(request.getHeaderNames())) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("x-quizopia-")
                    || lower.startsWith("x-authenticated-")
                    || Set.of("x-user-id", "x-user-roles", "x-service-identity").contains(lower)) throw invalid();
        }
    }

    private String oneHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw invalid();
        return values.getFirst();
    }

    private void expired(Jwt caller, Authentication authentication) {
        if (caller.getExpiresAt() == null || !Instant.now().isBefore(caller.getExpiresAt()))
            throw failure(401, "SERVICE_AUTHENTICATION_INVALID");
        if (!SecurityPurgeRoutes.permitted(authentication, properties))
            throw failure(401, "SERVICE_AUTHENTICATION_INVALID");
    }

    @PostMapping(SecurityPurgeRoutes.BATCH_PATH)
    public List<QuizPurgeService.Result> batch(
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            var contentTypes = Collections.list(request.getHeaders("Content-Type"));
            if (contentTypes.size() != 1
                    || !contentTypes
                            .getFirst()
                            .matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?")) throw invalid();
            var operations = parse(request);
            var result = service.purgeBatch(operations, properties.purgeBudget());
            expired(caller, authentication);
            return result;
        } catch (PublicationFailure known) {
            throw known;
        } catch (IOException | JacksonException malformed) {
            throw invalid();
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    private List<Operation> parse(HttpServletRequest request) throws IOException {
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) throw invalid();
        JsonNode body;
        try {
            body = json.readTree(bytes);
        } catch (JacksonException malformed) {
            throw invalid();
        }
        if (body == null
                || !body.isObject()
                || body.size() != 1
                || !body.path("operations").isArray()) throw invalid();
        JsonNode operations = body.path("operations");
        if (operations.size() < 1 || operations.size() > MAX_OPERATIONS) throw invalid();
        var parsed = new ArrayList<Operation>(operations.size());
        Set<UUID> quizIds = new HashSet<>(operations.size());
        Set<UUID> operationIds = new HashSet<>(operations.size());
        for (JsonNode item : operations) {
            if (item == null || !item.isObject() || item.size() != 2) throw invalid();
            String operationId = canonical(item, "operationId");
            String quizId = canonical(item, "quizId");
            if (!operationIds.add(UUID.fromString(operationId)) || !quizIds.add(UUID.fromString(quizId)))
                throw invalid();
            parsed.add(new Operation(UUID.fromString(operationId), UUID.fromString(quizId)));
        }
        return List.copyOf(parsed);
    }

    private String canonical(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isString() || !value.asString().matches(UUID_PATTERN)) throw invalid();
        return value.asString();
    }

    @GetMapping("/internal/security/quiz-purgings/{quizId}/status")
    public QuizPurgeService.PurgeStatus status(
            @PathVariable("quizId") String quizId,
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            try {
                if (request.getInputStream().read() != -1) throw invalid();
            } catch (IOException malformed) {
                throw invalid();
            }
            if (quizId == null || !quizId.matches(UUID_PATTERN)) throw invalid();
            var status = service.status(UUID.fromString(quizId));
            expired(caller, authentication);
            return status.orElseThrow(() -> failure(404, "PURGE_NOT_FOUND"));
        } catch (PublicationFailure known) {
            throw known;
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }
}
