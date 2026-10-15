package com.quizopia.proctoring.application;

import com.quizopia.proctoring.domain.Monitoring.Session;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
public final class MonitoringLookupService {
    private final MonitoringLookup lookup;
    private final MonitoringStore store;
    private final MonitoringAuthority authority;

    public MonitoringLookupService(MonitoringLookup lookup, MonitoringStore store, MonitoringAuthority authority) {
        this.lookup = lookup;
        this.store = store;
        this.authority = authority;
    }

    public Session byAttempt(MonitoringAuthority.Actor actor, UUID attemptId) {
        if (attemptId == null) throw new IllegalArgumentException();
        if (!actor.roles().contains("TEACHER")) throw new MonitoringFailure(403, "ACCESS_DENIED");
        // Establish current owning-Teacher/Classroom authority before revealing local existence.
        var snapshot = authority.check(actor, attemptId, "TEACHER");
        var now = Instant.now();
        if (snapshot == null
                || !actor.id().equals(snapshot.actorId())
                || !"TEACHER".equals(snapshot.viewerRole())
                || !attemptId.equals(snapshot.attemptId())
                || actor.expiresAt() == null
                || !actor.expiresAt().isAfter(now)
                || snapshot.checkedAt() == null
                || snapshot.checkedAt().isBefore(now.minusSeconds(5))
                || snapshot.checkedAt().isAfter(now.plusSeconds(1))) throw MonitoringFailure.unavailable();
        UUID sessionId = lookup.existingSession(attemptId);
        // Locked binding/freshness checks and the durable session-read audit are shared with status.
        return store.reconcile(actor, snapshot, sessionId, true);
    }
}
