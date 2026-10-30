package com.quizopia.proctoring.persistence;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.configuration.MonitoringProperties;
import com.quizopia.proctoring.domain.Monitoring.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
@ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
public class JdbcMonitoringStore implements MonitoringStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final MonitoringProperties properties;
    // FB-015 fence gate: server-side, always on; an empty fence table is a no-op.
    private final QuizRevocationGate fence;

    public JdbcMonitoringStore(JdbcTemplate db, PlatformTransactionManager manager, MonitoringProperties properties) {
        this.db = db;
        this.properties = properties;
        this.fence = new QuizRevocationGate(db);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(status -> {
                db.execute("SET LOCAL lock_timeout='1000ms'");
                db.execute("SET LOCAL statement_timeout='2000ms'");
                db.execute(
                        "SELECT pg_advisory_xact_lock(18015) WHERE to_regclass('public.purge_inventory') IS NOT NULL");
                return work.get();
            });
        } catch (MonitoringFailure f) {
            throw f;
        } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
            throw new MonitoringFailure(409, "EVENT_CONFLICT");
        } catch (RuntimeException unavailable) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private Session session(ResultSet r, int n) throws SQLException {
        var ended = r.getTimestamp("ended_at");
        return new Session(
                r.getObject("id", UUID.class),
                r.getObject("attempt_id", UUID.class),
                r.getObject("publication_id", UUID.class),
                r.getObject("classroom_id", UUID.class),
                r.getObject("student_id", UUID.class),
                r.getTimestamp("deadline_at").toInstant(),
                r.getString("status"),
                r.getTimestamp("created_at").toInstant(),
                ended == null ? null : ended.toInstant());
    }

    @Override
    public Session find(UUID id) {
        try {
            var rows = db.query("SELECT * FROM monitoring_sessions WHERE id=?", this::session, id);
            if (rows.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            fence.requireSessionOpen(id);
            return rows.getFirst();
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private Session lock(UUID id) {
        var rows = db.query("SELECT * FROM monitoring_sessions WHERE id=? FOR UPDATE", this::session, id);
        if (rows.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        fence.requireSessionOpen(id);
        return rows.getFirst();
    }

    private void authority(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, Session s, Instant time) {
        if (!actor.id().equals(a.actorId())
                || !actor.roles().contains(a.viewerRole())
                || !(a.viewerRole().equals("STUDENT") || a.viewerRole().equals("TEACHER"))
                || actor.expiresAt() == null
                || !actor.expiresAt().isAfter(time)
                || a.checkedAt() == null
                || a.checkedAt().isBefore(time.minusSeconds(5))
                || a.checkedAt().isAfter(time.plusSeconds(1))) throw MonitoringFailure.unavailable();
        if (!a.attemptId().equals(s.attemptId())
                || !a.publicationId().equals(s.publicationId())
                || !a.classroomId().equals(s.classroomId())
                || !a.studentId().equals(s.studentId())
                || !a.deadlineAt().equals(s.deadlineAt())) throw MonitoringFailure.unavailable();
        if (a.viewerRole().equals("STUDENT") && !s.studentId().equals(actor.id()))
            throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
    }

    @Override
    public Session create(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a) {
        return transaction(() -> {
            // FB-015: deny new monitoring sessions for a revoked quiz before any write.
            fence.requireQuizOpen(a.quizId());
            Instant time = now();
            UUID id = UUID.randomUUID();
            var proposed = new Session(
                    id,
                    a.attemptId(),
                    a.publicationId(),
                    a.classroomId(),
                    a.studentId(),
                    a.deadlineAt(),
                    "ACTIVE",
                    time,
                    null);
            authority(actor, a, proposed, time);
            if (!a.viewerRole().equals("STUDENT")
                    || !a.active()
                    || !a.deadlineAt().isAfter(time)) throw new MonitoringFailure(409, "SESSION_ENDED");
            // A null quiz means a pre-FB015 authority snapshot (or a pre-V4 schema); the
            // legacy INSERT keeps rolling upgrades byte-identical with no quiz binding.
            int inserted = a.quizId() == null
                    ? db.update(
                            "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at) VALUES (?,?,?,?,?,?,'ACTIVE',?) ON CONFLICT(attempt_id) DO NOTHING",
                            id,
                            a.attemptId(),
                            a.publicationId(),
                            a.classroomId(),
                            a.studentId(),
                            Timestamp.from(a.deadlineAt()),
                            Timestamp.from(time))
                    : db.update(
                            "INSERT INTO monitoring_sessions(id,attempt_id,publication_id,classroom_id,student_id,deadline_at,status,created_at,quiz_id) VALUES (?,?,?,?,?,?,'ACTIVE',?,?) ON CONFLICT(attempt_id) DO NOTHING",
                            id,
                            a.attemptId(),
                            a.publicationId(),
                            a.classroomId(),
                            a.studentId(),
                            Timestamp.from(a.deadlineAt()),
                            Timestamp.from(time),
                            a.quizId());
            var session = db.query(
                            "SELECT * FROM monitoring_sessions WHERE attempt_id=? FOR UPDATE",
                            this::session,
                            a.attemptId())
                    .getFirst();
            Instant lockedAt = now();
            authority(actor, a, session, lockedAt);
            // Recheck inside the row-locked transaction, exactly like every other writer.
            fence.requireSessionOpen(session.id());
            if (!a.active() || !session.deadlineAt().isAfter(lockedAt))
                throw new MonitoringFailure(409, "SESSION_ENDED");
            if (inserted == 1) serverEvent(session, EventType.SESSION_STARTED, time, actor.id());
            return session;
        });
    }

    private Session end(Session session, Instant time, UUID actor) {
        if (session.status().equals("ENDED")) return session;
        db.update(
                "UPDATE monitoring_sessions SET status='ENDED',ended_at=? WHERE id=?",
                Timestamp.from(time),
                session.id());
        serverEvent(session, EventType.SESSION_ENDED, time, actor);
        return find(session.id());
    }

    private void serverEvent(Session session, EventType type, Instant time, UUID actor) {
        var event = new Event(UUID.randomUUID(), null, 0L, type, null, null, 1);
        insert(session, event, time, "SERVER_AUTHORITY", actor);
    }

    @Override
    public Session reconcile(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID id, boolean audit) {
        return transaction(() -> {
            var session = lock(id);
            var time = now();
            authority(actor, a, session, time);
            if (!a.active() || !session.deadlineAt().isAfter(time)) session = end(session, time, actor.id());
            if (audit) audit(actor, a, id, "SESSION_READ", time);
            return session;
        });
    }

    @Override
    public Acknowledgment append(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID id, Event event) {
        event.validate();
        return transaction(() -> {
            var session = lock(id);
            var time = now();
            authority(actor, a, session, time);
            if (!a.viewerRole().equals("STUDENT")) throw new MonitoringFailure(403, "ACCESS_DENIED");
            var previous = db.query(
                    "SELECT * FROM monitoring_evidence WHERE session_id=? AND (id=? OR (stream_id=? AND client_sequence=?))",
                    this::evidence,
                    id,
                    event.eventId(),
                    event.streamId(),
                    event.sequence());
            if (!previous.isEmpty()) {
                var value = previous.getFirst();
                if (previous.size() != 1
                        || !value.integritySha256()
                                .equals(hash(id, event, value.receivedAt(), "CLIENT_OBSERVATION", actor.id())))
                    throw new MonitoringFailure(409, "EVENT_CONFLICT");
                return new Acknowledgment(value.id(), value.ordinal(), value.receivedAt());
            }
            if (!a.active()
                    || !session.status().equals("ACTIVE")
                    || !session.deadlineAt().isAfter(time)) throw new MonitoringFailure(409, "SESSION_ENDED");
            return insert(session, event, time, "CLIENT_OBSERVATION", actor.id());
        });
    }

    private Instant expires(Instant time) {
        return properties.getRetentionDays() == null ? null : time.plus(properties.getRetentionDays(), ChronoUnit.DAYS);
    }

    private String policy() {
        return properties.getRetentionDays() == null ? null : properties.getRetentionPolicyReference();
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value.truncatedTo(ChronoUnit.MICROS));
    }

    private Acknowledgment insert(Session session, Event event, Instant time, String source, UUID actor) {
        return db.queryForObject(
                """
            INSERT INTO monitoring_evidence(id,session_id,actor_id,stream_id,client_sequence,event_type,observed_at,question_number,received_at,source,integrity_sha256,expires_at,retention_policy_reference)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id,ordinal,received_at
            """,
                (r, n) -> new Acknowledgment(
                        r.getObject("id", UUID.class),
                        r.getLong("ordinal"),
                        r.getTimestamp("received_at").toInstant()),
                event.eventId(),
                session.id(),
                actor,
                event.streamId(),
                event.type().serverOnly() ? null : event.sequence(),
                event.type().name(),
                timestamp(event.observedAt()),
                event.questionNumber(),
                timestamp(time),
                source,
                hash(session.id(), event, time, source, actor),
                timestamp(expires(time)),
                policy());
    }

    private Evidence evidence(ResultSet r, int n) throws SQLException {
        var observed = r.getTimestamp("observed_at");
        var sequence = (Long) r.getObject("client_sequence");
        return new Evidence(
                r.getLong("ordinal"),
                r.getObject("id", UUID.class),
                r.getObject("stream_id", UUID.class),
                sequence,
                EventType.valueOf(r.getString("event_type")),
                observed == null ? null : observed.toInstant(),
                (Integer) r.getObject("question_number"),
                r.getTimestamp("received_at").toInstant(),
                r.getString("source"),
                r.getString("integrity_sha256"),
                r.getInt("schema_version"),
                r.getObject("actor_id", UUID.class),
                r.getString("assessment_revision"));
    }

    public static String hash(UUID session, Event e, Instant time, String source, UUID actor) {
        return hash(session, e, time, source, actor, null);
    }

    public static String hash(
            UUID session, Event e, Instant time, String source, UUID actor, String assessmentRevision) {
        String canonical = session + "|" + e.eventId() + "|" + e.streamId() + "|"
                + (e.type().serverOnly() ? null : e.sequence()) + "|" + e.type() + "|"
                + (e.observedAt() == null ? null : e.observedAt().truncatedTo(ChronoUnit.MICROS)) + "|"
                + e.questionNumber() + "|" + time.truncatedTo(ChronoUnit.MICROS) + "|" + source + "|"
                + e.schemaVersion() + "|" + actor;
        if (assessmentRevision != null) canonical += "|" + assessmentRevision;
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override
    public Page timeline(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID id, long after, int limit) {
        if (after < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException();
        return transaction(() -> {
            var session = lock(id);
            var time = now();
            authority(actor, a, session, time);
            if (!a.viewerRole().equals("TEACHER")) throw new MonitoringFailure(403, "ACCESS_DENIED");
            var all = db.query(
                    "SELECT * FROM monitoring_evidence WHERE session_id=? AND ordinal>? ORDER BY ordinal LIMIT ?",
                    this::evidence,
                    id,
                    after,
                    limit + 1);
            var items = List.copyOf(all.subList(0, Math.min(limit, all.size())));
            for (var e : items) {
                var input = new Event(
                        e.id(),
                        e.streamId(),
                        e.sequence() == null ? 0L : e.sequence(),
                        e.type(),
                        e.observedAt(),
                        e.questionNumber(),
                        e.schemaVersion());
                if (!e.integritySha256()
                        .equals(hash(id, input, e.receivedAt(), e.source(), e.actorId(), e.assessmentRevision())))
                    throw new MonitoringFailure(503, "EVIDENCE_INTEGRITY_UNAVAILABLE");
            }
            audit(actor, a, id, "TIMELINE_READ", time);
            return new Page(items, all.size() > limit ? items.getLast().ordinal() : null);
        });
    }

    private void audit(
            MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID id, String action, Instant time) {
        db.update(
                "INSERT INTO monitoring_access_audit(session_id,actor_id,viewer_role,action,accessed_at,expires_at,retention_policy_reference) VALUES (?,?,?,?,?,?,?)",
                id,
                actor.id(),
                a.viewerRole(),
                action,
                timestamp(time),
                timestamp(expires(time)),
                policy());
    }
}
