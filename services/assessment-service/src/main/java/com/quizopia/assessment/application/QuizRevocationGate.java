package com.quizopia.assessment.application;

import java.util.UUID;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FB-015 quiz-ID revocation fence (ADR-018 Phase A). Presence of a row in quiz_revocation_fences
 * means the owning Quiz is permanently revoked; every affected route denies with a stable
 * non-disclosing ASSESSMENT_REMOVED before any cached or idempotent-replay response is returned.
 * The gate is always compiled and executed, even while quizopia.deletion.enabled is false: an
 * empty fence table yields identical pre-existing behavior with no flag bypass, and a
 * reconciliation-installed fence is still enforced.
 */
@Component
public class QuizRevocationGate {
    public static final String REMOVED = "ASSESSMENT_REMOVED";

    private final JdbcTemplate db;

    public QuizRevocationGate(@Lazy JdbcTemplate db) {
        this.db = db;
    }

    public static PublicationFailure removed() {
        return new PublicationFailure(404, REMOVED);
    }

    /** One indexed primary-key probe per request; the table is empty unless a deletion ran. */
    public void requireQuizOpen(UUID quizId) {
        if (quizId == null) return;
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM quiz_revocation_fences WHERE quiz_id=?)", Boolean.class, quizId)))
            throw removed();
    }

    /**
     * Publication-scoped probe used inside mutation transactions after the publication row lock is
     * held, so the fence check linearizes with the fence installer's own row locking.
     */
    public void requirePublicationOpen(UUID publicationId) {
        if (publicationId == null) return;
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM quiz_revocation_fences f"
                        + " JOIN publications p ON p.quiz_id=f.quiz_id WHERE p.id=?)",
                Boolean.class,
                publicationId))) throw removed();
    }
}
