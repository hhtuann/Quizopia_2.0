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
 * FB-015 PR C FK-safe quiz purge (ADR-018 Phase B steps 6-7). Runs only after the owning
 * revocation fence is acknowledged and no active retention hold remains; delegates all row
 * removal to the narrowly-scoped privileged assessment_purge_quiz procedure (FK-ordered,
 * bounded batches, idempotent, triggers stay enabled) and never trusts an in-memory
 * assumption: PURGED is reported only from the procedure's zero-remaining verification plus
 * an independent SQL COUNT re-check. Receipts carry counts only - no answers, no PII.
 */
@Service
public class QuizPurgeService {
    public record Operation(UUID operationId, UUID quizId) {
        public Operation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(quizId, "quizId");
        }
    }

    public enum Outcome {
        PURGED,
        BLOCKED_BY_RETENTION,
        WAITING_REVOCATION_ACK
    }

    public record Result(UUID operationId, UUID quizId, Outcome status, Map<String, Long> steps) {
        public String toString() {
            return "Result[redacted]";
        }
    }

    public record PurgeStatus(
            UUID quizId,
            Instant revokedAt,
            Instant acknowledgedAt,
            boolean purged,
            boolean retentionHoldActive,
            Map<String, Long> steps) {
        public String toString() {
            return "PurgeStatus[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final AssessmentTransactionProperties limits;

    // Lazy JDBC dependencies keep DataSource-less smoke profiles loadable; the purge surface
    // stays compiled and wired in every real runtime profile.
    public QuizPurgeService(
            @Lazy JdbcTemplate db, @Lazy PlatformTransactionManager manager, AssessmentTransactionProperties limits) {
        this.db = db;
        this.named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(db);
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

    /** Idempotent: an already-purged quiz reports PURGED with unchanged cumulative receipts. */
    public List<Result> purgeBatch(List<Operation> operations, int batchLimit) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new PublicationFailure(400, "INVALID_REQUEST");
        if (batchLimit < 1 || batchLimit > 1000) throw new PublicationFailure(400, "INVALID_REQUEST");
        var results = new ArrayList<Result>(canonical.size());
        for (Operation operation : canonical) results.add(purge(operation, batchLimit));
        return List.copyOf(results);
    }

    private Result purge(Operation operation, int batchLimit) {
        return tx(() -> {
            Instant received = db.queryForObject(
                    "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
            db.update(
                    "INSERT INTO deletion_command_inbox(id,operation_id,quiz_id,received_at,processed_at,attempts,last_error)"
                            + " VALUES(?,?,?,?,?,1,NULL) ON CONFLICT (operation_id) DO NOTHING",
                    UUID.randomUUID(),
                    operation.operationId(),
                    operation.quizId(),
                    Timestamp.from(received),
                    Timestamp.from(received));
            if (retentionHoldActive(operation.quizId())) {
                db.update(
                        "UPDATE deletion_command_inbox SET last_error='BLOCKED_BY_RETENTION'" + " WHERE operation_id=?",
                        operation.operationId());
                return new Result(operation.operationId(), operation.quizId(), Outcome.BLOCKED_BY_RETENTION, Map.of());
            }
            var acknowledged = db.query(
                    "SELECT (ack_at IS NOT NULL) FROM quiz_revocation_fences WHERE quiz_id=?",
                    (r, n) -> r.getBoolean(1),
                    operation.quizId());
            if (acknowledged.isEmpty() || !acknowledged.getFirst()) {
                db.update(
                        "UPDATE deletion_command_inbox SET last_error='WAITING_REVOCATION_ACK'"
                                + " WHERE operation_id=?",
                        operation.operationId());
                return new Result(
                        operation.operationId(), operation.quizId(), Outcome.WAITING_REVOCATION_ACK, Map.of());
            }
            db.queryForObject(
                    "SELECT assessment_purge_quiz(?,?,?)",
                    Boolean.class,
                    operation.quizId(),
                    operation.operationId(),
                    batchLimit);
            // Independent COUNT re-check before any PURGED claim: no remaining row may
            // reference the quiz anywhere in the Assessment schema.
            long remaining = remainingReferences(operation.quizId());
            if (remaining != 0) throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
            return new Result(
                    operation.operationId(), operation.quizId(), Outcome.PURGED, receipts(operation.operationId()));
        });
    }

    private boolean retentionHoldActive(UUID quizId) {
        return Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM retention_holds WHERE quiz_id=?"
                        + " AND (expires_at IS NULL OR expires_at>clock_timestamp()))",
                Boolean.class,
                quizId));
    }

    private long remainingReferences(UUID quizId) {
        Long remaining = named.queryForObject(
                """
            SELECT (SELECT count(*) FROM publications p WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM attempts a JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM attempt_answers x JOIN attempts a ON a.id=x.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM submitted_answers s JOIN attempts a ON a.id=s.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM grading_results g JOIN attempts a ON a.id=g.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM grading_question_results q JOIN grading_results g ON g.id=q.result_id
                JOIN attempts a ON a.id=g.attempt_id JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM attempt_operations o JOIN attempts a ON a.id=o.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM attempt_creation_operations c JOIN attempts a ON a.id=c.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM attempt_finalization_audit f JOIN attempts a ON a.id=f.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM closure_requests c JOIN publications p ON p.id=c.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM closure_request_members m JOIN attempts a ON a.id=m.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM assessment_notifications n JOIN attempts a ON a.id=n.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM publication_operations o JOIN publications p ON p.id=o.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM closure_work_audit w JOIN attempts a ON a.id=w.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM assessment_monitoring_outbox e JOIN attempts a ON a.id=e.attempt_id
                JOIN publications p ON p.id=a.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM realtime_invalidation_outbox r WHERE r.resource_kind='PUBLICATION'
                AND r.resource_id IN (SELECT p.id FROM publications p WHERE p.quiz_id=:quiz))
             +(SELECT count(*) FROM publication_creations c JOIN publications p ON p.id=c.publication_id WHERE p.quiz_id=:quiz)
             +(SELECT count(*) FROM deletion_command_inbox i WHERE i.quiz_id=:quiz)
            """,
                java.util.Map.of("quiz", quizId),
                Long.class);
        return remaining == null ? 1 : remaining;
    }

    private Map<String, Long> receipts(UUID operationId) {
        var steps = new LinkedHashMap<String, Long>();
        db.query(
                        "SELECT step,rows_removed FROM deletion_purge_receipts WHERE operation_id=?"
                                + " ORDER BY completed_at,step",
                        (r, n) -> steps.put(r.getString(1), r.getLong(2)),
                        operationId)
                .size();
        return Collections.unmodifiableMap(steps);
    }

    /** Truthful per-quiz purge state; counts only, never content or PII. */
    public Optional<PurgeStatus> status(UUID quizId) {
        try {
            var fence = db.query(
                    "SELECT revoked_at,ack_at,operation_id FROM quiz_revocation_fences WHERE quiz_id=?",
                    (r, n) -> new Object[] {r.getTimestamp(1), r.getTimestamp(2), r.getObject(3, UUID.class)},
                    quizId);
            if (fence.isEmpty()) return Optional.empty();
            UUID operationId = (UUID) fence.getFirst()[2];
            var steps = receipts(operationId);
            return Optional.of(new PurgeStatus(
                    quizId,
                    ((Timestamp) fence.getFirst()[0]).toInstant(),
                    fence.getFirst()[1] == null ? null : ((Timestamp) fence.getFirst()[1]).toInstant(),
                    steps.containsKey("verified_zero"),
                    retentionHoldActive(quizId),
                    steps));
        } catch (RuntimeException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }
}
