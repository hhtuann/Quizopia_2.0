package com.quizopia.assessment;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Independent JDBC sessions prove committed-state constraints, without application checks. */
@Testcontainers
class SnapshotAssociationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:17-alpine");

    String schema;

    @BeforeEach
    void migrate() throws Exception {
        schema = "proof_" + UUID.randomUUID().toString().replace("-", "");
        flyway(null).migrate();
    }

    Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword())
                .schemas(schema)
                .defaultSchema(schema);
        if (target != null) config.target(target);
        return config.load();
    }

    Connection connection() throws SQLException {
        var c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        c.setSchema(schema);
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, c.getTransactionIsolation());
        sql(c, "SET statement_timeout='5s'");
        return c;
    }

    static void sql(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.execute();
        }
    }

    UUID draft(Connection c) throws SQLException {
        UUID id = UUID.randomUUID(), owner = UUID.randomUUID(), quiz = UUID.randomUUID(), version = UUID.randomUUID();
        sql(
                c,
                """
            INSERT INTO publications(id,owner_id,configuration,quiz_id,quiz_version_id,version_number,audience,classroom_id,start_at,end_at,duration_minutes,monitoring_choice,state,revision,created_at)
            SELECT ?,?,jsonb_build_object('title','Title','description',null,'quizId',?::text,'quizVersionId',?::text,'versionNumber',1,'audience','CLASS','classroomId',?::text,'startAt','2026-01-01T00:00:00Z','endAt','2026-01-02T00:00:00Z','durationMinutes',5,'monitoringChoice','DISABLED'),?,?,1,'CLASS',?,'2026-01-01T00:00:00Z','2026-01-02T00:00:00Z',5,'DISABLED','DRAFT',0,now()
            """,
                id,
                owner,
                quiz,
                version,
                id,
                quiz,
                version,
                id);
        return id;
    }

    void snapshot(Connection c, UUID id) throws SQLException {
        var payload = (tools.jackson.databind.node.ObjectNode) SnapshotFixtures.JSON.readTree(
                SnapshotFixtures.snapshot(SnapshotFixtures.configuration(), "Original **Markdown**"));
        try (var statement =
                c.prepareStatement("SELECT quiz_id,quiz_version_id FROM " + schema + ".publications WHERE id=?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    payload.put("quizId", rows.getString(1));
                    payload.put("quizVersionId", rows.getString(2));
                }
            }
        }
        sql(
                c,
                "INSERT INTO publication_snapshots VALUES (?,1,1,?::jsonb,now())",
                id,
                SnapshotFixtures.JSON.writeValueAsString(payload));
    }

    void open(Connection c, UUID id, UUID snapshot) throws SQLException {
        sql(
                c,
                "UPDATE publications SET state='OPEN',snapshot_id=?,opened_at=now(),opened_from_revision=revision,revision=revision+1 WHERE id=?",
                snapshot,
                id);
    }

    long count(Connection c, String table, UUID id) throws SQLException {
        try (var s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE "
                + (table.equals("publications") ? "id" : "publication_id") + "=?")) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    void draftWithoutSnapshot(UUID id) throws SQLException {
        try (var c = connection();
                var s = c.prepareStatement("SELECT state,snapshot_id FROM publications WHERE id=?")) {
            s.setObject(1, id);
            try (var r = s.executeQuery()) {
                assertTrue(r.next());
                assertEquals("DRAFT", r.getString(1));
                assertNull(r.getObject(2));
            }
            assertEquals(0, count(c, "publication_snapshots", id));
        }
    }

    @Test
    void nonexistentParentFailsAtCommitAndRollsBack() throws Exception {
        UUID id = UUID.randomUUID();
        try (var c = connection()) {
            c.setAutoCommit(false);
            snapshot(c, id);
            assertEquals(1, count(c, "publication_snapshots", id));
            assertEquals("23514", assertThrows(SQLException.class, c::commit).getSQLState());
            c.rollback();
        }
        try (var c = connection()) {
            assertEquals(0, count(c, "publication_snapshots", id));
        }
    }

    @Test
    void temporaryParentShadowCannotAuthorizeAnOrphan() throws Exception {
        UUID id = UUID.randomUUID();
        try (var c = connection()) {
            sql(c, "CREATE TEMP TABLE publications(id uuid,state text,snapshot_id uuid)");
            sql(c, "INSERT INTO pg_temp.publications VALUES(?,'OPEN',?)", id, id);
            c.setAutoCommit(false);
            snapshot(c, id);
            assertEquals("23514", assertThrows(SQLException.class, c::commit).getSQLState());
            c.rollback();
            assertEquals(0, count(c, "publication_snapshots", id));
        }
    }

    @Test
    void draftParentWithoutReferenceFailsAtCommit() throws Exception {
        UUID id;
        try (var c = connection()) {
            id = draft(c);
            c.setAutoCommit(false);
            snapshot(c, id);
            assertEquals("23514", assertThrows(SQLException.class, c::commit).getSQLState());
            c.rollback();
        }
        draftWithoutSnapshot(id);
    }

    @Test
    void normalInsertThenOpenCommitsAndGuardsRemain() throws Exception {
        UUID id;
        try (var c = connection()) {
            id = draft(c);
            c.setAutoCommit(false);
            snapshot(c, id);
            open(c, id, id);
            c.commit();
            sql(c, "SET CONSTRAINTS snapshot_publication_association IMMEDIATE");
            c.commit();
            for (String mutation : new String[] {
                "UPDATE publication_snapshots SET created_at=now() WHERE publication_id=?",
                "DELETE FROM publication_snapshots WHERE publication_id=?",
                "DELETE FROM publications WHERE id=?",
                "UPDATE publications SET snapshot_id=null,revision=revision+1 WHERE id=?"
            }) {
                assertEquals(
                        "P0001",
                        assertThrows(SQLException.class, () -> sql(c, mutation, id))
                                .getSQLState());
                c.rollback();
            }
            assertEquals(1, count(c, "publication_snapshots", id));
        }
    }

    @Test
    void openWithoutSnapshotOrWithWrongIdentityFailsAndRollsBack() throws Exception {
        try (var c = connection()) {
            UUID id = draft(c);
            c.setAutoCommit(false);
            assertEquals(
                    "23503",
                    assertThrows(SQLException.class, () -> open(c, id, id)).getSQLState());
            c.rollback();
            draftWithoutSnapshot(id);
            snapshot(c, id);
            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () -> open(c, id, UUID.randomUUID()))
                            .getSQLState());
            c.rollback();
            draftWithoutSnapshot(id);
            snapshot(c, id);
            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () -> open(c, id, null)).getSQLState());
            c.rollback();
            draftWithoutSnapshot(id);
        }
    }

    @Test
    void immediateValidationRejectsIncompleteSequenceButAcceptsFinalState() throws Exception {
        try (var c = connection()) {
            UUID id = draft(c);
            c.setAutoCommit(false);
            snapshot(c, id);
            assertEquals(
                    "23514",
                    assertThrows(
                                    SQLException.class,
                                    () -> sql(c, "SET CONSTRAINTS snapshot_publication_association IMMEDIATE"))
                            .getSQLState());
            c.rollback();
            draftWithoutSnapshot(id);
            snapshot(c, id);
            open(c, id, id);
            sql(c, "SET CONSTRAINTS snapshot_publication_association IMMEDIATE");
            c.commit();
            assertEquals(1, count(c, "publication_snapshots", id));
        }
    }

    @Test
    void failureAfterSnapshotInsertionRollsBackBothWrites() throws Exception {
        try (var c = connection()) {
            UUID id = draft(c);
            c.setAutoCommit(false);
            snapshot(c, id);
            assertEquals(
                    "23514",
                    assertThrows(
                                    SQLException.class,
                                    () -> sql(
                                            c,
                                            "UPDATE publications SET state='OPEN',snapshot_id=id,opened_at=now(),opened_from_revision=revision,revision=revision+1,duration_minutes=0 WHERE id=?",
                                            id))
                            .getSQLState());
            c.rollback();
            draftWithoutSnapshot(id);
        }
    }

    @Test
    void concurrentParentDeleteMakesDeferredInsertFailAfterLockWait() throws Exception {
        concurrentParentMutation(true);
    }

    @Test
    void concurrentDraftEditCannotLeaveFinalizedSnapshot() throws Exception {
        concurrentParentMutation(false);
    }

    void concurrentParentMutation(boolean delete) throws Exception {
        try (var parent = connection();
                var child = connection();
                var observer = connection();
                var pool = Executors.newSingleThreadExecutor()) {
            UUID id = draft(parent);
            parent.setAutoCommit(false);
            child.setAutoCommit(false);
            sql(
                    parent,
                    delete
                            ? "DELETE FROM publications WHERE id=?"
                            : "UPDATE publications SET revision=revision+1 WHERE id=?",
                    id);
            snapshot(child, id);
            int pid;
            try (var s = child.createStatement();
                    var r = s.executeQuery("SELECT pg_backend_pid()")) {
                r.next();
                pid = r.getInt(1);
            }
            var commit = pool.submit(() -> {
                try {
                    child.commit();
                    return "COMMITTED";
                } catch (SQLException e) {
                    child.rollback();
                    return e.getSQLState();
                }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                try (var s = observer.prepareStatement("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?")) {
                    s.setInt(1, pid);
                    try (var r = s.executeQuery()) {
                        if (r.next() && "Lock".equals(r.getString(1))) {
                            blocked = true;
                            break;
                        }
                    }
                }
                Thread.sleep(10);
            }
            assertTrue(blocked, "Deferred validator must wait for the parent transaction");
            assertFalse(commit.isDone());
            parent.commit();
            assertEquals("23514", commit.get(5, TimeUnit.SECONDS));
            assertEquals(0, count(observer, "publication_snapshots", id));
            assertEquals(delete ? 0 : 1, count(observer, "publications", id));
        }
    }

    @Test
    void openingParentPreventsConcurrentDeletionAfterCommit() throws Exception {
        try (var opening = connection();
                var deleting = connection();
                var observer = connection();
                var pool = Executors.newSingleThreadExecutor()) {
            UUID id = draft(opening);
            opening.setAutoCommit(false);
            deleting.setAutoCommit(false);
            sql(opening, "SELECT id FROM publications WHERE id=? FOR UPDATE", id);
            snapshot(opening, id);
            open(opening, id, id);
            int pid;
            try (var s = deleting.createStatement();
                    var r = s.executeQuery("SELECT pg_backend_pid()")) {
                r.next();
                pid = r.getInt(1);
            }
            var deletion = pool.submit(() -> {
                try {
                    sql(deleting, "DELETE FROM publications WHERE id=?", id);
                    deleting.commit();
                    return "COMMITTED";
                } catch (SQLException e) {
                    deleting.rollback();
                    return e.getSQLState();
                }
            });
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                try (var s = observer.prepareStatement("SELECT wait_event_type FROM pg_stat_activity WHERE pid=?")) {
                    s.setInt(1, pid);
                    try (var r = s.executeQuery()) {
                        if (r.next() && "Lock".equals(r.getString(1))) {
                            blocked = true;
                            break;
                        }
                    }
                }
                Thread.sleep(10);
            }
            assertTrue(blocked);
            opening.commit();
            assertEquals("P0001", deletion.get(5, TimeUnit.SECONDS));
            assertEquals(1, count(observer, "publications", id));
            assertEquals(1, count(observer, "publication_snapshots", id));
        }
    }

    @Test
    void validV1UpgradePreservesRowsAndChecksum() throws Exception {
        schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");
        flyway("1").migrate();
        UUID id;
        int checksum;
        try (var c = connection()) {
            id = draft(c);
            c.setAutoCommit(false);
            snapshot(c, id);
            open(c, id, id);
            c.commit();
            checksum = checksum(c);
        }
        assertEquals(1, flyway(null).migrate().migrationsExecuted);
        try (var c = connection()) {
            assertEquals(checksum, checksum(c));
            assertEquals(1, count(c, "publication_snapshots", id));
        }
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
    }

    int checksum(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT checksum FROM flyway_schema_history WHERE version='1'")) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void invalidV1UpgradeStopsWithoutDeletingOrRepairingData() throws Exception {
        schema = "invalid_" + UUID.randomUUID().toString().replace("-", "");
        flyway("1").migrate();
        UUID id = UUID.randomUUID();
        try (var c = connection()) {
            snapshot(c, id);
        }
        assertThrows(
                org.flywaydb.core.api.FlywayException.class, () -> flyway(null).migrate());
        try (var c = connection()) {
            assertEquals(1, count(c, "publication_snapshots", id));
            assertEquals(0, count(c, "publications", id));
        }
    }
}
