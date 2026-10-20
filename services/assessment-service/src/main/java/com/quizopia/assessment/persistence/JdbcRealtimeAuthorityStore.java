package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.application.RealtimeAuthorityStore;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRealtimeAuthorityStore implements RealtimeAuthorityStore {
    private final JdbcTemplate db;

    public JdbcRealtimeAuthorityStore(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public AttemptView attempt(UUID attemptId) {
        try {
            var rows = db.query(
                    """
                WITH sample AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT a.id AS attempt_id,a.publication_id,p.classroom_id,a.student_id,p.owner_id,
                    (a.state='ACTIVE' AND p.state='OPEN' AND sample.now<a.deadline_at) AS active,
                    sample.now AS checked_at
                FROM attempts a JOIN publications p ON p.id=a.publication_id CROSS JOIN sample WHERE a.id=?
                """,
                    (row, number) -> new AttemptView(
                            row.getObject("attempt_id", UUID.class),
                            row.getObject("publication_id", UUID.class),
                            row.getObject("classroom_id", UUID.class),
                            row.getObject("student_id", UUID.class),
                            row.getObject("owner_id", UUID.class),
                            row.getBoolean("active"),
                            row.getTimestamp("checked_at").toInstant()),
                    attemptId);
            if (rows.size() != 1) throw new PublicationFailure(404, "NOT_ACCESSIBLE");
            return rows.getFirst();
        } catch (DataAccessException failure) {
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        }
    }

    @Override
    public PublicationView publication(UUID publicationId) {
        try {
            var rows = db.query(
                    """
                WITH sample AS MATERIALIZED (SELECT clock_timestamp() AS now)
                SELECT p.id AS publication_id,p.classroom_id,p.owner_id,
                    (p.state='OPEN') AS open,sample.now AS checked_at
                FROM publications p CROSS JOIN sample WHERE p.id=?
                """,
                    (row, number) -> new PublicationView(
                            row.getObject("publication_id", UUID.class),
                            row.getObject("classroom_id", UUID.class),
                            row.getObject("owner_id", UUID.class),
                            row.getBoolean("open"),
                            row.getTimestamp("checked_at").toInstant()),
                    publicationId);
            if (rows.size() != 1) throw new PublicationFailure(404, "NOT_ACCESSIBLE");
            return rows.getFirst();
        } catch (DataAccessException failure) {
            throw new PublicationFailure(503, "AUTHORITY_UNAVAILABLE");
        }
    }
}
