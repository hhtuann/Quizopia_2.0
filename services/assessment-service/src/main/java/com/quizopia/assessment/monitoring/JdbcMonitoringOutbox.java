package com.quizopia.assessment.monitoring;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcMonitoringOutbox implements MonitoringOutbox {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public JdbcMonitoringOutbox(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    @Override
    public List<Event> claim(UUID owner, int limit) {
        if (owner == null || limit < 1 || limit > 5) throw new IllegalArgumentException("Invalid claim bounds");
        try {
            return transaction(() -> {
                return db.query(
                        """
                    WITH candidates AS (
                      SELECT event_id FROM assessment_monitoring_outbox
                      WHERE state='PENDING' AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                        AND NOT EXISTS (
                          SELECT 1 FROM attempts a JOIN publications p ON p.id=a.publication_id
                            JOIN quiz_revocation_fences rf ON rf.quiz_id=p.quiz_id
                          WHERE a.id=assessment_monitoring_outbox.attempt_id)
                      ORDER BY last_attempt_at NULLS FIRST,created_at,event_id
                      LIMIT ? FOR UPDATE SKIP LOCKED
                    ) UPDATE assessment_monitoring_outbox o
                      SET lease_owner=?,lease_until=clock_timestamp()+interval '60 seconds',
                          last_attempt_at=clock_timestamp(),delivery_attempts=delivery_attempts+1
                      FROM candidates c WHERE o.event_id=c.event_id
                      RETURNING o.event_id,o.payload::text
                    """,
                        (r, n) -> new Event(
                                r.getObject(1, UUID.class),
                                owner,
                                r.getString(2).getBytes(StandardCharsets.UTF_8)),
                        limit,
                        owner);
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Monitoring outbox unavailable");
        }
    }

    @Override
    public void published(Event event) {
        try {
            transaction(() -> {
                int changed = db.update(
                        """
                UPDATE assessment_monitoring_outbox SET state='PUBLISHED',published_at=clock_timestamp(),
                  lease_owner=NULL,lease_until=NULL WHERE event_id=? AND lease_owner=? AND state='PENDING'
                  AND lease_until>clock_timestamp()
                """,
                        event.id(),
                        event.owner());
                if (changed != 1) throw new IllegalStateException();
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Monitoring outbox acknowledgment uncertain");
        }
    }

    @Override
    public void retry(Event event) {
        try {
            transaction(() -> {
                db.update(
                        """
                UPDATE assessment_monitoring_outbox SET lease_owner=NULL,lease_until=NULL
                WHERE event_id=? AND lease_owner=? AND state='PENDING'
                """,
                        event.id(),
                        event.owner());
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Monitoring outbox retry uncertain");
        }
    }

    private <T> T transaction(java.util.function.Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Monitoring delivery transaction must be independent");
        return tx.execute(status -> {
            db.execute("SET LOCAL lock_timeout='1s'");
            db.execute("SET LOCAL statement_timeout='2s'");
            return action.get();
        });
    }
}
