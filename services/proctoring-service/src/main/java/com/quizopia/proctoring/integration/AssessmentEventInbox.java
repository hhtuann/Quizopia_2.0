package com.quizopia.proctoring.integration;

import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.*;
import com.quizopia.proctoring.persistence.JdbcMonitoringStore;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable inbox before broker ACK; missing sessions wait without creating or resurrecting one. */
public final class AssessmentEventInbox {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MonitoringProperties properties;

    public AssessmentEventInbox(JdbcTemplate db, PlatformTransactionManager manager, MonitoringProperties properties) {
        this.db = db;
        this.properties = properties;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(status -> {
                db.execute("SET LOCAL lock_timeout='1000ms'");
                db.execute("SET LOCAL statement_timeout='2000ms'");
                return work.get();
            });
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException();
        }
    }

    private void reject(String hash, String reason) {
        db.update(
                "INSERT INTO assessment_monitoring_rejections(payload_sha256,reason) VALUES (?,?) ON CONFLICT(payload_sha256) DO UPDATE SET occurrences=assessment_monitoring_rejections.occurrences+1",
                hash,
                reason);
    }

    public void reject(byte[] bytes) {
        transaction(() -> {
            reject(digest(bytes), "INVALID_EVENT");
            return null;
        });
    }

    public void accept(AssessmentAnswerEvent event) {
        event.validate();
        byte[] payload = event.bytes();
        String hash = digest(payload);
        transaction(() -> {
            db.update(
                    "INSERT INTO assessment_monitoring_inbox(event_id,attempt_id,payload,payload_sha256) VALUES (?,?,?::jsonb,?) ON CONFLICT(event_id) DO NOTHING",
                    event.eventId(),
                    event.attemptId(),
                    new String(payload, StandardCharsets.UTF_8),
                    hash);
            String existing = db.queryForObject(
                    "SELECT payload_sha256 FROM assessment_monitoring_inbox WHERE event_id=?",
                    String.class,
                    event.eventId());
            if (!hash.equals(existing)) reject(hash, "EVENT_CONFLICT");
            return null;
        });
    }

    public int drain() {
        int total = 0;
        for (int i = 0; i < 10; i++) {
            boolean applied = transaction(this::next);
            if (!applied) break;
            total++;
        }
        return total;
    }

    private boolean next() {
        var rows = db.query(
                "SELECT i.event_id,i.payload::text FROM assessment_monitoring_inbox i JOIN monitoring_sessions s ON s.attempt_id=i.attempt_id WHERE i.state='PENDING' ORDER BY i.last_attempt_at NULLS FIRST,i.received_at,i.event_id LIMIT 1 FOR UPDATE OF i SKIP LOCKED",
                (r, n) -> Map.entry(r.getObject(1, UUID.class), r.getString(2)));
        if (rows.isEmpty()) return false;
        var row = rows.getFirst();
        var event = AssessmentAnswerEvent.decode(row.getValue().getBytes(StandardCharsets.UTF_8));
        var sessions = db.query(
                "SELECT id,publication_id,classroom_id,student_id,deadline_at FROM monitoring_sessions WHERE attempt_id=? FOR UPDATE",
                (r, n) -> new Binding(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class),
                        r.getTimestamp(5).toInstant()),
                event.attemptId());
        if (sessions.isEmpty()) return false;
        var session = sessions.getFirst();
        if (!session.publication().equals(event.publicationId())
                || !session.classroom().equals(event.classroomId())
                || !session.student().equals(event.studentId())
                || !session.deadline().equals(event.deadlineAt())) {
            reject(digest(event.bytes()), "BINDING_CONFLICT");
            complete(event.eventId(), "QUARANTINED");
            return true;
        }
        var time = db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
        var input = new Event(
                event.eventId(), null, 0L, EventType.ANSWER_PERSISTED, event.occurredAt(), event.questionNumber(), 1);
        var previous = db.query(
                "SELECT id,received_at,integrity_sha256 FROM monitoring_evidence WHERE id=? OR (session_id=? AND question_number=? AND assessment_revision=?)",
                (r, n) -> new Previous(
                        r.getObject(1, UUID.class), r.getTimestamp(2).toInstant(), r.getString(3)),
                event.eventId(),
                session.id(),
                event.questionNumber(),
                Long.parseLong(event.revision()));
        if (!previous.isEmpty()) {
            var old = previous.getFirst();
            if (previous.size() != 1
                    || !old.id().equals(event.eventId())
                    || !old.hash()
                            .equals(JdbcMonitoringStore.hash(
                                    session.id(),
                                    input,
                                    old.time(),
                                    "ASSESSMENT_AUTHORITY",
                                    event.studentId(),
                                    event.revision()))) {
                reject(digest(event.bytes()), "EVENT_CONFLICT");
                complete(event.eventId(), "QUARANTINED");
                return true;
            }
            complete(event.eventId(), "APPLIED");
            return true;
        }
        String integrity = JdbcMonitoringStore.hash(
                session.id(), input, time, "ASSESSMENT_AUTHORITY", event.studentId(), event.revision());
        Instant expiry = properties.getRetentionDays() == null
                ? null
                : time.plus(Duration.ofDays(properties.getRetentionDays()));
        String policy = expiry == null ? null : properties.getRetentionPolicyReference();
        db.update(
                "INSERT INTO monitoring_evidence(id,session_id,actor_id,event_type,observed_at,question_number,received_at,source,integrity_sha256,expires_at,retention_policy_reference,assessment_revision) VALUES (?,?,?,'ANSWER_PERSISTED',?,?,?,'ASSESSMENT_AUTHORITY',?,?,?,?)",
                event.eventId(),
                session.id(),
                event.studentId(),
                Timestamp.from(event.occurredAt()),
                event.questionNumber(),
                Timestamp.from(time),
                integrity,
                expiry == null ? null : Timestamp.from(expiry),
                policy,
                Long.parseLong(event.revision()));
        complete(event.eventId(), "APPLIED");
        return true;
    }

    private record Previous(UUID id, Instant time, String hash) {}

    private record Binding(UUID id, UUID publication, UUID classroom, UUID student, Instant deadline) {}

    private void complete(UUID event, String state) {
        db.update(
                "UPDATE assessment_monitoring_inbox SET state=?,processed_at=clock_timestamp(),last_attempt_at=clock_timestamp() WHERE event_id=?",
                state,
                event);
    }
}
