package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.quizopia.proctoring.integration.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;

class AssessmentAnswerEventTest {
    static AssessmentAnswerEvent event() {
        var start = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        return new AssessmentAnswerEvent(
                UUID.randomUUID(),
                AssessmentAnswerEvent.TYPE,
                1,
                start.plusSeconds(30),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                start,
                start.plusSeconds(300),
                1,
                "9223372036854775807");
    }

    @Test
    void exactMetadataRoundTripsWithMaximumLongRevisionAndRedactedDiagnostics() {
        var event = event();
        assertEquals(event, AssessmentAnswerEvent.decode(event.bytes()));
        String body = new String(event.bytes(), StandardCharsets.UTF_8);
        assertFalse(body.contains("answerValue"));
        assertFalse(event.toString().contains(event.studentId().toString()));
        assertTrue(body.contains("\"revision\":\"9223372036854775807\""));
    }

    @Test
    void rejectsUnknownDuplicateMissingCoercedAndNoncanonicalFields() {
        var event = event();
        String json = new String(event.bytes(), StandardCharsets.UTF_8);
        for (String invalid : List.of(
                json.replace("}", ",\"answer\":\"never-copy-answer-content\"}"),
                json.replace("}", ",\"questionNumber\":2}"),
                json + "{}",
                json.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                json.replace("\"eventVersion\":1", "\"eventVersion\":\"1\""),
                json.replace("\"questionNumber\":1", "\"questionNumber\":1.5"),
                json.replace("\"questionNumber\":1", "\"questionNumber\":null"),
                json.replace("\"revision\":\"9223372036854775807\"", "\"revision\":9223372036854775807"),
                json.replace("9223372036854775807", "9223372036854775808"),
                json.replace("9223372036854775807", "01"),
                json.replace(
                        event.eventId().toString(), event.eventId().toString().toUpperCase(Locale.ROOT)),
                json.replace("\"eventType\":\"ASSESSMENT_ANSWER_PERSISTED\",", ""))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AssessmentAnswerEvent.decode(invalid.getBytes(StandardCharsets.UTF_8)),
                    invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> AssessmentAnswerEvent.decode(new byte[4097]));
        assertThrows(IllegalArgumentException.class, () -> AssessmentAnswerEvent.decode(null));
    }

    @Test
    void committedNormalizationAfterDeadlineIsHistoricalFactWithoutTimeExtension() {
        var old = event();
        var event = new AssessmentAnswerEvent(
                old.eventId(),
                old.eventType(),
                1,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                old.attemptId(),
                old.publicationId(),
                old.classroomId(),
                old.studentId(),
                Instant.now().minusSeconds(600).truncatedTo(ChronoUnit.MICROS),
                Instant.now().minusSeconds(300).truncatedTo(ChronoUnit.MICROS),
                1,
                "1");
        assertEquals(event, AssessmentAnswerEvent.decode(event.bytes()));
        var future = new AssessmentAnswerEvent(
                old.eventId(),
                old.eventType(),
                1,
                Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MICROS),
                old.attemptId(),
                old.publicationId(),
                old.classroomId(),
                old.studentId(),
                old.attemptStartedAt(),
                old.deadlineAt(),
                1,
                "1");
        assertThrows(IllegalArgumentException.class, () -> AssessmentAnswerEvent.decode(future.bytes()));
    }

    @Test
    void configurationDefaultsOffRejectsProductionAndUntrustedPlaintextTopology() {
        var properties = new AssessmentEventsProperties();
        assertFalse(properties.isEnabled());
        assertTrue(properties.isTls());
        properties.validate(true);
        properties.setEnabled(true);
        properties.setHost("broker.internal");
        properties.setVirtualHost("isolated-test");
        properties.setUsername("read-only-consumer");
        properties.setPassword("synthetic-secret");
        properties.setPublisherIdentity("assessment-writer");
        assertDoesNotThrow(() -> properties.validate(false));
        assertThrows(IllegalStateException.class, () -> properties.validate(true));
        properties.setTls(false);
        properties.setAllowInsecureLoopback(true);
        assertThrows(IllegalStateException.class, () -> properties.validate(false));
        properties.setHost("127.0.0.1");
        assertDoesNotThrow(() -> properties.validate(false));
        properties.setAllowInsecureLoopback(false);
        assertThrows(IllegalStateException.class, () -> properties.validate(false));
        properties.setTls(true);
        properties.setVirtualHost("/");
        assertThrows(IllegalStateException.class, () -> properties.validate(false));
    }
}
