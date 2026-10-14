package com.quizopia.proctoring.api;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.domain.Monitoring.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proctoring/monitoring/sessions")
@ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
public class MonitoringController {
    private final MonitoringService service;

    public MonitoringController(MonitoringService service) {
        this.service = service;
    }

    public record Create(UUID attemptId) {}

    private MonitoringAuthority.Actor actor(Jwt jwt) {
        return new MonitoringAuthority.Actor(
                UUID.fromString(jwt.getSubject()),
                Set.copyOf(jwt.getClaimAsStringList("roles")),
                jwt.getTokenValue(),
                jwt.getExpiresAt());
    }

    private <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping
    public ResponseEntity<Session> create(@AuthenticationPrincipal Jwt jwt, @RequestBody Create body) {
        return response(service.create(actor(jwt), body.attemptId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Session> status(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return response(service.status(actor(jwt), id));
    }

    @PostMapping("/{id}/events")
    public ResponseEntity<Acknowledgment> append(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @RequestBody Event event) {
        return response(service.append(actor(jwt), id, event));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<Page> timeline(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") UUID id,
            @RequestParam(name = "after", defaultValue = "0") long after,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return response(service.timeline(actor(jwt), id, after, limit));
    }

    @PostMapping("/{id}/reconcile")
    public ResponseEntity<Session> reconcile(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return response(service.status(actor(jwt), id));
    }
}
