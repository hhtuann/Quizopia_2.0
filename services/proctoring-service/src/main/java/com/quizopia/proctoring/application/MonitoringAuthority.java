package com.quizopia.proctoring.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public interface MonitoringAuthority {
    record Actor(UUID id, Set<String> roles, String userEvidence, Instant expiresAt) {
        @Override
        public String toString() {
            return "Actor[redacted]";
        }
    }

    record Snapshot(
            UUID actorId,
            String viewerRole,
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            boolean active,
            Instant deadlineAt,
            Instant checkedAt) {}

    Snapshot check(Actor actor, UUID attempt, String role);
}
