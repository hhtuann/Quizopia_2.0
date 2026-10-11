package com.quizopia.identity.persistence.admission;

import com.quizopia.identity.configuration.UserAdmissionProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class UserAuthorityWorkStore {
    private final JdbcTemplate jdbc;
    private final int batchSize;

    public UserAuthorityWorkStore(JdbcTemplate jdbc, UserAdmissionProperties properties) {
        this.jdbc = jdbc;
        batchSize = properties.getBatchSize();
    }

    public List<Work> pending() {
        return jdbc.query(
                "SELECT user_id, work_id FROM user_authority_work ORDER BY queued_at, user_id LIMIT ?",
                (rs, row) -> new Work(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)),
                batchSize);
    }

    public void acknowledge(Work work) {
        jdbc.update("DELETE FROM user_authority_work WHERE user_id = ? AND work_id = ?", work.userId(), work.workId());
    }

    public List<UUID> scanAfter(UUID cursor) {
        if (cursor == null)
            return jdbc.query(
                    "SELECT id FROM user_account ORDER BY id LIMIT ?",
                    (rs, row) -> rs.getObject(1, UUID.class),
                    batchSize);
        return jdbc.query(
                "SELECT id FROM user_account WHERE id > ? ORDER BY id LIMIT ?",
                (rs, row) -> rs.getObject(1, UUID.class),
                cursor,
                batchSize);
    }

    public record Work(UUID userId, UUID workId) {}
}
