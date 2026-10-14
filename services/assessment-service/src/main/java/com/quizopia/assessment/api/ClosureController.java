package com.quizopia.assessment.api;

import com.quizopia.assessment.application.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/assessments")
public class ClosureController {
    private final ClosureService service;
    private final JsonMapper json = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(16)
                            .maxNumberLength(19)
                            .maxStringLength(2097152)
                            .build())
                    .build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public ClosureController(ClosureService service) {
        this.service = service;
    }

    private PublicationFailure invalid() {
        return new PublicationFailure(400, "INVALID_REQUEST");
    }

    private UUID id(String value) {
        if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw invalid();
        return UUID.fromString(value);
    }

    private ClosureStore.Teacher actor(Jwt user) {
        return new ClosureStore.Teacher(id(user.getSubject()), user.getExpiresAt(), user.getTokenValue());
    }

    private void base(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        var encodings = Collections.list(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty()
                && (encodings.size() != 1 || !encodings.getFirst().equalsIgnoreCase("identity"))) throw invalid();
    }

    private Object command(
            Jwt user,
            String publication,
            String closure,
            String action,
            HttpServletRequest request,
            HttpServletResponse response) {
        base(request, response);
        if (request.getQueryString() != null) throw invalid();
        var types = Collections.list(request.getHeaders("Content-Type"));
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (types.size() != 1
                || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?")
                || keys.size() != 1
                || !keys.getFirst().matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
        long revision;
        try {
            if (request.getContentLengthLong() > 2097152) throw invalid();
            var bytes = request.getInputStream().readNBytes(2097153);
            if (bytes.length == 0 || bytes.length > 2097152) throw invalid();
            var body = json.readTree(bytes);
            var value = body.path("expectedRevision");
            if (!body.isObject()
                    || body.size() != 1
                    || !value.isIntegralNumber()
                    || !value.canConvertToLong()
                    || value.asLong() < 0) throw invalid();
            revision = value.asLong();
        } catch (IOException | JacksonException e) {
            throw invalid();
        }
        String key;
        try {
            key = HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(keys.getFirst().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        var reply = service.command(
                actor(user),
                new ClosureStore.Command(id(publication), closure == null ? null : id(closure), revision, key, action));
        response.setStatus(reply.status());
        return reply.body();
    }

    private Map<String, String> get(HttpServletRequest request, HttpServletResponse response, boolean paged) {
        base(request, response);
        try {
            if (request.getInputStream().read() != -1) throw invalid();
        } catch (IOException e) {
            throw invalid();
        }
        var result = new HashMap<String, String>();
        String query = request.getQueryString();
        if (query == null) return result;
        if (!paged || query.isEmpty()) throw invalid();
        for (var item : query.split("&", -1)) {
            var parts = item.split("=", -1);
            if (parts.length != 2
                    || !Set.of("limit", "cursor").contains(parts[0])
                    || result.putIfAbsent(parts[0], parts[1]) != null
                    || parts[1].isEmpty()) throw invalid();
        }
        if (result.containsKey("limit") && !result.get("limit").matches("[1-9][0-9]{0,2}")) throw invalid();
        if (result.containsKey("cursor")
                && (result.get("cursor").length() > 1024
                        || !result.get("cursor").matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) throw invalid();
        return result;
    }

    private int limit(Map<String, String> query) {
        int limit = Integer.parseInt(query.getOrDefault("limit", "20"));
        if (limit > 100) throw invalid();
        return limit;
    }

    @PostMapping("/publications/{publicationId}/close")
    public Object close(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            HttpServletRequest request,
            HttpServletResponse response) {
        return command(user, publication, null, "CLOSE", request, response);
    }

    @PostMapping("/publications/{publicationId}/closure-requests")
    public Object request(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            HttpServletRequest request,
            HttpServletResponse response) {
        return command(user, publication, null, "REQUEST", request, response);
    }

    @PostMapping("/publications/{publicationId}/closure-requests/{closureRequestId}/cancel")
    public Object cancel(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            @PathVariable("closureRequestId") String closure,
            HttpServletRequest request,
            HttpServletResponse response) {
        return command(user, publication, closure, "CANCEL", request, response);
    }

    @PostMapping("/publications/{publicationId}/closure-requests/{closureRequestId}/complete")
    public Object complete(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            @PathVariable("closureRequestId") String closure,
            HttpServletRequest request,
            HttpServletResponse response) {
        return command(user, publication, closure, "COMPLETE", request, response);
    }

    @GetMapping("/publications/{publicationId}/closure-requests/{closureRequestId}")
    public ClosureViews.Closure closure(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            @PathVariable("closureRequestId") String closure,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response, false);
        return service.get(actor(user), id(publication), id(closure));
    }

    @GetMapping("/publications/{publicationId}/results")
    public ClosureViews.Page<ClosureViews.TeacherRow> results(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("publicationId") String publication,
            HttpServletRequest request,
            HttpServletResponse response) {
        var query = get(request, response, true);
        return service.results(actor(user), id(publication), limit(query), query.get("cursor"));
    }

    @GetMapping("/notifications")
    public ClosureViews.Page<ClosureViews.Notification> inbox(
            @AuthenticationPrincipal Jwt user, HttpServletRequest request, HttpServletResponse response) {
        var query = get(request, response, true);
        return service.inbox(id(user.getSubject()), limit(query), query.get("cursor"));
    }

    @GetMapping("/notifications/{notificationId}")
    public ClosureViews.Notification notification(
            @AuthenticationPrincipal Jwt user,
            @PathVariable("notificationId") String notification,
            HttpServletRequest request,
            HttpServletResponse response) {
        get(request, response, false);
        return service.notification(id(user.getSubject()), id(notification));
    }
}
