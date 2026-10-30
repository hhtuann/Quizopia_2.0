package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FB-015 Leader-scenario regression: the hardened V11 purge least-privilege migration
 * fails closed when the provisioning contract is unmet. Even a CONTAINER SUPERUSER
 * Flyway connection must never create, alter or password any role - so an unprovisioned
 * cluster fails V11 with the provisioning error and ends with NO login-capable role
 * besides the PostgreSQL built-ins, while the disposable test-only init script keeps the
 * supported fixture path (and every other integration container) migrating cleanly.
 */
@Testcontainers
class QuizPurgeMigrationFailClosedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> UNPROVISIONED = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> PROVISIONED =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_assessment_roles.sql");

    @Test
    void superuserFlywayWithoutProvisionedRolesFailsClosedAndCreatesNoCredentials() {
        var flyway = Flyway.configure()
                .dataSource(UNPROVISIONED.getJdbcUrl(), UNPROVISIONED.getUsername(), UNPROVISIONED.getPassword())
                .locations("classpath:db/migration")
                .load();
        var failure = assertThrows(Exception.class, flyway::migrate);
        boolean provisioningContract = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null
                    && cause.getMessage().contains("provision owner, runtime and approved custodian roles")) {
                provisioningContract = true;
                break;
            }
        }
        assertTrue(
                provisioningContract, "V11 must fail closed with the provisioning error, got: " + failure.getMessage());

        var db = new JdbcTemplate(new DriverManagerDataSource(
                UNPROVISIONED.getJdbcUrl(), UNPROVISIONED.getUsername(), UNPROVISIONED.getPassword()));
        // No quizopia role was created as a side effect of the failed migration.
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM pg_roles WHERE rolname IN"
                                + " ('assessment','quizopia_assessment_owner','quizopia_assessment_custodian')",
                        Long.class));
        // No known-password login exists anywhere: only PostgreSQL's own superusers.
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM pg_roles WHERE rolcanlogin AND rolname NOT IN ('postgres','"
                                + UNPROVISIONED.getUsername()
                                + "')",
                        Long.class));
        // The failure is the fail-closed branch: everything before V11 succeeded.
        // Transactional-DDL PostgreSQL rolls the failed V11 history row back with
        // its script, so history ends at exactly the 11 pre-V11 migrations and
        // NEVER contains any V11 entry.
        assertEquals(11L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class));
        assertEquals(
                0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='11'", Long.class));
    }

    @Test
    void superuserFlywayWithProvisionedRolesMigratesCleanlyThroughV12() {
        var flyway = Flyway.configure()
                .dataSource(PROVISIONED.getJdbcUrl(), PROVISIONED.getUsername(), PROVISIONED.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertEquals(13, flyway.migrate().migrationsExecuted);
        flyway.validate();
        var db = new JdbcTemplate(new DriverManagerDataSource(
                PROVISIONED.getJdbcUrl(), PROVISIONED.getUsername(), PROVISIONED.getPassword()));
        assertEquals(13L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class));
    }
}
