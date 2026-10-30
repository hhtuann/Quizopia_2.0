package com.quizopia.proctoring.media.persistence;

import com.quizopia.proctoring.application.*;
import com.quizopia.proctoring.domain.Monitoring.Session;
import com.quizopia.proctoring.media.MediaStore;
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
@ConditionalOnProperty(name = "quizopia.proctoring.media.enabled", havingValue = "true")
public class JdbcMediaStore implements MediaStore {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    // FB-015 fence gate: media grants are denied for revoked quizzes, server-side.
    private final com.quizopia.proctoring.application.QuizRevocationGate fence;
    private static final String SELECT =
            "SELECT g.*,r.room_name FROM monitoring_media_grants g JOIN monitoring_media_rooms r ON r.session_id=g.session_id ";

    public JdbcMediaStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.fence = new com.quizopia.proctoring.application.QuizRevocationGate(db);
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    private <T> T transaction(Supplier<T> work) {
        try {
            return tx.execute(s -> {
                db.execute("SET LOCAL lock_timeout='1000ms'");
                db.execute(
                        "SELECT pg_advisory_xact_lock(18015) WHERE to_regclass('public.purge_inventory') IS NOT NULL");
                db.execute("SET LOCAL statement_timeout='2000ms'");
                return work.get();
            });
        } catch (MonitoringFailure f) {
            throw f;
        } catch (RuntimeException e) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private Instant now() {
        return db.queryForObject(
                "SELECT clock_timestamp()", (r, n) -> r.getTimestamp(1).toInstant());
    }

    private Lease lease(ResultSet r, int n) throws SQLException {
        return new Lease(
                r.getObject("id", UUID.class),
                r.getObject("session_id", UUID.class),
                r.getObject("actor_id", UUID.class),
                r.getString("viewer_role"),
                r.getString("room_name"),
                r.getString("participant_identity"),
                r.getTimestamp("lease_until").toInstant(),
                r.getString("state"));
    }

    private Session session(UUID id) {
        var rows = db.query(
                "SELECT * FROM monitoring_sessions WHERE id=? FOR UPDATE",
                (r, n) -> new Session(
                        r.getObject("id", UUID.class),
                        r.getObject("attempt_id", UUID.class),
                        r.getObject("publication_id", UUID.class),
                        r.getObject("classroom_id", UUID.class),
                        r.getObject("student_id", UUID.class),
                        r.getTimestamp("deadline_at").toInstant(),
                        r.getString("status"),
                        r.getTimestamp("created_at").toInstant(),
                        r.getTimestamp("ended_at") == null
                                ? null
                                : r.getTimestamp("ended_at").toInstant()),
                id);
        if (rows.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        // FB-015: no media work on a revoked quiz, rechecked under the session row lock.
        fence.requireSessionOpen(id);
        return rows.getFirst();
    }

    @Override
    public Lease find(UUID id) {
        try {
            var rows = db.query(SELECT + "WHERE g.id=?", this::lease, id);
            if (rows.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            return rows.getFirst();
        } catch (org.springframework.dao.DataAccessException error) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    private Lease lock(UUID id) {
        var rows = db.query(SELECT + "WHERE g.id=? FOR UPDATE OF g", this::lease, id);
        if (rows.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
        return rows.getFirst();
    }

    private void authority(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, Session s, Instant time) {
        if (!actor.id().equals(a.actorId())
                || !actor.roles().contains(a.viewerRole())
                || actor.expiresAt() == null
                || !actor.expiresAt().isAfter(time)
                || a.checkedAt() == null
                || a.checkedAt().isBefore(time.minusSeconds(5))
                || a.checkedAt().isAfter(time.plusSeconds(1))) throw MonitoringFailure.unavailable();
        if (!s.attemptId().equals(a.attemptId())
                || !s.publicationId().equals(a.publicationId())
                || !s.classroomId().equals(a.classroomId())
                || !s.studentId().equals(a.studentId())
                || !s.deadlineAt().equals(a.deadlineAt())
                || !Set.of("STUDENT", "TEACHER").contains(a.viewerRole())
                || a.viewerRole().equals("STUDENT") && !s.studentId().equals(actor.id()))
            throw MonitoringFailure.unavailable();
        if (!a.active() || !s.status().equals("ACTIVE") || !s.deadlineAt().isAfter(time))
            throw new MonitoringFailure(409, "SESSION_ENDED");
    }

    private Instant until(MonitoringAuthority.Actor actor, Session session, Instant time, Duration duration) {
        if (duration == null
                || duration.compareTo(Duration.ofSeconds(1)) < 0
                || duration.compareTo(Duration.ofSeconds(30)) > 0) throw new IllegalArgumentException();
        return Collections.min(List.of(time.plus(duration), actor.expiresAt(), session.deadlineAt()))
                .truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public Lease prepare(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID id, Duration duration) {
        return transaction(() -> {
            var session = session(id);
            var time = now();
            authority(actor, a, session, time);
            var previous = db.query(
                    SELECT
                            + "WHERE g.session_id=? AND g.actor_id=? AND g.viewer_role=? AND g.state IN ('PREPARED','ACTIVE') AND g.lease_until>? ORDER BY g.created_at DESC LIMIT 1 FOR UPDATE OF g",
                    this::lease,
                    id,
                    actor.id(),
                    a.viewerRole(),
                    Timestamp.from(time));
            if (!previous.isEmpty()) {
                var existing = previous.getFirst();
                var capped = Collections.min(List.of(existing.leaseUntil(), until(actor, session, time, duration)));
                db.update(
                        "UPDATE monitoring_media_grants SET lease_until=? WHERE id=?",
                        Timestamp.from(capped),
                        existing.id());
                authority(actor, a, session, now());
                return find(existing.id());
            }
            db.update(
                    "INSERT INTO monitoring_media_rooms(session_id,room_name) VALUES (?,?) ON CONFLICT(session_id) DO NOTHING",
                    id,
                    "m-" + UUID.randomUUID());
            var room = db.queryForObject(
                    "SELECT room_name FROM monitoring_media_rooms WHERE session_id=? AND deleted_at IS NULL",
                    String.class,
                    id);
            UUID grant = UUID.randomUUID();
            db.update(
                    "INSERT INTO monitoring_media_grants(id,session_id,actor_id,viewer_role,participant_identity,lease_until,state) VALUES (?,?,?,?,?,?,'PREPARED')",
                    grant,
                    id,
                    actor.id(),
                    a.viewerRole(),
                    "p-" + UUID.randomUUID(),
                    Timestamp.from(until(actor, session, time, duration)));
            var result = find(grant);
            authority(actor, a, session, now());
            return result;
        });
    }

    private void owner(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, Lease lease) {
        if (!lease.actorId().equals(actor.id()) || !lease.role().equals(a.viewerRole()))
            throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
    }

    @Override
    public Lease activate(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID grant) {
        return transaction(() -> {
            var initial = find(grant);
            var session = session(initial.sessionId());
            var current = lock(grant);
            var time = now();
            authority(actor, a, session, time);
            owner(actor, a, current);
            if (!Set.of("PREPARED", "ACTIVE").contains(current.status())
                    || !current.leaseUntil().isAfter(time)) throw new MonitoringFailure(409, "MEDIA_GRANT_ENDED");
            var capped = Collections.min(List.of(current.leaseUntil(), actor.expiresAt()))
                    .truncatedTo(ChronoUnit.MICROS);
            db.update(
                    "UPDATE monitoring_media_grants SET state='ACTIVE',lease_until=? WHERE id=?",
                    Timestamp.from(capped),
                    grant);
            return find(grant);
        });
    }

    @Override
    public Lease renew(MonitoringAuthority.Actor actor, MonitoringAuthority.Snapshot a, UUID grant, Duration duration) {
        return transaction(() -> {
            var initial = find(grant);
            var session = session(initial.sessionId());
            var current = lock(grant);
            var time = now();
            authority(actor, a, session, time);
            owner(actor, a, current);
            if (!current.status().equals("ACTIVE") || !current.leaseUntil().isAfter(time))
                throw new MonitoringFailure(409, "MEDIA_GRANT_ENDED");
            db.update(
                    "UPDATE monitoring_media_grants SET lease_until=? WHERE id=?",
                    Timestamp.from(until(actor, session, time, duration)),
                    grant);
            return find(grant);
        });
    }

    @Override
    public Lease fence(UUID grant, UUID actor) {
        return transaction(() -> {
            var current = lock(grant);
            if (actor != null && !current.actorId().equals(actor))
                throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            if (current.status().equals("REVOKED")) return current;
            if (current.status().equals("REVOKE_PENDING")) {
                db.update("UPDATE monitoring_media_grants SET last_recovery_at=clock_timestamp() WHERE id=?", grant);
                return current;
            }
            if (actor == null) {
                Boolean due = db.queryForObject(
                        "SELECT g.lease_until<=clock_timestamp() OR s.status='ENDED' OR s.deadline_at<=clock_timestamp() OR (g.state='PREPARED' AND g.created_at<clock_timestamp()-interval '5 seconds') FROM monitoring_media_grants g JOIN monitoring_sessions s ON s.id=g.session_id WHERE g.id=?",
                        Boolean.class,
                        grant);
                if (!Boolean.TRUE.equals(due)) return current;
            }
            db.update(
                    "UPDATE monitoring_media_grants SET state='REVOKE_PENDING',last_recovery_at=clock_timestamp() WHERE id=?",
                    grant);
            return find(grant);
        });
    }

    @Override
    public List<Lease> pending(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException();
        return transaction(() -> db.query(
                SELECT
                        + "JOIN monitoring_sessions s ON s.id=g.session_id WHERE g.state='REVOKE_PENDING' OR (g.state<>'REVOKED' AND (g.lease_until<=clock_timestamp() OR s.status='ENDED' OR s.deadline_at<=clock_timestamp() OR (g.state='PREPARED' AND g.created_at<clock_timestamp()-interval '5 seconds'))) ORDER BY g.last_recovery_at NULLS FIRST,g.created_at,g.id LIMIT ?",
                this::lease,
                limit));
    }

    @Override
    public void revoked(UUID grant) {
        transaction(() -> {
            var current = lock(grant);
            if (current.status().equals("REVOKED")) return null;
            if (!current.status().equals("REVOKE_PENDING")) throw new MonitoringFailure(409, "MEDIA_GRANT_ENDED");
            db.update(
                    "UPDATE monitoring_media_grants SET state='REVOKED',revoked_at=clock_timestamp() WHERE id=?",
                    grant);
            return null;
        });
    }

    @Override
    public List<String> endedRooms(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException();
        return transaction(() -> {
            var rooms = db.queryForList(
                    "SELECT r.room_name FROM monitoring_media_rooms r JOIN monitoring_sessions s ON s.id=r.session_id WHERE r.deleted_at IS NULL AND (s.status='ENDED' OR s.deadline_at<=clock_timestamp()) AND NOT EXISTS (SELECT 1 FROM monitoring_media_grants g WHERE g.session_id=s.id AND g.state<>'REVOKED') ORDER BY r.last_recovery_at NULLS FIRST,r.created_at LIMIT ?",
                    String.class,
                    limit);
            for (String room : rooms)
                db.update(
                        "UPDATE monitoring_media_rooms SET last_recovery_at=clock_timestamp() WHERE room_name=? AND deleted_at IS NULL",
                        room);
            return rooms;
        });
    }

    @Override
    public void roomDeleted(String room) {
        transaction(() -> {
            db.update(
                    "UPDATE monitoring_media_rooms SET deleted_at=clock_timestamp() WHERE room_name=? AND deleted_at IS NULL",
                    room);
            return null;
        });
    }
}
