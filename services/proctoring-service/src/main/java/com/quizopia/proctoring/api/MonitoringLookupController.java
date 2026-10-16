package com.quizopia.proctoring.api;

import com.quizopia.proctoring.application.MonitoringAuthority;
import com.quizopia.proctoring.application.MonitoringLookupService;
import com.quizopia.proctoring.domain.Monitoring.Session;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proctoring/monitoring/sessions")
@ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
public final class MonitoringLookupController {
    private final MonitoringLookupService service;

    public MonitoringLookupController(MonitoringLookupService service) {
        this.service = service;
    }

    @GetMapping("/by-attempt/{attemptId}")
    public ResponseEntity<Session> byAttempt(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("attemptId") UUID attemptId) {
        var actor = new MonitoringAuthority.Actor(
                UUID.fromString(jwt.getSubject()),
                Set.copyOf(jwt.getClaimAsStringList("roles")),
                jwt.getTokenValue(),
                jwt.getExpiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.byAttempt(actor, attemptId));
    }
}
