package com.quizopia.proctoring.integration;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Producer-owned business fact, never an assertion that a browser action or misconduct occurred. */
public record AssessmentAnswerEvent(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID attemptId,
        UUID publicationId,
        UUID classroomId,
        UUID studentId,
        Instant attemptStartedAt,
        Instant deadlineAt,
        int questionNumber,
        String revision) {
    public static final String TYPE = "ASSESSMENT_ANSWER_PERSISTED";
    private static final JsonMapper JSON = JsonMapper.builder()
            .findAndAddModules()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(tools.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(tools.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public static AssessmentAnswerEvent decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0 || bytes.length > 4096) throw new IllegalArgumentException();
            var node = JSON.readTree(bytes);
            if (!node.isObject()) throw new IllegalArgumentException();
            for (String field : List.of("revision", "eventType", "occurredAt", "attemptStartedAt", "deadlineAt"))
                if (!node.path(field).isString()) throw new IllegalArgumentException();
            if (!node.path("eventVersion").isIntegralNumber()
                    || !node.path("questionNumber").isIntegralNumber()) throw new IllegalArgumentException();
            for (String field : List.of("eventId", "attemptId", "publicationId", "classroomId", "studentId")) {
                var value = node.path(field);
                if (!value.isString()
                        || !value.asString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw new IllegalArgumentException();
            }
            var event = JSON.treeToValue(node, AssessmentAnswerEvent.class);
            event.validate();
            return event;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_EVENT");
        }
    }

    public void validate() {
        if (eventId == null
                || !TYPE.equals(eventType)
                || eventVersion != 1
                || attemptId == null
                || publicationId == null
                || classroomId == null
                || studentId == null
                || occurredAt == null
                || attemptStartedAt == null
                || deadlineAt == null
                || questionNumber < 1
                || revision == null
                || !revision.matches("[1-9][0-9]{0,18}")
                || Long.parseLong(revision) < 1
                || !deadlineAt.isAfter(attemptStartedAt)
                || Duration.between(attemptStartedAt, deadlineAt).compareTo(Duration.ofMinutes(5)) < 0
                || Duration.between(attemptStartedAt, deadlineAt).compareTo(Duration.ofHours(4)) > 0
                || occurredAt.isBefore(attemptStartedAt)
                || occurredAt.isAfter(Instant.now().plusSeconds(30)))
            throw new IllegalArgumentException("INVALID_EVENT");
        for (Instant time : List.of(occurredAt, attemptStartedAt, deadlineAt)) {
            if (time.isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || time.isAfter(Instant.parse("9999-12-31T23:59:59Z"))
                    || !time.equals(time.truncatedTo(ChronoUnit.MICROS)))
                throw new IllegalArgumentException("INVALID_EVENT");
        }
    }

    public byte[] bytes() {
        return JSON.writeValueAsBytes(this);
    }

    @Override
    public String toString() {
        return "AssessmentAnswerEvent[REDACTED]";
    }
}
