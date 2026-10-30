package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.PublicationFailure;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-side canonical contract for synthetic SERVER_AUTHORITY facts. No automatic producer,
 * capture hook or runtime activation is implemented here.
 */
public record ServerAuditFact(
        UUID eventId, String type, Instant occurredAt, Integer questionNumber, String assessmentRevision) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> TYPES = Set.of(
            "ATTEMPT_STARTED",
            "ANSWER_PERSISTED",
            "ATTEMPT_SUBMITTED",
            "ATTEMPT_ABANDONED",
            "CLOSURE_NOTICE_CONFIRMED",
            "RESULT_GRADED");
    private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,18}");

    private static boolean positive(String value) {
        if (!POSITIVE.matcher(value).matches()) return false;
        try {
            Long.parseLong(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public ServerAuditFact {
        if (eventId == null
                || !TYPES.contains(type)
                || occurredAt == null
                || (questionNumber != null && questionNumber < 1)
                || (assessmentRevision != null && !positive(assessmentRevision))) throw invalid();
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    public static PublicationFailure invalid() {
        return new PublicationFailure(400, "INVALID_REQUEST");
    }

    private static UUID uuid(JsonNode value) {
        if (!value.isString()
                || !value.asString().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid();
        return UUID.fromString(value.asString());
    }

    public static ServerAuditFact parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4096) throw invalid();
        try {
            var n = JSON.readTree(bytes);
            var required = Set.of(
                    "schemaVersion", "eventId", "source", "type", "occurredAt", "questionNumber", "assessmentRevision");
            if (!n.isObject()
                    || !n.propertyNames().equals(required)
                    || !n.path("schemaVersion").isIntegralNumber()
                    || !n.path("schemaVersion").canConvertToInt()
                    || n.path("schemaVersion").asInt() != 1
                    || !n.path("source").isString()
                    || !n.path("source").asString().equals("SERVER_AUTHORITY")
                    || !n.path("type").isString()
                    || !n.path("occurredAt").isString()) throw invalid();
            Integer question = null;
            if (!n.path("questionNumber").isNull()) {
                if (!n.path("questionNumber").isIntegralNumber()
                        || !n.path("questionNumber").canConvertToInt()
                        || n.path("questionNumber").asInt() < 1) throw invalid();
                question = n.path("questionNumber").asInt();
            }
            String revision = null;
            if (!n.path("assessmentRevision").isNull()) {
                if (!n.path("assessmentRevision").isString()
                        || !positive(n.path("assessmentRevision").asString())) throw invalid();
                revision = n.path("assessmentRevision").asString();
            }
            return new ServerAuditFact(
                    uuid(n.path("eventId")),
                    n.path("type").asString(),
                    Instant.parse(n.path("occurredAt").asString()),
                    question,
                    revision);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    public byte[] canonical() {
        var value = new TreeMap<String, Object>();
        value.put("schemaVersion", 1);
        value.put("eventId", eventId.toString());
        value.put("source", "SERVER_AUTHORITY");
        value.put("type", type);
        value.put("occurredAt", occurredAt.toString());
        value.put("questionNumber", questionNumber);
        value.put("assessmentRevision", assessmentRevision);
        return JSON.writeValueAsBytes(value);
    }

    public static String context(
            UUID publication, UUID attempt, UUID actor, UUID eventId, String type, Integer questionNumber) {
        return "audit-server-v1:" + publication + ":" + attempt + ":" + actor + ":" + eventId + ":" + type + ":"
                + (questionNumber == null ? "-" : questionNumber);
    }

    public String context(UUID publication, UUID attempt, UUID actor) {
        return context(publication, attempt, actor, eventId, type, questionNumber);
    }

    public String toString() {
        return "ServerAuditFact[redacted]";
    }
}
