package com.quizopia.assessment.persistence;

import com.quizopia.assessment.application.AttemptPersistence;
import com.quizopia.assessment.application.PublicationFailure;
import com.quizopia.assessment.domain.attempt.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAttemptPersistence implements AttemptPersistence {
    private final JdbcTemplate db;

    public JdbcAttemptPersistence(JdbcTemplate db) {
        this.db = db;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private <T> Optional<T> read(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
        try {
            return db.query(sql, mapper, args).stream().findFirst();
        } catch (org.springframework.dao.DataAccessException e) {
            throw new PublicationFailure(503, "PERSISTENCE_UNAVAILABLE");
        }
    }

    @Override
    public Optional<AttemptRecord> find(UUID id) {
        return read(
                "SELECT * FROM attempts WHERE id=?",
                (r, n) -> new AttemptRecord(
                        r.getObject("id", UUID.class),
                        r.getObject("publication_id", UUID.class),
                        r.getObject("student_id", UUID.class),
                        r.getLong("ordinal"),
                        r.getObject("snapshot_id", UUID.class),
                        r.getInt("grading_policy_version"),
                        r.getInt("answer_schema_version"),
                        r.getInt("duration_minutes"),
                        instant(r, "started_at"),
                        instant(r, "deadline_at"),
                        r.getLong("revision"),
                        AttemptRecord.State.valueOf(r.getString("state")),
                        r.getString("terminal_reason") == null
                                ? null
                                : AttemptRecord.Reason.valueOf(r.getString("terminal_reason")),
                        instant(r, "finalized_at"),
                        r.getObject("submitted_answers_id", UUID.class),
                        r.getObject("result_id", UUID.class)),
                id);
    }

    @Override
    public Optional<StoredResult> result(UUID id) {
        return read(
                "SELECT * FROM grading_results WHERE attempt_id=?",
                (r, n) -> new StoredResult(
                        r.getObject("id", UUID.class),
                        r.getObject("attempt_id", UUID.class),
                        r.getObject("submitted_answers_id", UUID.class),
                        r.getObject("snapshot_id", UUID.class),
                        r.getInt("grading_policy_version"),
                        r.getBigDecimal("earned_weight"),
                        r.getBigDecimal("possible_weight"),
                        instant(r, "graded_at")),
                id);
    }

    @Override
    public Optional<OperationReceipt> receipt(UUID id, String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid operation digest");
        return read(
                "SELECT * FROM attempt_operations WHERE attempt_id=? AND key_digest=?",
                (r, n) -> new OperationReceipt(
                        r.getString("action"),
                        r.getString("request_digest"),
                        r.getLong("accepted_revision"),
                        instant(r, "acknowledged_at"),
                        r.getInt("response_status"),
                        r.getString("response_body")),
                id,
                digest);
    }
}
