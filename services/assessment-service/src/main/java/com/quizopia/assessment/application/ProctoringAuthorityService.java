package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** External authority is checked outside local database transactions; this read never finalizes work. */
@Service
public class ProctoringAuthorityService {
    private final ProctoringAuthorityStore store;
    private final PublicationSources sources;

    public ProctoringAuthorityService(ProctoringAuthorityStore store, PublicationSources sources) {
        this.store = store;
        this.sources = sources;
    }

    public View authority(UUID actor, String role, UUID attemptId, String evidence) {
        var first = store.find(attemptId);
        authorize(first, actor, role);
        try {
            if (role.equals("STUDENT")) sources.member(first.classroomId(), actor, evidence);
            else sources.teacher(first.classroomId(), actor, evidence);
        } catch (PublicationFailure failure) {
            if (failure.status() == 404) throw new PublicationFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            if (failure.status() == 401) throw new PublicationFailure(401, "USER_EVIDENCE_INVALID");
            if (failure.status() == 403) throw failure;
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        }
        var current = store.find(attemptId);
        authorize(current, actor, role);
        if (!first.publicationId().equals(current.publicationId())
                || !first.classroomId().equals(current.classroomId())
                || !first.studentId().equals(current.studentId())
                || !first.ownerId().equals(current.ownerId()))
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        return new View(
                actor,
                role,
                current.attemptId(),
                current.publicationId(),
                current.classroomId(),
                current.studentId(),
                current.active(),
                current.deadlineAt(),
                current.checkedAt());
    }

    private void authorize(ProctoringAuthorityStore.Projection value, UUID actor, String role) {
        if (value == null
                || !value.monitorable()
                || value.deadlineAt() == null
                || !(role.equals("STUDENT")
                        ? actor.equals(value.studentId())
                        : role.equals("TEACHER") && actor.equals(value.ownerId())))
            throw new PublicationFailure(404, "PROCTORING_NOT_ACCESSIBLE");
    }

    public record View(
            UUID actorId,
            String viewerRole,
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            boolean active,
            Instant deadlineAt,
            Instant checkedAt) {
        @Override
        public String toString() {
            return "ProctoringAuthorityView[redacted]";
        }
    }
}
