package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class VerifiedEmailMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void migrationFailsWithoutRepairingDuplicateVerifiedOwnership() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("6"))
                .load()
                .migrate();
        String email = "existing-duplicate-owner-" + UUID.randomUUID() + "@example.com";
        insertVerifiedAccount(email, "first-" + UUID.randomUUID());
        insertVerifiedAccount(email, "second-" + UUID.randomUUID());

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        assertThrows(FlywayException.class, upgrade::migrate);
    }

    private void insertVerifiedAccount(String email, String username) throws Exception {
        String sql = "INSERT INTO user_account "
                + "(id, email, username, account_status, email_verified_at, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)";
        OffsetDateTime timestamp = OffsetDateTime.of(2026, 9, 22, 0, 0, 0, 0, ZoneOffset.UTC);
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, email);
            statement.setString(3, username);
            statement.setObject(4, timestamp);
            statement.setObject(5, timestamp);
            statement.setObject(6, timestamp);
            statement.executeUpdate();
        }
    }
}
