package com.quizopia.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * FB-015 Leader regression on disposable PostgreSQL 17: V5 fails closed under ANY
 * principal. A superuser Flyway run without the pre-provisioned owner, runtime and
 * custodian roles is rejected on V5, leaves no partial roles or login principals
 * behind, and records V1..V4 successful with V5 failed; the operator-side test-only
 * init script (fb015_proctoring_roles.sql) is the sole fixture that lets the same
 * superuser migration complete through V5.
 */
@Testcontainers
class QuizPurgeMigrationFailClosedIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> BARE = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer<?> PROVISIONED =
            new PostgreSQLContainer<>("postgres:17-alpine").withInitScript("fb015_proctoring_roles.sql");

    @Test
    void superuserMigrationWithoutRolesFailsClosedOnV5() {
        var source = new DriverManagerDataSource(BARE.getJdbcUrl(), BARE.getUsername(), BARE.getPassword());
        var failure = assertThrows(FlywayException.class, () -> Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load()
                .migrate());
        StringBuilder trail = new StringBuilder();
        for (Throwable cause = failure; cause != null && trail.length() < 4000; cause = cause.getCause()) {
            trail.append(cause.getMessage()).append(" | ");
        }
        assertTrue(
                trail.toString().contains("provision owner, runtime and custodian roles before migrating"),
                trail.toString());
        var db = new JdbcTemplate(source);
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM pg_roles WHERE rolname IN ('proctoring','quizopia_proctoring_owner'"
                                + ",'quizopia_proctoring_custodian')",
                        Long.class));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT count(*) FROM pg_roles WHERE rolcanlogin AND rolname NOT IN ('postgres',?)",
                        Long.class,
                        BARE.getUsername()));
        assertEquals(
                4L,
                db.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version IN ('1','2','3','4') AND success",
                        Long.class));
        // Transactional-DDL PostgreSQL rolls the failed V5 history row back with
        // its script: history ends at exactly the four pre-V5 migrations and NEVER
        // contains any V5 entry.
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='5'", Long.class));
    }

    @Test
    void provisionedRolesMigrateCleanlyThroughV5AsSuperuser() {
        var source = new DriverManagerDataSource(
                PROVISIONED.getJdbcUrl(), PROVISIONED.getUsername(), PROVISIONED.getPassword());
        Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var db = new JdbcTemplate(source);
        assertEquals(5L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class));
        assertEquals(0L, db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Long.class));
        assertEquals(
                "quizopia_proctoring_owner",
                db.queryForObject(
                        "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE oid="
                                + "'monitoring_purge_quiz(uuid,uuid,uuid[],uuid[],integer)'::regprocedure",
                        String.class));
    }
}
