package com.quizopia.proctoring.media;

import com.quizopia.proctoring.application.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Current authority is required on every request; stored bindings never authorize renewal. */
public final class MediaService {
    private final MonitoringStore sessions;
    private final MonitoringAuthority authority;
    private final MediaStore store;
    private final MediaProvider provider;
    private final MediaProperties properties;
    private final Semaphore capacity = new Semaphore(8);

    public MediaService(
            MonitoringStore sessions,
            MonitoringAuthority authority,
            MediaStore store,
            MediaProvider provider,
            MediaProperties properties) {
        this.sessions = sessions;
        this.authority = authority;
        this.store = store;
        this.provider = provider;
        this.properties = properties;
    }

    public record Grant(UUID id, String url, String token, Instant leaseUntil, String role, String technicalState) {
        @Override
        public String toString() {
            return "Grant[REDACTED]";
        }
    }

    public record Status(UUID id, Instant leaseUntil, String role, String status) {}

    private MonitoringAuthority.Snapshot check(MonitoringAuthority.Actor actor, UUID sessionId, boolean live) {
        var session = sessions.find(sessionId);
        String role = session.studentId().equals(actor.id()) ? "STUDENT" : "TEACHER";
        if (!actor.roles().contains(role)) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        var snapshot = live
                ? authority.checkLive(actor, session.attemptId(), role)
                : authority.check(actor, session.attemptId(), role);
        sessions.reconcile(actor, snapshot, sessionId, true);
        return snapshot;
    }

    private MediaStore.Lease owned(MonitoringAuthority.Actor actor, UUID sessionId, UUID id) {
        var lease = store.find(id);
        if (!lease.sessionId().equals(sessionId) || !lease.actorId().equals(actor.id()))
            throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        return lease;
    }

    private void available() {
        if (!provider.qualified() || !properties.isWorkerEnabled())
            throw new MonitoringFailure(503, "MEDIA_REVOCATION_UNVERIFIED");
    }

    public Grant create(MonitoringAuthority.Actor actor, UUID sessionId) {
        if (!capacity.tryAcquire()) throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
        try {
            var snapshot = check(actor, sessionId, true);
            available();
            var lease = store.prepare(actor, snapshot, sessionId, properties.getLeaseDuration());
            provider.ensureRoom(lease.room());
            // Network results do not authorize release: recheck actual resource authority afterwards.
            snapshot = check(actor, sessionId, true);
            lease = store.activate(actor, snapshot, lease.id());
            return grant(lease);
        } finally {
            capacity.release();
        }
    }

    private Grant grant(MediaStore.Lease lease) {
        String token = provider.token(lease.room(), lease.identity(), lease.role(), lease.leaseUntil());
        return new Grant(
                lease.id(),
                properties.getPublicUrl().toString(),
                token,
                lease.leaseUntil(),
                lease.role(),
                "GRANT_ISSUED");
    }

    public Grant renew(MonitoringAuthority.Actor actor, UUID sessionId, UUID id) {
        if (!capacity.tryAcquire()) throw new MonitoringFailure(503, "MEDIA_UNAVAILABLE");
        try {
            var snapshot = check(actor, sessionId, true);
            available();
            var current = owned(actor, sessionId, id);
            if (!current.status().equals("ACTIVE") || !current.leaseUntil().isAfter(Instant.now()))
                throw new MonitoringFailure(409, "MEDIA_GRANT_ENDED");
            provider.ensureRoom(current.room());
            snapshot = check(actor, sessionId, true);
            return grant(store.renew(actor, snapshot, id, properties.getLeaseDuration()));
        } finally {
            capacity.release();
        }
    }

    public Status status(MonitoringAuthority.Actor actor, UUID sessionId, UUID id) {
        check(actor, sessionId, false);
        return status(owned(actor, sessionId, id));
    }

    public Status revoke(MonitoringAuthority.Actor actor, UUID sessionId, UUID id) {
        check(actor, sessionId, false);
        owned(actor, sessionId, id);
        // Persist the irreversible local fence before any uncertain provider call.
        store.fence(id, actor.id());
        return status(store.find(id));
    }

    private Status status(MediaStore.Lease lease) {
        return new Status(lease.id(), lease.leaseUntil(), lease.role(), lease.status());
    }
}
