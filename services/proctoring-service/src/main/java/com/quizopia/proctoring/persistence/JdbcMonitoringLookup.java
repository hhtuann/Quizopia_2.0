package com.quizopia.proctoring.persistence;

import com.quizopia.proctoring.application.MonitoringFailure;
import com.quizopia.proctoring.application.MonitoringLookup;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "quizopia.proctoring.monitoring.enabled", havingValue = "true")
public class JdbcMonitoringLookup implements MonitoringLookup {
    private final JdbcTemplate db;

    public JdbcMonitoringLookup(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public UUID existingSession(UUID attemptId) {
        try {
            var ids = db.query(
                    "SELECT id FROM monitoring_sessions WHERE attempt_id=?",
                    (row, index) -> row.getObject("id", UUID.class),
                    attemptId);
            if (ids.isEmpty()) throw new MonitoringFailure(404, "PROCTORING_NOT_ACCESSIBLE");
            return ids.getFirst();
        } catch (DataAccessException unavailable) {
            throw new MonitoringFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }
}
