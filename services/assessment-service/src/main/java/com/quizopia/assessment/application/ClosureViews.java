package com.quizopia.assessment.application;

import java.time.Instant;
import java.util.*;

/** Explicit B3 projections. No snapshot, locator or peer data in inbox representations. */
public final class ClosureViews {
    private ClosureViews() {}

    public record Closure(
            UUID closureRequestId,
            UUID publicationId,
            UUID requestingTeacherId,
            Instant requestedAt,
            String state,
            Instant finalizationStartedAt,
            Instant resolvedAt,
            long publicationRevision) {}

    public record Closed(UUID publicationId, String state, long revision, Instant closedAt) {}

    public record Completion(UUID closureRequestId, UUID publicationId, boolean closed, long publicationRevision) {}

    public record TeacherRow(UUID attemptId, UUID studentId, String ordinal, String state, AttemptViews.Result result) {
        public String toString() {
            return "TeacherRow[redacted]";
        }
    }

    public record Page<T>(List<T> items, String nextCursor) {
        public Page {
            items = List.copyOf(items);
        }

        public String toString() {
            return "Page[redacted]";
        }
    }

    public record Notification(
            UUID notificationId,
            UUID publicationId,
            String publicationTitle,
            UUID attemptId,
            UUID closureRequestId,
            boolean teacherInitiated,
            int contentVersion,
            Instant publishedAt,
            Instant confirmedNoticeAt,
            Instant closureCutoffAt,
            int warningHours,
            String noticeStatus,
            String message,
            UUID resultId,
            Instant serverNow) {
        public String toString() {
            return "Notification[redacted]";
        }
    }

    public record Reply(int status, Object body) {}
}
