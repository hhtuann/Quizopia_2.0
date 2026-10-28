package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.ProctoringAuthorityStore;
import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.application.QuizRevocationGate;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProctoringAuthorityStore implements ProctoringAuthorityStore {
    private final JdbcTemplate db;
    private final QuizRevocationGate revocations;

    public JdbcProctoringAuthorityStore(JdbcTemplate db, QuizRevocationGate revocations) {
        this.db = db;
        this.revocations = revocations;
    }

    @Override
    public Projection find(UUID id) {
        try {
            var rows = db.query(
                    """
                WITH sample AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT a.id,a.publication_id,p.classroom_id,a.student_id,p.owner_id,p.quiz_id,
                    (p.audience='CLASS' AND p.monitoring_choice='ENABLED'
                     AND p.classroom_id IS NOT NULL AND p.duration_minutes BETWEEN 5 AND 240
                     AND a.duration_minutes BETWEEN 5 AND 240 AND a.deadline_at IS NOT NULL
                     AND p.state IN ('OPEN','CLOSED')) AS monitorable,
                    (a.state='ACTIVE' AND p.state='OPEN' AND sample.now<a.deadline_at) AS active,
                    a.duration_minutes,a.started_at,a.deadline_at,sample.now AS checked_at
                FROM attempts a JOIN publications p ON p.id=a.publication_id CROSS JOIN sample WHERE a.id=?
                """,
                    (row, number) -> new Projection(
                            row.getObject("id", UUID.class),
                            row.getObject("publication_id", UUID.class),
                            row.getObject("classroom_id", UUID.class),
                            row.getObject("student_id", UUID.class),
                            row.getObject("owner_id", UUID.class),
                            row.getBoolean("monitorable"),
                            row.getBoolean("active"),
                            row.getInt("duration_minutes"),
                            row.getTimestamp("started_at").toInstant(),
                            row.getTimestamp("deadline_at") == null
                                    ? null
                                    : row.getTimestamp("deadline_at").toInstant(),
                            row.getTimestamp("checked_at").toInstant(),
                            row.getObject("quiz_id", UUID.class)),
                    id);
            if (rows.size() != 1) throw new PublicationFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            // Revoked quizzes expose no monitoring evidence (ADR-018 Phase A step 2).
            revocations.requireQuizOpen(rows.getFirst().quizId());
            return rows.getFirst();
        } catch (DataAccessException failure) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }
}
