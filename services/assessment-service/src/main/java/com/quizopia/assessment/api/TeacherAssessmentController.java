package com.quizopia.assessment.api;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.application.TeacherAssessmentViews.*;
import com.quizopia.assessment.audit.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Narrow Task 2 adapters; authentication/admission independently required at edge and service. */
@RestController
@RequestMapping("/api/assessments/publications/{publicationId}")
public class TeacherAssessmentController {
    private final TeacherAssessmentService service;
    private final AnswerAuditService audit;

    public TeacherAssessmentController(TeacherAssessmentService service, AnswerAuditService audit) {
        this.service = service;
        this.audit = audit;
    }

    private static PublicationFailure invalid() {
        return new PublicationFailure(400, "INVALID_REQUEST");
    }

    private static UUID id(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value);
    }

    private ClosureStore.Teacher actor(Jwt jwt) {
        return new ClosureStore.Teacher(id(jwt.getSubject()), jwt.getExpiresAt(), jwt.getTokenValue());
    }

    private Map<String, String> query(HttpServletRequest request, HttpServletResponse response, Set<String> allowed) {
        response.setHeader("Cache-Control", "no-store");
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw invalid();
        try {
            if (request.getInputStream().read() != -1) throw invalid();
        } catch (IOException e) {
            throw invalid();
        }
        var encodings = Collections.list(request.getHeaders("Content-Encoding"));
        if (!encodings.isEmpty()
                && (encodings.size() != 1 || !encodings.getFirst().equalsIgnoreCase("identity"))) throw invalid();
        var values = new HashMap<String, String>();
        var raw = request.getQueryString();
        if (raw == null) return values;
        if (raw.isEmpty() || raw.length() > 4096) throw invalid();
        for (String item : raw.split("&", -1)) {
            var parts = item.split("=", -1);
            if (parts.length != 2
                    || !allowed.contains(parts[0])
                    || parts[1].isEmpty()
                    || values.putIfAbsent(parts[0], parts[1]) != null) throw invalid();
        }
        String cursor = values.get("cursor");
        if (cursor != null && (cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")))
            throw invalid();
        return values;
    }

    private int limit(Map<String, String> query) {
        String value = query.getOrDefault("limit", "20");
        if (!value.matches("[1-9][0-9]{0,2}")) throw invalid();
        int limit = Integer.parseInt(value);
        if (limit > 100) throw invalid();
        return limit;
    }

    @GetMapping("/student-overview")
    public Overview overview(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            HttpServletRequest request,
            HttpServletResponse response) {
        query(request, response, Set.of());
        return service.overview(actor(actor), id(publicationId));
    }

    @GetMapping("/students")
    public Students students(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var q = query(request, response, Set.of("limit", "cursor", "studentId", "status", "sort", "direction"));
        return service.students(
                actor(actor),
                id(publicationId),
                new TeacherAssessmentStore.Filter(
                        limit(q),
                        q.get("cursor"),
                        q.containsKey("studentId") ? id(q.get("studentId")) : null,
                        q.get("status"),
                        q.getOrDefault("sort", "LATEST"),
                        q.getOrDefault("direction", "DESC")));
    }

    @GetMapping("/students/{studentId}/attempts")
    public History history(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            @PathVariable("studentId") String studentId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var q = query(request, response, Set.of("limit", "cursor"));
        return service.history(actor(actor), id(publicationId), id(studentId), limit(q), q.get("cursor"));
    }

    @GetMapping("/attempts/{attemptId}/teacher-review")
    public Review review(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        query(request, response, Set.of());
        return service.review(actor(actor), id(publicationId), id(attemptId));
    }

    @GetMapping("/attempts/{attemptId}/answer-audit")
    public AnswerAuditStore.Timeline timeline(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        var q = query(request, response, Set.of("limit", "after"));
        String value = q.getOrDefault("after", "0");
        if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long after;
        try {
            after = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid();
        }
        if (!q.containsKey("limit")) q.put("limit", "50");
        return audit.timeline(actor(actor), id(publicationId), id(attemptId), after, limit(q));
    }

    @PostMapping("/attempts/{attemptId}/answer-observations")
    public AnswerAuditStore.Ack observe(
            @AuthenticationPrincipal Jwt actor,
            @PathVariable("publicationId") String publicationId,
            @PathVariable("attemptId") String attemptId,
            HttpServletRequest request,
            HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        // Cannot be enabled by environment/profile/attestation: policy is pending in the compiled application.
        audit.requireCollection();
        var types = Collections.list(request.getHeaders("Content-Type"));
        if (request.getQueryString() != null
                || request.getContentLengthLong() > 4096
                || types.size() != 1
                || !types.getFirst().matches("(?i)application/json(?:\\s*;\\s*charset=(?:UTF-8|\"UTF-8\"))?"))
            throw invalid();
        byte[] bytes;
        try {
            bytes = request.getInputStream().readNBytes(4097);
        } catch (IOException e) {
            throw invalid();
        }
        if (bytes.length == 0 || bytes.length > 4096) throw invalid();
        return audit.append(
                id(actor.getSubject()),
                actor.getExpiresAt(),
                actor.getTokenValue(),
                id(publicationId),
                id(attemptId),
                bytes);
    }
}
