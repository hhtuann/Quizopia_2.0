package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class AnswerAuditService {
    private final AnswerAuditStore store;
    private final PublicationSources sources;
    private final Semaphore budget = new Semaphore(8);

    public AnswerAuditService(AnswerAuditStore store, PublicationSources sources) {
        this.store = store;
        this.sources = sources;
    }

    public void requireCollection() {
        store.requirePolicy();
    }

    private <T> T bounded(Supplier<T> work) {
        if (!budget.tryAcquire()) throw new PublicationFailure(429, "AUDIT_RATE_LIMITED");
        try {
            return work.get();
        } finally {
            budget.release();
        }
    }

    public AnswerAuditStore.Ack append(
            UUID actor, Instant expiry, String evidence, UUID publication, UUID attempt, byte[] body) {
        return bounded(() -> {
            var b = store.resource(actor, expiry, publication, attempt, false);
            long until = System.nanoTime() + 5_000_000_000L;
            if (b.classroomId() != null) sources.member(b.classroomId(), actor, evidence);
            return store.append(actor, expiry, publication, attempt, b, until, AnswerObservation.parse(body));
        });
    }

    public AnswerAuditStore.Timeline timeline(
            ClosureStore.Teacher actor, UUID publication, UUID attempt, long after, int limit) {
        return bounded(() -> {
            var b = store.resource(actor.id(), actor.expiresAt(), publication, attempt, true);
            long until = System.nanoTime() + 5_000_000_000L;
            if (b.classroomId() != null) sources.teacher(b.classroomId(), actor.id(), actor.evidence());
            return store.timeline(actor, publication, attempt, b, until, after, limit);
        });
    }
}
