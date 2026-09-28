package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EmailVerificationOutboxMigrationIntegrationTest {
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(2026, 9, 23, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES_AT = CREATED_AT.plusMinutes(10);
    private static final byte[] CIPHERTEXT = new byte[17];
    private static final byte[] NONCE = new byte[12];
    private static final String BLOCKED_MIGRATION_MESSAGE =
            "Active legacy verification-email outbox jobs must be drained or resolved before V10 migration";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @BeforeEach
    void resetSchema() {
        flyway().clean();
    }

    @Test
    void cleanV9SchemaUpgradesToV10WithoutLosingIssuanceHistory() throws Exception {
        migrateToV9();
        String email = email("clean-upgrade");
        UUID issuanceId = UUID.randomUUID();
        insertIssuance(email, issuanceId);

        Flyway upgrade = flyway();
        upgrade.migrate();

        assertEquals("11", upgrade.info().current().getVersion().getVersion());
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_issuance WHERE id = ?", issuanceId));
        assertEquals(0L, count("SELECT COUNT(*) FROM email_verification_email_outbox"));
    }

    @Test
    void terminalLegacyJobsUpgradeWithoutAccountLinkOrDataMutation() throws Exception {
        migrateToV9();
        UUID sentJob = insertLegacyTerminalJob("SENT");
        UUID failedJob = insertLegacyTerminalJob("FAILED");
        UUID expiredJob = insertLegacyTerminalJob("EXPIRED");

        Flyway upgrade = flyway();
        upgrade.migrate();

        assertEquals("11", upgrade.info().current().getVersion().getVersion());
        assertTerminalLegacyJob(sentJob, "SENT");
        assertTerminalLegacyJob(failedJob, "FAILED");
        assertTerminalLegacyJob(expiredJob, "EXPIRED");
    }

    @Test
    void pendingLegacyJobBlocksMigrationWithoutLosingAcceptedDeliveryData() throws Exception {
        migrateToV9();
        String email = email("legacy-pending");
        UUID issuanceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        insertIssuance(email, issuanceId);
        insertLegacyActiveOutbox(jobId, issuanceId, email, "PENDING");

        assertLegacyActiveJobBlocksMigration(jobId, email, "PENDING", null);
    }

    @Test
    void claimedLegacyJobBlocksMigrationWithoutLosingClaimOrAcceptedDeliveryData() throws Exception {
        migrateToV9();
        String email = email("legacy-claimed");
        UUID issuanceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        insertIssuance(email, issuanceId);
        insertLegacyActiveOutbox(jobId, issuanceId, email, "CLAIMED");

        assertLegacyActiveJobBlocksMigration(jobId, email, "CLAIMED", "legacy-worker");
    }

    @Test
    void v10RequiresAValidUserForActiveJobsButAllowsTerminalRowsWithoutOne() throws Exception {
        flyway().migrate();
        UUID userId = insertUser(email("linked-user"));

        String linkedEmail = email("linked-active");
        UUID linkedIssuance = UUID.randomUUID();
        insertIssuance(linkedEmail, linkedIssuance);
        insertV10PendingOutbox(UUID.randomUUID(), linkedIssuance, linkedEmail, userId);

        String missingEmail = email("missing-account-link");
        UUID missingIssuance = UUID.randomUUID();
        insertIssuance(missingEmail, missingIssuance);
        assertThrows(
                SQLException.class,
                () -> insertV10PendingOutbox(UUID.randomUUID(), missingIssuance, missingEmail, null));

        String invalidEmail = email("invalid-account-link");
        UUID invalidIssuance = UUID.randomUUID();
        insertIssuance(invalidEmail, invalidIssuance);
        assertThrows(
                SQLException.class,
                () -> insertV10PendingOutbox(UUID.randomUUID(), invalidIssuance, invalidEmail, UUID.randomUUID()));

        UUID terminalJob = insertLegacyTerminalJob("FAILED");
        assertNull(value("SELECT user_id FROM email_verification_email_outbox WHERE id = ?", terminalJob));
        assertEquals(
                0L,
                count("SELECT COUNT(*) FROM email_verification_email_outbox "
                        + "WHERE state IN ('PENDING', 'CLAIMED') AND user_id IS NULL"));
    }

    @Test
    void v11PreservesLegacyChallengeAndAddsOptionalValidatedCurrentIssuanceLink() throws Exception {
        migrateToV10();
        String email = email("v11-challenge");
        UUID userId = insertUser(email);
        UUID issuanceId = UUID.randomUUID();
        insertIssuance(email, issuanceId);
        execute(
                "INSERT INTO email_verification_challenge "
                        + "(user_id, otp_hash, issued_at, expires_at, resend_not_before, failed_attempts, max_attempts) "
                        + "VALUES (?, 'legacy-hash', ?, ?, ?, 0, 5)",
                userId,
                CREATED_AT,
                EXPIRES_AT,
                CREATED_AT);

        Flyway upgrade = flyway();
        upgrade.migrate();

        assertEquals("11", upgrade.info().current().getVersion().getVersion());
        assertNull(value("SELECT current_issuance_id FROM email_verification_challenge WHERE user_id = ?", userId));
        execute(
                "UPDATE email_verification_challenge SET current_issuance_id = ? WHERE user_id = ?",
                issuanceId,
                userId);
        assertEquals(
                issuanceId,
                value("SELECT current_issuance_id FROM email_verification_challenge WHERE user_id = ?", userId));
        assertThrows(
                SQLException.class,
                () -> execute(
                        "UPDATE email_verification_challenge SET current_issuance_id = ? WHERE user_id = ?",
                        UUID.randomUUID(),
                        userId));
    }

    private void assertLegacyActiveJobBlocksMigration(
            UUID jobId, String email, String expectedState, String expectedClaimOwner) throws Exception {
        Flyway upgrade = flyway();

        FlywayException failure = assertThrows(FlywayException.class, upgrade::migrate);

        String diagnostic = exceptionMessages(failure);
        assertTrue(diagnostic.contains(BLOCKED_MIGRATION_MESSAGE));
        assertFalse(diagnostic.contains(email));
        assertEquals("9", upgrade.info().current().getVersion().getVersion());
        assertEquals(expectedState, text("SELECT state FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertArrayEquals(CIPHERTEXT, (byte[])
                value("SELECT ciphertext FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertArrayEquals(
                NONCE, (byte[]) value("SELECT nonce FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertNull(value("SELECT terminal_at FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertEquals(
                expectedClaimOwner,
                value("SELECT claim_owner FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertEquals(1L, count("SELECT COUNT(*) FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertEquals(
                0L,
                count("SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'email_verification_email_outbox' "
                        + "AND column_name = 'user_id'"));
    }

    private UUID insertLegacyTerminalJob(String state) throws Exception {
        String email = email("legacy-" + state.toLowerCase(java.util.Locale.ROOT));
        UUID issuanceId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        insertIssuance(email, issuanceId);
        OffsetDateTime sentAt = "SENT".equals(state) ? CREATED_AT.plusMinutes(1) : null;
        execute(
                "INSERT INTO email_verification_email_outbox "
                        + "(id, issuance_id, recipient_email, template_type, otp_expires_at, state, attempt_count, "
                        + "next_attempt_at, key_version, payload_format_version, ciphertext, nonce, created_at, sent_at, "
                        + "terminal_at, failure_category) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, 1, NULL, NULL, ?, ?, ?, ?)",
                jobId,
                issuanceId,
                email,
                "EMAIL_VERIFICATION_OTP",
                EXPIRES_AT,
                state,
                CREATED_AT,
                "v1",
                CREATED_AT,
                sentAt,
                CREATED_AT.plusMinutes(1),
                "LEGACY_TERMINAL");
        return jobId;
    }

    private void assertTerminalLegacyJob(UUID jobId, String expectedState) throws Exception {
        assertEquals(expectedState, text("SELECT state FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertNull(value("SELECT user_id FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertNull(value("SELECT ciphertext FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertNull(value("SELECT nonce FROM email_verification_email_outbox WHERE id = ?", jobId));
        assertEquals(
                "LEGACY_TERMINAL",
                text("SELECT failure_category FROM email_verification_email_outbox WHERE id = ?", jobId));
    }

    private void insertLegacyActiveOutbox(UUID jobId, UUID issuanceId, String email, String state) throws Exception {
        String claimOwner = "CLAIMED".equals(state) ? "legacy-worker" : null;
        OffsetDateTime claimExpiresAt = "CLAIMED".equals(state) ? CREATED_AT.plusMinutes(1) : null;
        execute(
                "INSERT INTO email_verification_email_outbox "
                        + "(id, issuance_id, recipient_email, template_type, otp_expires_at, state, attempt_count, "
                        + "next_attempt_at, claim_owner, claim_expires_at, key_version, payload_format_version, "
                        + "ciphertext, nonce, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, 1, ?, ?, ?)",
                jobId,
                issuanceId,
                email,
                "EMAIL_VERIFICATION_OTP",
                EXPIRES_AT,
                state,
                CREATED_AT,
                claimOwner,
                claimExpiresAt,
                "v1",
                CIPHERTEXT,
                NONCE,
                CREATED_AT);
    }

    private void insertV10PendingOutbox(UUID jobId, UUID issuanceId, String email, UUID userId) throws Exception {
        execute(
                "INSERT INTO email_verification_email_outbox "
                        + "(id, issuance_id, recipient_email, template_type, otp_expires_at, state, attempt_count, "
                        + "next_attempt_at, key_version, payload_format_version, ciphertext, nonce, created_at, user_id) "
                        + "VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, 1, ?, ?, ?, ?)",
                jobId,
                issuanceId,
                email,
                "EMAIL_VERIFICATION_OTP",
                EXPIRES_AT,
                CREATED_AT,
                "v1",
                CIPHERTEXT,
                NONCE,
                CREATED_AT,
                userId);
    }

    private UUID insertUser(String email) throws Exception {
        UUID userId = UUID.randomUUID();
        execute(
                "INSERT INTO user_account (id, email, username, account_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'PENDING_EMAIL_VERIFICATION', ?, ?)",
                userId,
                email,
                "migration-user-" + UUID.randomUUID(),
                CREATED_AT,
                CREATED_AT);
        return userId;
    }

    private void insertIssuance(String email, UUID issuanceId) throws Exception {
        execute("INSERT INTO email_verification_issuance_guard (email) VALUES (?)", email);
        execute(
                "INSERT INTO email_verification_issuance (id, email, issued_at) VALUES (?, ?, ?)",
                issuanceId,
                email,
                CREATED_AT);
    }

    private void migrateToV9() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate();
    }

    private void migrateToV10() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(MigrationVersion.fromVersion("10"))
                .load()
                .migrate();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
    }

    private long count(String sql, Object... parameters) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private String text(String sql, Object... parameters) throws Exception {
        return (String) value(sql, parameters);
    }

    private Object value(String sql, Object... parameters) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getObject(1);
            }
        }
    }

    private void execute(String sql, Object... parameters) throws Exception {
        try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            setParameters(statement, parameters);
            statement.executeUpdate();
        }
    }

    private void setParameters(PreparedStatement statement, Object... parameters) throws Exception {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private String exceptionMessages(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private String email(String prefix) {
        return prefix + '-' + UUID.randomUUID() + "@gmail.com";
    }

    private Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
