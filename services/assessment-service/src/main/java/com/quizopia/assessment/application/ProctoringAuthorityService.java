package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** External authority is checked outside local database transactions; this read never finalizes work. */
@Service
public class ProctoringAuthorityService {
    private final ProctoringAuthorityStore store;
    private final PublicationSources sources;
    private final ProctoringTargetEligibility target;

    public ProctoringAuthorityService(
            ProctoringAuthorityStore store, PublicationSources sources, ProctoringTargetEligibility target) {
        this.store = store;
        this.sources = sources;
        this.target = target;
    }

    public enum Purpose {
        HISTORY,
        LIVE_MEDIA
    }

    public View authority(UUID actor, String role, UUID attemptId, String evidence, Purpose purpose) {
        if (purpose == null) throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        var first = store.find(attemptId);
        authorize(first, actor, role);
        try {
            if (role.equals("STUDENT")) sources.member(first.classroomId(), actor, evidence);
            else sources.teacher(first.classroomId(), actor, evidence);
            if (purpose == Purpose.LIVE_MEDIA && role.equals("TEACHER")) {
                if (!first.active()) throw new PublicationFailure(404, "PROCTORING_NOT_ACCESSIBLE");
                target.require(first.classroomId(), first.studentId(), first.startedAt(), evidence);
            }
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
                || !first.ownerId().equals(current.ownerId())
                || first.durationMinutes() != current.durationMinutes()
                || !first.startedAt().equals(current.startedAt())
                || !first.deadlineAt().equals(current.deadlineAt()))
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        return new View(
                actor,
                role,
                purpose,
                current.attemptId(),
                current.publicationId(),
                current.classroomId(),
                current.studentId(),
                current.active(),
                current.deadlineAt(),
                current.checkedAt(),
                // FB-015 PR C: lets Proctoring scope its own revocation fence and purge.
                current.quizId());
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
            Purpose purpose,
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            boolean active,
            Instant deadlineAt,
            Instant checkedAt,
            UUID quizId) {
        /** Pre-FB015 spelling; a null quiz is still accepted by older callers. */
        public View(
                UUID actorId,
                String viewerRole,
                Purpose purpose,
                UUID attemptId,
                UUID publicationId,
                UUID classroomId,
                UUID studentId,
                boolean active,
                Instant deadlineAt,
                Instant checkedAt) {
            this(
                    actorId,
                    viewerRole,
                    purpose,
                    attemptId,
                    publicationId,
                    classroomId,
                    studentId,
                    active,
                    deadlineAt,
                    checkedAt,
                    null);
        }

        @Override
        public String toString() {
            return "ProctoringAuthorityView[redacted]";
        }
    }
}
