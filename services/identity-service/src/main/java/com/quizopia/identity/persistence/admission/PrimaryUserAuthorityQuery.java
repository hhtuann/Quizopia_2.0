package com.quizopia.identity.persistence.admission;

import com.quizopia.identity.application.admission.AdmissionFailure;
import com.quizopia.identity.application.admission.UserAuthorityFacts;
import com.quizopia.identity.configuration.UserAdmissionProperties;
import com.quizopia.identity.persistence.entity.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!test")
public class PrimaryUserAuthorityQuery {
    private static final String SQL =
            """
        SELECT requested.id AS actor_id, a.id IS NOT NULL AS account_exists,
               a.account_status, a.email_verified_at IS NOT NULL AS email_verified,
               ARRAY(SELECT r.role FROM user_role r WHERE r.user_id = a.id ORDER BY r.role) AS roles,
               v.revoked_before, pg_is_in_recovery() AS replica,
               current_setting('transaction_isolation') AS isolation, clock_timestamp() AS checked_at
        FROM (SELECT CAST(? AS uuid) AS id) requested
        LEFT JOIN user_account a ON a.id = requested.id
        LEFT JOIN user_access_revocation v ON v.user_id = a.id
        """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final int timeout;

    public PrimaryUserAuthorityQuery(
            JdbcTemplate jdbc, PlatformTransactionManager manager, UserAdmissionProperties properties) {
        properties.validate();
        this.jdbc = jdbc;
        timeout = properties.getQueryTimeoutSeconds();
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // Deliberately not readOnly: routing must use the primary, never a read replica.
        transaction.setReadOnly(false);
        transaction.setTimeout(timeout);
    }

    public UserAuthorityFacts read(UUID actor) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> jdbc.query(
                            connection -> {
                                var statement = connection.prepareStatement(SQL);
                                statement.setObject(1, actor);
                                statement.setQueryTimeout(timeout);
                                return statement;
                            },
                            (rs, row) -> map(rs))
                    .getFirst()));
        } catch (RuntimeException exception) {
            throw AdmissionFailure.unavailable();
        }
    }

    public record Stamped(UserAuthorityFacts facts, java.time.Instant checkedAt) {}

    public Stamped readStamped(UUID actor) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> jdbc.query(
                            connection -> {
                                var statement = connection.prepareStatement(SQL);
                                statement.setObject(1, actor);
                                statement.setQueryTimeout(timeout);
                                return statement;
                            },
                            (rs, row) -> new Stamped(
                                    map(rs), rs.getTimestamp("checked_at").toInstant()))
                    .getFirst()));
        } catch (RuntimeException e) {
            throw AdmissionFailure.unavailable();
        }
    }

    private UserAuthorityFacts map(ResultSet rs) throws SQLException {
        if (rs.getBoolean("replica") || !"read committed".equals(rs.getString("isolation")))
            throw AdmissionFailure.unavailable();
        var cutoff = rs.getObject("revoked_before", OffsetDateTime.class);
        String[] roles = (String[]) rs.getArray("roles").getArray();
        return new UserAuthorityFacts(
                1,
                rs.getObject("actor_id", UUID.class),
                rs.getBoolean("account_exists"),
                rs.getString("account_status"),
                rs.getBoolean("email_verified"),
                Arrays.stream(roles).map(UserRole::valueOf).toList(),
                Optional.ofNullable(cutoff).map(OffsetDateTime::toInstant));
    }
}
