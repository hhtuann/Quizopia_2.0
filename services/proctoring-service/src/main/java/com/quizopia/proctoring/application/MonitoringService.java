package com.quizopia.proctoring.application;

import com.quizopia.proctoring.domain.Monitoring.*;
import java.util.UUID;

public final class MonitoringService {
    private final MonitoringStore store;
    private final MonitoringAuthority authority;

    public MonitoringService(MonitoringStore store, MonitoringAuthority authority) {
        this.store = store;
        this.authority = authority;
    }

    public Session create(MonitoringAuthority.Actor actor, UUID attempt) {
        if (attempt == null) throw new IllegalArgumentException();
        var snapshot = authority.check(actor, attempt, "STUDENT");
        if (!snapshot.active()) throw new MonitoringFailure(409, "SESSION_ENDED");
        return store.create(actor, snapshot);
    }

    private MonitoringAuthority.Snapshot check(MonitoringAuthority.Actor actor, Session session, boolean teacher) {
        String role = teacher || !session.studentId().equals(actor.id()) ? "TEACHER" : "STUDENT";
        if (!actor.roles().contains(role)) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        return authority.check(actor, session.attemptId(), role);
    }

    public Session status(MonitoringAuthority.Actor actor, UUID id) {
        var session = store.find(id);
        return store.reconcile(actor, check(actor, session, false), id, true);
    }

    public Acknowledgment append(MonitoringAuthority.Actor actor, UUID id, Event event) {
        event.validate();
        var session = store.find(id);
        if (!session.studentId().equals(actor.id())) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        return store.append(actor, check(actor, session, false), id, event);
    }

    public Page timeline(MonitoringAuthority.Actor actor, UUID id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
        var session = store.find(id);
        return store.timeline(actor, check(actor, session, true), id, after, limit);
    }
}
