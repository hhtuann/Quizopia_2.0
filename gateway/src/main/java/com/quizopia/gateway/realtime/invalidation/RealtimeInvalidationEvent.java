package com.quizopia.gateway.realtime.invalidation;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Frozen realtime-amqp-invalidation-v1 schema; nothing beyond the six allowlisted fields is accepted. */
public final class RealtimeInvalidationEvent {
    public static final String EXCHANGE = "quizopia.realtime.invalidation.v1";
    public static final String PUBLICATION_ROUTING_KEY = "publication.invalidated.v1";
    public static final String ATTEMPT_ROUTING_KEY = "attempt.invalidated.v1";
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern OCCURRED_AT = Pattern.compile("[0-9TZ:.+-]{10,40}");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final UUID eventId;
    private final String eventType;
    private final String resourceKind;
    private final UUID resourceId;
    private final Instant occurredAt;

    private RealtimeInvalidationEvent(
            UUID eventId, String eventType, String resourceKind, UUID resourceId, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.resourceKind = resourceKind;
        this.resourceId = resourceId;
        this.occurredAt = occurredAt;
    }

    public UUID eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String resourceKind() {
        return resourceKind;
    }

    public UUID resourceId() {
        return resourceId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    /** Returns null for any violation; never trusts broker content beyond the frozen allowlist. */
    public static RealtimeInvalidationEvent parse(String body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject() || root.size() != 6) return null;
            String eventId = text(root, "eventId");
            String eventType = text(root, "eventType");
            String resourceKind = text(root, "resourceKind");
            String resourceId = text(root, "resourceId");
            String occurredAt = text(root, "occurredAt");
            JsonNode version = root.path("eventVersion");
            if (eventId == null
                    || resourceId == null
                    || occurredAt == null
                    || !CANONICAL_UUID.matcher(eventId).matches()
                    || !CANONICAL_UUID.matcher(resourceId).matches()
                    || !OCCURRED_AT.matcher(occurredAt).matches()) return null;
            if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() != 1) return null;
            boolean publication = "PUBLICATION_INVALIDATED".equals(eventType) && "PUBLICATION".equals(resourceKind);
            boolean attempt = "ATTEMPT_INVALIDATED".equals(eventType) && "ATTEMPT".equals(resourceKind);
            if (!publication && !attempt) return null;
            return new RealtimeInvalidationEvent(
                    UUID.fromString(eventId),
                    eventType,
                    resourceKind,
                    UUID.fromString(resourceId),
                    Instant.parse(occurredAt));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        return root.path(field).isString() ? root.path(field).asString() : null;
    }
}
