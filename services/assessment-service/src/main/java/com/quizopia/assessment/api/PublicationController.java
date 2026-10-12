package com.quizopia.assessment.api;

import com.quizopia.assessment.application.*;
import com.quizopia.assessment.domain.*;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assessments/publications")
public class PublicationController {
    private final PublicationService service;
    private final java.time.Clock clock;

    public PublicationController(PublicationService service, java.time.Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    public record Update(Long expectedRevision, PublicationConfiguration configuration) {
        @Override
        public String toString() {
            return "Update[redacted]";
        }
    }

    public record Open(Long expectedRevision) {}

    public record Schedule(Long expectedRevision, Instant endAt, Integer durationMinutes) {}

    public record Entry(String locator) {
        @Override
        public String toString() {
            return "Entry[redacted]";
        }
    }

    public record OwnerView(
            UUID publicationId,
            PublicationConfiguration configuration,
            Publication.State state,
            long revision,
            Instant createdAt,
            Instant openedAt,
            String privateLocator) {
        @Override
        public String toString() {
            return "OwnerView[redacted]";
        }
    }

    public record StudentView(
            UUID publicationId,
            String title,
            String description,
            PublicationConfiguration.Audience audience,
            Instant startAt,
            Instant endAt,
            int durationMinutes,
            boolean monitoringRequired,
            String windowStatus) {
        @Override
        public String toString() {
            return "StudentView[redacted]";
        }
    }

    private UUID actor(Jwt user) {
        return UUID.fromString(user.getSubject());
    }

    private OwnerView owner(Publication p) {
        return new OwnerView(
                p.id(), p.configuration(), p.state(), p.revision(), p.createdAt(), p.openedAt(), p.privateLocator());
    }

    private long revision(Long value) {
        if (value == null || value < 0) throw new PublicationFailure(400, "INVALID_REQUEST");
        return value;
    }

    @PostMapping
    public OwnerView create(
            @AuthenticationPrincipal Jwt user,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody PublicationConfiguration configuration,
            HttpServletResponse response) {
        var p = service.create(actor(user), key, configuration, user.getTokenValue());
        response.setStatus(201);
        return owner(p);
    }

    @GetMapping("/{id}")
    public OwnerView read(@AuthenticationPrincipal Jwt user, @PathVariable("id") UUID id) {
        return owner(service.read(actor(user), id));
    }

    @PutMapping("/{id}")
    public OwnerView update(@AuthenticationPrincipal Jwt user, @PathVariable("id") UUID id, @RequestBody Update body) {
        if (body.configuration() == null) throw new PublicationFailure(400, "INVALID_REQUEST");
        return owner(service.update(
                actor(user), id, revision(body.expectedRevision()), body.configuration(), user.getTokenValue()));
    }

    @PostMapping("/{id}/open")
    public OwnerView open(@AuthenticationPrincipal Jwt user, @PathVariable("id") UUID id, @RequestBody Open body) {
        return owner(service.open(actor(user), id, revision(body.expectedRevision()), user.getTokenValue()));
    }

    @PatchMapping("/{id}/schedule")
    public OwnerView schedule(
            @AuthenticationPrincipal Jwt user, @PathVariable("id") UUID id, @RequestBody Schedule body) {
        if (body.durationMinutes() == null) throw new PublicationFailure(400, "INVALID_REQUEST");
        return owner(service.extend(
                actor(user), id, revision(body.expectedRevision()), body.endAt(), body.durationMinutes()));
    }

    private StudentView student(Publication p) {
        var c = p.configuration();
        var now = clock.instant();
        return new StudentView(
                p.id(),
                c.title(),
                c.description(),
                c.audience(),
                c.startAt(),
                c.endAt(),
                c.durationMinutes(),
                c.monitoringChoice() == PublicationConfiguration.MonitoringChoice.ENABLED,
                now.isBefore(c.startAt()) ? "BEFORE_START" : now.isBefore(c.endAt()) ? "AVAILABLE" : "WINDOW_ENDED");
    }

    @PostMapping("/entry")
    public StudentView publicEntry(@RequestBody Entry body) {
        return student(service.publicEntry(body.locator()));
    }

    @PostMapping("/{id}/entry")
    public StudentView classEntry(@AuthenticationPrincipal Jwt user, @PathVariable("id") UUID id) {
        return student(service.classEntry(actor(user), id, user.getTokenValue()));
    }
}
