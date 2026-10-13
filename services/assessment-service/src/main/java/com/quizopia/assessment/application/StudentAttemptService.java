package com.quizopia.assessment.application;

import com.quizopia.assessment.domain.PublicationConfiguration.Audience;
import com.quizopia.assessment.domain.attempt.AttemptRecord.Reason;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Remote membership checks and C1 finalization never execute inside a local store transaction. */
@Service
public class StudentAttemptService {
    private final StudentAttemptStore store;
    private final PublicationSources sources;
    private final AttemptFinalization finalizer;

    public StudentAttemptService(StudentAttemptStore store, PublicationSources sources, AttemptFinalization finalizer) {
        this.store = store;
        this.sources = sources;
        this.finalizer = finalizer;
    }

    private void authorize(StudentAttemptStore.Actor actor, StudentAttemptStore.Resource resource, String evidence) {
        if (resource.configuration().audience() == Audience.CLASS)
            sources.member(resource.configuration().classroomId(), actor.id(), evidence);
    }

    private <T> StudentAttemptStore.Step<T> read(Supplier<StudentAttemptStore.Step<T>> work) {
        // A concurrent terminalization/retake may require another selection. Bound churn fail-closed.
        for (int n = 0; n < 4; n++) {
            var step = work.get();
            if (step.dueAttempt() == null) return step;
            finalizer.systemFinalize(step.dueAttempt(), step.reason());
        }
        throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
    }

    private <T> T mutation(StudentAttemptStore.Step<T> step) {
        if (step.dueAttempt() == null) return step.value();
        finalizer.systemFinalize(step.dueAttempt(), step.reason());
        // This conflict is outside the finalizer's committed transaction.
        throw new PublicationFailure(
                409,
                step.reason() == Reason.TEACHER_NOTICE_CUTOFF ? "CLOSURE_CUTOFF_REACHED" : "ATTEMPT_DEADLINE_REACHED");
    }

    private <T> T cutoffMutation(UUID id, Supplier<T> work) {
        try {
            return work.get();
        } catch (PublicationFailure failure) {
            if ("CLOSURE_CUTOFF_REACHED".equals(failure.code())) {
                finalizer.systemFinalize(id, Reason.TEACHER_NOTICE_CUTOFF);
                throw new PublicationFailure(409, "CLOSURE_CUTOFF_REACHED");
            }
            throw failure;
        }
    }

    public StudentAttemptStore.Step<AttemptViews.View> start(
            StudentAttemptStore.Actor actor, UUID id, String action, String locator, String digest, String evidence) {
        authorize(actor, store.publication(id), evidence);
        return read(() -> store.start(actor, id, action, locator, digest));
    }

    public AttemptViews.View resume(StudentAttemptStore.Actor actor, UUID id, String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        return read(() -> store.resume(actor, id)).value();
    }

    public AttemptViews.SaveAck save(
            StudentAttemptStore.Actor actor,
            UUID id,
            int question,
            long revision,
            String digest,
            String answer,
            String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        return cutoffMutation(id, () -> mutation(store.save(actor, id, question, revision, digest, answer)));
    }

    public AttemptViews.AbandonAck abandon(
            StudentAttemptStore.Actor actor, UUID id, long revision, String digest, String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        return cutoffMutation(id, () -> mutation(store.abandon(actor, id, revision, digest)));
    }

    public AttemptViews.Result submit(
            StudentAttemptStore.Actor actor,
            UUID id,
            long revision,
            String digest,
            String replacement,
            String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        var outcome = cutoffMutation(
                id,
                () -> finalizer.submit(new AttemptFinalization.Manual(
                        new AttemptFinalization.SelectedAttempt(id, actor.id(), actor.expiresAt()),
                        revision,
                        digest,
                        replacement)));
        if (outcome.kind() == AttemptFinalization.Kind.DEADLINE_REACHED)
            throw new PublicationFailure(409, "ATTEMPT_DEADLINE_REACHED");
        if (outcome.kind() == AttemptFinalization.Kind.CLOSURE_CUTOFF_REACHED)
            throw new PublicationFailure(409, "CLOSURE_CUTOFF_REACHED");
        return read(() -> store.result(actor, id)).value();
    }

    public AttemptViews.Result result(StudentAttemptStore.Actor actor, UUID id, String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        return read(() -> store.result(actor, id)).value();
    }

    public AttemptViews.Aggregate aggregate(StudentAttemptStore.Actor actor, UUID id, String evidence) {
        authorize(actor, store.publication(id), evidence);
        return read(() -> store.aggregate(actor, id)).value();
    }

    public AttemptViews.Review review(StudentAttemptStore.Actor actor, UUID id, String evidence) {
        authorize(actor, store.owned(actor, id), evidence);
        return read(() -> store.review(actor, id)).value();
    }
}
