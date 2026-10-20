package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assessment-owned realtime subscription authorization. External authority is checked outside
 * local database reads; every decision re-reads the current resource and fails closed on drift.
 * No positive decision is cached.
 */
@Service
public class RealtimeAuthorityService {
    public static final String ATTEMPT_OWNER = "ATTEMPT_OWNER";
    public static final String PUBLICATION_TEACHER = "PUBLICATION_TEACHER";

    private final RealtimeAuthorityStore store;
    private final PublicationSources sources;

    public RealtimeAuthorityService(RealtimeAuthorityStore store, PublicationSources sources) {
        this.store = store;
        this.sources = sources;
    }

    public Decision authorize(UUID actor, String resourceKind, UUID resourceId, String evidence) {
        return switch (resourceKind) {
            case ATTEMPT_OWNER -> attempt(actor, resourceId, evidence);
            case PUBLICATION_TEACHER -> publication(actor, resourceId, evidence);
            default -> throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        };
    }

    private Decision attempt(UUID actor, UUID attemptId, String evidence) {
        var first = store.attempt(attemptId);
        requireAttempt(first, actor);
        if (first.classroomId() != null) classroom(first.classroomId(), actor, evidence, false);
        var current = store.attempt(attemptId);
        requireAttempt(current, actor);
        if (!first.publicationId().equals(current.publicationId())
                || !equalsNullable(first.classroomId(), current.classroomId())
                || !first.studentId().equals(current.studentId())
                || !first.ownerId().equals(current.ownerId()))
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        return new Decision(ATTEMPT_OWNER, attemptId, current.checkedAt());
    }

    private Decision publication(UUID actor, UUID publicationId, String evidence) {
        var first = store.publication(publicationId);
        requirePublication(first, actor);
        if (first.classroomId() != null) classroom(first.classroomId(), actor, evidence, true);
        var current = store.publication(publicationId);
        requirePublication(current, actor);
        if (!equalsNullable(first.classroomId(), current.classroomId())
                || !first.ownerId().equals(current.ownerId()))
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        return new Decision(PUBLICATION_TEACHER, publicationId, current.checkedAt());
    }

    private void classroom(UUID classroomId, UUID actor, String evidence, boolean teacher) {
        try {
            if (teacher) sources.teacher(classroomId, actor, evidence);
            else sources.member(classroomId, actor, evidence);
        } catch (PublicationFailure failure) {
            throw switch (failure.status()) {
                case 401 -> new PublicationFailure(401, "USER_EVIDENCE_INVALID");
                case 403 -> new PublicationFailure(403, "SERVICE_CALLER_FORBIDDEN");
                case 404 -> new PublicationFailure(404, "NOT_ACCESSIBLE");
                default -> new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
            };
        }
    }

    private void requireAttempt(RealtimeAuthorityStore.AttemptView view, UUID actor) {
        if (view == null || !view.active() || !actor.equals(view.studentId()))
            throw new PublicationFailure(404, "NOT_ACCESSIBLE");
    }

    private void requirePublication(RealtimeAuthorityStore.PublicationView view, UUID actor) {
        if (view == null || !view.open() || !actor.equals(view.ownerId()))
            throw new PublicationFailure(404, "NOT_ACCESSIBLE");
    }

    private static boolean equalsNullable(UUID first, UUID second) {
        return first == null ? second == null : first.equals(second);
    }

    public record Decision(String resourceKind, UUID resourceId, Instant checkedAt) {
        @Override
        public String toString() {
            return "RealtimeAuthorityDecision[redacted]";
        }
    }
}
