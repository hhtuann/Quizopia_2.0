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
 * FB-015 PR C quiz-scoped hard purge (ADR-018 Phase B step 7), corrected per Leader
 * review. Runs only after the owning fence is acknowledged; delegates all row removal
 * to the narrowly-scoped privileged monitoring_purge_quiz procedure (FK-ordered,
 * idempotent, triggers stay enabled). Each invocation carries a hard total
 * deleted-row budget; an exhausted budget durably persists progress and reports
 * IN_PROGRESS - never a false PURGED. Legacy (pre-V4, quiz_id NULL) rows are purged
 * only through the Assessment-provided dependents inventory; when that mapping is
 * unavailable and legacy rows exist the outcome is RECONCILIATION_BLOCKED. Media
 * provider erasure blocks local deletion until independent custodian acknowledgment;
 * the original room/participant bindings remain durable during PENDING_PROVIDER.
 * Receipts carry counts only - no PII, no evidence or media payloads.
 */
@Service
public class QuizPurgeService {
    /**
     * @param publicationIds assessment-owned dependents inventory for the quiz; null
     *     means the mapping is unavailable (legacy rows cannot be resolved).
     * @param attemptIds see {@link #publicationIds}.
     */
    public record PurgeOperation(UUID operationId, UUID quizId, List<UUID> publicationIds, List<UUID> attemptIds) {
        public PurgeOperation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(quizId, "quizId");
            if (publicationIds != null)
                publicationIds = publicationIds.stream().distinct().sorted().toList();
            if (attemptIds != null)
                attemptIds = attemptIds.stream().distinct().sorted().toList();
        }

        public PurgeOperation(UUID operationId, UUID quizId) {
            this(operationId, quizId, null, null);
        }
    }

    public enum Outcome {
        PURGED,
        IN_PROGRESS,
        RECONCILIATION_BLOCKED,
        INVENTORY_MISMATCH,
        BLOCKED_BY_RETENTION,
        PENDING_PROVIDER,
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
            Map<String, Long> steps,
            String providerErasure) {
        public String toString() {
            return "PurgeStatus[redacted]";
        }
    }

    private final JdbcTemplate db;
    private final TransactionTemplate transaction;

    /**
     * @param mediaProviderName retained constructor compatibility; the privileged SQL
     *     tracks actual persisted LiveKit bindings, independently of runtime flags.
     */
    public QuizPurgeService(@Lazy JdbcTemplate db, @Lazy PlatformTransactionManager manager, String mediaProviderName) {
        this.db = db;
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
                db.execute("SELECT pg_advisory_xact_lock(18015)");
                return work.get();
            });
        } catch (MonitoringFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    /** Idempotent: an already-purged quiz simply reports PURGED with unchanged receipts. */
    public List<Result> purgeBatch(List<PurgeOperation> operations, int budget) {
        var canonical = List.copyOf(operations);
        if (canonical.isEmpty() || canonical.size() > 50) throw new MonitoringFailure(400, "INVALID_REQUEST");
        if (budget < 1 || budget > 50000) throw new MonitoringFailure(400, "INVALID_REQUEST");
        var results = new ArrayList<Result>(canonical.size());
        for (PurgeOperation operation : canonical) results.add(purge(operation, budget));
        return List.copyOf(results);
    }

    private Result purge(PurgeOperation operation, int budget) {
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
            String outcome = db.queryForObject(
                    "SELECT monitoring_purge_quiz(?,?,?::uuid[],?::uuid[],?)",
                    String.class,
                    operation.quizId(),
                    operation.operationId(),
                    arrayLiteral(operation.publicationIds()),
                    arrayLiteral(operation.attemptIds()),
                    budget);
            if ("RECONCILIATION_BLOCKED".equals(outcome)) {
                db.update(
                        "UPDATE deletion_command_inbox SET last_error='RECONCILIATION_BLOCKED'"
                                + " WHERE operation_id=?",
                        operation.operationId());
                return new Result(
                        operation.operationId(), operation.quizId(), Outcome.RECONCILIATION_BLOCKED, Map.of());
            }
            if (Set.of("INVENTORY_MISMATCH", "BLOCKED_BY_RETENTION", "PENDING_PROVIDER")
                    .contains(outcome)) {
                return new Result(
                        operation.operationId(),
                        operation.quizId(),
                        Outcome.valueOf(outcome),
                        receipts(operation.operationId()));
            }
            if (!"PURGED".equals(outcome)) {
                if (!"IN_PROGRESS".equals(outcome)) throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
                db.update(
                        "UPDATE deletion_command_inbox SET last_error='IN_PROGRESS' WHERE operation_id=?",
                        operation.operationId());
                return new Result(
                        operation.operationId(),
                        operation.quizId(),
                        Outcome.IN_PROGRESS,
                        receipts(operation.operationId()));
            }
            return new Result(
                    operation.operationId(), operation.quizId(), Outcome.PURGED, receipts(operation.operationId()));
        });
    }

    private String arrayLiteral(List<UUID> ids) {
        if (ids == null) return null;
        if (ids.isEmpty()) return "{}";
        var literal = new StringBuilder("{");
        for (UUID id : ids) literal.append(id).append(',');
        literal.setLength(literal.length() - 1);
        literal.append('}');
        return literal.toString();
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
            boolean providerAcknowledged = Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM proctoring_provider_cleanup WHERE operation_id=?)",
                    Boolean.class,
                    operationId));
            boolean retentionHoldActive = Boolean.TRUE.equals(
                    db.queryForObject("SELECT proctoring_retention_hold_active(?)", Boolean.class, quizId));
            boolean currentlyAbsent = steps.containsKey("verified_zero")
                    && Boolean.TRUE.equals(db.queryForObject(
                            "SELECT proctoring_purge_absent(?,?)", Boolean.class, quizId, operationId));
            return Optional.of(new PurgeStatus(
                    quizId,
                    revokedAt,
                    acknowledged == null ? null : acknowledged.toInstant(),
                    currentlyAbsent,
                    retentionHoldActive,
                    steps,
                    providerAcknowledged
                            ? "ACKNOWLEDGED"
                            : provider.isEmpty() ? "NOT_CONFIGURED" : provider.getFirst()));
        } catch (RuntimeException failure) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }
}
