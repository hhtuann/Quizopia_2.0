package com.quizopia.proctoring.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies durable quiz-ID revocation fences (ADR-018 Phase A step 4) and owns the ADR-009
 * command inbox. Installation serializes against every in-flight Proctoring writer: all
 * evidence/audit/media mutations take the owning monitoring_sessions row lock first, so
 * this transaction takes the same locks (ordered by id) before inserting fence rows and
 * sampling revoked_at. A writer that committed before the fence keeps its rows for later
 * purge; every writer afterwards is denied by {@link QuizRevocationGate} inside its own
 * transaction. Live media grants for the quiz are immediately fenced REVOKE_PENDING so
 * the existing recovery worker tears provider rooms down. No media payloads, evidence
 * content or PII are stored or returned.
 */
@Service
public class QuizRevocationService {
    /** One batch is bounded by the private endpoint; inserts are idempotent. */
    public record Operation(UUID operationId, UUID quizId) {
        public Operation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(quizId, "quizId");
        }
    }

    public record Revoked(UUID quizId, UUID operationId, Instant revokedAt) {
        public String toString() {
            return "Revoked[redacted]";
        }
    }

    public record Batch(List<Revoked> accepted, List<Revoked> alreadyRevoked) {
        public Batch {
            accepted = List.copyOf(accepted);
            alreadyRevoked = List.copyOf(alreadyRevoked);
        }

        public String toString() {
            return "Batch[redacted]";
        }
    }

    public record Status(
            UUID quizId,
            Instant revokedAt,
            Instant acknowledgedAt,
            long sessionCount,
            long evidenceCount,
            long auditCount,
            long liveGrantCount) {
        public String toString() {
            return "Status[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    // Lazy JDBC dependencies keep DataSource-less smoke profiles loadable; the fence
    // surface stays compiled and wired in every real runtime profile.
    public QuizRevocationService(@Lazy JdbcTemplate db, @Lazy PlatformTransactionManager manager) {
        this.db = db;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }

    private <T> T tx(Supplier<T> work) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
            return transaction.execute(status -> {
                db.execute("SET LOCAL lock_timeout='1000ms'");
                db.execute("SET LOCAL statement_timeout='2000ms'");
                db.execute(
                        "SELECT pg_advisory_xact_lock(18015) WHERE to_regclass('public.purge_inventory') IS NOT NULL");
                return work.get();
            });
        } catch (MonitoringFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /** Idempotent and redelivery safe: ON CONFLICT does nothing for both durable records. */
    public Batch applyBatch(List<Operation> operations) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new MonitoringFailure(400, "INVALID_REQUEST");
        return tx(() -> {
            // Serialize against every in-flight writer on affected sessions before fencing:
            // they hold/request this exact lock first, and recheck the fence after acquiring it.
            String placeholders = String.join(",", Collections.nCopies(canonical.size(), "?"));
            db.query(
                    "SELECT id FROM monitoring_sessions WHERE quiz_id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    canonical.stream().map(Operation::quizId).toArray());
            var accepted = new ArrayList<Revoked>(canonical.size());
            var already = new ArrayList<Revoked>(canonical.size());
            Instant received = db.queryForObject(
                    "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
            for (Operation operation : canonical) {
                db.update(
                        "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts,last_error)"
                                + " VALUES(?,?,?,?,?,1,NULL) ON CONFLICT (operation_id) DO NOTHING",
                        UUID.randomUUID(),
                        operation.operationId(),
                        operation.quizId(),
                        Timestamp.from(received),
                        Timestamp.from(received));
                int inserted = db.update(
                        "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,ack_at)"
                                + " VALUES(?,clock_timestamp(),?,NULL) ON CONFLICT (quiz_id) DO NOTHING",
                        operation.quizId(),
                        operation.operationId());
                Instant revokedAt = db.queryForObject(
                        "SELECT revoked_at FROM quiz_revocation_fences WHERE quiz_id=?",
                        (r, n) -> r.getTimestamp(1).toInstant(),
                        operation.quizId());
                // Capture/media cessation: fence every still-issued grant; the existing
                // recovery worker revokes identities and deletes provider rooms.
                db.update(
                        "UPDATE monitoring_media_grants g SET state='REVOKE_PENDING',last_recovery_at=clock_timestamp()"
                                + " WHERE g.state IN ('PREPARED','ACTIVE') AND EXISTS(SELECT 1 FROM monitoring_sessions s"
                                + " WHERE s.id=g.session_id AND s.quiz_id=?)",
                        operation.quizId());
                (inserted == 1 ? accepted : already)
                        .add(new Revoked(operation.quizId(), operation.operationId(), revokedAt));
            }
            return new Batch(List.copyOf(accepted), List.copyOf(already));
        });
    }

    /** Truthful per-quiz acknowledgment state; counts only, never content or PII. */
    public Optional<Status> status(UUID quizId) {
        try {
            var rows = db.query(
                    """
                SELECT f.revoked_at,f.ack_at,
                  (SELECT count(*) FROM monitoring_sessions s WHERE s.quiz_id=f.quiz_id) AS sessions,
                  (SELECT count(*) FROM monitoring_evidence e JOIN monitoring_sessions s ON s.id=e.session_id
                    WHERE s.quiz_id=f.quiz_id) AS evidence,
                  (SELECT count(*) FROM monitoring_access_audit a JOIN monitoring_sessions s ON s.id=a.session_id
                    WHERE s.quiz_id=f.quiz_id) AS audits,
                  (SELECT count(*) FROM monitoring_media_grants g JOIN monitoring_sessions s ON s.id=g.session_id
                    WHERE s.quiz_id=f.quiz_id AND g.state IN ('PREPARED','ACTIVE')) AS live_grants
                FROM quiz_revocation_fences f WHERE f.quiz_id=?
                """,
                    (r, n) -> new Status(
                            quizId,
                            r.getTimestamp(1).toInstant(),
                            r.getTimestamp(2) == null ? null : r.getTimestamp(2).toInstant(),
                            r.getLong(3),
                            r.getLong(4),
                            r.getLong(5),
                            r.getLong(6)),
                    quizId);
            return rows.stream().findFirst();
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /**
     * Bounded drainer for unacknowledged fences: takes the same session row locks as
     * writers, then verifies no committed evidence/audit timestamp postdates revoked_at
     * and no still-issued media grant remains. revoked_at is sampled inside the
     * installing transaction only after those locks were held, so any pre-fence committed
     * writer necessarily carries earlier timestamps and cannot block acknowledgment.
     */
    public int acknowledgeDue(int limit) {
        if (limit < 1 || limit > 100) throw new MonitoringFailure(400, "INVALID_REQUEST");
        List<UUID> pending;
        try {
            pending = db.query(
                    "SELECT quiz_id FROM quiz_revocation_fences WHERE ack_at IS NULL ORDER BY revoked_at LIMIT ?",
                    (r, n) -> r.getObject(1, UUID.class),
                    limit);
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
        int acknowledged = 0;
        for (UUID quizId : pending) if (acknowledge(quizId)) acknowledged++;
        return acknowledged;
    }

    private boolean acknowledge(UUID quizId) {
        return Boolean.TRUE.equals(tx(() -> {
            db.query(
                    "SELECT id FROM monitoring_sessions WHERE quiz_id=? ORDER BY id FOR UPDATE",
                    (r, n) -> r.getObject(1, UUID.class),
                    quizId);
            // Another instance may have acknowledged this fence since the pending scan.
            var unacknowledged = db.query(
                    "SELECT revoked_at FROM quiz_revocation_fences WHERE quiz_id=? AND ack_at IS NULL",
                    (r, n) -> r.getTimestamp(1).toInstant(),
                    quizId);
            if (unacknowledged.isEmpty()) return false;
            Instant revokedAt = unacknowledged.getFirst();
            Timestamp cutoff = Timestamp.from(revokedAt);
            boolean mutating = Boolean.TRUE.equals(db.queryForObject(
                    """
                        SELECT EXISTS(SELECT 1 FROM monitoring_sessions s WHERE s.quiz_id=? AND (
                          EXISTS(SELECT 1 FROM monitoring_evidence e WHERE e.session_id=s.id AND e.received_at>?)
                          OR EXISTS(SELECT 1 FROM monitoring_access_audit a WHERE a.session_id=s.id AND a.accessed_at>?)
                          OR EXISTS(SELECT 1 FROM monitoring_media_grants g WHERE g.session_id=s.id
                            AND g.state IN ('PREPARED','ACTIVE'))))
                        """,
                    Boolean.class,
                    quizId,
                    cutoff,
                    cutoff));
            if (mutating) return false;
            db.update(
                    "UPDATE quiz_revocation_fences SET ack_at=clock_timestamp() WHERE quiz_id=? AND ack_at IS NULL",
                    quizId);
            return true;
        }));
    }
}
