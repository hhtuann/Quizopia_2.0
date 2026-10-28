package com.quizopia.proctoring.application;

import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FB-015 quiz-ID revocation fence (ADR-018 Phase A step 4). Presence of a row in
 * quiz_revocation_fences means the owning Quiz is permanently revoked; every affected
 * Proctoring route denies with a stable non-disclosing PROCTORING_REMOVED before any
 * evidence, audit or media work. The gate is always compiled and executed, even while
 * quizopia.deletion.enabled is false: an empty fence table yields identical pre-existing
 * behavior, and a fence installed by redelivery is still enforced. Schemas below V4 cannot
 * express a fence at all; their presence is probed once through to_regclass (which never
 * aborts a transaction) instead of guessing from a failed statement.
 */
@Component
public final class QuizRevocationGate {
    public static final String REMOVED = "PROCTORING_REMOVED";

    private final JdbcTemplate db;
    private volatile Boolean fenceSurfacePresent;

    public QuizRevocationGate(@Lazy JdbcTemplate db) {
        this.db = db;
    }

    public static MonitoringFailure removed() {
        return new MonitoringFailure(404, REMOVED);
    }

    /** One indexed probe per new-session request; the table is empty unless a deletion ran. */
    public void requireQuizOpen(UUID quizId) {
        if (quizId == null) return;
        requireNoFence("SELECT EXISTS(SELECT 1 FROM quiz_revocation_fences WHERE quiz_id=?)", quizId);
    }

    /** Session-scoped probe used inside store transactions after the session row is held. */
    public void requireSessionOpen(UUID sessionId) {
        if (sessionId == null) return;
        requireNoFence(
                "SELECT EXISTS(SELECT 1 FROM quiz_revocation_fences f"
                        + " JOIN monitoring_sessions s ON s.quiz_id=f.quiz_id WHERE s.id=?)",
                sessionId);
    }

    private void requireNoFence(String sql, UUID key) {
        if (!fenceSurfacePresent()) return;
        boolean fenced;
        try {
            fenced = Boolean.TRUE.equals(db.queryForObject(sql, Boolean.class, key));
        } catch (DataAccessException unavailable) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
        if (fenced) throw removed();
    }

    private boolean fenceSurfacePresent() {
        Boolean present = fenceSurfacePresent;
        if (present == null) {
            try {
                present = Boolean.TRUE.equals(
                        db.queryForObject("SELECT to_regclass('quiz_revocation_fences') IS NOT NULL", Boolean.class));
            } catch (DataAccessException unavailable) {
                throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
            }
            fenceSurfacePresent = present;
        }
        return present;
    }
}
