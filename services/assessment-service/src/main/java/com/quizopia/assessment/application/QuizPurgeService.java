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
 * idempotent, triggers stay enabled) and never trusts an in-memory assumption: PURGED is
 * reported only from the procedure's zero-remaining verification plus an independent SQL
 * existence re-check. Each invocation carries a work-unit budget (at most three
 * content deletes per unit, including complete FK cycles); inventory writes consume
 * units too. An exhausted budget durably persists progress and reports IN_PROGRESS - never
 * a false PURGED. Receipts carry counts only - no answers, no PII.
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
        IN_PROGRESS,
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
    public List<Result> purgeBatch(List<Operation> operations, int budget) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new PublicationFailure(400, "INVALID_REQUEST");
        if (budget < 1 || budget > 50000) throw new PublicationFailure(400, "INVALID_REQUEST");
        var results = new ArrayList<Result>(canonical.size());
        for (Operation operation : canonical) results.add(purge(operation, budget));
        return List.copyOf(results);
    }

    private Result purge(Operation operation, int budget) {
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
            String outcome = db.queryForObject(
                    "SELECT assessment_purge_quiz(?,?,?)",
                    String.class,
                    operation.quizId(),
                    operation.operationId(),
                    budget);
            if ("BLOCKED_BY_RETENTION".equals(outcome)) {
                return new Result(operation.operationId(), operation.quizId(), Outcome.BLOCKED_BY_RETENTION, Map.of());
            }
            if (!"PURGED".equals(outcome)) {
                // Budget exhausted before verified-zero: durable progress was committed
                // by the procedure; the coordinator must invoke the next round. No
                // PURGED receipt exists while any dependent row may remain.
                if (!"IN_PROGRESS".equals(outcome)) throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
                return new Result(
                        operation.operationId(),
                        operation.quizId(),
                        Outcome.IN_PROGRESS,
                        receipts(operation.operationId()));
            }
            // Independent existence re-check before any PURGED claim: no remaining row may
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
            SELECT CASE WHEN EXISTS(SELECT 1 FROM publications WHERE quiz_id=:quiz)
              OR EXISTS(SELECT 1 FROM publication_snapshots s JOIN purged_publication_markers m
                ON m.publication_id=s.publication_id JOIN quiz_revocation_fences f ON f.operation_id=m.operation_id
                WHERE f.quiz_id=:quiz)
              OR EXISTS(SELECT 1 FROM attempts a JOIN purged_attempt_markers m ON m.attempt_id=a.id
                JOIN quiz_revocation_fences f ON f.operation_id=m.operation_id WHERE f.quiz_id=:quiz)
              OR EXISTS(SELECT 1 FROM realtime_invalidation_outbox r WHERE
                (r.resource_kind='PUBLICATION' AND r.resource_id IN
                  (SELECT m.publication_id FROM purged_publication_markers m JOIN quiz_revocation_fences f
                    ON f.operation_id=m.operation_id WHERE f.quiz_id=:quiz))
                OR (r.resource_kind='ATTEMPT' AND r.resource_id IN
                  (SELECT m.attempt_id FROM purged_attempt_markers m JOIN quiz_revocation_fences f
                    ON f.operation_id=m.operation_id WHERE f.quiz_id=:quiz)))
              OR EXISTS(SELECT 1 FROM deletion_command_inbox WHERE quiz_id=:quiz)
            THEN 1 ELSE 0 END
            """,
                java.util.Map.of("quiz", quizId),
                Long.class);
        // Validated forward FKs prove all other children absent when their roots are
        // absent. Snapshots and realtime have no forward FK; check durable scope explicitly.
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
