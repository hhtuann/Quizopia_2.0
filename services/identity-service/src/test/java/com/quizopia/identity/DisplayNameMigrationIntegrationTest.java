package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class DisplayNameMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Test
    void v14AddsNullableDisplayNameWithoutAnyBackfill() throws Exception {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("13")
                .load()
                .migrate();
        UUID legacyId = UUID.randomUUID();
        String legacyUsername = "legacy-" + UUID.randomUUID();
        insertLegacyAccount(legacyId, legacyUsername);

        Flyway upgrade = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load();
        upgrade.migrate();

        assertEquals("14", upgrade.info().current().getVersion().getVersion());
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                ResultSet columns = connection
                        .createStatement()
                        .executeQuery("SELECT is_nullable, data_type, character_maximum_length "
                                + "FROM information_schema.columns "
                                + "WHERE table_name = 'user_account' AND column_name = 'display_name'")) {
            assertTrue(columns.next());
            assertEquals("YES", columns.getString("is_nullable"));
            assertEquals("character varying", columns.getString("data_type"));
            assertEquals(100, columns.getInt("character_maximum_length"));
            assertFalse(columns.next());
        }
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                ResultSet rows = connection
                        .createStatement()
                        .executeQuery("SELECT display_name FROM user_account WHERE id = '" + legacyId + "'")) {
            assertTrue(rows.next());
            assertNull(rows.getString("display_name"));
            assertFalse(rows.next());
        }
        try (Connection connection =
                DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement update =
                    connection.prepareStatement("UPDATE user_account SET display_name = ? WHERE id = ?")) {
                update.setString(1, "Nguyễn Văn An");
                update.setObject(2, legacyId);
                assertEquals(1, update.executeUpdate());
            }
            try (PreparedStatement update =
                    connection.prepareStatement("UPDATE user_account SET display_name = ? WHERE id = ?")) {
                update.setString(1, "x".repeat(101));
                update.setObject(2, legacyId);
                assertThrows(Exception.class, update::executeUpdate);
            }
        }
    }

    private void insertLegacyAccount(UUID id, String username) throws Exception {
        String sql = "INSERT INTO user_account "
                + "(id, email, username, account_status, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', ?, ?)";
        try (Connection connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, "legacy-" + UUID.randomUUID() + "@gmail.com");
            statement.setString(3, username);
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.setTimestamp(5, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }
    }
}
