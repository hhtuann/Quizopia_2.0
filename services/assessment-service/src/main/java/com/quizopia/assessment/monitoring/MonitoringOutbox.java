package com.quizopia.assessment.monitoring;

import java.util.List;
import java.util.UUID;

/** Assessment-owned delivery metadata only; payloads contain neither answers nor credentials. */
public interface MonitoringOutbox {
    record Event(UUID id, UUID owner, byte[] payload) {
        public Event {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public String toString() {
            return "MonitoringOutbox.Event[redacted]";
        }
    }

    List<Event> claim(UUID owner, int limit);

    void published(Event event);

    void retry(Event event);
}
