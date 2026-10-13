package com.quizopia.gateway.security.admission;

import org.springframework.boot.health.contributor.*;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Technical readiness only; conveys no USER authority or reusable recipient assertion. */
@Component("assessmentInbox")
public final class AssessmentInboxReadiness implements ReactiveHealthIndicator {
    private final InboxProperties inbox;
    private final AdmissionProperties admission;
    private final RouteDefinitionLocator routes;

    public AssessmentInboxReadiness(
            InboxProperties inbox, AdmissionProperties admission, RouteDefinitionLocator routes) {
        this.inbox = inbox;
        this.admission = admission;
        this.routes = routes;
    }

    @Override
    public Mono<Health> health() {
        if (!inbox.inboxEnabled()
                || !admission.isEnabled()
                || !"STUDENT".equals(ClosureRoutes.role("GET", "/api/assessments/notifications")))
            return Mono.just(Health.unknown().build());
        return routes.getRouteDefinitions()
                .any(route -> route.getId().equals("assessment-service"))
                .map(ready -> ready ? Health.up().build() : Health.unknown().build())
                .onErrorReturn(Health.down().build());
    }
}
