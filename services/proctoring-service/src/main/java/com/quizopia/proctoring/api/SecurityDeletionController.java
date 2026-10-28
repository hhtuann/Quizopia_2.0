package com.quizopia.proctoring.api;

import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.application.QuizPurgeService;
import com.quizopia.proctoring.application.QuizRevocationService;
import com.quizopia.proctoring.configuration.ProctoringDeletionProperties;
import com.quizopia.proctoring.security.deletion.QuizDeletionRequestBudget;
import com.quizopia.proctoring.security.deletion.SecurityDeletionRoutes;
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
 * FB-015 PR C private surface for the Quiz Service deletion coordinator only (ADR-018
 * Phase A step 4 and Phase B step 7). Exact SERVICE caller, dedicated scopes, strict
 * bounded transport, no-store. Denials never disclose quiz content, counts or PII.
 * Disabled unless quizopia.deletion.enabled.
 */
@Hidden
@RestController
public class SecurityDeletionController {
    static final int MAX_BODY_BYTES = 16384;
    static final int MAX_OPERATIONS = 50;
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final ProctoringDeletionProperties properties;
    private final QuizRevocationService revocations;
    private final QuizPurgeService purges;
    private final QuizDeletionRequestBudget budget;
    private final Semaphore capacity;
    private final JsonMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SecurityDeletionController(
            ProctoringDeletionProperties properties,
            QuizRevocationService revocations,
            QuizPurgeService purges,
            QuizDeletionRequestBudget budget,
            Environment environment) {
        if (environment.matchesProfiles("prod") && properties.allowInsecureLoopback())
            throw new IllegalStateException("Private quiz deletion requires HTTPS in production");
        this.properties = properties;
        this.revocations = revocations;
        this.purges = purges;
        this.budget = budget;
        this.capacity = new Semaphore(properties.maxConcurrent());
    }

    private static MonitoringFailure failure(int status, String code) {
        return new MonitoringFailure(status, code);
    }

    private static MonitoringFailure invalid() {
        return failure(400, "INVALID_INTERNAL_REQUEST");
    }

    private void admit(Authentication authentication, Jwt caller, HttpServletRequest request, String scope) {
        if (!SecurityDeletionRoutes.permitted(authentication, properties, scope))
            throw failure(403, "SERVICE_CALLER_FORBIDDEN");
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

    private void expired(Jwt caller, Authentication authentication, String scope) {
        if (caller.getExpiresAt() == null || !Instant.now().isBefore(caller.getExpiresAt()))
            throw failure(401, "SERVICE_AUTHENTICATION_INVALID");
        if (!SecurityDeletionRoutes.permitted(authentication, properties, scope))
            throw failure(401, "SERVICE_AUTHENTICATION_INVALID");
    }

    private List<QuizRevocationService.Operation> parse(HttpServletRequest request) throws IOException {
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
        var parsed = new ArrayList<QuizRevocationService.Operation>(operations.size());
        Set<UUID> quizIds = new HashSet<>(operations.size());
        Set<UUID> operationIds = new HashSet<>(operations.size());
        for (JsonNode item : operations) {
            if (item == null || !item.isObject() || item.size() != 2) throw invalid();
            String operationId = canonical(item, "operationId");
            String quizId = canonical(item, "quizId");
            if (!operationIds.add(UUID.fromString(operationId)) || !quizIds.add(UUID.fromString(quizId)))
                throw invalid();
            parsed.add(new QuizRevocationService.Operation(UUID.fromString(operationId), UUID.fromString(quizId)));
        }
        return List.copyOf(parsed);
    }

    private String canonical(JsonNode item, String field) {
        JsonNode value = item.path(field);
        if (!value.isString() || !value.asString().matches(UUID_PATTERN)) throw invalid();
        return value.asString();
    }

    @PostMapping(SecurityDeletionRoutes.REVOCATION_BATCH_PATH)
    public QuizRevocationService.Batch revocationBatch(
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request, SecurityDeletionRoutes.SCOPE_REVOKE);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            contentType(request);
            var operations = parse(request);
            var result = revocations.applyBatch(operations);
            expired(caller, authentication, SecurityDeletionRoutes.SCOPE_REVOKE);
            return result;
        } catch (MonitoringFailure known) {
            throw known;
        } catch (IOException | JacksonException malformed) {
            throw invalid();
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    @GetMapping("/internal/security/quiz-revocations/{quizId}/status")
    public QuizRevocationService.Status revocationStatus(
            @PathVariable("quizId") String quizId,
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request, SecurityDeletionRoutes.SCOPE_REVOKE);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            drain(request);
            if (quizId == null || !quizId.matches(UUID_PATTERN)) throw invalid();
            var status = revocations.status(UUID.fromString(quizId));
            expired(caller, authentication, SecurityDeletionRoutes.SCOPE_REVOKE);
            return status.orElseThrow(() -> failure(404, "REVOCATION_NOT_FOUND"));
        } catch (MonitoringFailure known) {
            throw known;
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    @PostMapping(SecurityDeletionRoutes.PURGE_BATCH_PATH)
    public List<QuizPurgeService.Result> purgeBatch(
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request, SecurityDeletionRoutes.SCOPE_PURGE);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            contentType(request);
            var operations = parse(request).stream()
                    .map(operation -> new QuizPurgeService.PurgeOperation(operation.operationId(), operation.quizId()))
                    .toList();
            var result = purges.purgeBatch(operations, properties.purgeBatchSize());
            expired(caller, authentication, SecurityDeletionRoutes.SCOPE_PURGE);
            return result;
        } catch (MonitoringFailure known) {
            throw known;
        } catch (IOException | JacksonException malformed) {
            throw invalid();
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    @GetMapping("/internal/security/quiz-purgings/{quizId}/status")
    public QuizPurgeService.PurgeStatus purgeStatus(
            @PathVariable("quizId") String quizId,
            @AuthenticationPrincipal Jwt caller,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        response.setHeader("Cache-Control", "no-store");
        admit(authentication, caller, request, SecurityDeletionRoutes.SCOPE_PURGE);
        if (!capacity.tryAcquire()) throw failure(503, "AUTHORITY_UNAVAILABLE");
        try {
            transport(request);
            drain(request);
            if (quizId == null || !quizId.matches(UUID_PATTERN)) throw invalid();
            var status = purges.status(UUID.fromString(quizId));
            expired(caller, authentication, SecurityDeletionRoutes.SCOPE_PURGE);
            return status.orElseThrow(() -> failure(404, "PURGE_NOT_FOUND"));
        } catch (MonitoringFailure known) {
            throw known;
        } catch (RuntimeException untrusted) {
            throw failure(503, "AUTHORITY_UNAVAILABLE");
        } finally {
            capacity.release();
        }
    }

    private void drain(HttpServletRequest request) throws IOException {
        try {
            if (request.getInputStream().read() != -1) throw invalid();
        } catch (IOException malformed) {
            throw invalid();
        }
    }

    private void contentType(HttpServletRequest request) {
        var contentTypes = Collections.list(request.getHeaders("Content-Type"));
        if (contentTypes.size() != 1
                || !contentTypes.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
            throw invalid();
    }
}
