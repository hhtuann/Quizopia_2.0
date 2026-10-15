package com.quizopia.proctoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class Monitoring {
    private Monitoring() {}

    public enum EventType {
        QUESTION_VIEWED,
        QUESTION_LEFT,
        ANSWER_SELECTED,
        ANSWER_CHANGED,
        ANSWER_CLEARED,
        VISIBILITY_HIDDEN,
        VISIBILITY_VISIBLE,
        WINDOW_BLUR,
        WINDOW_FOCUS,
        FULLSCREEN_ENTERED,
        FULLSCREEN_EXITED,
        CAMERA_PERMISSION_GRANTED,
        CAMERA_PERMISSION_DENIED,
        DEVICE_MISSING,
        DEVICE_ERROR,
        CAMERA_UNAVAILABLE,
        MEDIA_DISCONNECTED,
        RECONNECTING,
        NETWORK_DISCONNECTED,
        NETWORK_RECONNECTED,
        DUPLICATE_TAB,
        ANSWER_PERSISTED,
        SESSION_STARTED,
        SESSION_ENDED;

        public boolean serverOnly() {
            return this == SESSION_STARTED || this == SESSION_ENDED || this == ANSWER_PERSISTED;
        }

        public boolean questionScoped() {
            return name().startsWith("QUESTION_") || name().startsWith("ANSWER_");
        }
    }

    public record Event(
            UUID eventId,
            UUID streamId,
            Long sequence,
            EventType type,
            Instant observedAt,
            Integer questionNumber,
            Integer schemaVersion) {
        public void validate() {
            if (schemaVersion == null
                    || schemaVersion != 1
                    || eventId == null
                    || streamId == null
                    || sequence == null
                    || sequence < 0
                    || type == null
                    || type.serverOnly()
                    || (observedAt != null
                            && (observedAt.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                                    || observedAt.isAfter(Instant.parse("9999-12-31T23:59:59.999999999Z"))))
                    || (type.questionScoped() ? questionNumber == null || questionNumber < 1 : questionNumber != null))
                throw new IllegalArgumentException("Invalid monitoring event");
        }
    }

    public record Session(
            UUID id,
            UUID attemptId,
            UUID publicationId,
            UUID classroomId,
            UUID studentId,
            Instant deadlineAt,
            String status,
            Instant createdAt,
            Instant endedAt) {}

    public record Acknowledgment(UUID id, long ordinal, Instant receivedAt) {}

    public record Evidence(
            long ordinal,
            UUID id,
            UUID streamId,
            Long sequence,
            EventType type,
            Instant observedAt,
            Integer questionNumber,
            Instant receivedAt,
            String source,
            String integritySha256,
            int schemaVersion,
            UUID actorId,
            String assessmentRevision) {}

    public record Page(List<Evidence> items, Long nextAfter) {}
}
