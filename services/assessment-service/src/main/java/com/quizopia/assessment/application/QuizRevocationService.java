package com.quizopia.assessment.application;

import com.quizopia.assessment.configuration.AssessmentTransactionProperties;
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
 * Applies durable quiz-ID revocation fences (ADR-018 Phase A step 2) and owns the ADR-009 command
 * inbox. Installation serializes against every in-flight Assessment writer: all attempt and
 * publication mutations take the owning publications row lock first, so this transaction takes the
 * same locks (ordered by id) before inserting fence rows. A writer that committed before the fence
 * keeps its rows for later purge; every writer afterwards rechecks the fence inside its own
 * transaction and is denied. No quiz content, answers or PII are stored or returned.
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
            UUID quizId, Instant revokedAt, Instant acknowledgedAt, long publicationCount, long attemptCount) {
        public String toString() {
            return "Status[redacted]";
        }
    }

    /**
     * FB-015 correction H5: assessment-owned dependent inventory (publication and attempt
     * ids across DRAFT/OPEN/CLOSED plus locator rows) for one quiz, so the coordinator can
     * resolve pre-V4 Proctoring sessions whose quiz_id is NULL without any cross-service
     * database join. Ids only - never titles, content or PII.
     */
    public record Dependents(UUID quizId, List<UUID> publicationIds, List<UUID> attemptIds) {
        public Dependents {
            Objects.requireNonNull(quizId, "quizId");
            publicationIds = List.copyOf(Objects.requireNonNull(publicationIds, "publicationIds"));
            attemptIds = List.copyOf(Objects.requireNonNull(attemptIds, "attemptIds"));
        }

        public String toString() {
            return "Dependents[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final AssessmentTransactionProperties limits;

    // Lazy JDBC dependencies keep DataSource-less smoke profiles loadable; the fence surface
    // stays compiled and wired in every real runtime profile.
    public QuizRevocationService(
            @Lazy JdbcTemplate db, @Lazy PlatformTransactionManager manager, AssessmentTransactionProperties limits) {
        this.db = db;
        this.limits = limits;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(limits.timeoutSeconds());
    }

    private <T> T tx(Supplier<T> work) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
            return transaction.execute(status -> {
                db.execute("SET LOCAL lock_timeout='" + limits.lockMillis() + "ms'");
                db.execute("SET LOCAL statement_timeout='" + limits.statementMillis() + "ms'");
                return work.get();
            });
        } catch (PublicationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /** Idempotent and redelivery safe: ON CONFLICT does nothing for both durable records. */
    public Batch applyBatch(List<Operation> operations) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new PublicationFailure(400, "INVALID_REQUEST");
        return tx(() -> {
            // Serialize against every in-flight writer on affected publications before fencing:
            // they hold/request this exact lock first, and recheck the fence after acquiring it.
            String placeholders = String.join(",", java.util.Collections.nCopies(canonical.size(), "?"));
            db.query(
                    "SELECT id FROM publications WHERE quiz_id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
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
                        "INSERT INTO quiz_revocation_fences(quiz_id,revoked_at,operation_id,source,ack_at)"
                                + " VALUES(?,clock_timestamp(),?,'COMMAND',NULL) ON CONFLICT (quiz_id) DO NOTHING",
                        operation.quizId(),
                        operation.operationId());
                Instant revokedAt = db.queryForObject(
                        "SELECT revoked_at FROM quiz_revocation_fences WHERE quiz_id=?",
                        (r, n) -> r.getTimestamp(1).toInstant(),
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
                  (SELECT count(*) FROM publications p WHERE p.quiz_id=f.quiz_id) AS publications,
                  (SELECT count(*) FROM attempts a JOIN publications p ON p.id=a.publication_id
                    WHERE p.quiz_id=f.quiz_id) AS attempts
                FROM quiz_revocation_fences f WHERE f.quiz_id=?
                """,
                    (r, n) -> new Status(
                            quizId,
                            r.getTimestamp(1).toInstant(),
                            r.getTimestamp(2) == null ? null : r.getTimestamp(2).toInstant(),
                            r.getLong(3),
                            r.getLong(4)),
                    quizId);
            return rows.stream().findFirst();
        } catch (RuntimeException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /**
     * Complete dependent id inventory for the quiz from Assessment-owned tables: every
     * publication (any state, including drafts and private-locator rows) and every
     * attempt attached to them. Read-only; never finalizes any work.
     */
    public Optional<Dependents> dependents(UUID quizId) {
        try {
            var fenced = db.query(
                    "SELECT quiz_id FROM quiz_revocation_fences WHERE quiz_id=?",
                    (r, n) -> r.getObject(1, UUID.class),
                    quizId);
            if (fenced.isEmpty()) return Optional.empty();
            var publications = db.query(
                    "SELECT id FROM publications WHERE quiz_id=? ORDER BY id",
                    (r, n) -> r.getObject(1, UUID.class),
                    quizId);
            var attempts = db.query(
                    """
                SELECT a.id FROM attempts a JOIN publications p ON p.id=a.publication_id
                 WHERE p.quiz_id=? ORDER BY a.id
                """,
                    (r, n) -> r.getObject(1, UUID.class),
                    quizId);
            return Optional.of(new Dependents(quizId, publications, attempts));
        } catch (RuntimeException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /**
     * Bounded drainer for unacknowledged fences: takes the same publication row locks as writers,
     * then verifies no committed mutation timestamp postdates revoked_at. revoked_at is sampled
     * inside the installing transaction only after those locks were held, so any pre-fence
     * committed writer necessarily carries earlier timestamps and cannot block acknowledgment.
     */
    public int acknowledgeDue(int limit) {
        if (limit < 1 || limit > 100) throw new PublicationFailure(400, "INVALID_REQUEST");
        List<UUID> pending;
        try {
            pending = db.query(
                    "SELECT quiz_id FROM quiz_revocation_fences WHERE ack_at IS NULL ORDER BY revoked_at LIMIT ?",
                    (r, n) -> r.getObject(1, UUID.class),
                    limit);
        } catch (RuntimeException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
        int acknowledged = 0;
        for (UUID quizId : pending) if (acknowledge(quizId)) acknowledged++;
        return acknowledged;
    }

    private boolean acknowledge(UUID quizId) {
        return Boolean.TRUE.equals(tx(() -> {
            db.query(
                    "SELECT id FROM publications WHERE quiz_id=? ORDER BY id FOR UPDATE",
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
                        SELECT EXISTS(SELECT 1 FROM attempts a JOIN publications p ON p.id=a.publication_id
                         WHERE p.quiz_id=? AND (
                          EXISTS(SELECT 1 FROM attempt_answers x WHERE x.attempt_id=a.id AND x.saved_at>?)
                          OR EXISTS(SELECT 1 FROM grading_results g WHERE g.attempt_id=a.id AND g.graded_at>?)
                          OR EXISTS(SELECT 1 FROM attempt_operations o WHERE o.attempt_id=a.id AND o.acknowledged_at>?)
                          OR EXISTS(SELECT 1 FROM attempt_finalization_audit f WHERE f.attempt_id=a.id AND f.finalized_at>?)
                          OR EXISTS(SELECT 1 FROM attempt_creation_operations c WHERE c.attempt_id=a.id AND c.created_at>?)))
                        """,
                    Boolean.class,
                    quizId,
                    cutoff,
                    cutoff,
                    cutoff,
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
