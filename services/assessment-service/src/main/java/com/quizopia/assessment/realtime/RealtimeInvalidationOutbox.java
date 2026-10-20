package com.quizopia.assessment.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Independent W4 dispatch state; identity columns are written only by the capture triggers. */
public interface RealtimeInvalidationOutbox {
    record Event(
            UUID id,
            UUID owner,
            String eventType,
            String resourceKind,
            UUID resourceId,
            Instant occurredAt,
            long deliveryAttempts) {
        @Override
        public String toString() {
            return "RealtimeInvalidationEvent[redacted]";
        }
    }

    record Pending(long count, double oldestPendingSeconds) {}

    List<Event> claim(UUID owner, int limit);

    void published(Event event);

    void retry(Event event, int backoffSeconds);

    void quarantined(Event event);

    Pending pending();
}
