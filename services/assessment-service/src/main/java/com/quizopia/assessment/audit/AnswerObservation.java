package com.quizopia.assessment.audit;

import com.quizopia.assessment.application.PublicationFailure;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict client-only envelope; actor, snapshot, source, revision and keys are never accepted. */
public record AnswerObservation(
        UUID eventId,
        UUID streamId,
        long sequence,
        String type,
        int questionNumber,
        Instant observedAt,
        List<String> fromOptionLabels,
        List<String> toOptionLabels) {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private static final Set<String> TYPES =
            Set.of("QUESTION_VIEWED", "QUESTION_LEFT", "ANSWER_SELECTED", "ANSWER_CHANGED", "ANSWER_CLEARED");

    public AnswerObservation {
        if (eventId == null || streamId == null || sequence < 0 || questionNumber < 1 || !TYPES.contains(type))
            throw invalid();
        fromOptionLabels = fromOptionLabels == null ? null : List.copyOf(fromOptionLabels);
        toOptionLabels = toOptionLabels == null ? null : List.copyOf(toOptionLabels);
        if (observedAt != null) observedAt = observedAt.truncatedTo(ChronoUnit.MICROS);
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

    private static List<String> labels(JsonNode value) {
        if (!value.isArray() || value.size() > 4) throw invalid();
        var result = new ArrayList<String>();
        for (var label : value) {
            if (!label.isString() || !Set.of("A", "B", "C", "D").contains(label.asString())) throw invalid();
            result.add(label.asString());
        }
        if (new HashSet<>(result).size() != result.size()
                || !result.equals(result.stream().sorted().toList())) throw invalid();
        return result;
    }

    public static AnswerObservation parse(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > 4096) throw invalid();
        try {
            var n = JSON.readTree(bytes);
            var required = Set.of("schemaVersion", "eventId", "streamId", "sequence", "type", "questionNumber");
            if (!n.isObject()
                    || !n.propertyNames().containsAll(required)
                    || !Set.of(
                                    "schemaVersion",
                                    "eventId",
                                    "streamId",
                                    "sequence",
                                    "type",
                                    "questionNumber",
                                    "observedAt",
                                    "transition")
                            .containsAll(n.propertyNames())
                    || !n.path("schemaVersion").isIntegralNumber()
                    || !n.path("schemaVersion").canConvertToInt()
                    || n.path("schemaVersion").asInt() != 1
                    || !n.path("sequence").isIntegralNumber()
                    || !n.path("sequence").canConvertToLong()
                    || !n.path("questionNumber").isIntegralNumber()
                    || !n.path("questionNumber").canConvertToInt()
                    || !n.path("type").isString()) throw invalid();
            Instant observed = null;
            if (n.has("observedAt") && !n.path("observedAt").isNull()) {
                if (!n.path("observedAt").isString()) throw invalid();
                observed = Instant.parse(n.path("observedAt").asString());
            }
            List<String> from = null, to = null;
            if (n.has("transition")) {
                var t = n.path("transition");
                if (!t.isObject() || !t.propertyNames().equals(Set.of("fromOptionLabels", "toOptionLabels")))
                    throw invalid();
                from = labels(t.path("fromOptionLabels"));
                to = labels(t.path("toOptionLabels"));
            }
            return new AnswerObservation(
                    uuid(n.path("eventId")),
                    uuid(n.path("streamId")),
                    n.path("sequence").asLong(),
                    n.path("type").asString(),
                    n.path("questionNumber").asInt(),
                    observed,
                    from,
                    to);
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    public void validate(JsonNode question) {
        boolean answer = type.startsWith("ANSWER_");
        String kind = question.path("type").asString();
        boolean supported = kind.equals("SINGLE_CHOICE") || kind.equals("MULTIPLE_CHOICE");
        if (!answer) {
            if (fromOptionLabels != null || toOptionLabels != null) throw invalid();
            return;
        }
        if (kind.equals("NUMERIC_FILL")) throw invalid();
        if (!supported) {
            if (!kind.equals("TRUE_FALSE_MATRIX") || fromOptionLabels != null || toOptionLabels != null)
                throw invalid();
            return;
        }
        if (fromOptionLabels == null
                || toOptionLabels == null
                || kind.equals("SINGLE_CHOICE") && (fromOptionLabels.size() > 1 || toOptionLabels.size() > 1))
            throw invalid();
        var known = new HashSet<String>();
        question.path("options").forEach(o -> known.add(o.path("label").asString()));
        if (!known.containsAll(fromOptionLabels)
                || !known.containsAll(toOptionLabels)
                || fromOptionLabels.equals(toOptionLabels)) throw invalid();
        if (type.equals("ANSWER_SELECTED") && (!fromOptionLabels.isEmpty() || toOptionLabels.isEmpty())
                || type.equals("ANSWER_CHANGED") && (fromOptionLabels.isEmpty() || toOptionLabels.isEmpty())
                || type.equals("ANSWER_CLEARED") && (fromOptionLabels.isEmpty() || !toOptionLabels.isEmpty()))
            throw invalid();
    }

    public byte[] canonical() {
        var value = new TreeMap<String, Object>();
        value.put("schemaVersion", 1);
        value.put("eventId", eventId.toString());
        value.put("streamId", streamId.toString());
        value.put("sequence", Long.toString(sequence));
        value.put("type", type);
        value.put("questionNumber", questionNumber);
        value.put("observedAt", observedAt == null ? null : observedAt.toString());
        if (fromOptionLabels != null)
            value.put(
                    "transition",
                    new TreeMap<>(Map.of("fromOptionLabels", fromOptionLabels, "toOptionLabels", toOptionLabels)));
        return JSON.writeValueAsBytes(value);
    }

    public String context(UUID publication, UUID attempt, UUID actor) {
        return "audit-v1:" + publication + ":" + attempt + ":" + actor + ":" + eventId + ":" + streamId + ":" + sequence
                + ":" + type + ":" + questionNumber;
    }

    public String toString() {
        return "AnswerObservation[redacted]";
    }
}
