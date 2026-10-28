package com.quizopia.assessment.realtime;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcRealtimeInvalidationOutbox implements RealtimeInvalidationOutbox {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;

    public JdbcRealtimeInvalidationOutbox(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    @Override
    public List<Event> claim(UUID owner, int limit) {
        if (owner == null || limit < 1 || limit > 5) throw new IllegalArgumentException("Invalid claim bounds");
        try {
            return transaction(() -> db.query(
                    """
                WITH candidates AS (
                  SELECT event_id FROM realtime_invalidation_outbox
                  WHERE state='PENDING' AND next_attempt_at<=clock_timestamp()
                    AND (lease_until IS NULL OR lease_until<=clock_timestamp())
                    AND NOT EXISTS (
                      SELECT 1 FROM publications p JOIN quiz_revocation_fences rf ON rf.quiz_id=p.quiz_id
                      WHERE (realtime_invalidation_outbox.resource_kind='PUBLICATION'
                          AND p.id=realtime_invalidation_outbox.resource_id)
                        OR (realtime_invalidation_outbox.resource_kind='ATTEMPT'
                          AND p.id=(SELECT a.publication_id FROM attempts a
                                    WHERE a.id=realtime_invalidation_outbox.resource_id)))
                  ORDER BY next_attempt_at,created_at,event_id
                  LIMIT ? FOR UPDATE SKIP LOCKED
                ) UPDATE realtime_invalidation_outbox o
                  SET lease_owner=?,lease_until=clock_timestamp()+interval '60 seconds',
                      last_attempt_at=clock_timestamp(),delivery_attempts=delivery_attempts+1
                  FROM candidates c WHERE o.event_id=c.event_id
                  RETURNING o.event_id,o.event_type,o.resource_kind,o.resource_id,o.occurred_at,o.delivery_attempts
                """,
                    (r, n) -> new Event(
                            r.getObject(1, UUID.class),
                            owner,
                            r.getString(2),
                            r.getString(3),
                            r.getObject(4, UUID.class),
                            r.getTimestamp(5).toInstant(),
                            r.getLong(6)),
                    limit,
                    owner));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Realtime invalidation outbox unavailable");
        }
    }

    @Override
    public void published(Event event) {
        try {
            transaction(() -> {
                int changed = db.update(
                        """
                UPDATE realtime_invalidation_outbox SET state='PUBLISHED',published_at=clock_timestamp(),
                  lease_owner=NULL,lease_until=NULL WHERE event_id=? AND lease_owner=? AND state='PENDING'
                  AND lease_until>clock_timestamp()
                """,
                        event.id(),
                        event.owner());
                if (changed != 1) throw new IllegalStateException();
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Realtime invalidation acknowledgment uncertain");
        }
    }

    @Override
    public void retry(Event event, int backoffSeconds) {
        try {
            transaction(() -> {
                db.update(
                        """
                UPDATE realtime_invalidation_outbox SET lease_owner=NULL,lease_until=NULL,
                  next_attempt_at=clock_timestamp()+make_interval(secs => ?::double precision)
                WHERE event_id=? AND lease_owner=? AND state='PENDING'
                """,
                        (double) Math.max(0, backoffSeconds),
                        event.id(),
                        event.owner());
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Realtime invalidation retry uncertain");
        }
    }

    @Override
    public void quarantined(Event event) {
        try {
            transaction(() -> {
                int changed = db.update(
                        """
                UPDATE realtime_invalidation_outbox SET state='QUARANTINED',lease_owner=NULL,lease_until=NULL
                WHERE event_id=? AND lease_owner=? AND state='PENDING'
                """,
                        event.id(),
                        event.owner());
                if (changed != 1) throw new IllegalStateException();
                return null;
            });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Realtime invalidation quarantine uncertain");
        }
    }

    @Override
    public Pending pending() {
        try {
            return transaction(() -> db.queryForObject(
                    """
                SELECT count(*),coalesce(extract(epoch FROM (clock_timestamp()-min(created_at))),0)
                FROM realtime_invalidation_outbox WHERE state='PENDING'
                """,
                    (r, n) -> new Pending(r.getLong(1), r.getDouble(2))));
        } catch (RuntimeException e) {
            return new Pending(0, 0.0);
        }
    }

    private <T> T transaction(java.util.function.Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Realtime invalidation delivery transaction must be independent");
        return tx.execute(status -> {
            db.execute("SET LOCAL lock_timeout='1s'");
            db.execute("SET LOCAL statement_timeout='2s'");
            return action.get();
        });
    }
}
