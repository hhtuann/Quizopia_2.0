package com.quizopia.assessment.api;

import com.quizopia.assessment.application.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/assessments")
public class StudentAttemptController {
    public static final int BODY_LIMIT = 2097152;
    private final StudentAttemptService service;
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(16)
                            .maxNumberLength(19)
                            .maxStringLength(BODY_LIMIT)
                            .build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public StudentAttemptController(StudentAttemptService service) {
        this.service = service;
    }

    private PublicationFailure invalid() {
        return new PublicationFailure(400, "INVALID_REQUEST");
    }

    private void base(HttpServletRequest request, HttpServletResponse response, boolean post) {
        response.setHeader("Cache-Control", "no-store");
        if (request.getQueryString() != null) throw invalid();
        var encodings = Collections.list(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty()
                && (encodings.size() != 1 || !encodings.getFirst().equalsIgnoreCase("identity"))) throw invalid();
        if (post) {
            var types = Collections.list(request.getHeaders("Content-Type"));
            if (types.size() != 1
                    || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
                throw invalid();
        }
    }

    private JsonNode body(HttpServletRequest request, HttpServletResponse response) {
        base(request, response, true);
        try {
            if (request.getContentLengthLong() > BODY_LIMIT) throw invalid();
            byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (bytes.length == 0 || bytes.length > BODY_LIMIT) throw invalid();
            var node = json.readTree(bytes);
            if (node == null || !node.isObject()) throw invalid();
            return node;
        } catch (IOException | JacksonException e) {
            throw invalid();
        }
    }

    private void get(HttpServletRequest request, HttpServletResponse response) {
        base(request, response, false);
        try {
            if (request.getInputStream().read() != -1) throw invalid();
        } catch (IOException e) {
            throw invalid();
        }
    }

    private void fields(JsonNode node, Set<String> required, Set<String> allowed) {
        if (!node.isObject()
                || !node.propertyNames().containsAll(required)
                || !allowed.containsAll(node.propertyNames())) throw invalid();
    }

    private String key(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1 || !keys.getFirst().matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
        // Do not persist or pass plaintext operation keys beyond the HTTP boundary.
        return sha(keys.getFirst());
    }

    private String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID id(String text) {
        if (!text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return UUID.fromString(text);
    }

    private long revision(JsonNode node) {
        var r = node.path("expectedRevision");
        if (!r.isString() || !r.asString().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try {
            return Long.parseLong(r.asString());
        } catch (NumberFormatException e) {
            throw invalid();
        }
    }

    private StudentAttemptStore.Actor actor(Jwt user) {
        return new StudentAttemptStore.Actor(UUID.fromString(user.getSubject()), user.getExpiresAt());
    }

    private AttemptViews.View creation(
            Jwt user, String publication, String action, HttpServletRequest request, HttpServletResponse response) {
        var body = body(request, response);
        fields(body, Set.of(), Set.of("locator"));
        String locator = null;
        if (body.has("locator")) {
            if (!body.path("locator").isString()) throw invalid();
            locator = body.path("locator").asString();
        }
        var result = service.start(actor(user), id(publication), action, locator, key(request), user.getTokenValue());
        response.setStatus(result.created() ? 201 : 200);
        return result.value();
    }

    @PostMapping("/publications/{publicationId}/attempts/start")
    public AttemptViews.View start(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publicationId,
            HttpServletRequest request,
            HttpServletResponse response) {
        return creation(user, publicationId, "START", request, response);
    }

    @PostMapping("/publications/{publicationId}/attempts/retake")
    public AttemptViews.View retake(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publicationId,
            HttpServletRequest request,
            HttpServletResponse response) {
        return creation(user, publicationId, "RETAKE", request, response);
    }

    @GetMapping("/attempts/{attemptId}")
    public AttemptViews.View resume(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response);
        return service.resume(actor(user), id(attemptId), user.getTokenValue());
    }

    @PostMapping("/attempts/{attemptId}/questions/{questionNumber}/answer")
    public AttemptViews.SaveAck save(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            @PathVariable("questionNumber") String questionNumber,
            HttpServletRequest request,
            HttpServletResponse response) {
        var b = body(request, response);
        fields(b, Set.of("expectedRevision", "answer"), Set.of("expectedRevision", "answer"));
        if (!questionNumber.matches("[1-9][0-9]{0,9}")) throw invalid();
        int q;
        try {
            q = Integer.parseInt(questionNumber);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        return service.save(
                actor(user),
                id(attemptId),
                q,
                revision(b),
                key(request),
                json.writeValueAsString(b.path("answer")),
                user.getTokenValue());
    }

    @PostMapping("/attempts/{attemptId}/submit")
    public AttemptViews.Result submit(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var b = body(request, response);
        fields(b, Set.of("expectedRevision"), Set.of("expectedRevision", "answers"));
        if (b.has("answers")
                && (!b.path("answers").isArray() || b.path("answers").isEmpty())) throw invalid();
        return service.submit(
                actor(user),
                id(attemptId),
                revision(b),
                key(request),
                b.has("answers") ? json.writeValueAsString(b.path("answers")) : null,
                user.getTokenValue());
    }

    @PostMapping("/attempts/{attemptId}/abandon")
    public AttemptViews.AbandonAck abandon(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var b = body(request, response);
        fields(b, Set.of("expectedRevision"), Set.of("expectedRevision"));
        return service.abandon(actor(user), id(attemptId), revision(b), key(request), user.getTokenValue());
    }

    @GetMapping("/attempts/{attemptId}/result")
    public AttemptViews.Result result(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response);
        return service.result(actor(user), id(attemptId), user.getTokenValue());
    }

    @GetMapping("/publications/{publicationId}/aggregate-score")
    public AttemptViews.Aggregate aggregate(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publicationId,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response);
        return service.aggregate(actor(user), id(publicationId), user.getTokenValue());
    }

    @GetMapping("/attempts/{attemptId}/review")
    public AttemptViews.Review review(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response);
        return service.review(actor(user), id(attemptId), user.getTokenValue());
    }
}
