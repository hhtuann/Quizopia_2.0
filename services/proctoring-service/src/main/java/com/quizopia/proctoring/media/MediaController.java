package com.quizopia.proctoring.media;

import com.quizopia.proctoring.application.MonitoringAuthority;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proctoring/monitoring/sessions/{id}/media-grants")
@ConditionalOnProperty(name = "quizopia.proctoring.media.enabled", havingValue = "true")
public class MediaController {
    private final MediaService service;

    public MediaController(MediaService s) {
        service = s;
    }

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
    public ResponseEntity<MediaService.Grant> create(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        return response(service.create(actor(jwt), id));
    }

    @GetMapping("/{grantId}")
    public ResponseEntity<MediaService.Status> status(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @PathVariable("grantId") UUID grant) {
        return response(service.status(actor(jwt), id, grant));
    }

    @PostMapping("/{grantId}/renew")
    public ResponseEntity<MediaService.Grant> renew(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @PathVariable("grantId") UUID grant) {
        return response(service.renew(actor(jwt), id, grant));
    }

    @PostMapping("/{grantId}/revoke")
    public ResponseEntity<MediaService.Status> revoke(
            @AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id, @PathVariable("grantId") UUID grant) {
        return response(service.revoke(actor(jwt), id, grant));
    }
}
