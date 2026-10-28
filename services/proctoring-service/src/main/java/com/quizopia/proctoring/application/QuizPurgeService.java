package com.quizopia.proctoring.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FB-015 PR C quiz-scoped hard purge (ADR-018 Phase B step 7). Runs only after the owning
 * fence is acknowledged; delegates all row removal to the narrowly-scoped privileged
 * monitoring_purge_quiz procedure (FK-ordered, bounded, idempotent, triggers stay enabled)
 * and records media-provider erasure REQUESTS honestly: no provider erasure API exists
 * yet, so requests stay PENDING_PROVIDER and no fabricated receipt is ever returned.
 * Receipts carry counts only - no PII, no evidence or media payloads.
 */
@Service
public class QuizPurgeService {
    public record PurgeOperation(UUID operationId, UUID quizId) {
        public PurgeOperation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(quizId, "quizId");
        }
    }

    public enum Outcome {
        PURGED,
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
            Map<String, Long> steps,
            String providerErasure) {
        public String toString() {
            return "PurgeStatus[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    private final String mediaProviderName;

    /**
     * @param mediaProviderName identity of the configured media provider; empty means no
     *     provider artifacts are tracked, and no erasure request is fabricated.
     */
    public QuizPurgeService(@Lazy JdbcTemplate db, @Lazy PlatformTransactionManager manager, String mediaProviderName) {
        this.db = db;
        this.mediaProviderName = mediaProviderName == null ? "" : mediaProviderName;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(30);
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive())
                throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
            return transaction.execute(status -> {
                db.execute("SET LOCAL lock_timeout='1000ms'");
                db.execute("SET LOCAL statement_timeout='10000ms'");
                return work.get();
            });
        } catch (MonitoringFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /** Idempotent: an already-purged quiz simply reports PURGED with unchanged receipts. */
    public List<Result> purgeBatch(List<PurgeOperation> operations, int batchLimit) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new MonitoringFailure(400, "INVALID_REQUEST");
        if (batchLimit < 1 || batchLimit > 1000) throw new MonitoringFailure(400, "INVALID_REQUEST");
        var results = new ArrayList<Result>(canonical.size());
        for (PurgeOperation operation : canonical) results.add(purge(operation, batchLimit));
        return List.copyOf(results);
    }

    private Result purge(PurgeOperation operation, int batchLimit) {
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
                    "SELECT monitoring_purge_quiz(?,?,?)",
                    Boolean.class,
                    operation.quizId(),
                    operation.operationId(),
                    batchLimit);
            if (!mediaProviderName.isEmpty()) {
                // Honest provider bookkeeping: request recorded, completion stays PENDING_PROVIDER.
                db.update(
                        "INSERT INTO media_erasure_requests(operation_id,quiz_id,provider,status,requested_at)"
                                + " VALUES(?,?,?,'PENDING_PROVIDER',?) ON CONFLICT (operation_id) DO NOTHING",
                        operation.operationId(),
                        operation.quizId(),
                        mediaProviderName,
                        Timestamp.from(received));
            }
            return new Result(
                    operation.operationId(), operation.quizId(), Outcome.PURGED, receipts(operation.operationId()));
        });
    }

    private Map<String, Long> receipts(UUID operationId) {
        var steps = new LinkedHashMap<String, Long>();
        db.query(
                        "SELECT step,rows_removed FROM deletion_purge_receipts WHERE operation_id=? ORDER BY completed_at,step",
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
            var revokedAt = ((java.sql.Timestamp) fence.getFirst()[0]).toInstant();
            var acknowledged = (java.sql.Timestamp) fence.getFirst()[1];
            UUID operationId = (UUID) fence.getFirst()[2];
            var steps = receipts(operationId);
            var provider = db.query(
                    "SELECT status FROM media_erasure_requests WHERE quiz_id=?", (r, n) -> r.getString(1), quizId);
            return Optional.of(new PurgeStatus(
                    quizId,
                    revokedAt,
                    acknowledged == null ? null : acknowledged.toInstant(),
                    steps.containsKey("verified_zero"),
                    steps,
                    provider.isEmpty() ? "NOT_CONFIGURED" : provider.getFirst()));
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }
}
